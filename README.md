# uDroid for Android

[![Android CI](https://github.com/RandomCoderOrg/udroid-app/actions/workflows/android.yml/badge.svg)](https://github.com/RandomCoderOrg/udroid-app/actions/workflows/android.yml)

> [!WARNING]
> **uDroid is in extremely early development.** The current builds are for
> testing and contributor feedback, not daily use. Expect incomplete features,
> breaking changes, installation failures, and Linux environments that may need
> to be reinstalled between versions. Back up anything important before using
> the app.

This is the standalone Android application for
[uDroid](https://github.com/RandomCoderOrg/fs-manager-udroid): a friendly,
supervised way to install, boot, manage, and use Linux distributions on
Android without making a terminal the product UI.

The app is device- and distro-oriented, not Tensor-specific. Optional hardware
profiles may provide graphics, video, or audio acceleration on supported
devices. The experimental Tensor G1 Panfrost work is one such future profile,
not a uDroid dependency or compatibility requirement.

Selecting an image does not start a download. The review screen persists across
Activity and process recreation, and the archive transfer begins only after
**Download image** is pressed. A verified archive is retained across pause or
failure, and is deleted only after the installed rootfs passes its health
check and is atomically activated.

PRoot and an interactive terminal are now bundled for the supported Android
ABIs. The terminal session belongs to the foreground service rather than the
Activity, so navigating away and returning reattaches to the same PTY and
transcript. No X server or GPU driver is bundled yet. The next core boundary is
multi-session lifecycle and a desktop-session contract.

## Development releases

Git tags matching `v*` are built by GitHub Actions. Each resulting prerelease
contains:

- one universal debug-signed APK covering `arm64-v8a`, `armeabi-v7a`, and
  `x86_64`;
- `SHA256SUMS` for verifying the downloaded APK;
- GitHub's source archives, including the vendored Termux terminal components
  and the corresponding third-party notices.

The `v0.0.1` APK is a development build, not a Play Store or production-signed
release. It can be installed for testing with:

```sh
adb install -r udroid-v0.0.1-debug.apk
```

## Build

Requirements:

- JDK 17 or newer
- Android SDK platform 36
- Android NDK 28.2 (the probes also build with NDK 26+)

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
./app/src/main/cpp/build-runtime-probe.sh
./tools/build-proot-assets.sh
./gradlew :app:assembleDebug
```

The app targets API 36. On Android 10 and newer it launches packaged Android
ELFs through `/system/bin/linker(64)`. PRoot's static guest loader is installed
as an extracted APK native library so its second execution hop is not blocked
by Android's writable-app-data execution policy.

## Licensing

The uDroid-owned Android shell is MIT-licensed, matching
`fs-manager-udroid`. Packaged PRoot is GPL-2.0 and statically links talloc,
whose library is LGPL-3.0-or-later. The vendored Termux terminal emulator and
view are the Apache-2.0 components identified by Termux's upstream license
exception. Exact source versions, checksums, local changes, and build commands
are recorded in `tools/` and `third_party/`; binary releases must also provide
the applicable corresponding source and license texts. See
[Third-party notices](THIRD_PARTY_NOTICES.md).

Future imports retain their own licenses. Importing other GPLv3 Termux or
Termux:X11 code would change the combined work's distribution obligations and
must be reviewed separately.
