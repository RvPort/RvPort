"""`python -m kb_scanner` 入口：转发到 cli.main 并以返回码退出进程。"""

from .cli import main


if __name__ == "__main__":
    raise SystemExit(main())

