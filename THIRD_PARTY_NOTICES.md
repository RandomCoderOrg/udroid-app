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
