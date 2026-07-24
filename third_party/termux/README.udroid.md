# Termux terminal modules in uDroid

This directory contains the `terminal-emulator` and `terminal-view` source
directories from the immutable upstream tag
[`v0.118.3`](https://github.com/termux/termux-app/tree/v0.118.3).

Termux's upstream `LICENSE.md` identifies these two reusable libraries as
Apache License 2.0 exceptions to the application repository's GPLv3 license.
The upstream license record is retained next to this file.

## Deliberate local changes

- The original module Gradle files were reduced to standalone Android-library
  builds compatible with uDroid's Android Gradle Plugin.
- Manifest `package` attributes were removed in favor of Gradle namespaces, as
  required by current Android build tools.
- `terminal-emulator/src/main/jni/Android.mk` links `libtermux.so` with
  `-Wl,-z,max-page-size=16384`.

No Java terminal behavior was changed. uDroid owns the
`TerminalSessionClient`, `TerminalViewClient`, service lifecycle, PRoot launch
vector, and Compose integration outside this directory.

## Reproducibility check

```sh
./gradlew clean :app:assembleDebug
llvm-readelf -lW \
  third_party/termux/terminal-emulator/build/intermediates/cxx/Debug/*/obj/local/arm64-v8a/libtermux.so
```

Every arm64 `LOAD` segment must report alignment `0x4000`.
