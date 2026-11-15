#!/usr/bin/env python3
"""bionic 引擎包的组装器（卓易通改造，P1 第 3.3、4、5 节）。由 runtime/build-bionic.sh 调用。

    python3 -I runtime/lib/rtbionic.py rg-info --lock <package-lock.json>
    python3 -I runtime/lib/rtbionic.py assemble ...

与 rtbuild.py 无共享状态：glibc 包的 assemble 围绕 Ubuntu Base / PRoot，不适用这里。
归档逐字节确定：成员按路径分段排序、uid/gid 0、mtime 固定、GNU 格式、xz 预设与线程数固定。
"""
import argparse
import base64
import hashlib
import io
import json
import os
import posixpath
import re
import stat
import subprocess
import sys
import tarfile

DSH_GO_BRIDGE = "opt/dsh-go/dsh-go-bridge"
# 可选的额外本地插件（BIONIC_EXTRA_PLUGIN）：整包进 opt/dsh-go/plugins/<目录名>/，
# 登记用该目录里的 dsh-extra.patch.yml，由 manifest 的 launch.argv 以 --patch 字面量带上，App 侧不用改
EXTRA_PLUGINS_DIR = "opt/dsh-go/plugins"
EXTRA_PATCH_NAME = "dsh-extra.patch.yml"
# 预设内插件（<包名>/<子路径>）按配置树的 baseUrl 解析，不是按 patch 文件所在目录：
# baseUrl = $DSH_HOME/profiles/<profile>/，App 把 DSH_HOME 固定在 <rootfs>/root/.dsh（BionicLaunch.kt plan()），
# 所以从 profile 目录回到 rootfs 是四级 ..；改了 DSH_HOME 的位置这里要跟着改
PROFILE_DIR_TO_ROOTFS = "../../../../"
# bionic 预加载（runtime/bionic/：路径边界适配器），launch.argv 以 --import 加载
BIONIC_PRELOAD_DIR = "opt/dsh-go/bionic"
RG_PACKAGE = "@vscode/ripgrep-linux-arm64"
FLOCK_PACKAGE_DIR = "opt/dsh/node_modules/@deepseek-ai/node-addon-system-android-arm64"
RG_PACKAGE_DIR = "opt/dsh/node_modules/@vscode/ripgrep-android-arm64"
FLOCK_REL = "opt/dsh/node_modules/@deepseek-ai/node-addon-system-android-arm64/bin/bionic/system.node"
RG_REL = "opt/dsh/node_modules/@vscode/ripgrep-android-arm64/bin/rg"
EM_AARCH64 = 183


class Entry:
    __slots__ = ("kind", "mode", "data", "path", "link", "origin")

    def __init__(self, kind, mode, data=None, path=None, link=None, origin=""):
        self.kind, self.mode, self.data, self.path, self.link, self.origin = kind, mode, data, path, link, origin

    def read(self):
        if self.data is not None:
            return self.data
        with open(self.path, "rb") as f:
            return f.read()


class HashingWriter:
    def __init__(self, sink):
        self.sink, self.h, self.n = sink, hashlib.sha256(), 0

    def write(self, b):
        self.h.update(b)
        self.n += len(b)
        self.sink.write(b)
        return len(b)

    def flush(self):
        self.sink.flush()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sri_sha512(path):
    h = hashlib.sha512()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return "sha512-" + base64.b64encode(h.digest()).decode()


# ---------------------------------------------------------------------------
# rg-info：从 DSH 锁文件读 rg 平台包的下载地址与完整性（锁文件即钉死）
# ---------------------------------------------------------------------------
def cmd_rg_info(args):
    lock = json.load(open(args.lock))
    entry = lock["packages"]["node_modules/" + RG_PACKAGE]
    print(json.dumps({"package": RG_PACKAGE, "version": entry["version"],
                      "resolved": entry["resolved"], "integrity": entry["integrity"]}))


