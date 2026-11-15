#!/usr/bin/env node
import { createRelay } from './lib/relay.mjs';
import { StartupError } from './lib/errors.mjs';

let relay;
try {
  relay = createRelay();
  const bound = await relay.start();
  console.log(`dsh-relay listening: ${bound.map((b) => `${b.plain ? 'http' : 'https'}://${b.address}:${b.port}`).join(' ')}`);
} catch (e) {
  if (e instanceof StartupError) {
    // 启动期配置/环境错误：一行说明，不打栈。launchd 的 ThrottleInterval 会按 10 秒节奏重试，所以信息要一眼看懂
    console.error(`dsh-relay: ${e.message}`);
    process.exit(e.exitCode);
  }
  throw e;
}
// 未处理的 rejection 只记日志不退出：单个请求的意外不能让常驻服务掉线（掉线期间手机端全部失败）
process.on('unhandledRejection', (reason) => {
  const msg = String(reason?.stack ?? reason).slice(0, 600);
  console.error(`dsh-relay: unhandledRejection ${msg}`);
  relay.log.write({ kind: 'ctl', op: 'unhandled_rejection', msg: msg.slice(0, 300), ok: false });
});
for (const sig of ['SIGTERM', 'SIGINT']) {
  process.on(sig, async () => {
    await relay.stop();
    process.exit(0);
  });
}
