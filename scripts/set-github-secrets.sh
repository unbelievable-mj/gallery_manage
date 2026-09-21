#!/usr/bin/env bash
#
# 用 gh CLI 一次性配置 4 个签名 Secret。
#
# 用法：./scripts/set-github-secrets.sh <owner/repo>
# 例如：./scripts/set-github-secrets.sh unbelievable-mj/gallery_manage
#
# 前置条件（只需做一次）：
#   macOS:  brew install gh
#            gh auth login        # 选 GitHub.com → HTTPS → 浏览器授权
#
# 装好之后，本脚本会把 keystore 的 base64 与三个口令一次性写入仓库 Secret，
# 无需在网页上手工粘贴 4 次。

set -euo pipefail

REPO="${1:-}"
if [ -z "$REPO" ]; then
  echo "用法：$0 <owner/repo>" >&2
  echo "例如：$0 unbelievable-mj/gallery_manage" >&2
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KS_FILE="$REPO_ROOT/keystore/release.p12"
PROPS_FILE="$REPO_ROOT/keystore/keystore.properties"

if ! command -v gh >/dev/null 2>&1; then
  echo "未安装 gh CLI。macOS 执行：brew install gh" >&2
  exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
  echo "gh 尚未登录，请先执行：gh auth login" >&2
  exit 1
fi

if [ ! -f "$KS_FILE" ]; then
  echo "找不到密钥库：$KS_FILE" >&2
  echo "请先运行 scripts/generate-keystore.sh" >&2
  exit 1
fi

if [ ! -f "$PROPS_FILE" ]; then
  echo "找不到凭据文件：$PROPS_FILE" >&2
  exit 1
fi

# 逐行取值，不用 source
read_prop() {
  grep -E "^$1=" "$PROPS_FILE" | head -n 1 | cut -d= -f2-
}

STORE_PASSWORD="$(read_prop storePassword)"
KEY_ALIAS="$(read_prop keyAlias)"
KEY_PASSWORD="$(read_prop keyPassword)"

for v in STORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
  if [ -z "${!v}" ]; then
    echo "凭据文件缺少字段：$v" >&2
    exit 1
  fi
done

echo "目标仓库：$REPO"
echo "正在写入 4 个 Secret..."

# gh secret set 从 stdin 读取值，避免 base64 长串出现在进程列表里
base64 < "$KS_FILE" | tr -d '\n' | gh secret set SIGNING_KEYSTORE_BASE64 --repo "$REPO"
gh secret set SIGNING_STORE_PASSWORD --repo "$REPO" --body "$STORE_PASSWORD"
gh secret set SIGNING_KEY_ALIAS --repo "$REPO" --body "$KEY_ALIAS"
gh secret set SIGNING_KEY_PASSWORD --repo "$REPO" --body "$KEY_PASSWORD"

echo
echo "完成。仓库当前 Secret 列表："
gh secret list --repo "$REPO"

echo
echo "下一步：推 tag 触发发版"
echo "  git tag v0.1.0 && git push origin v0.1.0"
