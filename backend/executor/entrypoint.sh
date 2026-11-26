#!/bin/sh
# RvPort AI 迁移执行器入口：
#   /app 为 worker 挂载的迁移 workspace（input/ source/ baseline/ output/）。
#   技能随镜像内置在 /root/.pi/agent/skills（构建时从 backend/executor/skills/ 复制，不挂载、目录可写）。
#   迁移指令由环境变量 AI_MIGRATION_PROMPT_FILE 指向的文件传入（长 prompt 不走 argv）。
#   退出码透传 pi；stdout 单行合同由 prompt 保证。
set -eu

: "${WXAI_MODEL:=rch-eco-tool-glm}"
: "${WXAI_BASE_URL:=https://wxai-rch.zx.zte.com.cn/v1}"
PROMPT_FILE="${AI_MIGRATION_PROMPT_FILE:-/app/.prompt.txt}"
SKILL_PATH="${AI_MIGRATION_SKILL_PATH:-/root/.pi/agent/skills/riscv-migrate}"

cd /app

exec pi --print \
  --provider wxai \
  --model "$WXAI_MODEL" \
  --extension /root/.pi/agent/extensions/wxai-provider.mjs \
  --skill "$SKILL_PATH" \
  --no-skills \
  --no-session \
  --no-context-files \
  "$(cat "$PROMPT_FILE")"
