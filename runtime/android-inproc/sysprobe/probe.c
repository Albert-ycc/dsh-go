/*
 * libdsh_sysprobe.so：判断本机的 seccomp 过滤器是否用 SIGSYS 杀掉 glibc 启动必调的 set_robust_list(99)。
 * 它是一个 PIE 可执行文件，只是借 lib*.so 的名字进 jniLibs（同 libproot.so），由 App 从 nativeLibraryDir exec。
 *
 * 接口（给 Kotlin 的 syscallProbe 用，P1 3.1）：
 *   调用：<nativeLibraryDir>/libdsh_sysprobe.so，无参数，无环境依赖。
 *   stdout：恰好一行，以换行结尾，字段以空格分隔，键值用 '='：
 *     dsh-sysprobe v1 set_robust_list=<result> errno=<n>
 *   <result> 取值：
 *     allowed       子进程里调用返回了（成功或任意 errno 都算放行，glibc 启动忽略失败）
 *     sigsys        子进程被 SIGSYS 杀死，即过滤器对该调用号是 KILL
 *     signal:<N>    子进程被别的信号杀死
 *     exit:-1       waitpid 失败或状态无法识别（子进程正常退出一律算 allowed，不会出现具体退出码）
 *     forkfail      fork 失败，errno 为 fork 的 errno
 *   errno=<n>：allowed 时是该调用返回 -1 后的 errno（返回 0 则为 0）；其余情况为 0，forkfail 例外。
 *   退出码：0 allowed / 42 sigsys / 43 signal:<N> / 44 exit:-1 / 45 forkfail。
 *   Kotlin 以退出码为准，stdout 行用于诊断展示。
 *
 * 为什么 fork：被 SIGSYS 杀的是调用者本身，探针不能在自己进程里试。子进程不装 SIGSYS 处理器，
 * 保持默认动作，这样过滤器的 KILL 会原样表现为 WIFSIGNALED && WTERMSIG == SIGSYS。
 *
 * 宿主自测（macOS 无该系统调用）：-DPROBE_FAKE=allowed|sigsys|segv 让子进程改为模拟对应行为。
 */
#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef PROBE_FAKE
#include <sys/syscall.h>
#if defined(__aarch64__)
_Static_assert(__NR_set_robust_list == 99, "set_robust_list 在 arm64 上应为 99");
#endif
#endif

enum { EXIT_ALLOWED = 0, EXIT_SIGSYS = 42, EXIT_SIGNAL = 43, EXIT_ODD = 44, EXIT_FORKFAIL = 45 };

/* 子进程体：只调一次，返回时把 errno 经退出码带回（errno 小于 128，装得下）。 */
static void child_main(void) {
#if defined(PROBE_FAKE)
#define PROBE_STR_(x) #x
#define PROBE_STR(x) PROBE_STR_(x)
  const char *mode = PROBE_STR(PROBE_FAKE);
  if (mode[0] == 's' && mode[1] == 'i') raise(SIGSYS);
  if (mode[0] == 'a') _exit(0);
  raise(SIGSEGV);
#else
  errno = 0;
  long r = syscall(SYS_set_robust_list, (void *)0, (size_t)0);
  _exit(r == 0 ? 0 : (errno & 0x7f));
#endif
}

int main(void) {
  pid_t pid = fork();
  if (pid < 0) {
    printf("dsh-sysprobe v1 set_robust_list=forkfail errno=%d\n", errno);
    return EXIT_FORKFAIL;
  }
  if (pid == 0) child_main();

  int status = 0;
  pid_t w;
  do { w = waitpid(pid, &status, 0); } while (w < 0 && errno == EINTR);
  if (w < 0) {
    printf("dsh-sysprobe v1 set_robust_list=exit:-1 errno=0\n");
    return EXIT_ODD;
  }
  if (WIFSIGNALED(status)) {
    int sig = WTERMSIG(status);
    if (sig == SIGSYS) {
      printf("dsh-sysprobe v1 set_robust_list=sigsys errno=0\n");
      return EXIT_SIGSYS;
    }
    printf("dsh-sysprobe v1 set_robust_list=signal:%d errno=0\n", sig);
    return EXIT_SIGNAL;
  }
  if (WIFEXITED(status)) {
    int code = WEXITSTATUS(status);
    printf("dsh-sysprobe v1 set_robust_list=allowed errno=%d\n", code);
    return EXIT_ALLOWED;
  }
  printf("dsh-sysprobe v1 set_robust_list=exit:-1 errno=0\n");
  return EXIT_ODD;
}
