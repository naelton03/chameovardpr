#!/usr/bin/env bash
set -euo pipefail

# Requisitos:
# - gh CLI autenticado (gh auth login)
# - jq instalado
# - adb com device conectado

REPO_SLUG="${1:-}"
REF_NAME="${2:-${REPLAYCAM_CI_REF:-}}"
if [[ -z "$REPO_SLUG" ]]; then
  echo "Uso: bash scripts/download_ci_artifact_and_install.sh <owner/repo> [ref]"
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

if [[ -z "$REF_NAME" ]]; then
  REF_NAME="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
fi
if [[ -z "$REF_NAME" || "$REF_NAME" == "HEAD" ]]; then
  REF_NAME="$(gh api "/repos/${REPO_SLUG}" | jq -r '.default_branch')"
fi

echo "Disparando novo workflow android-apk.yml em ${REPO_SLUG} (ref: ${REF_NAME})..."
gh api -X POST "/repos/${REPO_SLUG}/actions/workflows/android-apk.yml/dispatches" -f ref="$REF_NAME" >/dev/null

MAX_WAIT_SECONDS=1800
POLL_SECONDS=10
ELAPSED=0
RUN_ID=""

while [[ -z "$RUN_ID" && "$ELAPSED" -lt "$MAX_WAIT_SECONDS" ]]; do
  RUN_ID=$(gh api "/repos/${REPO_SLUG}/actions/workflows/android-apk.yml/runs?event=workflow_dispatch&branch=${REF_NAME}&per_page=1" \
    | jq -r '.workflow_runs[0].id // empty')
  if [[ -n "$RUN_ID" ]]; then
    break
  fi
  sleep "$POLL_SECONDS"
  ELAPSED=$((ELAPSED + POLL_SECONDS))
done

if [[ -z "$RUN_ID" ]]; then
  echo "Não foi possível localizar a nova run workflow_dispatch para ${REF_NAME}."
  exit 1
fi

echo "Aguardando conclusão da run ${RUN_ID}..."
ELAPSED=0
while [[ "$ELAPSED" -lt "$MAX_WAIT_SECONDS" ]]; do
  RUN_JSON=$(gh api "/repos/${REPO_SLUG}/actions/runs/${RUN_ID}")
  STATUS=$(echo "$RUN_JSON" | jq -r '.status')
  CONCLUSION=$(echo "$RUN_JSON" | jq -r '.conclusion // empty')

  if [[ "$STATUS" == "completed" ]]; then
    if [[ "$CONCLUSION" != "success" ]]; then
      echo "Run ${RUN_ID} finalizada com status: ${CONCLUSION}"
      echo "Logs: https://github.com/${REPO_SLUG}/actions/runs/${RUN_ID}"
      exit 1
    fi
    break
  fi

  sleep "$POLL_SECONDS"
  ELAPSED=$((ELAPSED + POLL_SECONDS))
done

if [[ "$ELAPSED" -ge "$MAX_WAIT_SECONDS" ]]; then
  echo "Timeout aguardando conclusão da run ${RUN_ID}."
  exit 1
fi

WORKDIR="$(mktemp -d)"
echo "Baixando artifact atualizado da run ${RUN_ID} em ${WORKDIR} ..."

ARTIFACT_NAME=$(gh api "/repos/${REPO_SLUG}/actions/runs/${RUN_ID}/artifacts" \
  | jq -r '.artifacts[] | select(.name | startswith("replaycam-debug-apk-")) | .name' \
  | head -n 1)

if [[ -z "$ARTIFACT_NAME" ]]; then
  echo "Nenhum artifact versionado encontrado para a run ${RUN_ID}."
  exit 1
fi

gh run download "$RUN_ID" \
  --repo "$REPO_SLUG" \
  --name "$ARTIFACT_NAME" \
  --dir "$WORKDIR"

APK_PATH=$(find "$WORKDIR" -maxdepth 3 -type f -name 'replaycam-debug-*.apk' | head -n 1)
if [[ -z "$APK_PATH" || ! -f "$APK_PATH" ]]; then
  echo "APK não encontrado no artifact versionado."
  find "$WORKDIR" -maxdepth 3 -type f
  exit 1
fi

SHA_PATH="${APK_PATH}.sha256"
if [[ -f "$SHA_PATH" ]]; then
  echo "Validando checksum..."
  (cd "$(dirname "$APK_PATH")" && sha256sum -c "$(basename "$SHA_PATH")")
fi

adb install -r "$APK_PATH"
echo "Instalação concluída: $APK_PATH"
