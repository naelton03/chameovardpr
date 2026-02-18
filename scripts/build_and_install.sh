#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REPO_SLUG="${1:-${REPLAYCAM_REPO_SLUG:-}}"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

install_local_apk() {
  if [[ ! -f "$APK_PATH" ]]; then
    echo "APK não encontrado em $APK_PATH"
    return 1
  fi

  DEVICE_COUNT=$(adb devices | awk 'NR>1 && $2=="device" {count++} END {print count+0}')
  if [[ "$DEVICE_COUNT" -eq 0 ]]; then
    echo "Nenhum dispositivo ADB conectado."
    return 1
  fi

  adb install -r "$APK_PATH"
  echo "Instalação concluída (build local): $APK_PATH"
}

echo "[ReplayCam] Tentando build local: gradle assembleDebug"
if gradle assembleDebug; then
  install_local_apk
  exit 0
fi

echo "[ReplayCam] Build local falhou."
if [[ -n "$REPO_SLUG" ]]; then
  echo "[ReplayCam] Fallback automático: baixando último APK da CI para $REPO_SLUG"
  bash scripts/download_ci_artifact_and_install.sh "$REPO_SLUG"
  exit 0
fi

cat <<'MSG'
[ReplayCam] Não foi possível gerar APK localmente.
Para não bloquear sua instalação, rode com fallback de CI:
  bash scripts/build_and_install.sh <owner/repo>
ou defina:
  export REPLAYCAM_REPO_SLUG=<owner/repo>
  bash scripts/build_and_install.sh
MSG

exit 1
