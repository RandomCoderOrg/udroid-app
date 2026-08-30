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

The compositor allocator boundary is now proven. Weston can keep its X11 EGL
renderer on Zink while using the socket-selected gfxstream GBM device as a
separate allocator. The current blocker is truthful linux-dmabuf feedback:
without a DRM render-device identity Weston advertises v3, while unchanged KWin
requires v4 feedback to choose a compatible render device.

## 1. Complete the gfxstream GBM contract

- [x] Add a two-device probe that exports and imports one BO.
- [x] Implement `GBM_BO_IMPORT_FD`.
- [x] Implement `GBM_BO_IMPORT_FD_MODIFIER` with complete plane metadata.
- [x] Verify imported-BO lifetime after the exporting BO is destroyed.
- [x] Verify pixel content across allocation, export and import.
- [x] Implement CPU map, unmap and write where the allocation is mappable.
- [ ] Implement GBM surfaces, front-buffer lock and release.
- [ ] Preserve acquire and release synchronization across ownership changes.
- [ ] Reject unsupported formats, modifiers, malformed descriptors and stale
  resources without falling back silently.
- [ ] Pass repeated teardown, client crash and host reconnect probes.

Pixel checkpoint (2026-08-30): the Pixel two-device probe passed both FD import
types, destroyed the exporting BO and GBM device, then verified the full
256x193 ABGR8888 pattern through both surviving imports. CPU access uses
`DMA_BUF_IOCTL_SYNC` around each mapping. The probe also exposed and verified a
Kumquat bug where duplicate logical attachments to one resource/context were
collapsed into a set; the host now reference-counts those attachments. Twenty
fresh host/probe teardown cycles passed consecutively. Client-crash and host
reconnect coverage remain open.

## 2. Expose the allocator to Weston

- [x] Maintain the Weston change in a RandomCoderOrg fork.
- [x] Add a generic external-GBM allocator input independent of the EGL
  rendering platform.
- [x] Keep X11 EGL rendering separate from GBM allocation.
- [x] Do not fabricate a DRM node or label the Kumquat socket as DRM.
- [ ] Verify Weston publishes linux-dmabuf v4 and explicit synchronization.
- [ ] Verify the feedback `main_device` resolves to the same accelerated
  renderer in an unchanged client compositor; protocol v4 alone is not enough.

Pixel checkpoint (2026-08-30): the forked Weston 14.0.1 X11 backend rendered
with `zink Vulkan 1.4(Virtio-GPU GFXStream (Mali-G78))` and independently
reported `Using external GBM allocator backend: gfxstream`. A registry
micro-probe observed `zwp_linux_dmabuf_v1` v3 and
`zwp_linux_explicit_synchronization_v1` v2. The allocator FD, GBM backend and
explicit-sync contract pass; linux-dmabuf feedback v4 remains blocked rather
than being simulated with a fake DRM device.

KWin reference checkpoint (master `6cf2d3f890bb`, 2026-08-30): its Wayland
backend rejects linux-dmabuf older than v4, then resolves feedback
`main_device` through `GpuManager::compatibleRenderDevice()`. Real DRM node
identities map to compatible hardware render devices. `/dev/udmabuf` is also a
real, accessible identity on the Pixel, but KWin intentionally maps it to a
software EGL render device. Advertising that identity would make v4 available
while returning Plasma composition to software, so it is not an acceleration
solution. A socket or arbitrary `dev_t` has no unchanged-KWin device mapping.

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

- Weston 14.0.1 (`61f2248d`) requires a renderer DRM path before constructing
  default linux-dmabuf feedback; the external allocator alone correctly stays
  at protocol v3.
- KWin master (`6cf2d3f890bb`) requires feedback v4 and a `main_device` that its
  GPU manager can resolve. Its explicit `/dev/udmabuf` fallback is software.
