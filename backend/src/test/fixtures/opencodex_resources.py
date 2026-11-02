"""本地候选镜像的单/双实例资源采样；不访问外部模型。"""
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time

sys.path.insert(0, str(Path(__file__).parents[2] / 'main/resources/agentruntime'))
from opencodex_runtime import configuration, wait_ready


def process_sample(roots):
    records = {}
    for entry in Path('/proc').glob('[0-9]*/stat'):
        try:
            fields = entry.read_text().rsplit(')', 1)[1].split()
            records[int(entry.parent.name)] = (int(fields[1]), int(fields[11]) + int(fields[12]), int(fields[21]))
        except (OSError, ValueError):
            continue
    owned = set(roots)
    while True:
        children = {pid for pid, values in records.items() if values[0] in owned}
        if children <= owned:
            break
        owned |= children
    return {'rss_bytes': sum(records[pid][2] for pid in owned if pid in records) * os.sysconf('SC_PAGE_SIZE'),
            'cpu_seconds': sum(records[pid][1] for pid in owned if pid in records) / os.sysconf('SC_CLK_TCK'),
            'processes': len(owned)}


def run(executable, count):
    processes, ports = [], []
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix='rvport-resource-') as temporary:
        try:
            configs = []
            for index in range(count):
                home = Path(temporary) / str(index)
                home.mkdir(mode=0o700)
                with socket.socket() as reservation:
                    reservation.bind(('127.0.0.1', 0))
                    port = reservation.getsockname()[1]
                ports.append(port)
                config = configuration('http://127.0.0.1:9/v1', 'fixture-model', 'local-fixture-key', port, 10)
                path = home / 'config.json'
                path.write_text(json.dumps(config)); path.chmod(0o600)
                environment = {'PATH': os.environ['PATH'], 'HOME': str(home), 'OPENCODEX_HOME': str(home),
                               'CODEX_HOME': str(home / 'codex'), 'OPENCODEX_ADMIN_AUTH_TOKEN': 'local-admin',
                               'OCX_DISABLE_UPDATE_CHECK': '1'}
                process = subprocess.Popen([executable, 'start'], env=environment, start_new_session=True,
                                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                processes.append(process); configs.append((config, home))
            for process, (config, home) in zip(processes, configs):
                wait_ready(process, config, home, 'local-admin', started + 20, threading.Event())
            ready = time.monotonic()
            sample = process_sample([p.pid for p in processes])
            result = {'instances': count, 'ready_ms': round((ready-started)*1000), **sample}
            result['startup_cpu_percent'] = round(sample['cpu_seconds'] / (ready-started) * 100, 1)
        finally:
            cleanup = time.monotonic()
            for process in processes:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
            for process in processes:
                try:
                    process.wait(timeout=.5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL); process.wait(timeout=2)
            for port in ports:
                with socket.socket() as check:
                    check.settimeout(.2)
                    assert check.connect_ex(('127.0.0.1', port)) != 0, 'residual listener'
        result['cleanup_ms'] = round((time.monotonic()-cleanup)*1000)
    assert not Path(temporary).exists()
    result['directories_removed'] = True
    return result


if __name__ == '__main__':
    rows = [run(sys.argv[1], count) for count in (1, 2)]
    Path(sys.argv[2]).write_text(json.dumps(rows, indent=2))
    print(json.dumps(rows, indent=2))
