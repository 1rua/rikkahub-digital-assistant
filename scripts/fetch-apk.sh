#!/usr/bin/env bash
#
# 等待 GitHub Actions 构建完成，并把构建产物（APK）自动下载到本地。
#
# 用法示例：
#   ./scripts/fetch-apk.sh                  # 下载最近一次成功构建的 Debug APK（不等待）
#   ./scripts/fetch-apk.sh --watch          # 若最新构建仍在进行，等它结束再下载
#   ./scripts/fetch-apk.sh --new            # 等待一个新构建出现并完成，然后下载（用于 push 后挂机）
#   ./scripts/fetch-apk.sh --release        # 从 Nightly Release 下载 Release APK（无需解压）
#
# 可选参数：
#   --workflow <file>   指定 workflow 文件（默认 ci.yml；可用 daily-build.yml）
#   --artifact <name>   指定 artifact 名称（默认 rikkahub-debug-apk）
#   --out <dir>         输出目录（默认 ~/Downloads/rikkahub）
#   --timeout <min>     单次等待的最长分钟数（默认 90）
#   --poll <sec>        轮询间隔秒数（默认 20）
#   --notify            结束时发送桌面通知（需要 notify-send）
#   -h, --help          显示帮助
#
# 退出码：0 下载成功；10 构建失败；11 等待超时；12 没有可下载的构建；1 环境/参数错误
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"

MODE=latest              # latest | watch | new
SOURCE=artifact          # artifact | release
WORKFLOW=ci.yml
ARTIFACT=rikkahub-debug-apk
RELEASE_TAG=nightly
OUT_DIR="${HOME}/Downloads/rikkahub"
TIMEOUT_MIN=90
NEW_RUN_TIMEOUT_MIN="${RIKKAHUB_NEW_RUN_TIMEOUT_MIN:-10}"  # --new 模式下等待"新构建出现"的最长时间
POLL=20
NOTIFY=0
LOCK_FILE="${TMPDIR:-/tmp}/rikkahub-fetch-apk.lock"

RUN_INFO=""

log()  { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
warn() { printf '[%s] 警告: %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }
die()  { printf '[%s] 错误: %s\n' "$(date '+%H:%M:%S')" "$*" >&2; exit "${2:-1}"; }

usage() {
  sed -n '3,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

notify() {
  [ "$NOTIFY" = 1 ] || return 0
  command -v notify-send >/dev/null 2>&1 || return 0
  notify-send -a "RikkaHub CI" "$1" "${2:-}" >/dev/null 2>&1 || true
}

while [ $# -gt 0 ]; do
  case "$1" in
    --watch|-w)       MODE=watch ;;
    --new|-n)         MODE=new ;;
    --release|-r)     SOURCE=release ;;
    --workflow)       WORKFLOW="${2:?--workflow 需要参数}"; shift ;;
    --artifact)       ARTIFACT="${2:?--artifact 需要参数}"; shift ;;
    --out|-o)         OUT_DIR="${2:?--out 需要参数}"; shift ;;
    --timeout)        TIMEOUT_MIN="${2:?--timeout 需要参数}"; shift ;;
    --poll)           POLL="${2:?--poll 需要参数}"; shift ;;
    --notify)         NOTIFY=1 ;;
    -h|--help)        usage; exit 0 ;;
    *)                die "未知参数: $1（使用 --help 查看帮助）" ;;
  esac
  shift
done

for cmd in gh jq; do
  command -v "$cmd" >/dev/null 2>&1 || die "缺少命令: $cmd（请先安装）"
done
gh auth status >/dev/null 2>&1 || die "gh 未登录，请先执行: gh auth login"

REPO="${REPO:-}"
if [ -z "$REPO" ]; then
  url="$(git -C "$ROOT_DIR" remote get-url origin 2>/dev/null || true)"
  [ -n "$url" ] || die "无法确定仓库地址，请设置环境变量 REPO=owner/name"
  url="${url%.git}"; url="${url#*github.com}"; url="${url#:}"; url="${url#/}"
  REPO="$url"
fi
log "仓库: $REPO | 来源: $SOURCE | 模式: $MODE"

# 单实例锁：避免连续 push 时启动多个监听进程
if command -v flock >/dev/null 2>&1; then
  exec 9>"$LOCK_FILE"
  flock -n 9 || die "已有下载任务在运行（如需强制并行请设置 LOCK_FILE 为其他路径）"
fi

list_runs() {
  gh run list --repo "$REPO" --workflow "$WORKFLOW" --limit 30 \
    --json databaseId,status,conclusion,number,headSha,createdAt,displayTitle,url
}

wait_run() {
  local id="$1" deadline=$(( $(date +%s) + TIMEOUT_MIN * 60 ))
  while :; do
    RUN_INFO="$(gh run view "$id" --repo "$REPO" \
      --json databaseId,status,conclusion,number,headSha,displayTitle,url)"
    if [ "$(jq -r '.status' <<< "$RUN_INFO")" = "completed" ]; then
      return 0
    fi
    if [ "$(date +%s)" -gt "$deadline" ]; then
      return 11
    fi
    log "构建进行中… run #$(jq -r '.number // "?"' <<< "$RUN_INFO")（$(date '+%H:%M:%S') 检查）"
    sleep "$POLL"
  done
}

assert_success() {
  local conclusion
  conclusion="$(jq -r '.conclusion // "unknown"' <<< "$RUN_INFO")"
  [ "$conclusion" = "success" ] || return 1
}