# ---------------------------------------------------------------------------
# 裁剪规则（P1 4.2）
# ---------------------------------------------------------------------------
def drop_reason(rel):
    low = rel.lower()
    if "/node-pty/prebuilds/" in "/" + rel or "/node-pty/third_party/" in "/" + rel:
        return "node-pty-prebuilds"
    if "/typescript/" not in "/" + rel and low.endswith((".d.ts", ".d.mts", ".d.cts", ".tsbuildinfo")):
        return "dev-metadata"
    if low.endswith(".map"):  # 含 .d.mts.map / .d.cts.map（595 + 12 个），P1 4.2 写的就是 *.map
        return "dev-metadata"
    return None


def pnpm_drop_reason(rel):
    parts = rel.split("/")
    for i in range(len(parts) - 1):  # pnpm 自带的 macOS / Windows reflink 预编译件
        if parts[i] == "@reflink" and parts[i + 1].startswith("reflink-") and not parts[i + 1].startswith("reflink-linux-"):
            return "foreign-binary"
    return drop_reason(rel)


def add_tree(entries, report, src, dest_root, origin, reason_fn):
    src = os.path.abspath(src)
    for dirpath, dirnames, filenames in os.walk(src):
        dirnames.sort()
        rel_dir = os.path.relpath(dirpath, src)
        rel_dir = "" if rel_dir == "." else rel_dir.replace(os.sep, "/")
        dest_dir = dest_root + ("/" + rel_dir if rel_dir else "")
        entries[dest_dir] = Entry("dir", 0o755, origin=origin)
        for name in list(dirnames):  # 符号链接目录记为符号链接，不进入
            if os.path.islink(os.path.join(dirpath, name)):
                dirnames.remove(name)
                filenames.append(name)
        for name in sorted(filenames):
            full = os.path.join(dirpath, name)
            rel = (rel_dir + "/" if rel_dir else "") + name
            dest = dest_root + "/" + rel
            if os.path.islink(full):
                entries[dest] = Entry("symlink", 0o777, link=os.readlink(full), origin=origin)
                continue
            st = os.lstat(full)
            if not stat.S_ISREG(st.st_mode):
                raise SystemExit("非常规文件: " + full)
            reason = reason_fn(rel)
            if reason:
                report["dropped"][reason] = report["dropped"].get(reason, 0) + st.st_size
                report["droppedFiles"][reason] = report["droppedFiles"].get(reason, 0) + 1
                continue
            entries[dest] = Entry("file", 0o755 if st.st_mode & 0o111 else 0o644, path=full, origin=origin)


def extra_patch_text(pkg_name, main_rel, text, exports=None, plugin_dir=None):
    """插件自带 cordis.patch.yml 里的引用写的是包名（入口 <包名>，预设内插件 <包名>/<子路径>），
    引擎在归档里按包名解析不到；改成相对本 patch 文件的路径（dsh 对相对路径按 patch 文件所在目录解析，与 cwd 无关）。
    入口必须恰有一处，改成相对 patch 文件的 ./<main>；子路径按 package.json 的 exports["./<子路径>"] 解析，
    改成相对 profile 目录的路径（见 PROFILE_DIR_TO_ROOTFS），找不到就报错。其余内容原样保留。"""
    exports = exports or {}
    pat = re.compile(r"^(\s*(?:-\s+)?name:\s*)(['\"]?)%s((?:/[A-Za-z0-9._/-]+)?)\2\s*$" % re.escape(pkg_name), re.M)
    seen = {"main": 0}

    def sub(m):
        rest = m.group(3)
        if not rest:
            target, seen["main"] = main_rel, seen["main"] + 1
        else:
            target = exports.get("." + rest)
            if not isinstance(target, str):
                raise SystemExit("cordis.patch.yml 引用了 %s%s，package.json 的 exports 里没有字符串形式的 \".%s\"" % (pkg_name, rest, rest))
        target = target[2:] if target.startswith("./") else target
        if rest:
            return m.group(1) + PROFILE_DIR_TO_ROOTFS + plugin_dir + "/" + target
        return m.group(1) + "./" + target

    if plugin_dir is None and any(True for _ in pat.finditer(text) if _.group(3)):
        raise SystemExit("引用了子路径但没给 plugin_dir")
    out = pat.sub(sub, text)
    if seen["main"] != 1:
        raise SystemExit("cordis.patch.yml 里应恰有一处 name: %s，实际 %d 处" % (pkg_name, seen["main"]))
    return out


