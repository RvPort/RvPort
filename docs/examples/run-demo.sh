#!/usr/bin/env bash
# Java kb-scanner demo 完整运行脚本：在一次性 JDK25 容器内编译运行 demo，
# 并现装 kb-scanner 运行依赖（首次约 5-8 分钟，pip 缓存卷可加速后续运行）。
#
# 用法:
#   ./docs/examples/run-demo.sh [项目目录]   # 单次扫描（缺省仓库 fixture）
#   ./docs/examples/run-demo.sh matrix      # 多语言×多架构矩阵（12 合成用例自检）
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MATRIX_MODE="${1:-}"
if [ "$MATRIX_MODE" = "matrix" ]; then
  DEMO_CLASS="KbScannerMatrixDemo"
  PROJECT_DIR="$REPO_ROOT/tools/kb-scanner/tests/fixtures/architecture-candidate-noise" # 占位挂载
else
  DEMO_CLASS="KbScannerDemo"
  PROJECT_DIR="${1:-$REPO_ROOT/tools/kb-scanner/tests/fixtures/architecture-candidate-noise}"
  [ -d "$PROJECT_DIR" ] || { echo "项目目录不存在: $PROJECT_DIR" >&2; exit 2; }
fi

# 项目目录（任意宿主路径）挂进容器 /project，容器内统一以 /project 引用；
# 产物目录挂进容器 /tmp/demo-out（demo 默认写到该路径，宿主直接可见）
docker run --rm -m 3g \
  -v "$REPO_ROOT/tools/kb-scanner:/workspace/tools/kb-scanner:ro" \
  -v "$REPO_ROOT/docs/examples:/demo" -v ~/.m2:/root/.m2 \
  -v "$PROJECT_DIR:/project:ro" \
  -e KB_SCANNER_DEMO_OUTPUT_DIR=/tmp/demo-out \
  -v "${KB_DEMO_OUTPUT_DIR:-/tmp}/demo-out:/tmp/demo-out" \
  -v kb-scanner-demo-pip-cache:/root/.cache/pip \
  -w /demo eclipse-temurin:25-jdk-noble bash -c '
    set -e
    CP=$(find /root/.m2/repository/com/fasterxml/jackson -name "*.jar" | grep -vE "sources|tests" | tr "\n" ":")
    # python venv + kb-scanner 运行依赖（tree-sitter 原生扩展需编译工具链）
    if [ ! -x /tmp/venv/bin/python ]; then
      apt-get update -qq >/dev/null
      apt-get install -y -qq python3 python3-venv build-essential python3-dev >/dev/null
      python3 -m venv /tmp/venv
      CC=gcc /tmp/venv/bin/pip install -q -r /workspace/tools/kb-scanner/requirements/runtime.lock
    fi
    /tmp/venv/bin/python -c "import tree_sitter, tree_sitter_cmake" # 依赖自检
    # demo 的解释器路径替换为 venv python（改副本，不动仓库文件；类名与文件名保持一致）
    mkdir -p /tmp/build && cd /tmp/build
    sed "s|\"python3\", \"-m\"|\"/tmp/venv/bin/python\", \"-m\"|" /demo/'"$DEMO_CLASS"'.java > '"$DEMO_CLASS"'.java
    javac -cp "$CP" '"$DEMO_CLASS"'.java
    if [ "'"$DEMO_CLASS"'" = "KbScannerDemo" ]; then
      java -cp ".:$CP" KbScannerDemo /project /workspace/tools/kb-scanner
    else
      java -cp ".:$CP" KbScannerMatrixDemo /workspace/tools/kb-scanner
    fi
    # 产物目录归还宿主用户（容器内以 root 写入）
    chown -R '"$(id -u)"':'"$(id -g)"' "${KB_DEMO_OUTPUT_DIR:-/tmp}"/demo-out/kb-scan-demo-* 2>/dev/null || true
  '
