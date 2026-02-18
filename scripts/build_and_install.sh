#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REPO_SLUG="${1:-${REPLAYCAM_REPO_SLUG:-}}"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

infer_repo_slug_from_git_remote() {
  local remote_url
  remote_url="$(git remote get-url origin 2>/dev/null || true)"
  if [[ -z "$remote_url" ]]; then
    return 1
  fi

  if [[ "$remote_url" =~ ^https?://github.com/([^/]+)/([^/.]+)(\.git)?$ ]]; then
    echo "${BASH_REMATCH[1]}/${BASH_REMATCH[2]}"
    return 0
  fi

  if [[ "$remote_url" =~ ^git@github.com:([^/]+)/([^/.]+)(\.git)?$ ]]; then
    echo "${BASH_REMATCH[1]}/${BASH_REMATCH[2]}"
    return 0
  fi

  return 1
}

can_reach_build_repositories() {
  if ! command -v curl >/dev/null 2>&1; then
    return 0
  fi

  local url
  for url in \
    "https://dl.google.com/dl/android/maven2/" \
    "https://repo.maven.apache.org/maven2/"; do
    if ! curl -sSfI --max-time 8 "$url" >/dev/null 2>&1; then
      return 1
    fi
  done
  return 0
}

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

if can_reach_build_repositories; then
  echo "[ReplayCam] Tentando build local: gradle assembleDebug"
  if gradle assembleDebug; then
    install_local_apk
    exit 0
  fi
  echo "[ReplayCam] Build local falhou."
else
  echo "[ReplayCam] Repositórios de build indisponíveis neste ambiente. Pulando build local."
fi
if [[ -z "$REPO_SLUG" ]]; then
  REPO_SLUG="$(infer_repo_slug_from_git_remote || true)"
fi

if [[ -n "$REPO_SLUG" ]]; then
  echo "[ReplayCam] Fallback automático: baixando último APK da CI para $REPO_SLUG"
  bash scripts/download_ci_artifact_and_install.sh "$REPO_SLUG"
  exit 0
fi

cat <<'MSG'
[ReplayCam] Não foi possível gerar APK localmente.
Também não foi possível inferir owner/repo do git remote.
Para não bloquear sua instalação, rode com fallback de CI:
  bash scripts/build_and_install.sh <owner/repo>
ou defina:
  export REPLAYCAM_REPO_SLUG=<owner/repo>
  bash scripts/build_and_install.sh
MSG

exit 1
