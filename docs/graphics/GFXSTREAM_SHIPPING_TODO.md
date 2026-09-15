# gfxstream shipping TODO

Status: experimental, fail closed. The Standard graphics profile remains the
fallback until every required gate passes on the supported device matrix.

Latest investigation: [September 10 release-barrier routing](2026-09-10-barrier-route.md),
following [September 6 semaphore lifecycle and frame pacing](2026-09-06-semaphore-lifecycle.md).
The temporary SYNC_FD regression probe passes, but removing queue-idle waits
has not improved total repaint time. The matched four-way one-shot probe now
isolates FOREIGN ownership release: ownership-only and ownership-plus-GENERAL
display blue; neither and GENERAL-only remain black. The pre-clock Weston
baseline remained black beneath a fully opaque desktop-shell fade curtain;
its Zink release and fence import were nevertheless traced and are present.
Matched legacy/core2/public-KHR2 ownership probes all display correctly. The
imported-writer control currently stops before rendering:
public image requirements are zero before and after bind, and the memory-plane
layout is zero. Source review traced premature native AHB requirements caching
and untranslated DRM-modifier layout queries. Full WSI-query redesign is
deferred: Weston uses a different LINEAR socket-GBM allocation path. A matched
surfaceless EGL/renderbuffer probe now visibly presents both a blue GPU clear
and a real shader-drawn spatial RGB gradient through that allocator, independent
presenter import, and native-fence sequence, with both fences signaled and exit 0.
No readback or runtime-driver changes were used. Serialized reuse of one BO
also passes 120 changing shader draws with all fences retired and clean exit.
Short SurfaceView timestats record 88 frames with a quantized 57.629 rate;
this does not qualify sustained desktop FPS. Native-fence creation/flush is
the largest measured guest stage (9.70 ms mean over non-final frames).
One-shot SHM-like BGRA upload and opaque RGBX sampling also visibly pass with
both fences retired and clean exit. Client-memory indexed geometry using
Weston's index order also visibly passes with GL_NO_ERROR and both fences
retired; its recovered log ends `[pass]`, though the terminal exit status was
not retained.

Actual Weston scene diagnostics then showed the correctly mapped SHM client,
panel, and background hidden beneath the stuck fade surface. Changing only the
headless presentation completion from a NULL timestamp to a local presentation
clock estimate, with flags 0, retires the fade and advances simple-shm frame
callbacks. The visible 90-second diagnostic run completed 2,400 presents and
releases with no free-slot starvation. Its 2,419 callback deltas average
36.945 ms, about 27 callbacks/s under verbose diagnostics; this is not measured
display FPS. Panel text is still upside-down at the bottom, so output
orientation remains incorrect. Next run the atomic 7 ms / 16 ms repaint-window
A/B to isolate scheduler delay, then re-enable the existing scoped sync timers
on the selected visible arm. The first quiet 7 ms attempt had only about 33
seconds of live overlap in a requested 60-second SurfaceFlinger window and is
excluded. The >59 visible-FPS goal is still incomplete.

The valid randomized scheduler A/B selected `repaint-window=16`: its exact
60-second SurfaceFlinger layer result was 2,305 frames / 39.078 FPS versus
1,655 / 27.784 FPS at 7 ms. Weston inter-repaint improved from 36.122 ms to
25.359 ms, but full repaint remained 17.854 ms and GL native-fence creation
remained 14.457 ms. The valid opt-in timing rerun of arm 16 then measured 2,336
layer frames / 39.664 FPS against a 2,400-frame Weston interval, with 24.867 ms
inter-repaint, 17.574 ms full repaint, and 14.226 ms native-fence creation.
Matched cumulative subtraction places 8.443 ms in submission-worker wait;
adjacent 120-sample reports place the actual DMA-BUF implicit attachment ioctl
at 0.482 ms, not the full 3.512 ms semaphore-export path. The import-oriented,
unimplemented `EGL_EXT_image_implicit_sync_control` is not an outbound fix.
Next, test opt-in queue-submit-ready native-fence export while retaining
implicit attachment and preserving the default behavior as baseline.
Historical rendering passes below are not qualification of
the current binary set.

