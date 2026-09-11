#!/usr/bin/env bash
# 桌面一条命令跑通提取管线（票 01 验收项）。
# 用法: ./run-cli.sh <截图1> [截图2 ...]
set -euo pipefail
cd "$(dirname "$0")"

MODEL="models/Qwen3VL-4B-Instruct-Q4_K_M.gguf"
MMPROJ="models/mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf"
SERVER="third_party/llama.cpp/build/bin/llama-server"

for f in "$MODEL" "$MMPROJ" "$SERVER"; do
  if [ ! -f "$f" ]; then
    echo "缺少 $f —— 先构建 llama-server 并下载模型（见票 01 Comments）" >&2
    exit 1
  fi
done

GRADLE="${GRADLE_BIN:-$HOME/.gradle-dist/gradle-8.14/bin/gradle}"
if [ ! -x "$GRADLE" ]; then
  GRADLE=gradle
fi

"$GRADLE" :cli:run --args="$*"
