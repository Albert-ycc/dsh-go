#!/usr/bin/env node
// R10 的请求体与期望值，放在这里避免 shell 引号嵌套。
//   payload.mjs inject            含单双引号、$() 与中文的 text 动作
//   payload.mjs argv              设备端 uitest 应收到的 argv（JSON 数组）
//   payload.mjs canary <dir>      试图在设备 shell 里执行 touch 的 text 动作
const TEXT = 'a\'b"c$(id);d 受试者入组';
const [kind, dir] = process.argv.slice(2);
if (kind === 'inject') console.log(JSON.stringify({ op: 'text', text: TEXT }));
else if (kind === 'argv') console.log(JSON.stringify(['uiInput', 'text', TEXT]));
else if (kind === 'canary') console.log(JSON.stringify({ op: 'text', text: `x$(touch ${dir}/canary)\`touch ${dir}/canary2\`;touch ${dir}/canary3` }));
else process.exit(2);
