// bionic 引擎的硬链接回退。卓易通里的 App 数据目录不许建硬链接：link() 被拒，返回 EACCES
// （有的文件系统返回 EPERM）。引擎用 link(暂存文件, 正式文件) 做「目标不存在才发布」：会话持久化
// worker 落盘新一代会话、首次落盘会话日志都走这一步，失败时每轮对话一开头就报错。
//
// 这两种拒绝退回到 copyFile(COPYFILE_EXCL) 再 fsync：目标已存在照样报 EEXIST，暂存文件原样保留，
// 由调用方照旧删除。其余错误与成功路径不变。
//
// 由 manifest 的 launch.env.NODE_OPTIONS 以 --import 加载：持久化 worker 以 execArgv: [] 启动，
// 命令行上的 --import 进不去，NODE_OPTIONS 在主线程、worker 与子进程里都生效。
import fs from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';

const MARK = Symbol.for('dsh.go.linkFallback');
const refused = (error) => error?.code === 'EACCES' || error?.code === 'EPERM';

/** 给 [target]（默认 node:fs）装回退；重复调用无副作用。 */
export function installLinkFallback(target = fs) {
  if (target[MARK]) return;
  const { COPYFILE_EXCL } = fs.constants;
  const link = target.link;
  const linkSync = target.linkSync;
  const linkAsync = target.promises.link;

  function copySync(existing, newPath) {
    target.copyFileSync(existing, newPath, COPYFILE_EXCL);
    const fd = target.openSync(newPath, 'r');
    try { target.fsyncSync(fd); } finally { target.closeSync(fd); }
  }
  async function copy(existing, newPath) {
    await target.promises.copyFile(existing, newPath, COPYFILE_EXCL);
    const handle = await target.promises.open(newPath, 'r');
    try { await handle.sync(); } finally { await handle.close(); }
  }

  target.linkSync = function linkSyncWithFallback(existing, newPath) {
    try {
      return linkSync.call(target, existing, newPath);
    } catch (error) {
      if (!refused(error)) throw error;
      copySync(existing, newPath);
    }
  };
  target.promises.link = async function linkWithFallback(existing, newPath) {
    try {
      return await linkAsync.call(target.promises, existing, newPath);
    } catch (error) {
      if (!refused(error)) throw error;
      await copy(existing, newPath);
    }
  };
  target.link = function linkCallbackWithFallback(existing, newPath, callback) {
    link.call(target, existing, newPath, (error) => {
      if (!refused(error)) return callback(error);
      copy(existing, newPath).then(() => callback(null), callback);
    });
  };
  Object.defineProperty(target, MARK, { value: true });
  // `import { link } from 'node:fs/promises'` 这类具名导入要跟着换。
  if (target === fs) syncBuiltinESMExports();
}

installLinkFallback();
