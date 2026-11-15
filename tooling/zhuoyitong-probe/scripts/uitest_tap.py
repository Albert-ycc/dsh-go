#!/usr/bin/env python3
# 鸿蒙 uitest 小助手：按文字找控件并点击
import json, os, re, subprocess, sys, time
HDC = os.path.expanduser('~/.local/bin/hdc')
def sh(*args, timeout=30):
    tgt = ['-t', os.environ['HDC_TARGET']] if os.environ.get('HDC_TARGET') else []
    return subprocess.run([HDC, *tgt, *args], capture_output=True, text=True, timeout=timeout).stdout.replace('\r','')
def dump():
    import tempfile
    out = os.path.join(tempfile.gettempdir(), 'zyt-layout-%d.json' % os.getpid())
    if os.path.exists(out): os.remove(out)
    for _ in range(4):
        sh('shell', 'uitest', 'dumpLayout', '-p', '/data/local/tmp/layout.json')
        sh('file', 'recv', '/data/local/tmp/layout.json', out)
        if os.path.exists(out): break
        time.sleep(2)
    return json.load(open(out, encoding='utf-8'))
def nodes(d):
    out = []
    def walk(n):
        a = n.get('attributes', {})
        out.append(a)
        for c in n.get('children', []): walk(c)
    walk(d); return out
def find(pattern, attempts=6):
    rx = re.compile(pattern)
    for _ in range(attempts):
        for a in nodes(dump()):
            t = a.get('text', '') or ''
            if rx.search(t):
                m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', a.get('bounds', ''))
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    return t, (x1 + x2) // 2, (y1 + y2) // 2
        time.sleep(1)
    return None
def click(x, y):
    sh('shell', 'uitest', 'uiInput', 'click', str(x), str(y))
def texts():
    return [ (a.get('bounds',''), (a.get('text') or '')[:50]) for a in nodes(dump()) if (a.get('text') or '').strip() ]
def shot(name):
    sh('shell', 'snapshot_display', '-f', f'/data/local/tmp/{name}.jpeg')
    sh('file', 'recv', f'/data/local/tmp/{name}.jpeg', f'./{name}.jpeg')
if __name__ == '__main__':
    cmd = sys.argv[1]
    if cmd == 'tap':
        for pat in sys.argv[2:]:
            r = find(pat)
            if not r: print(f'未找到: {pat}'); sys.exit(1)
            print(f'点 {r[0]!r} @ {r[1]},{r[2]}'); click(r[1], r[2]); time.sleep(2.5)
    elif cmd == 'texts':
        for b, t in texts(): print(b, t)
    elif cmd == 'shot':
        shot(sys.argv[2]); print('ok')
