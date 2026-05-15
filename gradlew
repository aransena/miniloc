#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
GRADLE_VERSION="${MINILOC_GRADLE_VERSION:-7.6.4}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle}"
export GRADLE_USER_HOME

GRADLE_HOME="$GRADLE_USER_HOME/gradle-$GRADLE_VERSION"
GRADLE_BIN="$GRADLE_HOME/bin/gradle"
BOOTSTRAP_JDK_HOME="$GRADLE_USER_HOME/jdks/temurin-17"

download() {
  local url="$1"
  local out="$2"
  if command -v curl >/dev/null 2>&1; then
    curl -L --fail --retry 3 --output "$out" "$url"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$out" "$url"
  else
    echo "Neither curl nor wget is available." >&2
    exit 1
  fi
}

if [[ ! -x "$GRADLE_BIN" ]]; then
  mkdir -p "$GRADLE_USER_HOME/wrapper/dists"
  ZIP="$GRADLE_USER_HOME/wrapper/dists/gradle-$GRADLE_VERSION-bin.zip"
  if [[ ! -f "$ZIP" ]]; then
    download "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" "$ZIP"
  fi
  rm -rf "$GRADLE_HOME"
  unzip -q "$ZIP" -d "$GRADLE_USER_HOME"
fi

CURRENT_JLINK=""
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jlink" ]]; then
  CURRENT_JLINK="$JAVA_HOME/bin/jlink"
elif command -v jlink >/dev/null 2>&1; then
  CURRENT_JLINK="$(command -v jlink)"
fi

if [[ -z "$CURRENT_JLINK" ]]; then
  mkdir -p "$GRADLE_USER_HOME/jdks"
  JDK_TAR="$GRADLE_USER_HOME/jdks/temurin-17-jdk.tar.gz"
  if [[ ! -x "$BOOTSTRAP_JDK_HOME/bin/jlink" ]]; then
    if [[ ! -f "$JDK_TAR" ]]; then
      download "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" "$JDK_TAR"
    fi
    rm -rf "$BOOTSTRAP_JDK_HOME" "$GRADLE_USER_HOME/jdks/temurin-17-tmp"
    mkdir -p "$GRADLE_USER_HOME/jdks/temurin-17-tmp"
    tar -xzf "$JDK_TAR" -C "$GRADLE_USER_HOME/jdks/temurin-17-tmp" --strip-components=1
    mv "$GRADLE_USER_HOME/jdks/temurin-17-tmp" "$BOOTSTRAP_JDK_HOME"
  fi
  export JAVA_HOME="$BOOTSTRAP_JDK_HOME"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
  export ANDROID_HOME="$GRADLE_USER_HOME/android-sdk"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
elif [[ -n "${ANDROID_HOME:-}" ]]; then
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
else
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
fi

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$SDKMANAGER" ]]; then
  mkdir -p "$ANDROID_HOME/cmdline-tools" "$GRADLE_USER_HOME/android-sdk-downloads"
  CMDLINE_ZIP="$GRADLE_USER_HOME/android-sdk-downloads/commandlinetools-linux-9477386_latest.zip"
  if [[ ! -f "$CMDLINE_ZIP" ]]; then
    download "https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip" "$CMDLINE_ZIP"
  fi
  TMP_DIR="$GRADLE_USER_HOME/android-sdk-downloads/cmdline-tools-tmp"
  rm -rf "$TMP_DIR" "$ANDROID_HOME/cmdline-tools/latest"
  mkdir -p "$TMP_DIR"
  unzip -q "$CMDLINE_ZIP" -d "$TMP_DIR"
  mv "$TMP_DIR/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi

if [[ ! -f "$ANDROID_HOME/platforms/android-35/android.jar" || ! -x "$ANDROID_HOME/build-tools/35.0.0/aapt2" ]]; then
  set +o pipefail
  yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null
  set -o pipefail
  "$SDKMANAGER" --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
fi

exec "$GRADLE_BIN" "-Pandroid.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/35.0.0/aapt2" "$@"