def add_extra_plugin(entries, report, src):
    src = os.path.abspath(src)
    pkg = json.load(open(os.path.join(src, "package.json")))
    name, version, main = pkg["name"], pkg["version"], pkg.get("main")
    if not main:
        raise SystemExit("额外插件的 package.json 缺 main: " + src)
    if not os.path.isfile(os.path.join(src, main)):
        raise SystemExit("额外插件的 main 不存在: " + os.path.join(src, main))
    dirname = re.sub(r"[^A-Za-z0-9._-]", "_", name)
    dest = EXTRA_PLUGINS_DIR + "/" + dirname
    add_tree(entries, report, src, dest, "extra-plugin",
             lambda rel: "mac-metadata" if posixpath.basename(rel) == ".DS_Store" or posixpath.basename(rel).startswith("._") else None)
    patch_rel = dest + "/" + EXTRA_PATCH_NAME
    text = open(os.path.join(src, "cordis.patch.yml"), encoding="utf-8").read()
    entries[patch_rel] = Entry("file", 0o644, origin="extra-plugin", data=extra_patch_text(name, main, text, pkg.get("exports"), dest).encode("utf-8"))
    entries.setdefault(EXTRA_PLUGINS_DIR, Entry("dir", 0o755, origin="implicit"))
    return {"name": name, "version": version, "path": "/" + dest, "patch": "/" + patch_rel}


def read_tgz_member(tgz, name):
    with tarfile.open(tgz, "r:gz") as t:
        return t.extractfile(name).read()


def add_platform_packages(entries, args, report):
    """P1 第 5 节：flock 与 ripgrep 的 android-arm64 平台包，目录与 package.json 在这里创建。"""
    flock_version = json.load(open(os.path.join(args.dsh_prefix, "node_modules/@deepseek-ai/node-addon-system/package.json")))["version"]
    entries[FLOCK_PACKAGE_DIR + "/package.json"] = Entry("file", 0o644, origin="platform", data=(json.dumps({
        "name": "@deepseek-ai/node-addon-system-android-arm64",
        "version": flock_version,
        "description": "bionic flock addon, built by runtime/build-bionic.sh (runtime/android-inproc/addons/flock-android.c)",
        "os": ["android"],
        "cpu": ["arm64"],
    }, indent=2) + "\n").encode())
    entries[FLOCK_REL] = Entry("file", 0o755, path=args.flock_node, origin="platform")

    # rg：校验下载件与锁里的 sha512 完整性一致，再取 package/bin/rg
    got = sri_sha512(args.rg_tgz)
    if got != args.rg_integrity:
        raise SystemExit("rg 包完整性不符: 得到 %s 期望 %s" % (got, args.rg_integrity))
    meta = json.loads(read_tgz_member(args.rg_tgz, "package/package.json"))
    rg = read_tgz_member(args.rg_tgz, "package/bin/rg")
    with tarfile.open(args.rg_tgz, "r:gz") as t:
        extra = sorted(m.name for m in t if m.isreg() and m.name.startswith("package/") and "/bin/" not in m.name
                       and m.name != "package/package.json")
    meta["name"] = "@vscode/ripgrep-android-arm64"
    meta["os"] = ["android"]
    meta["cpu"] = ["arm64"]
    entries[RG_PACKAGE_DIR + "/package.json"] = Entry("file", 0o644, origin="platform", data=(json.dumps(meta, indent=2) + "\n").encode())
    entries[RG_REL] = Entry("file", 0o755, data=rg, origin="platform")
    for name in extra:  # LICENSE 等随包文件
        entries[RG_PACKAGE_DIR + "/" + name[len("package/"):]] = Entry(
            "file", 0o644, data=read_tgz_member(args.rg_tgz, name), origin="platform")
    return {
        "package": RG_PACKAGE, "version": meta["version"],
        "tgzSha256": sha256_file(args.rg_tgz), "tgzIntegrity": got,
        "binSha256": hashlib.sha256(rg).hexdigest(), "binBytes": len(rg),
        "note": "官方 ripgrep 的 linux-arm64 静态 EXEC，原样放入，未在容器内运行过（S11）",
    }