## Current checkpoint

- [x] Rootless Kumquat host reaches Android's vendor Vulkan driver.
- [x] Vulkan XCB WSI presents AHardwareBuffer-backed images through Lorie.
- [x] Zink GLX and Weston GL rendering identify the gfxstream Mali device.
- [x] Basic socket-selected GBM allocation exports authoritative linear
  DMA-BUF metadata.
- [x] A standard GBM BO imports into the independent gfxstream EGLDevice and
  passes hardware-rendered pixel readback.
- [x] Nested Plasma reaches `kwin_wayland`, Xwayland and `plasmashell`.
- [x] Transport the public AHardwareBuffer description from Rutabaga through
  matched Kumquat host/guest builds to common Vulkan WSI.
- [x] Render and continuously recycle a native Wayland Vulkan/Zink client
  using the transported AHardwareBuffer stride.
- [x] Implement an opt-in Android `ASurfaceControl` presenter using public
  API 29+ NDK entry points and protocol-v2 resource identities.
- [ ] Weston publishes `zwp_linux_dmabuf_v1` v4 or newer.

The compositor allocator boundary is now proven. Weston can keep its X11 EGL
renderer on Zink while using the socket-selected gfxstream GBM device as a
separate allocator. The next gate is a non-DRM render-device contract: KWin
must select that same gfxstream GBM transport and the Zink EGLDevice, while
linux-dmabuf feedback advertises only resources created by this AHardwareBuffer
allocator. A fake DRM node remains explicitly out of scope.

The direct Android presentation path is implemented but not runtime-qualified.
It accepts only AHardwareBuffers carrying both `GPU_SAMPLED_IMAGE` and
`COMPOSER_OVERLAY`, and preserves the matched protocol-v2 guest/host
resource-generation contract. Acquire eventfds are consumed asynchronously;
release eventfds are signalled only after SurfaceFlinger releases the previous
buffer. Unsupported buffers use the GLES presenter, while mixed protocol or
resource generations fail the experimental profile closed. No Pixel success is
claimed for this checkpoint because no device is currently visible over ADB.

### Direct Android presentation micro-probe gates

Complete these before using the path for Weston:

- [ ] Negotiate matching protocol-v2 host and guest builds and register a
  sampled+overlay AHardwareBuffer pool.
- [ ] Verify acquire -> SurfaceControl submit -> previous-buffer release ->
  `REUSE_READY` ordering across sustained frame recycling.
- [ ] Pass first frame, replacement, final detach, Surface recreation, producer
  disconnect/reconnect and process teardown without stale frames or hangs.
- [ ] Confirm unsupported usage selects GLES, while mixed generations fail the
  experimental profile closed to the Standard profile.
- [ ] Measure BufferQueue submissions, physical presentation, frame pacing,
  CPU use and dropped-detached frames before comparing against Weston.

## 1. Complete the gfxstream GBM contract

- [x] Add a two-device probe that exports and imports one BO.
- [x] Implement `GBM_BO_IMPORT_FD`.
- [x] Implement `GBM_BO_IMPORT_FD_MODIFIER` with complete plane metadata.
- [x] Verify imported-BO lifetime after the exporting BO is destroyed.
- [x] Verify pixel content across allocation, export and import.
- [x] Pass GBM allocation -> EGLDevice/Zink render and readback without a raw
  vendor Vulkan DMA-BUF re-import.
- [x] Implement CPU map, unmap and write where the allocation is mappable.
- [ ] Implement GBM surfaces, front-buffer lock and release.
- [ ] Preserve acquire and release synchronization across ownership changes.
- [ ] Reject unsupported formats, modifiers, malformed descriptors and stale
  resources without falling back silently.
- [ ] Pass repeated teardown, client crash and host reconnect probes.

