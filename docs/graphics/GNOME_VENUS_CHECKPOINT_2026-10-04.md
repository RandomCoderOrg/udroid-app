# GNOME / Venus checkpoint — 2026-10-04

Originally paused at the user's request. The source patches were subsequently pushed. This is a historical investigation record, not a current device-state report or GNOME qualification. A controlled EGL/Gallium A/B was run on 2026-10-09; the GNOME issue was not resolved.

## Locations

- App: `/Users/saicharankandukuri/anyc/udroid-android-ci`, branch `improvements/zink-requirements-check`.
- Mesa: `/Users/saicharankandukuri/anyc/worktrees/mesa-venus-vtest-guard`, base `6a02618ccf6c5651ecb9cccbde571eb61fd73592` (26.1.5).
- Pixel 6a: ADB serial `36171JEGR11359`; app `org.randomcoder.udroid.dev`; rootfs `noble-raw`.
- Session: `GNOME on uDroid (Venus WSI)`.

## Patch inventory

Mesa has four source changes, pushed as `7c8f8371df1` on `fix/venus-zink-gnome-compat`:

- `src/virtio/vulkan/vn_renderer.h`: guard an unavailable `create_from_dma_buf` callback.
- `src/gallium/drivers/zink/zink_screen.c`: derive DMA-BUF capabilities from Vulkan external-buffer properties.
- `src/gallium/frontends/dri/kopper.c`: use imported pixmap buffers only when DMA-BUF import is supported.
- `src/gallium/auxiliary/pipe-loader/pipe_loader_sw.c`: expose Zink's DRI configuration table when the swrast loader selects `GALLIUM_DRIVER=zink`. This fixes the missing `dual_color_blend_by_location` option assertion in `mutter-x11-frames`.

App changes were pushed as `3774493` on `improvements/zink-requirements-check`. They include the Mesa assets/manifest, X11 service diagnostics, performance/roadmap docs, profiling tools, and Termux:X11 patches:

- `0008`: buffer transport protocol additions.
- `0011`: Perfetto slices.
- `0012`: raw FD import diagnostics.
- `0013`: Present flip height comparison fix; inspect applicability before using direct flip.
- `0014`: Present lifecycle diagnostics.
- `0015`: DRI3 pixmap export. The patch's hunk counts were corrected and it parses, but normal source preparation still needs a separate build validation; the A/B APKs used `-x prepareTermuxX11Source`.
- `0016`: accept existing FD and AHardwareBuffer pixmaps as GPU-copy sources. Wired into experimental profile application/removal in `tools/prepare-termux-x11-source.sh`.

The active native source also contains the `0016` change in `third_party/termux-x11/lorie/src/main/cpp/lorie/InitOutput.c`. Preserve unrelated app changes.

## Evidence and remaining failure

- Patched Mesa builds and the dev APK build/install succeeded. The committed Jammy assets require at most GLIBC 2.34. The earlier Debian/GCC14 proof Gallium needs GLIBC 2.38 (`__isoc23_*` symbols) and **must not be shipped** as the Jammy-compatible asset.
- The missing DRI option crash is fixed, but GNOME interaction remains unqualified: user observed sudden corruption and a crash.
- One captured failure showed GNOME and `mutter-x11-frames` still alive, followed by an Android back gesture detaching the display. Do not generalize that observation to every reported crash.
- Before `0016`, Lorie rejected imported FD sources (`type=2`) and reported zero GPU-offloaded Present copies.
- After `0016`, a clean desktop rendered and logs reported `10/10 present copies offloaded to GPU`. This proves the transport path was used, not sustained GNOME correctness.
- Subsequent UXTerm and Thunar launches reached X11 but remained `IsUnMapped`; Mutter's `_NET_CLIENT_LIST` was empty while GNOME and its frame process remained alive in poll.
- SurfaceFlinger emitted `writeReleaseFence failed ... Broken pipe` around surface-generation changes and app launches. Causality is unresolved.
- Forced direct flip was rejected as an experiment: root CPU-lock/unflip paths are unsafe for GPU-only buffers and could reproduce the pinned-pixmap failure.
- Left-edge ADB taps triggered predictive back. Use a central input target or X11 input tools for future GNOME interaction tests.
- Both repository diffs passed `git diff --check` at checkpoint.

## 2026-10-09 controlled A/B on Pixel 6a

All runs used the same Noble rootfs, Virtio Vulkan asset (`9a21b21f90484960b3a3383144e7bd65bb725ede546ae4ce205f978b894f54a1`), app runtime, and Lorie `0016`. The EGL/Gallium pair was the only APK asset variable. Each APK was installed and GNOME restarted. The original Jammy assets were backed up under `/tmp/udroid-gnome-ab-current-20261009`.

