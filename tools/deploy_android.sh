#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_ID="com.miniloc.app"
APK_PATH="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
ADB_BIN="${ADB:-}"
DEVICE_SERIAL=""
BUILD_FIRST=1
LAUNCH_AFTER_INSTALL=1
GRANT_RUNTIME_PERMISSIONS=1

usage() {
  cat <<'EOF_USAGE'
Build and deploy the miniloc debug APK to a connected Android phone.

Usage:
  tools/deploy_android.sh [options]

Options:
  --device SERIAL     Install to a specific adb device serial.
  --apk PATH          Install this APK path after building or with --skip-build.
  --skip-build        Do not run ./gradlew assembleDebug before installing.
  --no-launch         Install only; do not launch miniloc after install.
  --no-grant          Do not pass adb install -g for runtime permissions.
  --help              Show this help.

Environment:
  ADB=/path/to/adb    Override adb executable.

Examples:
  tools/deploy_android.sh
  tools/deploy_android.sh --device 143bdfa2
  tools/deploy_android.sh --skip-build --no-launch
EOF_USAGE
}

fail() {
  echo "deploy_android.sh: $*" >&2
  exit 1
}

find_adb() {
  if [[ -n "$ADB_BIN" ]]; then
    [[ -x "$ADB_BIN" ]] || fail "ADB is set but is not executable: $ADB_BIN"
    return
  fi

  local local_adb="$ROOT_DIR/.gradle/android-sdk/platform-tools/adb"
  if [[ -x "$local_adb" ]]; then
    ADB_BIN="$local_adb"
    return
  fi

  if command -v adb >/dev/null 2>&1; then
    ADB_BIN="$(command -v adb)"
    return
  fi

  fail "adb was not found. Run ./gradlew assembleDebug once, or install Android platform-tools."
}

adb_cmd() {
  if [[ -n "$DEVICE_SERIAL" ]]; then
    "$ADB_BIN" -s "$DEVICE_SERIAL" "$@"
  else
    "$ADB_BIN" "$@"
  fi
}

print_device_help() {
  cat >&2 <<'EOF_HELP'

Device troubleshooting:
  - If the state is "unauthorized", unlock the phone and accept the USB debugging prompt.
  - If the state is "no permissions", install udev rules:
      sudo apt install android-sdk-platform-tools-common
      sudo udevadm control --reload-rules
      sudo udevadm trigger
    Then unplug/replug the phone and rerun this script.
  - If multiple phones are connected, pass --device SERIAL.
EOF_HELP
}

select_device() {
  "$ADB_BIN" start-server >/dev/null

  local output
  output="$("$ADB_BIN" devices)"

  local online=()
  local requested_status=""
  while IFS=$'\t' read -r serial status; do
    [[ -n "${serial:-}" ]] || continue
    [[ "$serial" == "List of devices attached" ]] && continue
    status="${status:-unknown}"

    if [[ -n "$DEVICE_SERIAL" && "$serial" == "$DEVICE_SERIAL" ]]; then
      requested_status="$status"
    fi
    if [[ "$status" == "device" ]]; then
      online+=("$serial")
    fi
  done <<< "$output"

  if [[ -n "$DEVICE_SERIAL" ]]; then
    if [[ -z "$requested_status" ]]; then
      echo "$output" >&2
      print_device_help
      fail "device serial not found: $DEVICE_SERIAL"
    fi
    if [[ "$requested_status" != "device" ]]; then
      echo "$output" >&2
      print_device_help
      fail "device $DEVICE_SERIAL is not ready: $requested_status"
    fi
    return
  fi

  if [[ "${#online[@]}" -eq 1 ]]; then
    DEVICE_SERIAL="${online[0]}"
    return
  fi

  echo "$output" >&2
  print_device_help
  if [[ "${#online[@]}" -eq 0 ]]; then
    fail "no ready adb device found."
  fi
  fail "multiple ready devices found; rerun with --device SERIAL."
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --device)
      [[ "$#" -ge 2 ]] || fail "--device requires a serial"
      DEVICE_SERIAL="$2"
      shift 2
      ;;
    --apk)
      [[ "$#" -ge 2 ]] || fail "--apk requires a path"
      APK_PATH="$2"
      shift 2
      ;;
    --skip-build)
      BUILD_FIRST=0
      shift
      ;;
    --no-launch)
      LAUNCH_AFTER_INSTALL=0
      shift
      ;;
    --no-grant)
      GRANT_RUNTIME_PERMISSIONS=0
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      fail "unknown option: $1"
      ;;
  esac
done

find_adb
select_device

if [[ "$BUILD_FIRST" -eq 1 ]]; then
  echo "Building debug APK..."
  "$ROOT_DIR/gradlew" assembleDebug
fi

[[ -f "$APK_PATH" ]] || fail "APK not found: $APK_PATH"

install_args=(install -r)
if [[ "$GRANT_RUNTIME_PERMISSIONS" -eq 1 ]]; then
  install_args+=(-g)
fi
install_args+=("$APK_PATH")

echo "Installing $APK_PATH to $DEVICE_SERIAL..."
adb_cmd "${install_args[@]}"

if [[ "$LAUNCH_AFTER_INSTALL" -eq 1 ]]; then
  echo "Launching miniloc..."
  adb_cmd shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null
fi

echo "Deployment complete."
