#!/usr/bin/env bash
# Builds a cffi wheel for Android (CPython 3.13, arm64-v8a) with libffi linked statically.
# curl_cffi (browser impersonation, needed by yt-dlp's TikTok extractor) has an official
# Android wheel on PyPI but depends on cffi>=2.0, which has none.
#
# Output: native/out/wheels/cffi-<ver>-cp313-cp313-android_24_arm64_v8a.whl
#
# Uses cibuildwheel's Android platform. All state stays in .tools/: a project-local SDK
# (links to the shared SDK's components plus a real copy of cmdline-tools, so sdkmanager
# installs anything new, e.g. the NDK CPython pins, locally), caches and temp files.
set -euo pipefail

CFFI_VERSION=2.1.1
CFFI_SHA256=dd31f52ea1086513bb9df30f8fcee9b8918323ae067a3d5b78bc826a000712be
LIBFFI_VERSION=3.8.0
LIBFFI_SHA256=7da3e2d9a171eb0a038f592ecad3ff2bb2550f3496d87b3b29ad0cf4430c0db4
CIBUILDWHEEL_VERSION=4.3.0
API=24

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
T="$ROOT/.tools"
WORK="$ROOT/native/.build/cffi"
OUT="$ROOT/native/out/wheels"
SHARED_SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
SDK="$T/android-sdk"
PYTHON="${PYTHON:-python3.13}"

fetch() {  # url sha256 dest
    [ -f "$3" ] || { curl -fsSL -o "$3.tmp" "$1" && mv "$3.tmp" "$3"; }
    echo "$2  $3" | sha256sum -c --quiet
}

# Project-local SDK overlay: never let sdkmanager write into the shared SDK.
mkdir -p "$SDK"/{build-tools,platforms,ndk,cmdline-tools}
for d in build-tools platforms ndk; do
    for v in "$SHARED_SDK/$d"/*; do [ -e "$SDK/$d/$(basename "$v")" ] || ln -s "$v" "$SDK/$d/"; done
done
[ -e "$SDK/platform-tools" ] || ln -s "$SHARED_SDK/platform-tools" "$SDK/platform-tools"
[ -d "$SDK/licenses" ] || cp -r "$SHARED_SDK/licenses" "$SDK/licenses"
# A real copy: sdkmanager derives the SDK root from its own resolved location.
[ -d "$SDK/cmdline-tools/latest" ] || cp -r "$SHARED_SDK/cmdline-tools/latest" "$SDK/cmdline-tools/latest"

mkdir -p "$WORK" "$OUT" "$T/tmp"
[ -x "$T/cibw-venv/bin/cibuildwheel" ] || {
    "$PYTHON" -m venv "$T/cibw-venv"
    PIP_CACHE_DIR="$T/pip-cache" "$T/cibw-venv/bin/pip" install -q "cibuildwheel==$CIBUILDWHEEL_VERSION" build
}

export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
export ANDROID_USER_HOME="$T/android-home" GRADLE_USER_HOME="$T/gradle-home"
export CIBW_CACHE_PATH="$T/cibw-cache" XDG_CACHE_HOME="$T/xdg-cache" PIP_CACHE_DIR="$T/pip-cache" TMPDIR="$T/tmp"
export JAVA_HOME="${JAVA_HOME:-/var/lib/flatpak/app/com.google.AndroidStudio/x86_64/stable/active/files/extra/jbr}"

# libffi: static + PIC so _cffi_backend is self-contained (Chaquopy's own libffi is linked into _ctypes).
fetch "https://github.com/libffi/libffi/releases/download/v$LIBFFI_VERSION/libffi-$LIBFFI_VERSION.tar.gz" \
    "$LIBFFI_SHA256" "$WORK/libffi-$LIBFFI_VERSION.tar.gz"
NDK_DIR=$(ls -d "$SDK"/ndk/27.* 2>/dev/null | sort -V | tail -1 || true)
NDK_DIR="${NDK_DIR:-$SDK/ndk/28.2.13676358}"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
FFI_PREFIX="$WORK/libffi-arm64-v8a"
if [ ! -f "$FFI_PREFIX/lib/libffi.a" ]; then
    rm -rf "$WORK/libffi-src" && mkdir -p "$WORK/libffi-src"
    tar -xzf "$WORK/libffi-$LIBFFI_VERSION.tar.gz" -C "$WORK/libffi-src" --strip-components=1
    (cd "$WORK/libffi-src" && \
        ./configure --host=aarch64-linux-android --prefix="$FFI_PREFIX" \
            --disable-shared --enable-static --with-pic --disable-docs --disable-multi-os-directory \
            CC="$TOOLCHAIN/bin/aarch64-linux-android$API-clang" \
            AR="$TOOLCHAIN/bin/llvm-ar" RANLIB="$TOOLCHAIN/bin/llvm-ranlib" >/dev/null && \
        make -j"$(nproc)" >/dev/null && make install >/dev/null)
fi

# cffi wheel via cibuildwheel; cffi's setup.py finds libffi through pkg-config.
fetch "https://files.pythonhosted.org/packages/source/c/cffi/cffi-$CFFI_VERSION.tar.gz" \
    "$CFFI_SHA256" "$WORK/cffi-$CFFI_VERSION.tar.gz"
rm -rf "$WORK/cffi-src" && mkdir -p "$WORK/cffi-src"
tar -xzf "$WORK/cffi-$CFFI_VERSION.tar.gz" -C "$WORK/cffi-src" --strip-components=1
export CIBW_PLATFORM=android CIBW_BUILD=cp313-android_arm64_v8a CIBW_ARCHS=arm64_v8a CIBW_TEST_SKIP='*'
export CIBW_BUILD_FRONTEND=build
# The Android env sets its own PKG_CONFIG_LIBDIR (CPython prefix); PKG_CONFIG_PATH is searched first.
export CIBW_ENVIRONMENT_ANDROID="PKG_CONFIG_PATH=$FFI_PREFIX/lib/pkgconfig"
(cd "$WORK/cffi-src" && "$T/cibw-venv/bin/cibuildwheel" --output-dir "$OUT" .)
ls -la "$OUT"