| EGL | Gallium | Result |
| --- | --- | --- |
| Jammy `01360784` | Jammy `00de405a` | Corrupt/duplicated output; UXTerm `IsUnMapped`, `_NET_CLIENT_LIST` empty. |
| Debian proof `5e1fc256` | Debian proof `fab409d3` | Clean desktop; UXTerm and Thunar both `IsViewable` and listed by Mutter. |
| Debian proof `5e1fc256` | Jammy `00de405a` | Corrupt output; UXTerm `IsUnMapped`. |
| Jammy `01360784` | Debian proof `fab409d3` | UXTerm `IsViewable` and visibly rendered, but a later Thunar launch stayed `IsUnMapped`. This is only a partial improvement. |

The Jammy Gallium binary/build is implicated by both UXTerm splits, but the mixed pair does not qualify GNOME. The proven EGL/Gallium pair's glibc floor also prevents shipping it across supported Ubuntu versions. Do not assume a single driver-source regression: the binaries were compiled with different toolchains and potentially different Meson settings. The proof build used Debian GCC 14.2, `-Dbuildtype=debugoptimized`, and `-Dgbm=enabled`; the Jammy binary reports Ubuntu GCC 11.4, but its original build directory/configuration still needs inspection. Docker Desktop failed to start on 2026-10-09, so this comparison could not be completed.

Evidence: `/tmp/udroid-ab-uxterm.png`, `/tmp/udroid-ab-thunar.png`, `/tmp/udroid-mixed2-uxterm.png`, `/tmp/udroid-gnome-current-20261009.png`, and `/tmp/udroid-gnome-mixed-thunar-20261009.png`. These screenshots are temporary and should not be treated as durable artifacts. A visible UXTerm alone is not a GNOME stability result.

The user then dragged UXTerm on the mixed Jammy-EGL/proof-Gallium build and reported the display dead. The Android screenshot stayed on the prior frame. At the same time, `gnome-shell`, `mutter-x11-frames`, Xterm, Thunar, and both app processes remained alive; X11 reported UXTerm `IsViewable`, Thunar `IsUnMapped`, and `_NET_CLIENT_LIST` contained UXTerm only. Injecting `FRAME_PROBE_20261009` into Xterm's PTY changed an `xwd` capture of the UXTerm window (`07e51112…` to `411821f4…`), but neither `xwd -root` nor the Android screenshot showed the new text. The immediate stall is therefore after the client window's pixel update and before the composed X11 root is refreshed; the Android surface alone cannot explain it. SurfaceFlinger emitted repeated `writeReleaseFence failed. error 32 (Broken pipe)` around 15:28:30 local time, but causality is not established. The main GNOME thread was in `do_sys_poll`, not an observed synchronous GPU wait. Evidence: `/tmp/udroid-freeze-window-before.png`, `/tmp/udroid-freeze-window-after.png`, `/tmp/udroid-freeze-root-after.png`, and `/tmp/udroid-freeze-screen-after-probe.png`.

At that checkpoint, the local app repository's Gallium asset and manifest were restored to their committed Jammy hashes. **The installed dev APK on the Pixel still contained the mixed diagnostic pair at that time**; its current installed state must be checked afresh. Do not mistake that diagnostic pair for a compatible release build.

The first software-rendering A/B was **inconclusive**: the generic `GNOME on Xorg` session did not remain running and logged service/D-Bus failures, while `/usr/local/bin/udroid-gnome-venus-wsi` explicitly sets `GALLIUM_DRIVER=zink` and Venus library paths regardless of the app's graphics selector. These were not equivalent sessions.

A fairer control then reused the uDroid GNOME compatibility launcher, intercepting only its final `gnome-shell` invocation with a temporary executable on `PATH`. That executable removed the Venus/Zink library overrides and set `LIBGL_ALWAYS_SOFTWARE=1`. `/proc/3868/environ` confirmed the software flag and no Venus/Zink overrides; `/proc/3868/maps` showed Noble's own `libEGL_mesa.so`, `libGLX_mesa.so`, and `libgallium-25.2.8-0ubuntu0.24.04.4.so`. The app displayed GNOME and a persistent UXTerm, including `FRAME_PROBE_20261009` text that was absent from the frozen accelerated root. The user moved UXTerm and reported no issue with software rendering. This is one bounded device run, not a sustained stability qualification, but it narrows the failure toward the accelerated compositor path rather than the generic GNOME compatibility startup or Android display surface alone.

The software control desktop was stopped. Its temporary guest executable and `.desktop` entry were removed and verified absent; the app's original Venus graphics selection and `GNOME on uDroid (Venus WSI)` session selection were restored. The desktop was left stopped; the Linux rootfs/terminal remained installed and running. The UI may briefly cache the removed session entry until its next scan.

## Matched Jammy rebuild awaiting device A/B

Docker Desktop was recovered. The preserved `udroid-mesa232-build` Ubuntu 22.04 container uses GCC 11.4 and Meson 1.7.2, but its bind mount points to a partial `/tmp/mesa-23.2.1` tree, **not** the original Mesa 26.1.5 asset build directory. It cannot establish the committed Jammy binary's original Meson settings. A clean archive of Mesa revision `7c8f8371df1` was copied into the container at `/work/mesa261` and built with the proof build's explicit options: `platforms=x11`, `gallium-drivers=zink`, no Vulkan drivers, `glx=dri`, EGL and GBM enabled, LLVM and video codecs disabled, and `buildtype=debugoptimized`. Both target libraries built successfully. After `strip --strip-unneeded`, their hashes are:

