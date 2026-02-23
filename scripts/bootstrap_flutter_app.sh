#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${1:-flutter_app}"

if ! command -v flutter >/dev/null 2>&1; then
  echo "[ReplayOn] Flutter não encontrado no PATH." >&2
  exit 1
fi

mkdir -p "$APP_DIR"
cd "$APP_DIR"

flutter create . \
  --platforms=android,ios \
  --project-name=replayon_cross \
  --org=com.example.replaycam \
  --overwrite

echo "[ReplayOn] Projeto Flutter pronto em $APP_DIR (Android + iOS)."