AHardwareBuffer layout checkpoint (2026-08-31): Android described each
800x500 RGBA8 display allocation with a stride of 800 pixels. Rutabaga now
retains that public descriptor, a versioned Kumquat resource-create reply
carries it to the guest, and gfxstream exposes it to common WSI through a
driver callback. WSI converts the known 32-bit format to a 3200-byte row pitch
only after validating version, format, extent, layer count, linear modifier and
the complete byte range against the exported DMA-BUF allocation. Unknown,
padded-without-metadata and malformed layouts fail closed.

The previous Vulkan memory-plane query returned an unset row pitch and a
nonsensical offset on the Pixel vendor stack. The actual DMA-BUF allocation
was 1,667,072 bytes, so inferring a tightly packed layout from allocation size
was correctly rejected. The transported descriptor produced four valid
swapchain images, a correct `weston-simple-egl` triangle and 3,969 observed
`wl_buffer.release` events without the old forced-stride probe. Host and guest
protocol files had identical SHA-256 hashes at validation; mixed generations
remain unsupported and must be rejected by packaging.

Pixel checkpoint (2026-08-30): the Pixel two-device probe passed both FD import
types, destroyed the exporting BO and GBM device, then verified the full
256x193 ABGR8888 pattern through both surviving imports. CPU access uses
`DMA_BUF_IOCTL_SYNC` around each mapping. The probe also exposed and verified a
Kumquat bug where duplicate logical attachments to one resource/context were
collapsed into a set; the host now reference-counts those attachments. Twenty
fresh host/probe teardown cycles passed consecutively. Client-crash and host
reconnect coverage remain open.

The stricter GBM-to-EGLDevice probe initially exposed two missing contracts.
Mesa returned a zero memory requirement for the imported external image, and
Kumquat collapsed duplicate logical attachments to one resource/context into
a set. Mesa checkpoint `ea05ebe7134` supplies a bounded packed size for the
supported external 32-bit color images. Rutabaga checkpoint `c1fb067365e`
reference-counts duplicate attachments. Five clean Pixel runs then selected
`Virtio-GPU GFXStream (Mali-G78)`, imported the GBM BO by its existing host
resource identity, GPU-cleared it through EGLDevice/Zink, verified every pixel,
and released both aliases without a protocol error.

## 2. Expose the allocator to Weston

- [x] Maintain the Weston change in a RandomCoderOrg fork.
- [x] Add a generic external-GBM allocator input independent of the EGL
  rendering platform.
- [x] Keep X11 EGL rendering separate from GBM allocation.
- [x] Do not fabricate a DRM node or label the Kumquat socket as DRM.
- [ ] Verify Weston publishes linux-dmabuf v4 and explicit synchronization.
- [ ] Verify the feedback `main_device` resolves to the same accelerated
  renderer in an unchanged client compositor; protocol v4 alone is not enough.
- [x] Test whether the Android app domain can use `/dev/udmabuf` as KWin's
  existing non-DRM allocation identity.
- [x] Test allocation and gfxstream import from the accessible system DMA heap.
- [ ] Define a truthful non-DRM `main_device` mapping for the external GBM
  allocation domain.
- [ ] Add a generic external-GBM `RenderDevice` path to the KWin fork.
- [ ] Expose the underlying Vulkan physical-device type through EGLDevice so
  Zink over gfxstream is classified as a GPU rather than as software.

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
identities map to hardware render devices. Its only upstream non-DRM path opens
`/dev/udmabuf` with `O_RDWR` and deliberately pairs that identity with a
software EGLDevice and `UDmabufAllocator`.

Pixel import checkpoint (2026-08-30): the uDroid app domain cannot open either
`/dev/udmabuf` or `/dev/dri`, despite permissive Unix mode bits, because Android
SELinux denies both. It can allocate `/dev/dma_heap/system`, but importing a
known linear RGBA allocation into the gfxstream EGLDevice reaches Kumquat and
fails with `MagmaGpuError(Unsupported)`. This eliminates both uDMABUF and an
arbitrary raw DMA-heap allocator. The only proven cross-client buffers are
gfxstream-created, AHardwareBuffer-backed GBM BOs whose DMA-BUF identity is
reattached by the host resource table.

