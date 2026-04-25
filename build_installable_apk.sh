#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

APK_SOURCE="app/build/outputs/apk/debug/app-debug.apk"
APK_OUTPUT_DIR="build/installable"
APK_OUTPUT="$APK_OUTPUT_DIR/demeter-speech-debug.apk"

fail() {
  printf 'Erreur: %s\n' "$*" >&2
  exit 1
}

info() {
  printf '\n==> %s\n' "$*"
}

[[ -x ./gradlew ]] || fail "gradlew est introuvable ou non executable dans $SCRIPT_DIR."
[[ -f .env.production ]] || fail "App/.env.production est manquant. Ajoute BACKEND_BASE_URL avant de compiler."

if ! grep -Eq '^[[:space:]]*(export[[:space:]]+)?BACKEND_BASE_URL[[:space:]]*=' .env.production; then
  fail "BACKEND_BASE_URL est manquant dans App/.env.production."
fi

if [[ ! -f local.properties ]]; then
  SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -z "$SDK_DIR" && -d "$HOME/Android/Sdk" ]]; then
    SDK_DIR="$HOME/Android/Sdk"
  fi

  if [[ -n "$SDK_DIR" && -d "$SDK_DIR" ]]; then
    printf 'sdk.dir=%s\n' "$SDK_DIR" > local.properties
    info "SDK Android detecte: $SDK_DIR"
  else
    fail "SDK Android introuvable. Definis ANDROID_HOME ou cree App/local.properties avec: sdk.dir=/chemin/vers/Android/Sdk"
  fi
fi

info "Compilation de l'APK debug installable"
./gradlew :app:assembleDebug

[[ -f "$APK_SOURCE" ]] || fail "APK compile introuvable: $APK_SOURCE"

mkdir -p "$APK_OUTPUT_DIR"
cp "$APK_SOURCE" "$APK_OUTPUT"

info "APK pret a installer"
printf '%s\n' "$SCRIPT_DIR/$APK_OUTPUT"
