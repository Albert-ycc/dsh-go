#!/usr/bin/env python3
# ui-probe.sh 的读屏小工具：fg（顶层窗口 bundle/ability）、grep（按文字找节点并报所属窗口）、cap（截屏+dump 存盘）
import json, os, re, subprocess, sys, time
HDC = os.path.expanduser('~/.local/bin/hdc')
T = os.environ.get('HDC_TARGET')
def sh(*a, timeout=60):
    for _ in range(5):
        o = subprocess.run([HDC, '-t', T, *a], capture_output=True, text=True, timeout=timeout)
        out = (o.stdout + o.stderr).replace('\r', '')
        if 'Connect server failed' not in out: return out
        time.sleep(1.5)
    return out
def dump(path=None):
    for _ in range(3):
        sh('shell', 'uitest', 'dumpLayout', '-p', '/data/local/tmp/uip.json')
        sh('file', 'recv', '/data/local/tmp/uip.json', '/tmp/uip.json')
        try:
            d = json.load(open('/tmp/uip.json', encoding='utf-8'))
            if path: json.dump(d, open(path, 'w'), ensure_ascii=False)
            return d
        except Exception: time.sleep(1)
    return None
def windows(d):
    return [(c['attributes'].get('bundleName', ''), c['attributes'].get('abilityName', ''), c['attributes'].get('bounds', '')) for c in d['children']]
def walk(n, win, out):
    a = n['attributes']
    out.append((win, a))
    for c in n.get('children', []): walk(c, win, out)
def nodes(d):
    out = []
    for c in d['children']:
        w = c['attributes'].get('bundleName', '') + '/' + c['attributes'].get('abilityName', '')
        walk(c, w, out)
    return out
def cap(prefix):
    sh('shell', 'snapshot_display', '-f', '/data/local/tmp/uip.jpeg')
    sh('file', 'recv', '/data/local/tmp/uip.jpeg', prefix + '.jpeg')
    d = dump(prefix + '.json')
    sh('shell', 'rm', '/data/local/tmp/uip.jpeg', '/data/local/tmp/uip.json')
    return d
if __name__ == '__main__':
    c = sys.argv[1]
    if c == 'fg':
        d = dump()
        print('DUMP_FAIL' if not d else ' | '.join(f'{b}/{a} {bd}' for b, a, bd in windows(d)))
    elif c == 'grep':
        rx = re.compile(sys.argv[2]); d = dump()
        if not d: print('DUMP_FAIL'); sys.exit(2)
        hit = [(w, a['text'] or a.get('description', ''), a['bounds'], a['clickable']) for w, a in nodes(d) if rx.search(a['text'] or '') or rx.search(a.get('description', '') or '')]
        print('WINDOWS', ' | '.join(f'{b}/{a}' for b, a, _ in windows(d)))
        for h in hit: print('HIT', *h)
        if not hit: print('NO_HIT')
    elif c == 'cap':
        d = cap(sys.argv[2]); print('WINDOWS', ' | '.join(f'{b}/{a}' for b, a, _ in windows(d)) if d else 'DUMP_FAIL')
    elif c == 'tap':   # 按文字点（正则，忽略大小写），不用 uitest_tap 的 layout.json
        rx = re.compile(sys.argv[2], re.I)
        for _ in range(6):
            d = dump()
            for w, a in nodes(d or {'children': []}):
                if rx.search(a['text'] or '') and a['clickable'] == 'true':
                    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', a['bounds']); x1, y1, x2, y2 = map(int, m.groups())
                    x, y = (x1 + x2) // 2, (y1 + y2) // 2
                    print(f'TAP {a["text"]!r} {w} @ {x},{y}'); sh('shell', 'uitest', 'uiInput', 'click', str(x), str(y)); sys.exit(0)
            time.sleep(1)
        print('NOT_FOUND', sys.argv[2]); sys.exit(1)