Pixel GBM-to-EGLDevice checkpoint (2026-08-30): the standard GBM allocation is
now one of those proven buffers. The host observes the same DMA-BUF
`(device, inode)` on import and attaches the existing gfxstream resource; it
does not invoke the proprietary Mali driver's unsupported raw DMA-BUF import.
This clears allocator-to-renderer buffer compatibility. The remaining KWin
gate is truthful non-DRM device/feedback wiring and compositor lifecycle, not
another allocation or format workaround.

EGLDevice checkpoint (Mesa main `80f5c9174b09`, 2026-08-29):
`EGL_EXT_device_type` is implemented, but Mesa's singleton
`EGL_MESA_device_software` device reports `EGL_DEVICE_TYPE_CPU_EXT`. The current
Zink platform-device route reuses that EGLDevice even though its renderer is
`Virtio-GPU GFXStream (Mali-G78)`. KWin therefore still classifies the display
as software. The production fix must expose a distinct, truthful Vulkan-backed
EGLDevice (or equivalent generic compositor contract), not override a renderer
string.

## 3. Qualify native Wayland clients

- [ ] Pass `weston-simple-shm`.
- [x] Pass `weston-simple-egl` on gfxstream/Zink.
- [ ] Pass `weston-simple-dmabuf-egl`.
- [ ] Pass simultaneous SHM, EGL, DMA-BUF and Vulkan clients.
- [ ] Fix stale exposed regions during move, resize, overlap and unmap.
- [ ] Pass display detach, reattach, resize and rotation.
- [ ] Record FPS, frame time, CPU, memory, GPU-offloaded copies and latency.

Pixel native-Wayland checkpoint (2026-08-31): the clean nested proof stack
remained alive after protocol tracing was removed. Its animated 800x500
baseline consumed approximately 57% of one CPU in the host renderer, 24% in
KWin, 13% in outer Weston and 7% in `weston-simple-egl`. These numbers are a
measurement baseline for the deliberately nested proof topology, not a
shipping performance result.

## 4. Qualify Plasma Wayland unchanged

- [ ] Start KWin without renderer or capability overrides.
- [ ] Confirm KWin consumes linux-dmabuf v4 from the parent compositor.
- [ ] Confirm Xwayland initializes GLAMOR rather than software rendering.
- [ ] Verify panel, launcher, tooltips, Dolphin, window movement and fullscreen.
- [ ] Run interaction and idle stability soaks.

A direct `plasmashell` child starts under the validated KWin compositor but
does not produce a visible desktop. Do not classify this as another buffer or
GBM failure: the same compositor renders the native Wayland triangle, while
the Plasma log reports missing session services and session management. The
next Plasma step is a real lifecycle/bootstrap contract, not forced surface or
renderer overrides.

## 5. Choose the production presentation topology from measurements

- [ ] Qualify direct AHardwareBuffer -> SurfaceControl presentation as the
  Android-side zero-copy candidate before wiring Weston into it.
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
- wlroots master (`bd75ebfe96a4`) can select a non-DRM EGLDevice and consumes
  `EGL_EXT_device_type`, but its non-DRM allocator still falls back to uDMABUF.
- Mutter master (`e730d1e6fc00`) still constructs its GBM render device from a
  DRM device-file fd and has no comparable non-DRM EGLDevice path.
- AOSP TerminalApp avoids this identity gap by exposing a normal virtio-gpu
  DRM device inside its VM; Kumquat proves gfxstream can run Linux Vulkan/Zink
  without a VM but does not define a compositor `main_device` contract.
- Linux DRM PRIME returns an existing GEM handle for duplicate DMA-BUF imports
  and explicitly requires userspace to reference-count duplicated handles.
  Kumquat's `(device, inode)` cache and per-context attachment counts mirror
  that ownership rule without pretending its socket is a DRM node.
