#!/usr/bin/env bash
# Build the LBA2-DS APK (dual-screen build for the Retroid Pocket Duo Lite).
#
# 1. the engine, with scripts/dev/build-android.sh (arm64-v8a),
# 2. its libraries staged into android-ds/app/src/main/jniLibs,
# 3. the Gradle project in android-ds/ (Kotlin + Compose second screen).
#
# Needs ANDROID_NDK, SDL3_ANDROID_DIR (scripts/dev/build-sdl3-android.sh) and
# ANDROID_HOME / JAVA_HOME for Gradle. Output: dist/lba2ds-<version>.apk
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

: "${ANDROID_NDK:?set ANDROID_NDK}"
: "${SDL3_ANDROID_DIR:?set SDL3_ANDROID_DIR}"
: "${ANDROID_HOME:?set ANDROID_HOME}"

if [[ "${SKIP_NATIVE:-0}" != 1 ]]; then
    bash scripts/dev/build-android.sh
fi

BUILD=out/build/android_arm64
JNI=android-ds/app/src/main/jniLibs/arm64-v8a
STRIP="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
rm -rf "$JNI"
mkdir -p "$JNI"
cp "$BUILD/SOURCES/liblba2cc.so" "$JNI/libmain.so"
cp "$SDL3_ANDROID_DIR/lib/libSDL3.so" "$JNI/"
cp "$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$JNI/"
for lib in "$JNI"/*.so; do "$STRIP" --strip-debug "$lib"; done

cd android-ds
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew --no-daemon -q assembleRelease

# The app's own release version (android-ds/app/build.gradle.kts), not the engine's.
VERSION="$(sed -n 's/^val releaseVersion = "\(.*\)"/\1/p' app/build.gradle.kts)"
mkdir -p "$REPO_ROOT/dist"
cp app/build/outputs/apk/release/app-release.apk "$REPO_ROOT/dist/lba2ds-$VERSION.apk"
echo "built: dist/lba2ds-$VERSION.apk"
