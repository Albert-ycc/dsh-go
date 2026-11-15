#!/usr/bin/env node
// 设备端 uitest 替身：把收到的 argv 原样记下来，回显 No Error。
import fs from 'node:fs';
import path from 'node:path';
fs.appendFileSync(path.join(process.env.FAKE_HDC_DIR, 'uitest.log'), JSON.stringify(process.argv.slice(2)) + '\n');
process.stdout.write('No Error\n');
