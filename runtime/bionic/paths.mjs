import { posix } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * bionic 引擎的路径边界适配器。
 *
 * Dart 客户端、会话元数据与配置里记录的是 guest 绝对路径（`/root/workspace`、`/root/.dsh`、
 * `/tmp`、`/opt/dsh`…）。PRoot 里这些路径真实存在；Direct 由 `runtime/direct/preload.mjs` 装
 * `createPaths` 适配器，在引擎的类型化边界（`zz-direct-runtime.diff`）把它们映射成物理路径；
 * bionic 引擎跑在物理路径世界里，没有 `/root` 视图，同一套边界需要同一个契约的适配器。
 *
 * 与 Direct 的区别只有一条：这里只改写 guest 前缀，其余绝对路径（`/sdcard`、`/system`、
 * `/data`…）在容器里本来就真实可用，原样放行。前缀表与 Kotlin `GuestPaths.MAPPED` 一致，
 * 两边不一致就是缺陷（`runtime/tests/bionic-paths.test.mjs` 对拍）。
 */
export const GUEST_PREFIXES = Object.freeze([
  '/opt/dsh-app', '/opt/dsh-go', '/opt/dsh', '/opt/pnpm',
  '/etc/dsh-app', '/var/lib/dsh-app', '/root', '/tmp',
]);

const within = (value, root) => value === root || value.startsWith(`${root}/`);

export function createBionicPaths(rootfs) {
  if (typeof rootfs !== 'string' || !rootfs.startsWith('/') || rootfs === '/') {
    throw new Error('bionic paths: rootfs must be a non-root absolute directory');
  }
  const root = rootfs.replace(/\/+$/u, '');
  const guestPrefixOf = (value) => GUEST_PREFIXES.find((prefix) => within(value, prefix));
  function toHost(value) {
    if (typeof value !== 'string' || !value.startsWith('/')) return value;
    if (within(value, root)) return value;
    return guestPrefixOf(value) ? root + value : value;
  }
  function toGuest(value) {
    if (typeof value !== 'string' || !within(value, root)) return value;
    const rest = value.slice(root.length) || '/';
    return guestPrefixOf(rest) ? rest : value;
  }
  function cwd(value) {
    return value === undefined ? undefined : toHost(value instanceof URL ? fileURLToPath(value) : value);
  }
  const executable = (value) => typeof value === 'string' && value.startsWith('/')
    ? posix.normalize(toHost(value)) : value;
  // 没有 PRoot 假硬链接，也没有被更具体挂载遮住的宿主目录：两项检查在 bionic 上恒为「可用」。
  const requiresProot = () => false;
  const containsExecutionPath = () => true;
  // 子进程本来就在物理路径世界里起，返回 undefined 让 dsh-subprocess-local 走原分支。
  const command = () => undefined;
  // 建会话时的目录边界：映射后交给 mkdir -p；相对路径按进程 cwd 解析。
  async function prepareDirectory(value) {
    const mapped = toHost(value);
    if (typeof mapped !== 'string' || mapped.length === 0) throw new TypeError('bionic paths: directory must be a nonempty string');
    return posix.isAbsolute(mapped) ? mapped : posix.join(process.cwd(), mapped);
  }
  return Object.freeze({ root, within, toHost, toGuest, cwd, executable, requiresProot, containsExecutionPath, command, prepareDirectory });
}
