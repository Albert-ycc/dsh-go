// bionic 引擎的预加载：装上路径边界适配器（见 paths.mjs）。由 manifest 的 launch.argv 以
// `--import <rootfs>/opt/dsh-go/bionic/preload.mjs` 加载，位置在 bin.js 之前。
// 只含路径，不读取、不写入任何凭据或调用方环境。
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { createBionicPaths } from './paths.mjs';

const self = fileURLToPath(import.meta.url);
const rootfs = self.replace(/\/opt\/dsh-go\/bionic\/preload\.mjs$/u, '');
try {
  if (rootfs === self) throw new Error('preload is outside its runtime path');
  // BionicLaunch 把 rootfs 的规范路径放进 DSH_GO_RUNTIME_ROOT；两边指的必须是同一个目录。
  const declared = process.env.DSH_GO_RUNTIME_ROOT;
  if (declared !== undefined && fs.realpathSync(declared) !== fs.realpathSync(rootfs)) {
    throw new Error(`DSH_GO_RUNTIME_ROOT does not match this rootfs (${rootfs})`);
  }
  if (!fs.statSync(`${rootfs}/root`).isDirectory()) throw new Error(`rootfs has no root/ directory (${rootfs})`);
  const key = Symbol.for('dsh.go.direct');
  if (globalThis[key] === undefined) globalThis[key] = createBionicPaths(rootfs);
} catch (error) {
  console.error(`[dsh-app-bionic] launch failed: ${error instanceof Error ? error.message : String(error)}`);
  throw error;
}