report_failure() {
  local number url
  number="$(jq -r '.number' <<< "$RUN_INFO")"
  url="$(jq -r '.url' <<< "$RUN_INFO")"
  local conclusion
  conclusion="$(jq -r '.conclusion // "unknown"' <<< "$RUN_INFO")"
  warn "构建 #${number} 未成功（$conclusion）：$url"
  warn "查看失败日志: gh run view $(jq -r '.databaseId' <<< "$RUN_INFO") --repo $REPO --log-failed"
  notify "构建失败 #${number}" "$conclusion — $url"
}

download_artifact() {
  local id="$1" tmp apk number sha label target
  tmp="$(mktemp -d)"
  log "下载 artifact「$ARTIFACT」…"
  if ! gh run download "$id" --repo "$REPO" --name "$ARTIFACT" --dir "$tmp"; then
    rm -rf "$tmp"
    die "下载 artifact 失败（该构建可能未输出 artifact）"
  fi
  apk="$(find "$tmp" -type f -name '*.apk' | head -n 1)"
  [ -n "$apk" ] || { rm -rf "$tmp"; die "artifact 中未找到 .apk 文件"; }

  number="$(jq -r '.number' <<< "$RUN_INFO")"
  sha="$(jq -r '.headSha' <<< "$RUN_INFO")"
  label="${ARTIFACT%-apk}"
  mkdir -p "$OUT_DIR"
  target="$OUT_DIR/${label}-r${number}-${sha:0:7}.apk"
  mv -f "$apk" "$target"
  rm -rf "$tmp"

  log "已下载: $target（$(du -h "$target" | cut -f1)）"
}

download_release() {
  local tmp apk target
  tmp="$(mktemp -d)"
  log "从 Release「$RELEASE_TAG」下载 APK…"
  if ! gh release download "$RELEASE_TAG" --repo "$REPO" --pattern '*.apk' --dir "$tmp" --clobber; then
    rm -rf "$tmp"
    die "下载 Release 失败（Nightly 可能尚未发布）"
  fi
  apk="$(find "$tmp" -type f -name '*.apk' | head -n 1)"
  [ -n "$apk" ] || { rm -rf "$tmp"; die "Release 中未找到 .apk 文件"; }

  mkdir -p "$OUT_DIR"
  target="$OUT_DIR/rikkahub-${RELEASE_TAG}.apk"
  mv -f "$apk" "$target"
  rm -rf "$tmp"

  log "已下载: $target（$(du -h "$target" | cut -f1)）"
}

finish() {
  log "完成，产物目录: $OUT_DIR"
}

# ---------- Release 来源：直接取最新 Nightly ----------
if [ "$SOURCE" = "release" ]; then
  download_release
  notify "Nightly APK 已下载" "$OUT_DIR/rikkahub-${RELEASE_TAG}.apk"
  finish
  exit 0
fi

# ---------- Artifact 来源 ----------
case "$MODE" in
  new)
    baseline="$(gh run list --repo "$REPO" --workflow "$WORKFLOW" --limit 1 \
      --json databaseId --jq '.[0].databaseId // 0')"
    log "等待新构建出现（基线 run id: $baseline）…"
    deadline=$(( $(date +%s) + NEW_RUN_TIMEOUT_MIN * 60 ))
    while :; do
      new_id="$(gh run list --repo "$REPO" --workflow "$WORKFLOW" --limit 1 \
        --json databaseId --jq '.[0].databaseId // 0')"
      if [ "$new_id" != "0" ] && [ "$new_id" != "$baseline" ]; then
        log "检测到新构建: run id $new_id"
        break
      fi
      [ "$(date +%s)" -gt "$deadline" ] && die "等待超时：${NEW_RUN_TIMEOUT_MIN} 分钟内没有新构建出现（push 是否成功？）" 11
      sleep "$POLL"
    done
    wait_run "$new_id" || die "等待超时：构建超过 ${TIMEOUT_MIN} 分钟仍未结束" 11
    assert_success || { report_failure; exit 10; }
    download_artifact "$new_id"
    number="$(jq -r '.number' <<< "$RUN_INFO")"
    notify "构建 #${number} 已完成" "$OUT_DIR"
    finish
    ;;
  watch)
    run_id="$(list_runs | jq -r '[.[0]] | .[0].databaseId // empty')"
    [ -n "$run_id" ] || die "没有找到 workflow「$WORKFLOW」的任何构建记录" 12
    wait_run "$run_id" || die "等待超时：构建超过 ${TIMEOUT_MIN} 分钟仍未结束" 11
    assert_success || { report_failure; exit 10; }
    download_artifact "$run_id"
    number="$(jq -r '.number' <<< "$RUN_INFO")"
    notify "构建 #${number} 已完成" "$OUT_DIR"
    finish
    ;;
  latest)
    runs="$(list_runs)"
    success_id="$(jq -r '[.[] | select(.conclusion == "success")][0].databaseId // empty' <<< "$runs")"
    running="$(jq -r '[.[] | select(.status != "completed")][0] | if . then "#\(.number) (\(.status))" else empty end' <<< "$runs")"
    [ -n "$success_id" ] || {
      [ -n "$running" ] && warn "当前构建 #${running} 仍在进行中，还没有成功的构建记录（可用 --watch 等待）"
      die "没有可下载的成功构建" 12
    }
    RUN_INFO="$(gh run view "$success_id" --repo "$REPO" \
      --json databaseId,status,conclusion,number,headSha,displayTitle,url)"
    log "最近一次成功构建: #$(jq -r '.number' <<< "$RUN_INFO")（$(jq -r '.headSha[0:7]' <<< "$RUN_INFO")）"
    [ -n "$running" ] && warn "另有一个构建 #${running} 正在进行中（若要等它完成，请使用 --watch 或 --new）"
    download_artifact "$success_id"
    finish
    ;;
esac
