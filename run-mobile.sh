#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$SCRIPT_DIR"
TOOLING_DIR="$APP_DIR/.tooling"
TOOLING_ENV_FILE="$TOOLING_DIR/run-mobile.env"
REPO_ROOT="$(cd "$APP_DIR/.." && pwd)"

mkdir -p "$TOOLING_DIR"

if [ -r "$TOOLING_ENV_FILE" ]; then
  # Local defaults for Java, SDK and emulator settings.
  # shellcheck disable=SC1090
  . "$TOOLING_ENV_FILE"
fi

JAVA_HOME_DEFAULT="${APP_JAVA_HOME:-$REPO_ROOT/.tooling/jdk-17}"
SDK_ROOT_DEFAULT="${APP_ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
AVD_HOME_DEFAULT="${APP_ANDROID_AVD_HOME:-$HOME/.config/.android/avd}"
AVD_NAME_DEFAULT="${APP_AVD_NAME:-demeter_api35}"

JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"
SDK_ROOT="${ANDROID_SDK_ROOT:-$SDK_ROOT_DEFAULT}"
AVD_HOME="${ANDROID_AVD_HOME:-$AVD_HOME_DEFAULT}"
AVD_NAME="${AVD_NAME:-$AVD_NAME_DEFAULT}"
APK_PATH="$APP_DIR/app/build/outputs/apk/debug/app-debug.apk"
EMULATOR_LOG="$TOOLING_DIR/mobile-emulator.log"
MODE="auto"
EMULATOR_WINDOW=0
FRESH_EMULATOR=0
CLEAN_MODE=0
TARGET_SERIAL="${ADB_SERIAL:-}"

usage() {
  cat <<'EOF'
Usage: ./run-mobile.sh [--device|--emulator|--emulator-window|--fresh|--clean] [--help]

Modes:
  --device     Use an already connected physical device.
  --emulator   Use or start the local Android emulator AVD in headless mode.
               If a different emulator mode is already running, it will be restarted.
  --emulator-window
               Use or start the local Android emulator AVD with a visible window.
               Any running emulator will be stopped first so only one instance
               remains active.
  --fresh      Force restart of any running emulator before starting a new one.
               This applies to emulator modes and to auto mode when it falls back
               to the local emulator.
  --clean      Stop leftover emulator resources before launch and force-stop the
               app on the selected device before installation.

Environment:
  ADB_SERIAL   Force a specific adb serial and bypass auto selection.
  The script also loads local defaults from App/.tooling/run-mobile.env
  when the file exists.
  AVD_NAME     Emulator AVD name (default: demeter_api35).
  JAVA_HOME    Local JDK path (default: <repo>/.tooling/jdk-17).
  ANDROID_SDK_ROOT  Android SDK path (default: ~/Android/Sdk).
  ANDROID_AVD_HOME  AVD home (default: ~/.config/.android/avd).
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --device)
      MODE="device"
      shift
      ;;
    --emulator)
      MODE="emulator"
      EMULATOR_WINDOW=0
      shift
      ;;
    --emulator-window)
      MODE="emulator-window"
      EMULATOR_WINDOW=1
      shift
      ;;
    --fresh)
      FRESH_EMULATOR=1
      shift
      ;;
    --clean)
      CLEAN_MODE=1
      FRESH_EMULATOR=1
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "[run-mobile] unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [ ! -x "$APP_DIR/gradlew" ]; then
  echo "[run-mobile] missing Gradle wrapper at $APP_DIR/gradlew" >&2
  exit 1
fi

if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "[run-mobile] Java not found at $JAVA_HOME" >&2
  exit 1
fi

if [ ! -x "$SDK_ROOT/platform-tools/adb" ]; then
  echo "[run-mobile] Android platform-tools not found in $SDK_ROOT" >&2
  exit 1
fi

if [ ! -x "$SDK_ROOT/emulator/emulator" ]; then
  echo "[run-mobile] Android emulator not found in $SDK_ROOT" >&2
  exit 1
fi

export JAVA_HOME
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_AVD_HOME="$AVD_HOME"
export PATH="$JAVA_HOME/bin:$SDK_ROOT/platform-tools:$SDK_ROOT/emulator:$PATH"

cd "$APP_DIR"

if [ "$CLEAN_MODE" -eq 1 ] && [ -n "$TARGET_SERIAL" ] && [[ "$TARGET_SERIAL" == emulator-* ]]; then
  echo "[run-mobile] clean mode with emulator serial override; restarting emulator selection"
  if [ "$EMULATOR_WINDOW" -eq 1 ]; then
    MODE="emulator-window"
  else
    MODE="emulator"
  fi
  TARGET_SERIAL=""
fi

first_connected_physical_device() {
  adb devices | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ { print $1; exit }'
}

first_connected_emulator() {
  adb devices | awk 'NR > 1 && $2 == "device" && $1 ~ /^emulator-/ { print $1; exit }'
}

running_emulator_serials() {
  adb devices | awk 'NR > 1 && $1 ~ /^emulator-/ { print $1 }'
}

running_emulator_count() {
  pgrep -af 'qemu-system.*-avd' 2>/dev/null | awk 'NF { count++ } END { print count + 0 }' || true
}

running_emulator_cmdline() {
  local line
  line="$(pgrep -af 'qemu-system.*-avd' 2>/dev/null | head -n 1 || true)"
  line="${line#* }"
  printf '%s\n' "$line"
}

