#!/usr/bin/env bash
#
# 打印配置 GitHub Secrets 所需的全部值。
#
# 用法：./scripts/print-signing-secrets.sh
#
# 输出内容包含签名口令，属于敏感信息。请勿把输出粘贴到公开渠道，
# 也请勿把 keystore/release.p12 提交进仓库（.gitignore 已忽略）。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE="${1:-$REPO_ROOT/keystore/release.p12}"

STORE_PASSWORD="SwipeGallery2026Release"
KEY_ALIAS="swipegallery"
KEY_PASSWORD="SwipeGallery2026Release"

if [ ! -f "$KEYSTORE" ]; then
  echo "找不到密钥库：$KEYSTORE" >&2
  echo "请先运行 scripts/generate-keystore.sh 生成。" >&2
  exit 1
fi

B64="$(base64 < "$KEYSTORE" | tr -d '\n')"

cat <<EOF
=====================================================================
 GitHub Secrets 配置值
 打开仓库 → Settings → Secrets and variables → Actions → New repository secret
=====================================================================

SIGNING_KEYSTORE_BASE64
$B64

SIGNING_STORE_PASSWORD
$STORE_PASSWORD

SIGNING_KEY_ALIAS
$KEY_ALIAS

SIGNING_KEY_PASSWORD
$KEY_PASSWORD

=====================================================================
 备份提醒
=====================================================================
密钥库文件：$KEYSTORE

这个文件决定了 APK 的签名身份。一旦丢失，已安装旧版本的用户
将无法通过覆盖安装升级到新版本（必须卸载重装）。
请把它备份到安全的地方，并且永远不要提交进 Git 仓库。
EOF
