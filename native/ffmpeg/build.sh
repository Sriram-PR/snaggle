#!/usr/bin/env bash
# Builds a minimal, remux-only ffmpeg + ffprobe for Android (LGPL, no external libs).
# The app only stream-copies H.264/AAC (and friends) between containers, so no encoders,
# decoders, network or hardware support is compiled in.
#
# Output: native/out/<abi>/libffmpeg.so, libffprobe.so (executables named lib*.so so
# Android extracts them into nativeLibraryDir, the only place an app may exec from).
#
# Usage: native/ffmpeg/build.sh [abi...]   (default: arm64-v8a x86_64)
# Env:   ANDROID_NDK_HOME (default: $ANDROID_HOME/ndk/28.2.13676358)
set -euo pipefail

FFMPEG_VERSION=9.0.2
FFMPEG_SHA256=8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e
API=24

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$ROOT/native/.build/ffmpeg"
OUT="$ROOT/native/out"
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/28.2.13676358}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
ABIS=("${@:-arm64-v8a x86_64}")
read -r -a ABIS <<< "${ABIS[*]}"

mkdir -p "$WORK"
TARBALL="$WORK/ffmpeg-$FFMPEG_VERSION.tar.xz"
if [ ! -f "$TARBALL" ]; then
    curl -fsSL -o "$TARBALL.tmp" "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
    mv "$TARBALL.tmp" "$TARBALL"
fi
echo "$FFMPEG_SHA256  $TARBALL" | sha256sum -c --quiet

for ABI in "${ABIS[@]}"; do
    case "$ABI" in
        arm64-v8a) ARCH=aarch64; TRIPLE=aarch64-linux-android ;;
        x86_64)    ARCH=x86_64;  TRIPLE=x86_64-linux-android ;;
        *) echo "Unsupported ABI: $ABI" >&2; exit 1 ;;
    esac

    SRC="$WORK/src-$ABI"
    rm -rf "$SRC" && mkdir -p "$SRC"
    tar -xf "$TARBALL" -C "$SRC" --strip-components=1
    cd "$SRC"

    # Remux only, plus the VP9 decoder: probing needs it to learn VP9's pixel format, without
    # which the MP4 muxer writes an empty vpcC box and Android can't read the file.
    # The ffmetadata demuxer reads the chapter file yt-dlp's FFmpegMetadata passes as a second input.
    ./configure \
        --prefix="$SRC/install" \
        --target-os=android --arch="$ARCH" --enable-cross-compile \
        --cc="$TOOLCHAIN/bin/$TRIPLE$API-clang" \
        --cxx="$TOOLCHAIN/bin/$TRIPLE$API-clang++" \
        --ar="$TOOLCHAIN/bin/llvm-ar" \
        --nm="$TOOLCHAIN/bin/llvm-nm" \
        --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
        --strip="$TOOLCHAIN/bin/llvm-strip" \
        --enable-pic --enable-small --disable-asm \
        --disable-debug --disable-doc --disable-network --disable-autodetect \
        --disable-everything \
        --disable-ffplay --enable-ffmpeg --enable-ffprobe \
        --enable-protocol=file,pipe \
        --enable-demuxer=mov,matroska,mpegts,aac,mp3,ogg,flac,wav,concat,ffmetadata \
        --enable-muxer=mp4,ipod,mov,matroska,webm,adts,mp3,ogg,opus \
        --enable-parser=h264,hevc,aac,aac_latm,vp8,vp9,av1,opus,vorbis,mpegaudio,flac \
        --enable-bsf=aac_adtstoasc,h264_mp4toannexb,hevc_mp4toannexb,extract_extradata,vp9_superframe,setts \
        --enable-decoder=vp9 \
        --extra-cflags="-O2" \
        --extra-ldflags="-Wl,-z,max-page-size=16384"

    make -j"$(nproc)" ffmpeg ffprobe

    mkdir -p "$OUT/$ABI"
    "$TOOLCHAIN/bin/llvm-strip" -o "$OUT/$ABI/libffmpeg.so" ffmpeg
    "$TOOLCHAIN/bin/llvm-strip" -o "$OUT/$ABI/libffprobe.so" ffprobe
    printf '%s\n' "$FFMPEG_VERSION" > "$OUT/$ABI/ffmpeg.version"
    cd "$ROOT"
done

# Every LOAD segment must be 16 KB aligned for Android 15+ 16 KB-page devices.
for f in "$OUT"/*/lib*.so; do
    if "$TOOLCHAIN/bin/llvm-readelf" -lW "$f" | awk '/LOAD/ && $NF != "0x4000" { bad=1 } END { exit bad }'; then
        echo "ok (16 KB aligned): $f ($(du -h "$f" | cut -f1))"
    else
        echo "NOT 16 KB aligned: $f" >&2; exit 1
    fi
done
