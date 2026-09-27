# Development

## CI builds and releases

Git tags matching `v*` are built by GitHub Actions. Each prerelease contains:

- one universal, optimized APK for `arm64-v8a`, `armeabi-v7a`, and `x86_64`,
  signed with the project update key;
- `SHA256SUMS` for the APK;
- GitHub source archives with vendored Termux components and third-party
  notices.

Pull requests and non-tag workflow runs publish an optimized `uDroid Dev` APK
for 30 days. It uses the `org.randomcoder.udroid.dev` application ID and the
Android debug key, so it can be installed beside the update-signed app. PR
artifacts include the pull request number and head commit, for example
`udroid-pr-24-cf9c1ed-dev.apk`. These artifacts cannot update the official app
or a debug build signed on another machine.

The published `v0.0.2` APK predates stable update signing and retains its
original debug asset name:

```sh
adb install -r udroid-v0.0.2-debug.apk
```

Tagged releases require these GitHub Actions secrets:

- `UDROID_SIGNING_STORE_BASE64`
- `UDROID_SIGNING_STORE_PASSWORD`
- `UDROID_SIGNING_KEY_ALIAS`
- `UDROID_SIGNING_KEY_PASSWORD`

The first stable-signed build cannot replace an older ephemeral-debug-signed
APK. Testers must reinstall once; later builds signed with the same key can use
the in-app updater.

## Build locally

Requirements:

- JDK 17 or newer
- Android SDK platform 36
- Android NDK 28.2 (runtime probes also build with NDK 26 or newer)

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
as an extracted APK native library so Android can execute its second hop.

UI performance is measured from optimized builds with device Macrobenchmarks,
Perfetto traces, and generated Baseline Profiles. See
[Performance](PERFORMANCE.md) for commands and current results.

## Licensing and redistribution

The uDroid-owned Android shell is MIT-licensed, matching `fs-manager-udroid`.
Packaged PRoot is GPL-2.0 and statically links LGPL-3.0-or-later talloc. The
rootfs installer packages GNU tar and Termux's BSD-licensed libandroid-glob.
The vendored Termux terminal components use Apache-2.0 under Termux's upstream
license exception. The embedded Termux:X11 module is GPLv3, so APKs containing
it are distributed as GPLv3 combined works.

Exact source versions, checksums, patches, and build commands are recorded in
`tools/` and `third_party/`. Binary releases must provide the applicable source
and license texts. See the repository [license](../LICENSE) and
[third-party notices](../THIRD_PARTY_NOTICES.md).
