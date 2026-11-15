/*
 * bionic 版 @deepseek-ai/node-addon-system 的 system.node：只含 flock，没有 landlock。
 *
 * 接口形状（读 node-addon-system 0.1.2 的 lib/flock.js，并对 darwin-arm64 的 bin/system.node
 * 执行 `nm -gU` / `nm -u` 对照得到）：
 *   导出符号只有两个：napi_register_module_v1、node_api_module_get_api_version_v1（返回 8）。
 *   exports.tryLock(fd: number, callback: (errno: number) => void): undefined
 *     - 在线程池里对 fd 执行 flock(fd, LOCK_EX | LOCK_NB)，完成后在主线程以 0 或正的 errno 调 callback。
 *     - 本模块不 open / dup / close fd。参数不合法时同步抛 TypeError / RangeError。
 *   darwin 版引用的 napi_* 共 18 个，本文件用到的是其中的同一批；libc 符号只有 calloc、free、flock。
 *
 * 链接：不链 libnode.so。napi_* 由宿主 node 可执行文件导出，加载时解析，所以不加 -z defs，
 * 产物没有 NEEDED libnode。
 *
 * 与上游 src/flock.c 的关系：行为按上述接口重写，不是拷贝；环境销毁时的清理钩子保留，
 * 防止 worker 退出时线程池里还有未完成的请求。
 */
#include <node_api.h>
#include <errno.h>
#include <limits.h>
#include <stdbool.h>
#include <stdlib.h>
#include <sys/file.h>

typedef struct {
  napi_env env;
  napi_ref callback;
  napi_async_work work;
  napi_async_cleanup_hook_handle cleanup;
  int fd;
  int error;
  bool closing;
} lock_request;

static void must(napi_status status, const char *message) {
  if (status != napi_ok) napi_fatal_error("flock", NAPI_AUTO_LENGTH, message, NAPI_AUTO_LENGTH);
}

static void release_request(lock_request *r) {
  if (r->callback != NULL) (void)napi_delete_reference(r->env, r->callback);
  if (r->work != NULL) (void)napi_delete_async_work(r->env, r->work);
  if (r->cleanup != NULL) (void)napi_remove_async_cleanup_hook(r->cleanup);
  free(r);
}

static napi_value fail_setup(napi_env env, napi_status status, const char *message) {
  if (status != napi_pending_exception) {
    status = napi_throw_error(env, "ERR_FLOCK_ASYNC_WORK", message);
    bool pending = false;
    must(napi_is_exception_pending(env, &pending), "cannot inspect pending exception");
    if (!pending && status != napi_pending_exception) must(status, message);
  }
  return NULL;
}

static void execute_lock(napi_env env, void *data) {
  (void)env;
  lock_request *r = data;
  r->error = flock(r->fd, LOCK_EX | LOCK_NB) == 0 ? 0 : errno;
}

static void complete_lock(napi_env env, napi_status status, void *data) {
  lock_request *r = data;
  if (env != NULL && !r->closing) {
    napi_value callback, receiver, result;
    must(status, "flock async work did not complete");
    must(napi_get_reference_value(env, r->callback, &callback), "cannot get callback");
    must(napi_get_undefined(env, &receiver), "cannot create receiver");
    must(napi_create_int32(env, r->error, &result), "cannot create result");
    status = napi_call_function(env, receiver, callback, 1, &result, NULL);
  } else {
    status = napi_ok;
  }
  release_request(r);
  /* 回调里抛出的 JS 异常由 Node 的 async work 派发层上报 */
  if (status != napi_pending_exception) must(status, "cannot invoke callback");
}

static void cleanup_lock(napi_async_cleanup_hook_handle handle, void *data) {
  (void)handle;
  lock_request *r = data;
  r->closing = true;
  /* 已在执行的 flock 取消不了；钩子只负责让 env 活到 complete_lock 释放它 */
  (void)napi_cancel_async_work(r->env, r->work);
}

static napi_value try_lock(napi_env env, napi_callback_info info) {
  size_t argc = 2;
  napi_value argv[2];
  double fd;
  napi_status status = napi_get_cb_info(env, info, &argc, argv, NULL, NULL);
  if (status != napi_ok) return fail_setup(env, status, "cannot read arguments");
  if (argc < 1 || napi_get_value_double(env, argv[0], &fd) != napi_ok) {
    (void)napi_throw_type_error(env, NULL, "fd must be a number");
    return NULL;
  }
  if (!(fd >= INT_MIN && fd <= INT_MAX) || fd != (int)fd) {
    (void)napi_throw_range_error(env, NULL, "fd must be a signed C int");
    return NULL;
  }
  napi_valuetype type;
  if (argc < 2 || napi_typeof(env, argv[1], &type) != napi_ok || type != napi_function) {
    (void)napi_throw_type_error(env, NULL, "callback must be a function");
    return NULL;
  }

  lock_request *r = calloc(1, sizeof(*r));
  if (r == NULL) {
    (void)napi_throw_error(env, "ENOMEM", "cannot allocate flock request");
    return NULL;
  }
  r->env = env;
  r->fd = (int)fd;

  status = napi_create_reference(env, argv[1], 1, &r->callback);
  if (status != napi_ok) {
    release_request(r);
    return fail_setup(env, status, "cannot retain callback");
  }
  napi_value name;
  status = napi_create_string_utf8(env, "flock", NAPI_AUTO_LENGTH, &name);
  if (status == napi_ok)
    status = napi_create_async_work(env, NULL, name, execute_lock, complete_lock, r, &r->work);
  if (status != napi_ok) {
    release_request(r);
    return fail_setup(env, status, "cannot create async work");
  }
  status = napi_add_async_cleanup_hook(env, cleanup_lock, r, &r->cleanup);
  if (status != napi_ok) {
    release_request(r);
    return fail_setup(env, status, "cannot register cleanup hook");
  }
  status = napi_queue_async_work(env, r->work);
  if (status != napi_ok) {
    /* 入队失败不会再有 complete 回调 */
    release_request(r);
    return fail_setup(env, status, "cannot queue async work");
  }
  return NULL;
}

NAPI_MODULE_INIT() {
  napi_value fn;
  if (napi_create_function(env, "tryLock", NAPI_AUTO_LENGTH, try_lock, NULL, &fn) != napi_ok ||
      napi_set_named_property(env, exports, "tryLock", fn) != napi_ok)
    return NULL;
  return exports;
}
