#!/usr/bin/env bash
set -euo pipefail

# Requisitos:
# - gh CLI autenticado (gh auth login)
# - jq instalado
# - adb com device conectado

REPO_SLUG="${1:-}"
if [[ -z "$REPO_SLUG" ]]; then
  echo "Uso: bash scripts/download_ci_artifact_and_install.sh <owner/repo>"
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "gh CLI não encontrado. Instale: https://cli.github.com/"
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq não encontrado. Instale o jq para continuar."
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb não encontrado. Instale Android Platform Tools."
  exit 1
fi

DEVICE_COUNT=$(adb devices | awk 'NR>1 && $2=="device" {count++} END {print count+0}')
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
  echo "Nenhum device ADB conectado."
  exit 1
fi

RUN_ID=$(gh api "/repos/${REPO_SLUG}/actions/workflows/android-apk.yml/runs?status=success&per_page=1" \
  | jq -r '.workflow_runs[0].id // empty')

if [[ -z "$RUN_ID" ]]; then
  echo "Nenhuma execução bem-sucedida encontrada para o workflow android-apk.yml"
  exit 1
fi

WORKDIR="$(mktemp -d)"
echo "Baixando artifact da run ${RUN_ID} em ${WORKDIR} ..."

gh run download "$RUN_ID" \
  --repo "$REPO_SLUG" \
  --name replaycam-debug-apk \
  --dir "$WORKDIR"

APK_PATH="${WORKDIR}/replaycam-debug.apk"
if [[ ! -f "$APK_PATH" ]]; then
  echo "APK não encontrado no artifact."
  find "$WORKDIR" -maxdepth 3 -type f
  exit 1
fi

if [[ -f "${WORKDIR}/replaycam-debug.apk.sha256" ]]; then
  echo "Validando checksum..."
  (cd "$WORKDIR" && sha256sum -c replaycam-debug.apk.sha256)
fi

adb install -r "$APK_PATH"
echo "Instalação concluída: $APK_PATH"
