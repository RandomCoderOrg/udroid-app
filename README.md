# uDroid for Android

[![Android CI](https://github.com/RandomCoderOrg/udroid-app/actions/workflows/android.yml/badge.svg)](https://github.com/RandomCoderOrg/udroid-app/actions/workflows/android.yml)

> [!WARNING]
> **uDroid is in early development.** Expect incomplete features and breaking
> changes. Use it for testing and keep important data backed up.

This is the standalone Android application for
[uDroid](https://github.com/RandomCoderOrg/fs-manager-udroid): a friendly,
supervised way to install and use Linux distributions on Android.

## Download and install

[Download uDroid from GitHub Releases](https://github.com/RandomCoderOrg/udroid-app/releases),
open the newest prerelease, and select its `.apk` file. Android may ask you to
allow installs from the browser or file manager you used.

Existing stable-signed builds can also download and verify newer releases from
the app's **About** page.

## Current feature support

| Area | What is supported |
| --- | --- |
| Linux installation | Search uDroid and PRoot-Distro archives alongside official Docker Hub operating-system images. Choose a compatible OCI tag, review its architecture and compressed size, resume transfers, verify SHA-256 digests, and retain validated data across retries. |
| Multiple systems | Keep multiple distributions installed. Selecting an installed system opens its own status and controls page instead of immediately starting a terminal. |
| Terminal | Use the bundled Termux terminal emulator and persistent, service-owned PTY without installing Termux separately. Each terminal action targets the selected rootfs. |
| Linux applications | Discover freedesktop `.desktop` entries, launch graphical or terminal applications, publish dynamic shortcuts, and pin selected applications to the Android home screen. |
| Embedded X11 | Use the bundled Termux:X11 server and uDroid display surface without installing the separate Termux:X11 application. Keyboard, direct-pointer, trackpad-style, native multi-touch, and display settings are available inside uDroid. |
| Desktop environments | Detect installed X11 sessions from standard `xsessions` directories. Start, stop, or restart the selected desktop while uDroid reports which rootfs and desktop own `DISPLAY :0`. |
| Compositing | Enable or disable compositing for supported XFCE, Plasma, and MATE sessions, then apply the setting through a supervised desktop restart. GNOME is treated as compositor-required. |
| Audio | Play Linux audio through Android with an app-owned PulseAudio/OpenSL ES bridge. Enable microphone input per Linux system with Android runtime consent; guest traffic stays on authenticated device-local loopback. |

### Current limitations

- uDroid currently supervises one active Linux runtime and one X11 display,
  `DISPLAY :0`, at a time. Multiple distributions can remain installed, but
  their live desktop sessions are not concurrent.
- Desktop lifecycle support currently targets X11 sessions advertised through
  `/usr/share/xsessions` or `/usr/local/share/xsessions`. Wayland sessions are
  not launched.
- Compositor control is desktop-specific. LXQt and unknown/custom sessions are
  still launchable, but uDroid does not guess at a compositor switch when no
  safe standard adapter is known.
- PRoot provides the Linux userspace. It is not a virtual machine, does not
  provide a normal systemd/logind boot, and cannot reproduce every native Linux
  service or sandbox boundary.
- The embedded display is functional, but general-purpose GPU acceleration is
  not yet a supported app contract. Desktop responsiveness depends on the
  device, resolution, applications, and compositor choice.
- Background desktop recovery, multiple displays, and production-level desktop
  compatibility remain incomplete. Audio currently targets PulseAudio clients;
  direct ALSA-only applications may require a PulseAudio compatibility plugin.

See the [distribution catalogue](docs/DISTRIBUTION_CATALOGUE.md),
[OCI image architecture](docs/OCI_IMAGES.md),
[Linux application launcher](docs/LINUX_APPLICATION_LAUNCHER.md),
[X11 runtime architecture](docs/X11_RUNTIME_ARCHITECTURE.md),
[audio runtime](docs/AUDIO_RUNTIME.md),
[performance notes](docs/PERFORMANCE.md), and
[app updates](docs/APP_UPDATES.md) for current behavior and technical details.

## Development releases

Git tags matching `v*` are built by GitHub Actions. Each resulting prerelease
contains:

- one universal, optimized APK covering `arm64-v8a`, `armeabi-v7a`, and
  `x86_64`, signed with the persistent project update key;
- `SHA256SUMS` for verifying the downloaded APK;
- GitHub's source archives, including the vendored Termux terminal components
  and the corresponding third-party notices.

Pull requests and non-tag workflow runs publish a separate optimized
`uDroid Dev` APK for 30 days. It uses the `org.randomcoder.udroid.dev`
application ID and the Android debug key, so it can be installed beside the
update-signed app without replacing it. PR artifacts are named with the pull
request number and head commit, for example `udroid-pr-24-cf9c1ed-dev.apk`.
They are test artifacts and cannot update either the official app or another
machine's debug build.

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
./tools/build-gnu-tar-assets.sh
./tools/build-pulseaudio-assets.sh
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
whose library is LGPL-3.0-or-later. The rootfs installer also packages GNU tar
and Termux's BSD-licensed libandroid-glob implementation. The vendored Termux
terminal emulator and view are the Apache-2.0 components identified by
Termux's upstream license exception. The embedded Termux:X11 module is GPLv3,
so APKs containing it are distributed as GPLv3 combined works. Exact source
versions, checksums, local changes, and build commands are recorded in
`tools/` and `third_party/`; binary releases must also provide the applicable
corresponding source and license texts. See
[Third-party notices](THIRD_PARTY_NOTICES.md).
