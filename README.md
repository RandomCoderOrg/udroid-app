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

Selecting an image does not start a download. The review screen persists across
Activity and process recreation, and the archive transfer begins only after
**Download image** is pressed. Checkpoint 3 stops after the compressed archive
is verified; extraction and rootfs activation are the next core boundary.

No rootfs, X server, interactive PTY, or GPU driver is bundled yet. The native
child remains deliberately small: it proves process ownership and recovery
before the same supervisor is allowed to own PRoot and a graphical session.

See [Checkpoint 2: distro and installation experience](docs/CHECKPOINT_2_INSTALL_EXPERIENCE.md).
The visual rules are maintained in
[Checkpoint 2.1: compact UI foundation](docs/CHECKPOINT_2_1_UI_FOUNDATION.md).
The transfer and integrity contract is documented in
[Checkpoint 3: resumable image core](docs/CHECKPOINT_3_DOWNLOAD_CORE.md).

## Build

Requirements:

- JDK 17 or newer
- Android SDK platform 36
- Android NDK 28.2 (the probes also build with NDK 26+)

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
./app/src/main/cpp/build-runtime-probe.sh
./gradlew :app:assembleDebug
```

The development APK targets API 28 intentionally. Android 10 blocks
`execve()` from writable app-private storage for apps targeting API 29+, while
a downloaded Linux rootfs necessarily contains executable files. Initial
distribution is therefore GitHub/F-Droid-oriented; Play compatibility is a
separate architecture gate.

## Source plan

The original standalone-app analysis is currently maintained in
`tensor-g1-proot-gpu/tensor-g1/STANDALONE_ANDROID_APP_PLAN.md`. Its lifecycle,
installer, X11, and supervisor findings apply here, but its working name and
Tensor-first product framing are superseded by this repository.

## Licensing

The uDroid-owned Android shell is MIT-licensed, matching
`fs-manager-udroid`. Future imports retain their own licenses; distributing a
combined build containing GPLv3 Termux or Termux:X11 code must satisfy GPLv3
for that combined work and include corresponding source.
