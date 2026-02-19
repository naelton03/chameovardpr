#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
KEYSTORE_DIR="${ROOT_DIR}/.keystore"
KEYSTORE_PATH="${KEYSTORE_DIR}/replaycam-oauth.jks"
ALIAS="${REPLAYCAM_KEY_ALIAS:-replaycamoauth}"
STORE_PASS="${REPLAYCAM_STORE_PASSWORD:-replaycam123}"
KEY_PASS="${REPLAYCAM_KEY_PASSWORD:-replaycam123}"
DNAME="${REPLAYCAM_DNAME:-CN=ReplayCam, OU=Mobile, O=ReplayCam, L=Sao Paulo, ST=SP, C=BR}"
VALIDITY_DAYS="${REPLAYCAM_KEY_VALIDITY_DAYS:-9125}"

mkdir -p "${KEYSTORE_DIR}"

if [[ ! -f "${KEYSTORE_PATH}" ]]; then
  keytool -genkeypair \
    -keystore "${KEYSTORE_PATH}" \
    -storepass "${STORE_PASS}" \
    -keypass "${KEY_PASS}" \
    -alias "${ALIAS}" \
    -keyalg RSA \
    -keysize 2048 \
    -validity "${VALIDITY_DAYS}" \
    -dname "${DNAME}" >/dev/null
  echo "[ok] Keystore criado em ${KEYSTORE_PATH}"
else
  echo "[ok] Keystore já existe em ${KEYSTORE_PATH}"
fi

echo
printenv_cmd() {
  local key="$1" value="$2"
  printf '%s=%q\n' "$key" "$value"
}

echo "Use estes valores no gradle.properties (ou exporte como variáveis de ambiente):"
printenv_cmd REPLAYCAM_KEYSTORE_PATH "${KEYSTORE_PATH}"
printenv_cmd REPLAYCAM_KEY_ALIAS "${ALIAS}"
printenv_cmd REPLAYCAM_STORE_PASSWORD "${STORE_PASS}"
printenv_cmd REPLAYCAM_KEY_PASSWORD "${KEY_PASS}"

echo
echo "Fingerprints para cadastrar no Google Cloud OAuth Android:"
keytool -list -v \
  -keystore "${KEYSTORE_PATH}" \
  -storepass "${STORE_PASS}" \
  -alias "${ALIAS}" | awk '/SHA1:|SHA256:/{print $0}'
