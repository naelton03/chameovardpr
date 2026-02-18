#!/usr/bin/env bash

# Ajusta JAVA_HOME para uma versão compatível com builds Android (JDK 17..21)
# quando o Java atual é muito novo para alguns plugins legados do Android/Gradle.

java_major_version() {
  local version_line version
  version_line="$({ java -version 2>&1 || true; } | head -n 1)"
  version="$(echo "$version_line" | sed -E 's/.*version "([^"]+)".*/\1/')"

  if [[ -z "$version" ]]; then
    return 1
  fi

  if [[ "$version" =~ ^1\.([0-9]+) ]]; then
    echo "${BASH_REMATCH[1]}"
    return 0
  fi

  echo "$version" | cut -d. -f1
}

find_compatible_java_home() {
  local candidates=()
  local candidate

  if [[ -n "${JAVA_HOME:-}" ]]; then
    candidates+=("$JAVA_HOME")
  fi

  candidates+=(
    "$HOME/.local/share/mise/installs/java/21"
    "$HOME/.local/share/mise/installs/java/17"
    "$HOME/.sdkman/candidates/java/current"
    "/usr/lib/jvm/java-21"
    "/usr/lib/jvm/java-17"
    "/usr/lib/jvm/jdk-21"
    "/usr/lib/jvm/jdk-17"
  )

  for candidate in "$HOME/.local/share/mise/installs/java"/*; do
    [[ -d "$candidate" ]] || continue
    candidates+=("$candidate")
  done

  for candidate in "${candidates[@]}"; do
    [[ -x "$candidate/bin/java" ]] || continue
    local v
    v="$($candidate/bin/java -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p' | cut -d. -f1)"
    if [[ "$v" =~ ^(17|18|19|20|21)$ ]]; then
      echo "$candidate"
      return 0
    fi
  done

  return 1
}

ensure_android_build_java() {
  local current_major
  current_major="$(java_major_version || true)"

  if [[ "$current_major" =~ ^(17|18|19|20|21)$ ]]; then
    return 0
  fi

  local compatible_home
  compatible_home="$(find_compatible_java_home || true)"

  if [[ -n "$compatible_home" ]]; then
    export JAVA_HOME="$compatible_home"
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "[ReplayCam] JAVA_HOME ajustado para build Android: $JAVA_HOME"
    return 0
  fi

  echo "[ReplayCam] Aviso: Java atual (${current_major:-desconhecido}) pode ser incompatível com este build e nenhuma instalação JDK 17..21 foi encontrada." >&2
  return 0
}
