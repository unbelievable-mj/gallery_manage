#!/usr/bin/env bash
#
# 生成 release 签名密钥库，并把口令写入 keystore/keystore.properties。
#
# 口令是随机生成的，且只落在 keystore/ 目录下 —— 该目录已被 .gitignore 排除。
# 不要把口令写进任何会被提交的脚本里（本仓库是公开的）。
#
# 优先使用 keytool（JDK 自带，标准做法）；没有 JDK 时回退到 openssl，
# 生成 PKCS12 格式的密钥库 —— AGP 与 apksigner 都支持该格式。
#
# 用法：./scripts/generate-keystore.sh
#
# 注意：本脚本不会覆盖已存在的密钥库。换签名密钥会导致老用户无法覆盖安装。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KS_DIR="$REPO_ROOT/keystore"
KS_FILE="$KS_DIR/release.p12"
PROPS_FILE="$KS_DIR/keystore.properties"

KEY_ALIAS="swipegallery"
VALIDITY_DAYS=10950   # 约 30 年，Android 应用签名证书建议尽量长

if [ -f "$KS_FILE" ]; then
  echo "密钥库已存在，未做改动：$KS_FILE"
  echo "如需重新生成，请先手动删除该文件与 $PROPS_FILE。"
  exit 0
fi

mkdir -p "$KS_DIR"

# 32 位随机口令。用 openssl rand 而非读 /dev/urandom：
# 后者在部分受限环境（CI 沙箱、容器）里会阻塞。
STORE_PASSWORD="$(openssl rand -hex 16)"
KEY_PASSWORD="$STORE_PASSWORD"

if [ "${#STORE_PASSWORD}" -ne 32 ]; then
  echo "随机口令生成失败，请重试。" >&2
  exit 1
fi

# 注意：macOS 自带 /usr/bin/keytool 桩程序，command -v 能查到但一执行就报
# 「Unable to locate a Java Runtime」。所以必须实际试跑一次才能确认可用。
if command -v keytool >/dev/null 2>&1 && keytool -help >/dev/null 2>&1; then
  echo "使用 keytool 生成 PKCS12 密钥库..."
  keytool -genkeypair \
    -keystore "$KS_FILE" \
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
  # 临时目录放在工作区内，避免受限环境对系统临时目录的拦截
  TMP_DIR="$KS_DIR/.tmp"
  rm -rf "$TMP_DIR"
  mkdir -p "$TMP_DIR"
  trap 'rm -rf "$TMP_DIR"' EXIT

  openssl req -x509 -newkey rsa:4096 -sha256 -days "$VALIDITY_DAYS" -nodes \
    -keyout "$TMP_DIR/key.pem" -out "$TMP_DIR/cert.pem" \
    -subj "/CN=SwipeGallery/O=SwipeGallery/C=CN" \
    -addext "keyUsage=critical,digitalSignature" \
    -addext "basicConstraints=critical,CA:FALSE"

  openssl pkcs12 -export \
    -in "$TMP_DIR/cert.pem" -inkey "$TMP_DIR/key.pem" \
    -name "$KEY_ALIAS" -out "$KS_FILE" \
    -passout "pass:$STORE_PASSWORD"
else
  echo "既没有 keytool 也没有 openssl，无法生成密钥库。" >&2
  exit 1
fi

cat > "$PROPS_FILE" <<EOF
# 签名凭据 —— 已被 .gitignore 排除，切勿提交。
# CI 中由 GitHub Secrets 提供；本地构建时通过环境变量传给 build.gradle.kts。
storeFile=release.p12
storePassword=$STORE_PASSWORD
keyAlias=$KEY_ALIAS
keyPassword=$KEY_PASSWORD
EOF
chmod 600 "$PROPS_FILE"

echo "已生成密钥库：$KS_FILE"
echo "已写入凭据：  $PROPS_FILE（权限 600，已 gitignore）"
echo
echo "下一步：运行 ./scripts/print-signing-secrets.sh 获取 GitHub Secrets 配置值。"
