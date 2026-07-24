# Third-party notices

The repository includes reproducible Android builds of the following
third-party components.

## PRoot

- Project: <https://github.com/termux/proot>
- Version: `5.1.107.86`
- Source archive SHA-256:
  `692da7f952ac390eb65c4117d360cad23a052525eea4eb110ae42f8a4a7d7bb8`
- License: GNU General Public License version 2
- Local modification: `tools/proot-ndk28.patch`

## talloc

- Project: <https://talloc.samba.org/>
- Version: `2.4.3`
- Source archive SHA-256:
  `dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd`
- License: GNU Lesser General Public License version 3 or later

`tools/build-proot-assets.sh` downloads the pinned source archives, verifies
the hashes, applies the recorded patch, and builds the checked-in binaries.
Release packaging must provide the applicable full license texts and complete
corresponding source alongside binary downloads.

## Termux terminal emulator and view

- Project: <https://github.com/termux/termux-app>
- Version: `v0.118.3`
- Imported paths: `terminal-emulator` and `terminal-view`
- License: Apache License 2.0, as identified by the exception in Termux's
  upstream `LICENSE.md`
- Upstream license record: `third_party/termux/LICENSE.md`
- Local change record: `third_party/termux/README.udroid.md`

Only the reusable terminal emulator and view modules are included. Other
Termux application code is not part of this checkpoint. The source is built
inside this Gradle project so the APK and its native PTY bridge are
reproducible from the checked-in tree.

## Termux:X11

- Project: <https://github.com/termux/termux-x11>
- Version: `0cb0203c283bfafbad380b90444296aa42af058d`
- Imported module: `lorie`, including its pinned native Xorg dependencies
- License: GNU General Public License version 3
- Upstream license record: `third_party/termux-x11/LICENSE`
- Integration design: `docs/X11_RUNTIME_ARCHITECTURE.md`
- Local patch set: `patches/termux-x11`

The Termux:X11 module is linked into the uDroid APK. Consequently, distributed
APKs containing this module are GPLv3 combined works. uDroid-authored files
retain their existing MIT grants and may also be distributed under GPLv3 as
part of the combined application. Complete corresponding source includes this
repository and all recursively pinned Termux:X11 submodules.
