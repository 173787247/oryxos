#!/usr/bin/env python3
"""Fault injection test ONLY for the dedicated kind-oryxos-042-nfs fixture.

Requires direct Pod forwards on 18046/18047. Pauses its NFS task, probes
5-second HTTP rejection after readiness DOWN, then resumes in finally.
Creates unique test Agents if rejection is broken; never uses production.
Run only while this isolated fixture has no other testers or writers.
"""
import concurrent.futures, json, subprocess, time, urllib.request, urllib.error, uuid
from pathlib import Path
ctx = 'kind-oryxos-042-nfs'
ns = 'oryxos-nfs'
node = 'oryxos-042-nfs-control-plane'
e = {'deadline_seconds': 5, 'requests': [], 'scope': 'direct Pod HTTP after readiness DOWN, NFSv4.1 hard mount'}

def get(port, path, method='GET', body=None, timeout=5):
    t = time.monotonic()
    r = urllib.request.Request(f'http://127.0.0.1:{port}' + path, method=method, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(r, timeout=timeout) as x:
            return {'status': x.status, 'seconds': time.monotonic() - t, 'body': json.load(x)}
    except urllib.error.HTTPError as x:
        return {'status': x.code, 'seconds': time.monotonic() - t, 'body': json.load(x)}
    except Exception as x:
        return {'status': None, 'seconds': time.monotonic() - t, 'error': type(x).__name__}
pods = json.loads(subprocess.check_output(['kubectl', '--context', ctx, '-n', ns, 'get', 'pods', '-l', 'app=nfs-provisioner', '-o', 'json']))['items']
cid = pods[0]['status']['containerStatuses'][0]['containerID'].split('://')[1]

def task(op):
    subprocess.run(['docker', 'exec', node, 'ctr', '-n', 'k8s.io', 'tasks', op, cid], check=True, timeout=10)
ports = [18046, 18047]
assert all((get(p, '/actuator/health/readiness')['status'] == 200 for p in ports))
names = ['blocked-' + uuid.uuid4().hex[:12] for p in ports]
try:
    task('pause')
    t = time.monotonic()
    while time.monotonic() - t < 40:
        hs = [get(p, '/actuator/health/readiness') for p in ports]
        if all((h['status'] == 503 for h in hs)):
            break
        time.sleep(1)
    assert all((h['status'] == 503 for h in hs)), hs
    e['down_after_seconds'] = time.monotonic() - t

    def write(i):
        r = get(ports[i], '/api/v1/agents', 'POST', {'name': names[i], 'description': 'blocked NFS write probe', 'provider': 'mock', 'model': 'mock'})
        return dict(r, port=ports[i], name=names[i])
    with concurrent.futures.ThreadPoolExecutor(2) as x:
        e['requests'] = list(x.map(write, range(2)))
finally:
    task('resume')
t = time.monotonic()
while time.monotonic() - t < 90:
    if all((get(p, '/actuator/health/readiness')['status'] == 200 for p in ports)):
        break
    time.sleep(1)
e['recovered_health'] = [get(p, '/actuator/health/readiness')['status'] for p in ports]
assert e['recovered_health'] == [200, 200], e['recovered_health']
time.sleep(3)
e['after_recovery'] = [dict(get(ports[i], '/api/v1/agents/' + name), name=name) for i, name in enumerate(names)]
e['control_writes'] = [get(p, '/api/v1/agents', 'POST', {'name': 'recovered-' + uuid.uuid4().hex[:12], 'description': 'healthy control', 'provider': 'mock', 'model': 'mock'}) for p in ports]
e['passed'] = all((r['status'] == 200 for r in e['control_writes'])) and all((r['status'] == 503 and r['seconds'] <= 5 for r in e['requests'])) and all((r['status'] == 404 for r in e['after_recovery']))
Path('/tmp/oryxos-042-nfs/blocked-write-fixed-evidence.json').write_text(json.dumps(e, indent=2) + '\n')
print(json.dumps(e, indent=2))
raise SystemExit(0 if e['passed'] else 1)
