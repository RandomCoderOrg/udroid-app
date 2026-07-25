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

Selecting an image does not start a download. The review screen persists across
Activity and process recreation, and the archive transfer begins only after
**Download image** is pressed. A verified archive is retained across pause or
failure, and is deleted only after the installed rootfs passes its health
check and receives its readiness marker. On a new installation, uDroid opens
the Linux image catalogue first; terminal, app, and desktop actions appear only
after a Linux system is ready.

The searchable catalogue combines uDroid's tested Ubuntu images with pinned,
checksum-verified archives from `termux/proot-distro` for Debian, Arch Linux,
Alpine Linux, and Void Linux. Each distribution uses its actual logo and shows
the image source before installation. See the
[distribution catalogue](docs/DISTRIBUTION_CATALOGUE.md) for support scope and
how new image sources are admitted.

PRoot and an interactive terminal are now bundled for the supported Android
ABIs. The terminal session belongs to the foreground service rather than the
Activity, so navigating away and returning reattaches to the same PTY and
transcript. The pinned Termux:X11 server and renderer now build inside the APK,
and the Desktop page attaches an Android surface to its supervised X11 process
and private display socket. Its compact controls expose the keyboard and
persistent output/input settings without tying the X server lifetime to the
page. The Linux Apps page also discovers freedesktop entries from the installed
rootfs and can boot the runtime and launch a graphical application directly.
Selected Linux applications can also be published to the Android launcher's
long-press menu and pinned to the home screen.
See the [Linux application launcher](docs/LINUX_APPLICATION_LAUNCHER.md) for
the current contract and limitations.

uDroid also checks its public GitHub prereleases in the background. Newer
versions appear in the Workspace and, when Android notification permission is
available, as a system notification. APK downloads are resumable and are
accepted only when GitHub's asset digest and the release `SHA256SUMS` agree.
Android's package installer remains the final confirmation boundary. See
[App updates](docs/APP_UPDATES.md) for the trust model, signing requirements,
and migration limitation of early debug-signed builds.

## Development releases

Git tags matching `v*` are built by GitHub Actions. Each resulting prerelease
contains:

- one universal, optimized APK covering `arm64-v8a`, `armeabi-v7a`, and
  `x86_64`, signed with the persistent project update key;
- `SHA256SUMS` for verifying the downloaded APK;
- GitHub's source archives, including the vendored Termux terminal components
  and the corresponding third-party notices.

The published `v0.0.2` APK predates stable update signing and retains its
original debug asset name. It is a development build, not a Play Store or
production-signed release:

```sh
adb install -r udroid-v0.0.2-debug.apk
```

Tagged releases after this updater checkpoint require these GitHub Actions
secrets:

- `UDROID_SIGNING_STORE_BASE64`
- `UDROID_SIGNING_STORE_PASSWORD`
- `UDROID_SIGNING_KEY_ALIAS`
- `UDROID_SIGNING_KEY_PASSWORD`

The first stable-signed development release cannot replace the older
ephemeral-debug-signed APK in place. Existing testers must uninstall and
install that release once; subsequent releases signed by the same key can use
the in-app updater.

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
./gradlew :app:assembleRelease
```

The app targets API 36. On Android 10 and newer it launches packaged Android
ELFs through `/system/bin/linker(64)`. PRoot's static guest loader is installed
as an extracted APK native library so its second execution hop is not blocked
by Android's writable-app-data execution policy.

UI performance is measured from optimized builds with device Macrobenchmarks,
Perfetto traces, and generated Baseline Profiles. See
[Performance](docs/PERFORMANCE.md) for the commands and current catalogue
results.

## Licensing

The uDroid-owned Android shell is MIT-licensed, matching
`fs-manager-udroid`. Packaged PRoot is GPL-2.0 and statically links talloc,
whose library is LGPL-3.0-or-later. The vendored Termux terminal emulator and
view are the Apache-2.0 components identified by Termux's upstream license
exception. The embedded Termux:X11 module is GPLv3, so APKs containing it are
distributed as GPLv3 combined works. Exact source versions, checksums, local
changes, and build commands are recorded in `tools/` and `third_party/`;
binary releases must also provide the applicable corresponding source and
license texts. See [Third-party notices](THIRD_PARTY_NOTICES.md).