def native_inventory(entries, report):
    """归档里所有原生二进制：ELF 的机器类型、Mach-O / PE 的存在。不在这里裁掉，只报告。"""
    out = []
    for p in sorted(entries):
        e = entries[p]
        if e.kind != "file":
            continue
        if e.data is not None:
            head = e.data[:20]
        else:
            with open(e.path, "rb") as f:
                head = f.read(20)
        kind = machine = None
        if head[:4] == b"\x7fELF":
            kind, machine = "elf", int.from_bytes(head[18:20], "little")
        elif head[:4] in (b"\xcf\xfa\xed\xfe", b"\xca\xfe\xba\xbe", b"\xfe\xed\xfa\xcf"):
            kind = "macho"
        elif head[:2] == b"MZ":
            kind = "pe"
        if kind:
            out.append({"path": p, "kind": kind, "machine": machine})
    report["native"] = out
    wrong = [n for n in out if n["kind"] == "elf" and n["machine"] != EM_AARCH64]
    if wrong:
        raise SystemExit("归档里有非 aarch64 的 ELF: " + ", ".join(n["path"] for n in wrong))
    return out


def normalize(entries):
    for p in list(entries):
        parent = posixpath.dirname(p)
        while parent and parent not in entries:
            entries[parent] = Entry("dir", 0o755, origin="implicit")
            parent = posixpath.dirname(parent)
    symlinks = {p for p, e in entries.items() if e.kind == "symlink"}
    for p in entries:
        anc = posixpath.dirname(p)
        while anc:
            if anc in symlinks:
                raise SystemExit("成员 %s 位于符号链接 %s 之下" % (p, anc))
            if entries[anc].kind != "dir":
                raise SystemExit("成员 %s 位于非目录 %s 之下" % (p, anc))
            anc = posixpath.dirname(anc)
    for p in symlinks:
        e = entries[p]
        if e.link.startswith("/"):
            raise SystemExit("绝对符号链接: %s -> %s" % (p, e.link))
        if posixpath.normpath(posixpath.join(posixpath.dirname(p), e.link)).startswith(".."):
            raise SystemExit("符号链接越出根: %s -> %s" % (p, e.link))
    for e in entries.values():
        if e.kind == "dir":
            e.mode = (e.mode & 0o1777) | 0o700
        elif e.kind == "file":
            e.mode = (e.mode & 0o777) | 0o600


