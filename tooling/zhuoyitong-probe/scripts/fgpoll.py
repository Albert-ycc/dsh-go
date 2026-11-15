#!/usr/bin/env python3
# 每秒记一次前台窗口的 bundle/ability，配合手机上的操作看哪一步切到了鸿蒙原生页
import json, subprocess, sys, time, os
HDC = os.path.expanduser('~/.local/bin/hdc'); T = os.environ.get('HDC_TARGET')
def run(*a):
    return subprocess.run([HDC, *(['-t', T] if T else []), *a], capture_output=True, text=True, timeout=30).stdout
def wins():
    run('shell', 'uitest', 'dumpLayout', '-p', '/data/local/tmp/fg.json'); run('file', 'recv', '/data/local/tmp/fg.json', '/tmp/fg.json')
    d = json.load(open('/tmp/fg.json')); out = []
    def walk(n):
        a = n['attributes']
        if a.get('bundleName'): out.append(f"{a['bundleName']}/{a.get('abilityName','')}")
        for c in n.get('children', []): walk(c)
    walk(d); return out
t0 = time.time()
while time.time() - t0 < float(sys.argv[1]):
    try: print(f"{time.time()-t0:5.1f}s", wins(), flush=True)
    except Exception as e: print('ERR', e, flush=True)
    time.sleep(0.5)
