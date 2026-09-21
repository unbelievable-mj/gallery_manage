#!/usr/bin/env bash
#
# 打印配置 GitHub Secrets 所需的全部值。
#
# 用法：./scripts/print-signing-secrets.sh
#
# 凭据从 keystore/keystore.properties 读取（该目录已被 .gitignore 排除）。
# 输出内容含签名口令，属敏感信息：请勿粘贴到公开渠道。
#
# 提示：base64 串很长，建议用
#   base64 -i keystore/release.p12 | tr -d '\n' | pbcopy
# 直接放进剪贴板再粘贴到 GitHub 的 Secret 输入框。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KS_DIR="$REPO_ROOT/keystore"
KEYSTORE="$KS_DIR/release.p12"
PROPS_FILE="$KS_DIR/keystore.properties"

if [ ! -f "$KEYSTORE" ]; then
  echo "找不到密钥库：$KEYSTORE" >&2
  echo "请先运行 scripts/generate-keystore.sh 生成。" >&2
  exit 1
fi

if [ ! -f "$PROPS_FILE" ]; then
  echo "找不到凭据文件：$PROPS_FILE" >&2
  echo "该文件由 scripts/generate-keystore.sh 生成，且不会被提交。" >&2
  exit 1
fi

# 逐行取值，不用 source —— 避免执行文件里的任意内容
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

B64="$(base64 < "$KEYSTORE" | tr -d '\n')"

cat <<EOF
=====================================================================
 GitHub Secrets 配置值
 打开仓库 → Settings → Secrets and variables → Actions → New repository secret
 共 4 条，名称必须完全一致
=====================================================================

1) SIGNING_KEYSTORE_BASE64
$B64

2) SIGNING_STORE_PASSWORD
$STORE_PASSWORD

3) SIGNING_KEY_ALIAS
$KEY_ALIAS

4) SIGNING_KEY_PASSWORD
$KEY_PASSWORD

=====================================================================
 备份提醒
=====================================================================
密钥库文件：$KEYSTORE

它决定 APK 的签名身份。一旦丢失，已安装旧版本的用户将无法通过
覆盖安装升级，只能卸载重装。请单独备份到安全的地方，
并且永远不要提交进 Git 仓库（.gitignore 已排除 keystore/）。
EOF
