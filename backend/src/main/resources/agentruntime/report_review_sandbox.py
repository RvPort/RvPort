"""报告复核子进程隔离：输入只读，写入限定为 output 和独立运行缓存。"""
import ctypes
import os
from pathlib import Path
import stat
import sys
import subprocess
import tempfile

READ = (1 << 0) | (1 << 2) | (1 << 3)
WRITE = sum(1 << bit for bit in [1, 4, 5, 6, 7, 8, 9, 10, 11, 12])
UID = 65534


class PathRule(ctypes.Structure):
    _pack_ = 1
    _fields_ = [("allowed_access", ctypes.c_uint64), ("parent_fd", ctypes.c_int32)]


def checked(result):
    if result < 0:
        raise OSError(ctypes.get_errno(), "REPORT_AI_SANDBOX_UNAVAILABLE")
    return result


def protect(path, writable=False):
    """ABI 1 不覆盖 truncate；root 所有权、降权和 DAC 同时保护只读输入。"""
    path = Path(path)
    for root, directories, files in os.walk(path, followlinks=False):
        current = Path(root)
        for entry in [current] + [current / name for name in directories + files]:
            if entry.is_symlink():
                raise ValueError("REPORT_AI_WORKSPACE_PATH_UNSAFE")
            os.chown(entry, UID if writable else 0, UID if writable else 0)
            mode = 0o700 if entry.is_dir() else 0o600
            os.chmod(entry, mode if writable else (0o555 if entry.is_dir() else 0o444))


def restrict(workspace):
    if sys.platform != "linux" or os.geteuid() != 0:
        raise RuntimeError("REPORT_AI_SANDBOX_UNAVAILABLE")
    workspace = Path(workspace).resolve(strict=True)
    for name in ("input", "source"):
        protect(workspace / name)
    os.chmod(workspace, 0o755)
    writable = [workspace / "output", workspace / ".runtime"]
    for path in writable:
        path.mkdir(exist_ok=True)
    (workspace / ".runtime/codex").mkdir(exist_ok=True)
    for path in writable:
        protect(path, True)
    runtime = workspace / ".runtime"
    os.environ.update(HOME=str(runtime), TMPDIR=str(runtime), XDG_CONFIG_HOME=str(runtime / "config"),
                      XDG_CACHE_HOME=str(runtime / "cache"), XDG_DATA_HOME=str(runtime / "data"),
                      CODEX_HOME=str(runtime / "codex"))
    libc = ctypes.CDLL(None, use_errno=True)
    abi = checked(libc.syscall(444, 0, 0, 1))
    handled = READ | WRITE
    if abi >= 2:
        handled |= 1 << 13
    if abi >= 3:
        handled |= 1 << 14
    attributes = ctypes.c_uint64(handled)
    ruleset = checked(libc.syscall(444, ctypes.byref(attributes), ctypes.sizeof(attributes), 0))
    try:
        readonly = [Path(p) for p in ("/usr", "/bin", "/lib", "/lib64", "/opt", "/etc", "/proc/self",
                                     "/proc/cpuinfo", "/proc/meminfo", "/proc/sys", "/dev/urandom")]
        for path, allowed in [(p, READ) for p in readonly + [workspace]] + [(p, handled) for p in writable] + [(Path("/dev/null"), READ | 2)]:
            if not path.exists():
                continue
            fd = os.open(path, os.O_PATH | os.O_CLOEXEC)
            try:
                rights = allowed if stat.S_ISDIR(os.fstat(fd).st_mode) else allowed & ((1 << 0) | (1 << 1) | (1 << 2) | (1 << 14))
                rule = PathRule(rights, fd)
                checked(libc.syscall(445, ruleset, 1, ctypes.byref(rule), 0))
            finally:
                os.close(fd)
        checked(libc.prctl(38, 1, 0, 0, 0))
        checked(libc.syscall(446, ruleset, 0))
        os.setgroups([])
        os.setgid(UID)
        os.setuid(UID)
    finally:
        os.close(ruleset)


def main(argv):
    if argv == ["--self-test"]:
        return self_test()
    if not argv or not os.environ.get("RVPORT_REPORT_REVIEW_SANDBOX"):
        return 126
    # 不向 Agent 继承 worker 的数据库、对象存储、凭据加密等环境变量。
    permitted = {"PATH", "LANG", "LC_ALL", "SSL_CERT_FILE", "SSL_CERT_DIR", "NODE_EXTRA_CA_CERTS",
                 "OPENAI_API_KEY", "OPENAI_BASE_URL", "OPENAI_MODEL", "DEEPSEEK_API_KEY",
                 "RVPORT_AGENT_API_KEY", "RVPORT_AGENT_BASE_URL", "RVPORT_AGENT_MODEL",
                 "OPENCODE_CONFIG",
                 "RVPORT_REPORT_REVIEW_SANDBOX"}
    safe = {key: value for key, value in os.environ.items() if key in permitted}
    os.environ.clear()
    os.environ.update(safe)
    try:
        restrict(os.environ["RVPORT_REPORT_REVIEW_SANDBOX"])
        os.execvp(argv[0], argv)
    except (OSError, ValueError, RuntimeError):
        print("REPORT_AI_SANDBOX_UNAVAILABLE", file=sys.stderr)
        return 126


def self_test():
    """只在独立临时目录验证真实隔离，不继承模型或 Worker 凭据。"""
    try:
        with tempfile.TemporaryDirectory(prefix="rvport-sandbox-check-") as root:
            workspace = Path(root)
            (workspace / "input").mkdir()
            (workspace / "source").mkdir()
            (workspace / "source/probe").write_text("fixed")
            code = """
from pathlib import Path
p=Path('source/probe')
assert p.read_text()=='fixed'
try:
    p.write_text('changed')
except PermissionError:
    pass
else:
    raise RuntimeError('read-only enforcement failed')
Path('output/probe').write_text('writable')
"""
            result = subprocess.run([sys.executable, str(Path(__file__).resolve()), sys.executable, "-c", code],
                                    cwd=root, env={"PATH": "/usr/local/bin:/usr/bin:/bin", "RVPORT_REPORT_REVIEW_SANDBOX": root},
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=8, check=False)
            return 0 if result.returncode == 0 else 126 if result.returncode == 126 else 125
    except (OSError, subprocess.TimeoutExpired):
        return 125


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
