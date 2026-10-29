# PR #30 stability handoff — 2026-10-26

Resume target: [RandomCoderOrg/udroid-app#30](https://github.com/RandomCoderOrg/udroid-app/pull/30), branch `improvements/zink-requirements-check`. This checkpoint began at `b1150c3`; later local commits added desktop input controls. It is not a release qualification. Do not merge or publish merely because the APK builds.

## Current scope

- Venus/Zink: device-readable Zink requirements, updated Mesa/Virgl assets, native Venus WSI by default, and Perfetto profiling notes.
- Embedded Termux:X11: DRI3 DMA-BUF/AHardwareBuffer export, Present damage ordering, CPU/GPU ownership transfer, guarded GPU-copy routing, and input/debug probes. The authoritative patch order and production/diagnostic split are in [`patches/termux-x11/README.md`](../../patches/termux-x11/README.md) and [`tools/prepare-termux-x11-source.sh`](../../tools/prepare-termux-x11-source.sh); source is pinned at `0cb0203`.
- The experimental SPHAL/Zink app profile was moved off this PR to `feat/sphal-zink-profile`. Shared AHardwareBuffer and Present patches remain in PR #30 for the other graphics paths.
- Dev-only supervised ADB controls and X11 probes are included. Configurable desktop keyboard and pointer controls were added later. Do not expose arbitrary guest-command execution in release builds.

The PR check, `Test and build APK`, passed at `5a73892` on 2026-10-25. The later desktop-controls build, unit tests, and dev lint passed locally; recheck hosted CI before merge. Neither result proves compositor stability or performance. The PR description may understate the accumulated scope.

## Resume gates

1. Start from a clean checkout at the PR head. Replay standard and diagnostic Termux:X11 source preparation twice each; build the dev APK without bypassing source preparation. Keep local uncommitted work in this worktree intact.
2. Inventory each bundled graphics binary's source commit, build command, ABI/glibc floor, checksum, and license. Rebuild from pinned sources; a locally working binary is not sufficient for redistribution.
3. On the Pixel, test software, any separately installed Panfrost control, and Venus/Zink separately. Record selected renderer, transport route, Present GPU-copy counts/fallback reason, faults, and frame-production timing. Do not infer acceleration or correctness from one visible frame. Qualify SPHAL/Zink on its own branch.
4. Run repeated window move/resize, typing, menu/tooltip, suspend/resume, and app launch/close on XFCE, Plasma X11, and GNOME X11. Treat GNOME as an explicit unresolved stress gate, not proven support. Capture an A/B with upstream Termux:X11 and with GPU Present disabled when a regression appears.
5. Check the diagnostics security boundary, release-default feature flags, upgrade/install behavior, and source-profile cleanup. Run unit tests and PR CI again after any changes.

## Forks and build documentation to complete

- Maintain the app patch stack against the pinned Termux:X11 base and track which patches are already upstream. `RandomCoderOrg/termux-x11` exists; decide which remaining patches belong in a maintained fork/PR and keep uDroid-specific JNI bridge work clearly separate. Do not move patches just to make the list shorter.
- Keep Mesa/Virgl source patches and binary provenance in reproducible, pinned forks or build repositories as applicable; document exact revision and artifact-to-source mapping before shipping them. Track SPHAL's separate source and packaging work on `feat/sphal-zink-profile`. Do not assume the app repository's packaged binaries are a complete source recipe.
- Add a user-facing build/install guide for each *qualified* X11 desktop environment: distro/package commands, session selection, recommended graphics profile and compositor setting, known failure modes, and how to collect logs. Mark experimental paths honestly; do not promise Wayland or universal GPU support.
- Add a contributor guide for reproducing the APK and embedded X11/graphics assets from clean sources, including toolchain versions, patch order, test commands, and expected outputs. Keep it separate from the desktop user's guide.

Local state at checkpoint: Docker Desktop containers were removed on 2026-10-26, including the PostgreSQL container; images, build cache, and volumes were preserved. Recreate build containers as needed. This worktree already contains unrelated modified/untracked graphics docs; preserve and review them before staging anything.