def cmd_assemble(args):
    entries = {}
    report = {"abi": "arm64-v8a", "dropped": {}, "droppedFiles": {}, "warnings": []}

    add_tree(entries, report, args.dsh_prefix, "opt/dsh", "dsh", drop_reason)
    rg_info = add_platform_packages(entries, args, report)
    add_tree(entries, report, os.path.join(args.pnpm_prefix, "node_modules", "pnpm"),
             "opt/pnpm/node_modules/pnpm", "pnpm", pnpm_drop_reason)
    add_tree(entries, report, args.bridge, DSH_GO_BRIDGE, "bridge", lambda rel: None)
    add_tree(entries, report, args.bionic_preload, BIONIC_PRELOAD_DIR, "bionic-preload", lambda rel: None)
    extra = add_extra_plugin(entries, report, args.extra_plugin) if args.extra_plugin else None
    for d in ("root", "root/.dsh", "root/workspace", "tmp"):
        entries[d] = Entry("dir", 0o755, origin="skeleton")
    for d in ("opt", "opt/dsh-go"):
        entries.setdefault(d, Entry("dir", 0o755, origin="implicit"))

    native_inventory(entries, report)
    normalize(entries)

    # 写 tar：GNU 格式、排序、root 属主、固定 mtime，经 xz 压缩
    out_dir = os.path.abspath(args.out_dir)
    assets = os.path.join(out_dir, "assets", "runtime", "bionic")
    jni = os.path.join(out_dir, "jniLibs", "arm64-v8a")
    os.makedirs(assets, exist_ok=True)
    os.makedirs(jni, exist_ok=True)
    archive = os.path.join(assets, "engine-arm64-v8a.tar.xz")
    tmp = archive + ".tmp"
    comp = subprocess.Popen([args.xz, "-T%d" % args.threads, "-%s" % args.xz_preset, "-c"],
                            stdin=subprocess.PIPE, stdout=open(tmp, "wb"))
    hw = HashingWriter(comp.stdin)
    counts = {"dir": 0, "file": 0, "symlink": 0}
    file_bytes = 0
    with tarfile.open(fileobj=hw, mode="w|", format=tarfile.GNU_FORMAT) as tar:
        for p in sorted(entries, key=lambda x: x.split("/")):
            e = entries[p]
            ti = tarfile.TarInfo(p)
            ti.uid = ti.gid = 0
            ti.uname = ti.gname = "root"
            ti.mtime = args.epoch
            ti.mode = e.mode
            counts[e.kind] += 1
            if e.kind == "dir":
                ti.type = tarfile.DIRTYPE
                tar.addfile(ti)
            elif e.kind == "symlink":
                ti.type = tarfile.SYMTYPE
                ti.linkname = e.link
                tar.addfile(ti)
            else:
                data = e.read()
                ti.size = len(data)
                file_bytes += len(data)
                tar.addfile(ti, io.BytesIO(data))
    comp.stdin.close()
    if comp.wait() != 0:
        raise SystemExit("xz 失败")
    os.replace(tmp, archive)

    # jniLibs：node 原样改名
    for src, name in ((args.node, "libdsh_node.so"), (args.sysprobe, "libdsh_sysprobe.so")):
        dst = os.path.join(jni, name)
        with open(src, "rb") as a, open(dst, "wb") as b:
            b.write(a.read())
        os.chmod(dst, 0o755)
    jni_info = {
        n: {"sha256": sha256_file(os.path.join(jni, n)), "bytes": os.path.getsize(os.path.join(jni, n)), "source": s}
        for n, s in (("libdsh_node.so", "runtime/android-inproc/out/arm64-v8a/node (Node %s, NDK build, 原样改名)" % args.node_version),
                     ("libdsh_sysprobe.so", "runtime/android-inproc/sysprobe/probe.c"))
    }

    archive_sha = sha256_file(archive)
    tar_sha = hw.h.hexdigest()
    patches = {d: sorted(n for n in os.listdir(p) if n.endswith(".diff")) for d, p in (("main", args.patches_main), ("bionic", args.patches_bionic))}
    runtime_version = "bionic-%s-dsh%s-node%s-%s" % (args.revision, args.dsh_version, args.node_version, tar_sha[:12])
    native_addons = {FLOCK_REL: hashlib.sha256(entries[FLOCK_REL].read()).hexdigest()}
    launch_argv = [
        "{NATIVE_LIB_DIR}/libdsh_node.so",
        "--expose-internals",
        "--import", "{ROOTFS}/" + BIONIC_PRELOAD_DIR + "/preload.mjs",
        "{ROOTFS}/opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js",
        "{PROFILE_ARGS}",
        "--patch", "{ROOTFS}/etc/dsh-app/dsh-web.patch.yml",
        "{EXTRA_PATCHES}",
    ]
    if extra:  # 固定在 App 的可选覆盖层之后；safe 模式下也会带上（argv 里没法按模式分支）
        launch_argv += ["--patch", "{ROOTFS}" + extra["patch"]]
    launch_argv += ["--host", "127.0.0.1", "--port", "{PORT}", "--no-open"]
    manifest = {
        "schema": 1,
        "family": "bionic",
        "runtimeVersion": runtime_version,
        "abi": "arm64-v8a",
        "rootfs": os.path.basename(archive),
        "rootfsFormat": "tar.xz",
        "rootfsSha256": archive_sha,
        "rootfsBytes": os.path.getsize(archive),
        "tarBytes": hw.n,
        "tarSha256": tar_sha,
        # 解压后落在 filesDir 的字节数，加上 nativeLibraryDir 里的 node 与探针：引导页「存储约 N MB」和空间检查按这个值（P1 4.3）
        "uncompressedBytes": file_bytes + sum(v["bytes"] for v in jni_info.values()),
        "rootfsUncompressedBytes": file_bytes,
        "entries": counts,
        "dshVersion": args.dsh_version,
        "nodeVersion": args.node_version,
        "pnpmVersion": args.pnpm_version,
        "dshPatches": patches["main"] + ["bionic/" + n for n in patches["bionic"]],
        "dshGoBridge": {"version": args.bridge_version, "path": "/" + DSH_GO_BRIDGE},
        "jniLibs": jni_info,
        "bionic": {
            "node": "libdsh_node.so",
            "tree": "opt/dsh",
            "preload": BIONIC_PRELOAD_DIR + "/preload.mjs",
            "nativeAddons": native_addons,
            "ripgrep": RG_REL,
            "ripgrepSource": rg_info,
        },
        "launch": {
            "argv": launch_argv,
            "env": {
                # link-fallback 只能经 NODE_OPTIONS 进持久化 worker（它以 execArgv: [] 启动），见 runtime/bionic/link-fallback.mjs
                "NODE_OPTIONS": "--dns-result-order=ipv4first --import {ROOTFS}/" + BIONIC_PRELOAD_DIR + "/link-fallback.mjs",
                "pnpm_config_registry": "https://registry.npmmirror.com/",
                "pnpm_config_package_import_method": "copy",
                "npm_config_package_import_method": "copy",
                "LANG": "C.UTF-8",
                "TERM": "xterm-256color",
            },
            "placeholders": {
                "{PROFILE_ARGS}": "web, or in safe mode `--profile dsh-app-safe --from-default-profile web --patch <safe overlay>` (SafeMode.SAFE_PROFILE_ARGS with GuestPaths applied, split on spaces)",
                "{EXTRA_PATCHES}": "zero or more `--patch <file>` pairs, order: speech, auth, search, phone (same as the proot template's insertion order)",
            },
            "tokenLine": {"stream": "stdout", "regex": "^dsh web: (http://127\\.0\\.0\\.1:[0-9]+/)\\?token=([A-Za-z0-9_-]+)"},
            "stop": {"pidFile": "{ROOTFS}/tmp/dsh-web.pid",
                     "graceful": "SIGTERM to the pid in pidFile; destroyForcibly after 5 s; then killStrays"},
        },
        "build": {
            "revision": args.revision,
            "sourceDateEpoch": args.epoch,
            "xz": {"preset": args.xz_preset, "threads": args.threads, "version": args.xz_version},
            "dshLockSha256": args.dsh_lock_sha256,
            "pnpmIntegrity": args.pnpm_integrity,
            "nodeSha256": jni_info["libdsh_node.so"]["sha256"],
        },
    }
    if extra:
        manifest["extraPlugin"] = extra
        report["extraPlugin"] = extra
    with open(os.path.join(assets, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    report.update({"archiveSha256": archive_sha, "archiveBytes": manifest["rootfsBytes"], "tarSha256": tar_sha,
                   "counts": counts, "fileBytes": file_bytes})
    with open(os.path.join(out_dir, "build-report.json"), "w") as f:
        json.dump(report, f, indent=2, sort_keys=True)
        f.write("\n")
    nm = sum(1 for p, e in entries.items() if e.kind == "file" and p.startswith("opt/dsh/node_modules/"))
    print("archive %s: %d files (%d under opt/dsh/node_modules), %d dirs, %d symlinks, %.1f MiB unpacked, %.1f MiB xz"
          % (archive, counts["file"], nm, counts["dir"], counts["symlink"], file_bytes / 2**20, manifest["rootfsBytes"] / 2**20))
    print("rootfsSha256 " + archive_sha)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("rg-info")
    p.add_argument("--lock", required=True)
    p.set_defaults(fn=cmd_rg_info)

    p = sub.add_parser("assemble")
    for opt in ("dsh-prefix", "pnpm-prefix", "bridge", "bionic-preload", "flock-node", "rg-tgz", "rg-integrity", "node", "sysprobe",
                "out-dir", "patches-main", "patches-bionic", "dsh-version", "node-version", "pnpm-version",
                "bridge-version", "dsh-lock-sha256", "pnpm-integrity", "revision", "xz", "xz-version"):
        p.add_argument("--" + opt, required=True)
    p.add_argument("--extra-plugin", default=None)
    p.add_argument("--epoch", type=int, default=1767225600)
    p.add_argument("--xz-preset", default="6")
    p.add_argument("--threads", type=int, default=4)
    p.set_defaults(fn=cmd_assemble)
    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
