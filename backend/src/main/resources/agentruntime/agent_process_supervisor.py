"""Linux 执行级 subreaper：回收 CLI 退出后被遗留的孙进程与管道。"""
import ctypes
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


def children():
    pid = os.getpid()
    return [int(value) for value in Path(f"/proc/{pid}/task/{pid}/children").read_text().split()]


def reap(primary):
    result = None
    while True:
        try:
            pid, status = os.waitpid(-1, os.WNOHANG)
        except ChildProcessError:
            break
        if not pid:
            break
        if pid == primary:
            result = os.waitstatus_to_exitcode(status)
    return result


def terminate_owned():
    # 只操作本 supervisor 的直接子进程；父进程退出后，后代由 subreaper 接管并在下一轮回收。
    for pid in children():
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass


def supervise(process, cancelled, clock=time.monotonic, pause=time.sleep):
    result = None
    cleanup_started = None
    while True:
        code = reap(process.pid)
        if code is not None:
            result = code
            process.returncode = code
        if result is not None or cancelled[0]:
            if cleanup_started is None:
                cleanup_started = clock()
            if not children():
                return 130 if cancelled[0] else (128 - result if result < 0 else result)
            terminate_owned()
            if clock() - cleanup_started >= 1.5:
                print("AGENT_SUPERVISOR_CLEANUP_INCOMPLETE", file=sys.stderr)
                return 125
        pause(0.01)


def main(argv):
    if not argv:
        print("AGENT_SUPERVISOR_COMMAND_REQUIRED", file=sys.stderr)
        return 127
    # PR_SET_CHILD_SUBREAPER 必须成功；不能静默退回可能泄漏孤儿进程的执行方式。
    if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:
        print("AGENT_SUPERVISOR_UNAVAILABLE", file=sys.stderr)
        return 127
    cancelled = [False]

    def cancel(_signum, _frame):
        cancelled[0] = True

    signal.signal(signal.SIGTERM, cancel)
    signal.signal(signal.SIGINT, cancel)
    try:
        process = subprocess.Popen(argv, start_new_session=True)
    except OSError:
        print("AGENT_SUPERVISOR_START_FAILED", file=sys.stderr)
        return 127
    return supervise(process, cancelled)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
