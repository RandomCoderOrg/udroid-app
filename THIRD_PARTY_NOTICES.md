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

## GNU tar and libandroid-glob

- GNU tar project: <https://www.gnu.org/software/tar/>
- GNU tar version: `1.35`
- GNU tar source archive SHA-256:
  `4d62ff37342ec7aed748535323930c7cf94acf71c3591882b26a7ea50f3edc16`
- GNU tar license: GNU General Public License version 3 or later
- libandroid-glob project:
  <https://github.com/termux/termux-packages/tree/fea50ba4649e6fddd1861741402c0aafa63411f2/packages/libandroid-glob>
- libandroid-glob version: `0.6`, revision `3`
- libandroid-glob license: BSD 3-Clause

`tools/build-gnu-tar-assets.sh` reproduces the checked-in Android helpers from
the pinned sources. The helper is intentionally private to uDroid; it does not
assume Termux's application id or prefix. It provides the
`--delay-directory-restore` behavior required by PRoot-Distro archives such as
Arch Linux, whose read-only directory modes must be applied after their
contents have been extracted.

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

## PRoot-Distro rootfs recipes

- Project: <https://github.com/termux/proot-distro>
- Recipe release: `v4.34.2`
- Recipe commit: `deb3abd32f233605b51baf6726ba70ad9ca57c57`
- License: GNU General Public License version 3
- Local catalogue:
  `app/src/main/java/org/randomcoder/udroid/catalog/ProotDistroArchiveCatalog.kt`

uDroid does not bundle these distribution archives in the APK. It packages
their pinned upstream URLs and SHA-256 values and downloads a selected archive
at runtime. Current PRoot-Distro has moved to arbitrary OCI image installation;
the pinned archive recipes remain intentionally separate from future registry
search support.

## XZ for Java

- Project: <https://tukaani.org/xz/java.html>
- Version: `1.12`
- License: BSD Zero Clause License

XZ for Java streams pinned PRoot-Distro `.tar.xz` root filesystems into the
existing PRoot-aware tar extraction path.

## Distribution marks

- Project: <https://github.com/simple-icons/simple-icons>
- Version: `16.21.0`
- Project license: CC0 1.0
- Included marks: Ubuntu, Debian, Arch Linux, Alpine Linux, and Void Linux

The vector paths and brand colors identify their respective distributions.
Distribution names and marks may be trademarks of their respective owners;
Simple Icons' legal disclaimer applies.
