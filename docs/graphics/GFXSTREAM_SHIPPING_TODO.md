# gfxstream shipping TODO

Status: experimental, fail closed. The Standard graphics profile remains the
fallback until every required gate passes on the supported device matrix.

## Current checkpoint

- [x] Rootless Kumquat host reaches Android's vendor Vulkan driver.
- [x] Vulkan XCB WSI presents AHardwareBuffer-backed images through Lorie.
- [x] Zink GLX and Weston GL rendering identify the gfxstream Mali device.
- [x] Basic socket-selected GBM allocation exports authoritative linear
  DMA-BUF metadata.
- [x] Nested Plasma reaches `kwin_wayland`, Xwayland and `plasmashell`.
- [ ] Weston publishes `zwp_linux_dmabuf_v1` v4 or newer.

The current blocker is the compositor allocator boundary. Weston can render
through Zink, but it cannot allocate or import standard Wayland DMA-BUFs without
a complete GBM device.

## 1. Complete the gfxstream GBM contract

- [ ] Add a two-device probe that exports and imports one BO.
- [ ] Implement `GBM_BO_IMPORT_FD`.
- [ ] Implement `GBM_BO_IMPORT_FD_MODIFIER` with complete plane metadata.
- [ ] Verify imported-BO lifetime after the exporting BO is destroyed.
- [ ] Verify pixel content across allocation, export and import.
- [ ] Implement CPU map, unmap and write where the allocation is mappable.
- [ ] Implement GBM surfaces, front-buffer lock and release.
- [ ] Preserve acquire and release synchronization across ownership changes.
- [ ] Reject unsupported formats, modifiers, malformed descriptors and stale
  resources without falling back silently.
- [ ] Pass repeated teardown, client crash and host reconnect probes.

## 2. Expose the allocator to Weston

- [ ] Maintain the Weston change in a RandomCoderOrg fork.
- [ ] Add a generic external-GBM allocator input independent of the EGL
  rendering platform.
- [ ] Keep X11/Wayland EGL rendering separate from GBM allocation.
- [ ] Do not fabricate a DRM node or label the Kumquat socket as DRM.
- [ ] Verify Weston publishes linux-dmabuf v4 and explicit synchronization.

## 3. Qualify native Wayland clients

- [ ] Pass `weston-simple-shm`.
- [ ] Pass `weston-simple-egl` on gfxstream/Zink.
- [ ] Pass `weston-simple-dmabuf-egl`.
- [ ] Pass simultaneous SHM, EGL, DMA-BUF and Vulkan clients.
- [ ] Fix stale exposed regions during move, resize, overlap and unmap.
- [ ] Pass display detach, reattach, resize and rotation.
- [ ] Record FPS, frame time, CPU, memory, GPU-offloaded copies and latency.

## 4. Qualify Plasma Wayland unchanged

- [ ] Start KWin without renderer or capability overrides.
- [ ] Confirm KWin consumes linux-dmabuf v4 from the parent compositor.
- [ ] Confirm Xwayland initializes GLAMOR rather than software rendering.
- [ ] Verify panel, launcher, tooltips, Dolphin, window movement and fullscreen.
- [ ] Run interaction and idle stability soaks.

## 5. Choose the production presentation topology from measurements

- [ ] Measure `Lorie -> Weston -> KWin` copies and latency.
- [ ] Keep the nested route only if its performance is acceptable.
- [ ] Otherwise implement an Android-Surface Weston backend and remove the
  outer X11 presentation boundary.
- [ ] Consider a direct KWin Android backend only if measured nested-compositor
  overhead justifies the larger maintenance cost.

## 6. Integrate uDroid lifecycle and packaging

- [ ] Restore and verify the stashed process-group host-loss cleanup.
- [ ] Stop all dependent desktop processes when Kumquat exits.
- [ ] Package matching, immutable host and guest runtimes with manifests.
- [ ] Bind the runtime per launch without replacing distro Mesa packages.
- [ ] Keep Standard and gfxstream profiles selectable.
- [ ] Probe capabilities and explain every fallback in diagnostics.

## 7. Device qualification

- [ ] Tensor test device.
- [ ] Exynos test device 1.
- [ ] Exynos test device 2.
- [ ] MediaTek test device.
- [ ] Select capabilities rather than device or GPU model names.
- [ ] Require correctness, lifecycle and performance gates before enabling the
  profile by default on any device class.

## 8. Upstream reference watch

Before changing transport, allocation, synchronization or presentation, check
current AOSP TerminalApp, Google gfxstream, crosvm/Rutabaga, Mesa, Weston and
KWin. Record the compared upstream commits in the winsys contract.