running_emulator_mode() {
  local cmdline
  cmdline="$(running_emulator_cmdline)"
  if [ -z "$cmdline" ]; then
    echo "none"
    return 0
  fi
  if [[ "$cmdline" == *"-no-window"* ]]; then
    echo "headless"
  else
    echo "window"
  fi
}

stop_running_emulators() {
  local serials serial waited
  serials="$(running_emulator_serials)"
  if [ -n "$serials" ]; then
    echo "[run-mobile] stopping running emulator(s)"
    while IFS= read -r serial; do
      [ -n "$serial" ] || continue
      adb -s "$serial" emu kill >/dev/null 2>&1 || true
    done <<EOF
$serials
EOF
  fi

  waited=0
  while [ "$waited" -lt 30 ]; do
    if [ "$(running_emulator_count)" -eq 0 ]; then
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done

  if pgrep -af 'qemu-system.*-avd' >/dev/null 2>&1; then
    echo "[run-mobile] force-stopping lingering emulator process" >&2
    pkill -f 'qemu-system.*-avd' || true
  fi
}

wait_for_boot() {
  local serial="$1"
  adb -s "$serial" wait-for-device
  until [ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 2
  done
}

ensure_emulator_device() {
  local serial

  local running_count
  running_count="$(running_emulator_count)"

  if [ "$FRESH_EMULATOR" -eq 1 ] && [ "$running_count" -gt 0 ]; then
    echo "[run-mobile] fresh emulator requested; restarting existing emulator"
    stop_running_emulators
    running_count=0
  fi

  case "$running_count" in
    0)
      ;;
    1)
      if [ "$EMULATOR_WINDOW" -eq 1 ] && [ "$(running_emulator_mode)" = "headless" ]; then
        echo "[run-mobile] switching emulator from headless to visible window"
        stop_running_emulators
      elif [ "$EMULATOR_WINDOW" -eq 0 ] && [ "$(running_emulator_mode)" = "window" ]; then
        echo "[run-mobile] switching emulator from window to headless"
        stop_running_emulators
      fi
      ;;
    *)
      echo "[run-mobile] multiple emulators detected; stopping them so only one remains"
      stop_running_emulators
      ;;
  esac

  serial="$(first_connected_emulator)"
  if [ -n "$serial" ]; then
    if [ "$EMULATOR_WINDOW" -eq 1 ]; then
      echo "[run-mobile] emulator already running; if it was started headless, stop it to open a visible window" >&2
    fi
    echo "$serial"
    return 0
  fi

  if ! emulator -list-avds | grep -qx "$AVD_NAME"; then
    echo "[run-mobile] AVD '$AVD_NAME' not found. Create it with avdmanager first." >&2
    exit 1
  fi

  if [ "$EMULATOR_WINDOW" -eq 1 ]; then
    echo "[run-mobile] starting emulator $AVD_NAME with window"
  else
    echo "[run-mobile] starting emulator $AVD_NAME in headless mode"
  fi

  emulator_args=(-avd "$AVD_NAME" -no-audio -no-snapshot -gpu swiftshader_indirect)
  if [ "$EMULATOR_WINDOW" -eq 0 ]; then
    emulator_args+=(-no-window)
  fi

  nohup emulator "${emulator_args[@]}" >"$EMULATOR_LOG" 2>&1 &

  serial=""
  until [ -n "$serial" ]; do
    serial="$(first_connected_emulator)"
    if [ -z "$serial" ]; then
      sleep 2
    fi
  done
  wait_for_boot "$serial"
  echo "$serial"
}

ensure_device_mode() {
  local serial
  serial="$(first_connected_physical_device)"
  if [ -z "$serial" ]; then
    echo "[run-mobile] no physical Android device connected" >&2
    exit 1
  fi
  wait_for_boot "$serial"
  echo "$serial"
}

force_stop_app() {
  local serial="$1"
  adb -s "$serial" shell am force-stop com.demeter.speech >/dev/null 2>&1 || true
}

if [ "$CLEAN_MODE" -eq 1 ]; then
  echo "[run-mobile] clean mode: stopping leftover emulator resources"
  stop_running_emulators
fi

DEVICE_SERIAL=""
if [ -n "$TARGET_SERIAL" ]; then
  DEVICE_SERIAL="$TARGET_SERIAL"
  wait_for_boot "$DEVICE_SERIAL"
else
  case "$MODE" in
    auto)
      DEVICE_SERIAL="$(first_connected_physical_device)"
      if [ -z "$DEVICE_SERIAL" ]; then
        DEVICE_SERIAL="$(ensure_emulator_device)"
      else
        wait_for_boot "$DEVICE_SERIAL"
      fi
      ;;
    device)
      DEVICE_SERIAL="$(ensure_device_mode)"
      ;;
    emulator)
      DEVICE_SERIAL="$(ensure_emulator_device)"
      ;;
    emulator-window)
      DEVICE_SERIAL="$(ensure_emulator_device)"
      ;;
    *)
      echo "[run-mobile] invalid mode: $MODE" >&2
      exit 1
      ;;
  esac
fi

echo "[run-mobile] using device: $DEVICE_SERIAL"

if [ "$CLEAN_MODE" -eq 1 ]; then
  echo "[run-mobile] clean mode: force-stopping app before install"
  force_stop_app "$DEVICE_SERIAL"
fi

echo "[run-mobile] building debug APK"
./gradlew :app:assembleDebug --no-daemon

echo "[run-mobile] installing APK"
adb -s "$DEVICE_SERIAL" install -r -d "$APK_PATH"

echo "[run-mobile] launching app"
adb -s "$DEVICE_SERIAL" shell am start -W -n com.demeter.speech/.MainActivity
