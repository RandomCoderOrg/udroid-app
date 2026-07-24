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

Checkpoint 2 currently animates an explicit UX preview. It fetches only the
small catalogue; it does not download or extract a rootfs yet. That boundary
lets us validate the interaction contract before connecting it to a
long-running, resumable installer service.

No rootfs, X server, interactive PTY, or GPU driver is bundled yet. The native
child remains deliberately small: it proves process ownership and recovery
before the same supervisor is allowed to own PRoot and a graphical session.

See [Checkpoint 2: distro and installation experience](docs/CHECKPOINT_2_INSTALL_EXPERIENCE.md).

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
