#!/usr/bin/env bash
# 安装仓库内置的 git hooks（push 后自动下载 Actions 构建产物）。
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="$ROOT_DIR/scripts/hooks"
DST_DIR="$ROOT_DIR/.git/hooks"

[ -d "$SRC_DIR" ] || { echo "找不到 $SRC_DIR" >&2; exit 1; }
[ -d "$DST_DIR" ] || { echo "找不到 $DST_DIR（当前目录不是 git 仓库？）" >&2; exit 1; }

for hook in "$SRC_DIR"/*; do
  name="$(basename "$hook")"
  target="$DST_DIR/$name"
  if [ -e "$target" ] && ! grep -q "rikkahub-apk-autodownload" "$target" 2>/dev/null; then
    echo "已存在同名 hook，备份为 $name.bak"
    cp "$target" "$target.bak"
  fi
  cp "$hook" "$target"
  chmod +x "$target"
  echo "已安装: $target"
done

echo "完成。之后每次 push 到 origin/master 都会自动等待构建并下载 APK。"
echo "日志: .git/rikkahub-apk-autodownload.log"
echo "卸载: 删除 .git/hooks/pre-push 即可。"
