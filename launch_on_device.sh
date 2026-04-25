#!/usr/bin/env bash
set -Eeuo pipefail

APP_ID="com.demeter.speech"
MAIN_ACTIVITY=".MainActivity"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

fail() {
  printf 'Erreur: %s\n' "$*" >&2
  exit 1
}

info() {
  printf '\n==> %s\n' "$*"
}

command -v adb >/dev/null 2>&1 || fail "adb est introuvable. Installe Android Platform Tools et ajoute adb au PATH."
[[ -x ./gradlew ]] || fail "gradlew est introuvable ou non executable dans $SCRIPT_DIR."
[[ -f .env.production ]] || fail "App/.env.production est manquant. Ajoute BACKEND_BASE_URL avant de compiler."

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

info "Recherche du telephone connecte en USB"
adb start-server >/dev/null

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  DEVICE_SERIAL="$ANDROID_SERIAL"
  adb -s "$DEVICE_SERIAL" get-state >/dev/null 2>&1 || fail "aucun appareil adb disponible pour ANDROID_SERIAL=$DEVICE_SERIAL."
else
  mapfile -t USB_DEVICES < <(
    adb devices |
      awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ && $1 !~ /:/ { print $1 }'
  )

  mapfile -t UNAUTHORIZED_DEVICES < <(
    adb devices |
      awk 'NR > 1 && $2 == "unauthorized" { print $1 }'
  )

  if (( ${#USB_DEVICES[@]} == 0 )); then
    if (( ${#UNAUTHORIZED_DEVICES[@]} > 0 )); then
      fail "telephone non autorise. Deverrouille-le et accepte la demande de debogage USB."
    fi
    fail "aucun telephone USB detecte par adb. Verifie le cable, le debogage USB et lance 'adb devices'."
  fi

  if (( ${#USB_DEVICES[@]} > 1 )); then
    printf 'Plusieurs telephones USB detectes:\n' >&2
    printf '  %s\n' "${USB_DEVICES[@]}" >&2
    fail "relance avec ANDROID_SERIAL=<serial> ./launch_on_device.sh"
  fi

  DEVICE_SERIAL="${USB_DEVICES[0]}"
fi

info "Compilation et installation sur $DEVICE_SERIAL"
./gradlew :app:installDebug -Pandroid.injected.adb.device.serial="$DEVICE_SERIAL"

info "Lancement de l'application"
adb -s "$DEVICE_SERIAL" shell am start \
  -n "$APP_ID/$MAIN_ACTIVITY" \
  -a android.intent.action.MAIN \
  -c android.intent.category.LAUNCHER >/dev/null

info "Application lancee sur $DEVICE_SERIAL"
