// BIONIC_EXTRA_PLUGIN 的归档登记逻辑：入口改写与归档落点（rtbionic.py 的 extra_patch_text / add_extra_plugin）
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const LIB = fileURLToPath(new URL("../lib", import.meta.url));

function py(code, ...args) {
  return spawnSync("python3", ["-I", "-c", `import sys, json; sys.path.insert(0, ${JSON.stringify(LIB)}); import rtbionic as r\n${code}`, ...args], { encoding: "utf8" });
}

const PATCH = `- insert:
    - id: demo
      name: demo-plugin
- insert:
    - id: preset-x
      name: '@deepseek-ai/dsh-agent-preset'
      config:
        id: x
`;

test("入口按包名改写成相对路径，预设等其余内容原样保留", () => {
  const r = py("print(r.extra_patch_text('demo-plugin', './lib/index.js', sys.argv[1]), end='')", PATCH);
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.stdout, PATCH.replace("name: demo-plugin", "name: ./lib/index.js"));
});

test("包名/子路径按 exports 改写，exports 里没有就报错", () => {
  const t = PATCH + "          - id: t\n            name: demo-plugin/tools\n";
  const ok = py("print(r.extra_patch_text('demo-plugin', './lib/index.js', sys.argv[1], {'./tools': './lib/tools.js'}, 'opt/dsh-go/plugins/demo-plugin'), end='')", t);
  assert.equal(ok.status, 0, ok.stderr);
  assert.match(ok.stdout, /name: \.\.\/\.\.\/\.\.\/\.\.\/opt\/dsh-go\/plugins\/demo-plugin\/lib\/tools\.js/);
  assert.notEqual(py("r.extra_patch_text('demo-plugin', './lib/index.js', sys.argv[1], {}, 'x')", t).status, 0);
});

test("包名在 patch 里出现 0 次或多次都报错", () => {
  assert.notEqual(py("r.extra_patch_text('other', './lib/index.js', sys.argv[1])", PATCH).status, 0);
  assert.notEqual(py("r.extra_patch_text('demo-plugin', './lib/index.js', sys.argv[1])", PATCH + PATCH).status, 0);
});

test("add_extra_plugin：整包落 opt/dsh-go/plugins/<包名>/，登记文件在其中，Mac 元数据文件被丢弃", () => {
  const dir = mkdtempSync(join(tmpdir(), "extra-plugin-"));
  mkdirSync(join(dir, "lib"));
  writeFileSync(join(dir, "package.json"), JSON.stringify({ name: "demo-plugin", version: "1.2.3", main: "./lib/index.js" }));
  writeFileSync(join(dir, "cordis.patch.yml"), PATCH);
  writeFileSync(join(dir, "lib/index.js"), "export const name = 'demo-plugin'\n");
  writeFileSync(join(dir, ".DS_Store"), "x");
  writeFileSync(join(dir, "lib/._index.js"), "x");
  const r = py(
    "e={}; rep={'dropped':{},'droppedFiles':{}}\n" +
      "info=r.add_extra_plugin(e, rep, sys.argv[1])\n" +
      "print(json.dumps({'info':info,'files':sorted(p for p,v in e.items() if v.kind=='file'),'patch':e[info['patch'][1:]].data.decode()}))",
    dir,
  );
  assert.equal(r.status, 0, r.stderr);
  const out = JSON.parse(r.stdout);
  assert.deepEqual(out.info, { name: "demo-plugin", version: "1.2.3", path: "/opt/dsh-go/plugins/demo-plugin", patch: "/opt/dsh-go/plugins/demo-plugin/dsh-extra.patch.yml" });
  assert.deepEqual(out.files, [
    "opt/dsh-go/plugins/demo-plugin/cordis.patch.yml",
    "opt/dsh-go/plugins/demo-plugin/dsh-extra.patch.yml",
    "opt/dsh-go/plugins/demo-plugin/lib/index.js",
    "opt/dsh-go/plugins/demo-plugin/package.json",
  ]);
  assert.match(out.patch, /name: \.\/lib\/index\.js/);
});
