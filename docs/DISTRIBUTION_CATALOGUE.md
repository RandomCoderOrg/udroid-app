# Distribution catalogue

uDroid presents one searchable catalogue while preserving where each image
came from. Search matches distribution, release, suite, variant, architecture,
experience, and provider. Selecting a result opens a review page; it never
starts a download by itself.

Every healthy rootfs remains independently discoverable through its
`.udroid-ready` marker. The Linux page uses one searchable catalogue rather
than duplicating installed systems into a second section. Installed entries
sort first, show their active or installed state in the existing row, and open
the matching terminal instead of returning to the download screen. One
explicit active system is persisted separately from installer progress.
Existing installations are migrated by scanning app-private rootfs storage;
no archive download or rootfs move is required.

The active selection is the default for Terminal, Desktop, and Linux Apps.
Opening another installed system stops an incompatible running session before
starting the selected rootfs. uDroid currently supervises one active distro
session at a time; this is distinct from limiting storage to one distro.

## Current sources

| Distribution | Release | Source | Archive handling |
| --- | --- | --- | --- |
| Ubuntu | uDroid catalogue releases | uDroid | gzip tar |
| Debian | 13 (Trixie) | `termux/proot-distro` v4.29.0 | XZ tar, strip one wrapper directory |
| Arch Linux | rolling | `termux/proot-distro` v4.29.0 | XZ tar, strip one wrapper directory |
| Alpine Linux | 3.22 | `termux/proot-distro` v4.30.1 | XZ tar, strip one wrapper directory |
| Void Linux | rolling | `termux/proot-distro` v4.29.0 | XZ tar, strip one wrapper directory |

The PRoot-Distro URLs and SHA-256 hashes are pinned in
`ProotDistroArchiveCatalog.kt`. uDroid streams XZ decompression into its PRoot
tar extractor, retains a verified archive when setup fails, and deletes it only
after the installed rootfs passes the execution health probe. Alpine 3.22 was
installed end to end on an arm64 Pixel 6a as the device proof for this
integration.

Fedora is not offered from the legacy archive set because its upstream recipe
was marked broken on Android 15 and newer. An image is not shown merely because
an archive exists; its architecture, checksum, extraction layout, and PRoot
startup must be understood first.

## Official container images

The same searchable list also includes active operating-system repositories
from Docker Hub's official `library` namespace. Selecting one opens a version
page containing only tags that publish an image for the phone's OCI platform.
The review page records the selected platform, compressed size, tag, and
immutable manifest digest before installation begins.

Container images follow a separate verified OCI pipeline; they are not passed
to Docker, containerd, or a daemon. uDroid resolves the manifest, downloads and
verifies each referenced blob, applies ordered filesystem layers and OCI
whiteouts in app-private staging storage, adds Android/PRoot compatibility
files, executes the standard rootfs health probe, and only then activates the
installation. See [OCI image architecture](OCI_IMAGES.md).

## Visual identity

Distribution marks are packaged VectorDrawables derived from the Simple Icons
project rather than generated artwork or letter placeholders. They load
offline, keep list layout stable, and are accompanied by the trademark and
licensing notice in `THIRD_PARTY_NOTICES.md`.

References:

- [termux/proot-distro](https://github.com/termux/proot-distro)
- [PRoot-Distro releases](https://github.com/termux/proot-distro/releases)
- [XZ for Java](https://tukaani.org/xz/java.html)
- [Simple Icons](https://github.com/simple-icons/simple-icons)
