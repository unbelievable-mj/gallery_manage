#!/usr/bin/env bash
#
# 生成 release 签名密钥库。
#
# 优先使用 keytool（JDK 自带，标准做法）；没有 JDK 时回退到 openssl，
# 生成 PKCS12 格式的密钥库 —— AGP 与 apksigner 都支持该格式。
#
# 用法：./scripts/generate-keystore.sh
#
# 注意：本脚本不会覆盖已存在的密钥库。换签名密钥会导致老用户无法覆盖安装。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$REPO_ROOT/keystore"
OUT_FILE="$OUT_DIR/release.p12"

STORE_PASSWORD="SwipeGallery2026Release"
KEY_ALIAS="swipegallery"
VALIDITY_DAYS=10950   # 约 30 年，Android 应用签名证书建议尽量长

if [ -f "$OUT_FILE" ]; then
  echo "密钥库已存在，未做改动：$OUT_FILE"
  echo "如需重新生成，请先手动删除该文件。"
  exit 0
fi

mkdir -p "$OUT_DIR"

if command -v keytool >/dev/null 2>&1; then
  echo "使用 keytool 生成 PKCS12 密钥库..."
  keytool -genkeypair \
    -keystore "$OUT_FILE" \
    -storetype PKCS12 \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -validity "$VALIDITY_DAYS" \
    -storepass "$STORE_PASSWORD" \
    -keypass "$STORE_PASSWORD" \
    -dname "CN=SwipeGallery, O=SwipeGallery, C=CN"
elif command -v openssl >/dev/null 2>&1; then
  echo "未找到 keytool，改用 openssl 生成 PKCS12 密钥库..."
  TMP_DIR="$(mktemp -d)"
  trap 'rm -rf "$TMP_DIR"' EXIT

  openssl req -x509 -newkey rsa:4096 -sha256 -days "$VALIDITY_DAYS" -nodes \
    -keyout "$TMP_DIR/key.pem" -out "$TMP_DIR/cert.pem" \
    -subj "/CN=SwipeGallery/O=SwipeGallery/C=CN" \
    -addext "keyUsage=critical,digitalSignature" \
    -addext "basicConstraints=critical,CA:FALSE"

  openssl pkcs12 -export \
    -in "$TMP_DIR/cert.pem" -inkey "$TMP_DIR/key.pem" \
    -name "$KEY_ALIAS" -out "$OUT_FILE" \
    -passout "pass:$STORE_PASSWORD"
else
  echo "既没有 keytool 也没有 openssl，无法生成密钥库。" >&2
  exit 1
fi

echo "已生成：$OUT_FILE"
echo
echo "下一步：运行 ./scripts/print-signing-secrets.sh 获取 GitHub Secrets 配置值。"
