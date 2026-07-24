# uDroid for Android

This is the standalone Android application for
[uDroid](https://github.com/RandomCoderOrg/fs-manager-udroid): a friendly,
supervised way to install, boot, manage, and use Linux distributions on
Android without making a terminal the product UI.

The app is device- and distro-oriented, not Tensor-specific. Optional hardware
profiles may provide graphics, video, or audio acceleration on supported
devices. The experimental Tensor G1 Panfrost work is one such future profile,
not a uDroid dependency or compatibility requirement.

## Current checkpoints

- Checkpoint 1: supervised Android runtime shell, persisted lifecycle state,
  structured journal, device probes, and ABI-specific native child probes.
- Checkpoint 2: live uDroid distro catalogue with cache and built-in fallback,
  recommended versus advanced image selection, weighted installation stages,
  and a half-screen terminal drawer driven by the same event model.
- Checkpoint 2.1: compact application shell and responsive visual foundation,
  with bottom navigation, a restrained type scale, flatter working surfaces,
  and an installation stage rail tested at enlarged Android font scale.
- Checkpoint 3: foreground Linux-image service with explicit confirmation,
  validated HTTP Range resume, throttled persisted progress, pause/resume,
  streamed SHA-256 verification, and atomic cache promotion.
- Checkpoint 4: cancellable PRoot extraction into disposable staging, Android
  compatibility files, a guest-command health gate, and atomic rootfs
  activation.

Selecting an image does not start a download. The review screen persists across
Activity and process recreation, and the archive transfer begins only after
**Download image** is pressed. A verified archive is retained across pause or
failure, and is deleted only after the installed rootfs passes its health
check and is atomically activated.

PRoot is now bundled for the supported Android ABIs, and a selected rootfs can
be installed. No X server, interactive PTY, or GPU driver is bundled yet. The
next core boundary is a supervised interactive distro session.

See [Checkpoint 2: distro and installation experience](docs/CHECKPOINT_2_INSTALL_EXPERIENCE.md).
The visual rules are maintained in
[Checkpoint 2.1: compact UI foundation](docs/CHECKPOINT_2_1_UI_FOUNDATION.md).
The transfer and integrity contract is documented in
[Checkpoint 3: resumable image core](docs/CHECKPOINT_3_DOWNLOAD_CORE.md).
Extraction, recovery, and activation are documented in
[Checkpoint 4: recoverable rootfs activation](docs/CHECKPOINT_4_ROOTFS_ACTIVATION.md).

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

## Source plan

The original standalone-app analysis is currently maintained in
`tensor-g1-proot-gpu/tensor-g1/STANDALONE_ANDROID_APP_PLAN.md`. Its lifecycle,
installer, X11, and supervisor findings apply here, but its working name and
Tensor-first product framing are superseded by this repository.

## Licensing

The uDroid-owned Android shell is MIT-licensed, matching
`fs-manager-udroid`. Packaged PRoot is GPL-2.0 and statically links talloc,
whose library is LGPL-3.0-or-later. Their exact source versions, checksums,
local patch, and build commands are recorded in `tools/`; binary releases must
also make complete corresponding source and license texts available. See
[Third-party notices](THIRD_PARTY_NOTICES.md).

Future imports retain their own licenses. Distributing a combined build
containing GPLv3 Termux or Termux:X11 code must satisfy GPLv3 for that combined
work and include corresponding source.
