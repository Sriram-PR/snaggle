# Snaggle

Save videos and audio from YouTube, TikTok, X and other sites to your Android phone, straight from the share menu. Built on [yt-dlp](https://github.com/yt-dlp/yt-dlp), which Snaggle keeps up to date by itself, without app updates.

Android 10 or newer; tested mainly on Android 14+.

## Install

Download the APK for your phone from [Releases](https://github.com/Sriram-PR/snaggle/releases): `arm64-v8a` for almost every phone, `x86_64` for emulators. [Obtainium](https://github.com/ImranR98/Obtainium) can install it and keep it updated: add `https://github.com/Sriram-PR/snaggle`.

## Verify the download

Every release lists SHA-256 checksums in `SHA256SUMS` and has a GitHub build attestation. The APK signing certificate's SHA-256 fingerprint is:

    (added with the first release)

Check it with `apksigner verify --print-certs snaggle-<version>-arm64-v8a.apk`.

## Build from source

Linux x86_64. Needs JDK 25 with `javac`, `python3.13` on `PATH`, and an Android SDK (`ANDROID_HOME`) with platform `android-37.0`, build-tools `36.1.0` and NDK `28.2.13676358`.

    native/ffmpeg/build.sh
    native/quickjs/build.sh
    native/cffi/build.sh
    ./gw :app:assembleArm64Release

`./gw` wraps `./gradlew` and keeps Gradle's state in `.tools/`.

### FFmpeg

The bundled ffmpeg/ffprobe are FFmpeg 9.0.2 under the LGPL-2.1-or-later, built by `native/ffmpeg/build.sh` from the official release tarball (pinned by SHA-256). That script is the complete build recipe.

## Use responsibly

Download only what you have the right to. Respect each site's terms and the rights of the people who made the content.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE). Bundled components: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
