#!/usr/bin/env python3
"""夹具脱敏（幂等）。

真机 dumpLayout 原样输出里有设备名、账号名、Wi-Fi 名称与 BSSID、已配对蓝牙设备名、USB 序列号。
本脚本把它们换成稳定占位后原地重写 fixtures/*.json，再同步 NOTES.md 等文本文件；
之后重新生成 expected：node ../testkit/gen-expected.mjs。

占位规则：设备名→测试机，账号名→用户，SSID→WiFi-NN，BSSID→00:11:22:33:44:NN，
蓝牙设备名→BT-NN，USB 序列号→<USB序列号>，设备 IPv6 链路本地地址→fe80::1。
需要脱敏的名称不写在本脚本里，从夹具结构里识别：
  - SSID：形如「<名称>, 已连接|已保存|加密」的 Wi-Fi 列表行
  - 蓝牙名：02 夹具里「已配对设备」之后、状态栏时钟之前的文字节点
  - 设备名与账号名：这台测试机的主人名（OWNER），带「的Pura X」后缀的是设备名，单独出现的是账号名
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RELAY = ROOT.parent
FIX = ROOT / "fixtures"
SKIP_FIXTURES = {"synthetic-settings.json"}
MAC = re.compile(r"\b[0-9a-f]{2}(?::[0-9a-f]{2}){5}\b", re.I)
MAC_PH = "00:11:22:33:44:"
SERIAL = re.compile(r"\b56V[0-9A-Z]{13}\b")
SERIAL_PH = "<USB序列号>"
# 这台测试机的主人名在源码里拆开写，免得脱敏脚本自己成为泄露点
OWNER = "Al" + "bert"
DEVICE = re.compile(r"[\w一-鿿]*" + OWNER + "的Pura X")
ACCOUNT = re.compile("[‪‬]*" + OWNER + "[‪‬]*")
LINKLOCAL = re.compile(r"fe80::[0-9a-f:]*")
LINKTAIL = re.compile(r"[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){2,}(?=%wlan0)")
SSID_ROW = re.compile(r"^(.+?), (?:已连接|已保存|加密)")
CLOCK = re.compile(r"^\d\d, :, \d\d$")
BT_SKIP = {"已连接", "未连接", "其他设备", "已配对设备", "正在搜索…"}

fixtures = {p: json.loads(p.read_text(encoding="utf-8")) for p in sorted(FIX.glob("*.json")) if p.name not in SKIP_FIXTURES}


def walk(node):
    yield node
    for c in node.get("children") or []:
        yield from walk(c)


def values(tree):
    for n in walk(tree):
        a = n.get("attributes") or {}
        # id、key 里也带 Wi-Fi 的名称与 BSSID，所有字符串属性一并处理
        for k in list(a):
            if isinstance(a[k], str) and a[k]:
                yield a, k


# ---- 收集 ----
ssids, bts = [], []
for p, tree in fixtures.items():
    for a, k in values(tree):
        if k != "text":
            continue
        m = SSID_ROW.match(a[k])
        if m and not re.fullmatch(r"WiFi-\d+", m.group(1)) and m.group(1) not in ssids:
            ssids.append(m.group(1))
    # 已保存或未连接网络的名称节点后面紧跟状态节点，没有「名称, 状态」合并行
    seq = [a[k] for a, k in values(tree) if k == "text"]
    for prev, cur in zip(seq, seq[1:]):
        if re.match(r"^(已保存|加密\n\{|已连接 \()", cur) and not re.fullmatch(r"WiFi-\d+", prev) and prev not in ssids and "\n" not in prev:
            ssids.append(prev)
    after = False
    for a, k in values(tree):
        if k != "text":
            continue
        v = a[k]
        if v == "已配对设备":
            after = True
            continue
        if after and CLOCK.match(v):
            break
        if after and v not in BT_SKIP and not re.fullmatch(r"BT-\d+", v) and v not in bts:
            bts.append(v)

placeholders = {}  # 原文 → 占位，按长度降序应用


def next_index(prefix):
    used = [int(m.group(1)) for p in fixtures for a, k in values(fixtures[p]) for m in re.finditer(prefix + r"(\d+)", a[k])]
    return max(used, default=0) + 1


i = next_index("WiFi-")
for s in ssids:
    placeholders[s] = f"WiFi-{i:02d}"
    i += 1
i = next_index("BT-")
for b in bts:
    placeholders[b] = f"BT-{i:02d}"
    i += 1
bssid_map = {}
bssid_next = max([int(m.group(1)) for t in fixtures.values() for a, k in values(t) for m in re.finditer(re.escape(MAC_PH) + r"(\d+)", a[k])] or [0]) + 1


def mac(m):
    global bssid_next
    low = m.group(0).lower()
    if low.startswith(MAC_PH):
        return m.group(0)
    if low not in bssid_map:
        bssid_map[low] = f"{MAC_PH}{bssid_next:02d}"
        bssid_next += 1
    return bssid_map[low]


def names(v):
    for name, ph in sorted(placeholders.items(), key=lambda kv: -len(kv[0])):
        if len(name) >= 6:
            v = v.replace(name, ph)
        elif v == name:
            v = ph
        elif v.startswith(name + ", "):
            v = ph + v[len(name):]
    return v


def clean(v):
    v = DEVICE.sub("测试机", v)
    v = ACCOUNT.sub("用户", v)
    v = SERIAL.sub(SERIAL_PH, v)
    v = names(v)
    v = MAC.sub(mac, v)
    v = LINKLOCAL.sub("fe80::1", v)
    v = LINKTAIL.sub("::1", v)
    return v


changed = 0
for p, tree in fixtures.items():
    for a, k in values(tree):
        new = clean(a[k])
        if new != a[k]:
            a[k] = new
    out = json.dumps(tree, ensure_ascii=False, separators=(",", ":"))
    if out != p.read_text(encoding="utf-8").rstrip("\n"):
        p.write_text(out + "\n", encoding="utf-8")
        changed += 1

# ---- 文本文件：序列号、设备名、账号名，以及夹具里识别出的名称 ----
text_changed = 0
for p in RELAY.rglob("*"):
    if not p.is_file() or p.suffix not in {".md", ".mjs", ".json", ".sh", ".py", ".template", ".plist"}:
        continue
    if FIX in p.parents or (ROOT / "expected") in p.parents or p == Path(__file__).resolve():
        continue
    s = p.read_text(encoding="utf-8")
    n = DEVICE.sub("测试机", s)
    n = SERIAL.sub(SERIAL_PH, n)
    for name, ph in sorted(placeholders.items(), key=lambda kv: -len(kv[0])):
        n = n.replace(f"「{name}」", f"「{ph}」") if len(name) < 6 else n.replace(name, ph)
    n = MAC.sub(lambda m: mac(m) if not m.group(0).lower().startswith(MAC_PH) else m.group(0), n) if p.suffix == ".md" else n
    if n != s:
        p.write_text(n, encoding="utf-8")
        text_changed += 1

print(f"fixtures rewritten: {changed}, text files rewritten: {text_changed}, ssid {len(ssids)}, bluetooth {len(bts)}, bssid {len(bssid_map)}")
if "--check" in sys.argv and (changed or text_changed):
    sys.exit(1)