| Diagnostic library | SHA-256 | Maximum required glibc |
| --- | --- | --- |
| `libEGL_mesa.so.0` | `5d09a3e23759234728f89662758ec006f39fd93ce091f2d2affa7e80d5dc1235` | 2.34 |
| `libgallium-26.1.5.so` | `56f95695a36df5f9cecdddb9481adf7697aedad1fff800775bd7bb3b72d1a025` | 2.34 |

The binaries are in `/tmp/udroid-gnome-jammy-matched-20261009` on the Mac and remain in the preserved container. `:app:assembleDev -x prepareTermuxX11Source --offline` succeeded, including `verifyGraphicsRuntimeAssets`. The resulting `app/build/outputs/apk/dev/app-dev.apk` contains the diagnostic hashes, verified from the packaged binary bytes; a copy is at `/tmp/udroid-gnome-jammy-matched-20261009/app-dev.apk`. The Pixel disconnected immediately before `adb install`, so **this APK has not been installed or runtime-tested**. The app source assets and manifest were restored to their committed Jammy hashes and `git status` shows only this untracked checkpoint; a later Gradle build will replace the original APK output, and `/tmp` copies are not durable. Both temporarily started Docker containers were stopped without deleting them.

## Binary identity

Committed Jammy app assets:

| Library | SHA-256 |
| --- | --- |
| EGL | `013607840e63dc6cbca87e3ca3126f46734ec102b2072b5757d509615a3a1a46` |
| Gallium | `00de405a69fab61c49f0dbbe6a13c0b8332ffb664f20ed021baba0b713d2f06c` |
| Virtio Vulkan | `9a21b21f90484960b3a3383144e7bd65bb725ede546ae4ce205f978b894f54a1` |

Previously proven Debian EGL/Gallium pair located and hash-verified (diagnostic only, not release-compatible with GLIBC 2.34):

- `build-zink-vtest-fallback/src/egl/libEGL_mesa.so.0.0.0`: `5e1fc256dadcf31595a3bcbdbc216e154071054e82a3d933c0daa9f1b24ad041`.
- `build-zink-vtest-fallback/src/gallium/targets/dri/libgallium-26.1.5.so`: `fab409d33b287ca84ac39a1e0109932ba3851923797c2d297de29f1267dab887`.

The current `build-wsi-virtio` binary hashes to `b49048234bb4717a34f4e8eaebb46221407aaf54c8ab875f3e8fff017a0efc59`; it does not match the recorded prior proof Virtio hash `6d1ead4806ad513e4c3aaee2f0e3419e577e454c0356a1beb25003eeaf2c7b6d`.

`/tmp/udroid-mesa-original-assets-20261004` is an older backup, not the exact proof pair. Do not treat it as proven. Temporary files may disappear; the Mesa build tree holds the located proof pair.

## Resume here

1. Reconnect the Pixel and install the existing diagnostic `app-dev.apk`; confirm the extracted EGL/Gallium hashes before launching GNOME. Do not mistake it for a release build.
2. A/B this matched pair with the same Virtio and Lorie runtime. Launch persistent UXTerm and Thunar clients, inspect `IsViewable` plus `_NET_CLIENT_LIST`, and compare client-window `xwd`, root `xwd`, and Android screenshots during several drags. If the client changes but root stalls, trace Mutter's damage/event dispatch before changing Lorie's Android presentation path.
3. Compare the accelerated drag test with the observed software control. One clean accelerated frame or one software run is insufficient to claim stability; continue with overview, repeated launches, and GPU-copy counters.
4. Find or reconstruct the committed Jammy asset's build settings before deciding whether the new pair replaces it. Validate normal source preparation (`0015`) separately before any release.

Existing build container `udroid-mesa232-build` must be preserved. Check container ownership before cleanup. Screenshots previously saved under `/tmp/udroid-gpucopy-fd-*.png` are supporting evidence only and may be temporary.

## 2026-10-18 GNOME Venus retest

The generic `gnome` X session is still not a valid compatibility test. It reached
GNOME's recovery screen and logged missing runtime/system-bus setup together with
Venus/Zink swapchain initialization failures. The dedicated
`udroid-gnome-venus-wsi` launcher did render the overview, but opening Show Apps
left a black/frozen display while `gnome-shell` and `mutter-x11-frames` remained
alive. Lorie reported about `0.8 FPS`, acknowledged all four observed GPU-copy
presents, and SurfaceFlinger repeatedly logged release-fence `Broken pipe` errors.

Temporary evidence: `/tmp/udroid-gnome-start.png`,
`/tmp/udroid-gnome-compat-appgrid.png`,
`/tmp/udroid-gnome-compat-supervisor.jsonl`, and
`/tmp/udroid-gnome-compat-logcat.txt`.
