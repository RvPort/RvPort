#!/bin/sh
# RvPort AI 迁移执行器入口：
#   /app 为 worker 挂载的迁移 workspace（input/ source/ baseline/ output/）。
#   技能随镜像内置在 /root/.pi/agent/skills（构建时从 backend/executor/skills/ 复制，不挂载、目录可写）。
#   迁移指令由环境变量 AI_MIGRATION_PROMPT_FILE 指向的文件传入（长 prompt 不走 argv）。
#   退出码透传 pi；stdout 单行合同由 prompt 保证。
#   模型连接信息（WXAI_MODEL / WXAI_API_KEY / WXAI_BASE_URL）一律来自用户在
#   平台「模型配置」中的 provider 设置，由 worker 注入容器环境；本脚本不内置
#   任何默认值——缺失即快速失败，提示用户去补全模型配置。
set -eu

: "${WXAI_MODEL:?WXAI_MODEL 未注入：请在模型配置中设置 provider 与模型名}"
: "${WXAI_API_KEY:?WXAI_API_KEY 未注入：API Key 来自用户模型配置}"
: "${WXAI_BASE_URL:?WXAI_BASE_URL 未注入：Base URL 来自用户模型配置}"
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
