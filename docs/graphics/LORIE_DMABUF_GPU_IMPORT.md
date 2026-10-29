# Lorie DMA-BUF GPU import dossier

Status: implementation gate, 2026-10-20

## Goal

Replace Lorie's raw-FD presentation hot path:

```text
Panfrost DMA-BUF -> mmap -> DMA_BUF_IOCTL_SYNC -> glTexSubImage2D -> GLES texture
```

with a GPU-native path:

```text
Panfrost DMA-BUF -> EGLImage or Android-native buffer -> GLES texture -> Android Surface
```

The result is only correct when buffer metadata, producer completion, consumer
completion, and reuse are all handled. Importing the FD without that complete
contract can produce the stale, partially repainted, or reordered frames already
seen during window movement.

This document is the implementation checklist. It does not make the raw path a
default or remove the synchronized CPU fallback.

The evidence vocabulary, normative source conclusions, local code map, and the
allocation/lifecycle decision are centralized in
[`PRESENTATION_CONTRACT_REFERENCE.md`](PRESENTATION_CONTRACT_REFERENCE.md).

## Current path and evidence

G0 is implemented as a non-invasive startup probe in patch `0020`. Renderer
startup logs the active EGL vendor/version, DMA-BUF and AHardwareBuffer import
capabilities, synchronization extensions, eligible route, and fallback reason.
The probe does not change allocation, import, presentation, or fallback.

The Pixel 6a gate reports `eligible_route=ahardwarebuffer`: direct app-facing
DMA-BUF import is unavailable, while the native-buffer, EGLImage, external
texture, native-fence, and wait-sync capabilities are exposed.

### Producer

The Tensor G1 Panfrost fork allocates shareable color buffers from an Android
DMA heap, imports them into kbase, and exports a duplicated DMA-BUF FD:

The following paths are in the patched Mesa source tree used to build the guest
driver; that source tree is not vendored in this repository.

- `src/panfrost/lib/kmod/kbase_kmod.c`: `DMA_HEAP_IOCTL_ALLOC`, kbase import,
  and `kbase_kmod_bo_export_fd()`.
- `src/gallium/drivers/panfrost/pan_resource.c`:
  `panfrost_resource_get_handle()` returns the FD, stride, offset, and modifier.
- `src/gallium/frontends/dri/loader_dri3_helper.c`: the Termux:X11 transport
  sends one FD with private marker `1274` through DRI3 `PixmapFromBuffers`.

`1274` is a transport discriminator, not a DRM format modifier. It must never
be passed to EGL as `EGL_DMA_BUF_PLANE0_MODIFIER_*_EXT`.

### Consumer

The embedded Lorie server receives the FD in
`third_party/termux-x11/lorie/src/main/cpp/lorie/InitOutput.c` and wraps it as
`LORIEBUFFER_FD`. Its current renderer does not import that DMA-BUF into the
Android GPU:

1. `LorieBuffer_wrapFileDescriptor()` mmaps the FD.
2. `LorieBuffer_attachToGL()` allocates an ordinary `GL_TEXTURE_2D`.
3. Every `LorieBuffer_bindTexture()` brackets a full-frame
   `glTexSubImage2D()` upload with `DMA_BUF_IOCTL_SYNC`.

By contrast, the existing `LORIEBUFFER_AHARDWAREBUFFER` path creates an
`EGLImage`, binds it to a texture, and can sample it as
`GL_TEXTURE_EXTERNAL_OES`.

### Pixel 6a capability snapshot

Observed on the connected Pixel 6a on 2026-10-20:

- Android META-EGL: EGL 1.5.
- App-facing EGL advertises `EGL_ANDROID_image_native_buffer`,
  `EGL_ANDROID_native_fence_sync`, `EGL_KHR_wait_sync`, and
  `EGL_KHR_image_base`.
- App-facing EGL does **not** advertise `EGL_EXT_image_dma_buf_import` or
  `EGL_EXT_image_dma_buf_import_modifiers`.
- The vendor `/vendor/lib64/egl/libGLES_mali.so` contains the DMA-BUF import
  extension names and `eglQueryDmaBufFormatsEXT` / `eglQueryDmaBufModifiersEXT`
  symbols.

This distinction is intentional Android architecture: META-EGL exposes an
allow-listed set of vendor extensions to applications. Header declarations and
symbols in the vendor library do not make an extension valid on the
app-facing `EGLDisplay`.

Consequently, direct `EGL_LINUX_DMA_BUF_EXT` import is not a portable Android
app implementation and is not currently available through Lorie's existing
EGL display on this device.

## Required buffer contract

Every imported image needs all of the following metadata. An FD alone is not
an image description.

| Field | Initial supported value | Rule |
| --- | --- | --- |
| Plane count | 1 | Reject or fall back for any other value. |
| Width, height | X pixmap dimensions | Both non-zero and bounded. |
| FourCC | Probe `DRM_FORMAT_XRGB8888` and `DRM_FORMAT_ARGB8888` | AHardwareBuffer format enums are not DRM FourCC values. |
| FD | duplicated DMA-BUF FD | Set `CLOEXEC`; define one owner and close point. |
| Offset | producer-provided byte offset | Validate against allocation size. |
| Pitch | producer-provided byte stride | Must be at least `width * 4`; do not substitute width. |
| Modifier | initially `DRM_FORMAT_MOD_LINEAR` only | `1274` is translated only after the producer proves a linear allocation. |
| Color/alpha semantics | XRGB or ARGB, established by probe | Never infer channel order from the numeric AHardwareBuffer enum. |

The first implementation must stay single-plane and linear. Tiled, compressed,
AFBC, multi-plane, and YUV imports need real modifier negotiation and are out of
scope for the first gate.

Validate `offset + pitch * (height - 1) + width * 4` with overflow-safe
arithmetic against the DMA-BUF size when the exporter exposes a meaningful
size. Reject malformed metadata before EGL, mmap, or GL sees it.

## Import routes

### Route A: app-facing EGL DMA-BUF import

Use only when the active `EGLDisplay` advertises both:

- `EGL_EXT_image_dma_buf_import`
- `EGL_KHR_image_base`

Use `eglCreateImageKHR` with:

```text
display = renderer's active EGLDisplay
context = EGL_NO_CONTEXT
target = EGL_LINUX_DMA_BUF_EXT
buffer = NULL
attributes = width, height, DRM FourCC, plane-0 FD, offset, pitch
```

If the modifier extension is advertised, query the FourCC/modifier pair first
with `eglQueryDmaBufFormatsEXT` and `eglQueryDmaBufModifiersEXT`. Supply both
the high and low modifier attributes or neither. Never treat an omitted or
`DRM_FORMAT_MOD_INVALID` modifier as equivalent to `DRM_FORMAT_MOD_LINEAR`;
the former is implementation-defined while the latter is an explicit linear
layout guarantee.

On successful image creation EGL retains a reference to the DMA-BUF, but the
application still owns and must close the FD. Lorie should keep its owned FD
for the `LorieBuffer` lifetime for diagnostics and fallback, then close it once
during destruction.

This route is unavailable on the current Pixel app-facing display and must
remain capability-gated.

### Route B: Android-native AHardwareBuffer transport

This is the portable Android import route. BufferQueue normally makes the
consumer own the queue and its slots, but that does not require Lorie to
allocate every buffer in a custom transport. The existing Panfrost route in
`SaicharanKandukuri/tensor-g1-proot-gpu` uses a Bionic broker to own the AHB,
gives its data DMA-BUF to glibc Panfrost, and gives its complete native handle
to Lorie through private DRI3 marker `1255`.

That route already has a three-buffer ring. Mesa blocks for producer completion
before Present, retains each `pipe_resource`, and releases it only after X
Present `IdleNotify`. The next work is correlated tracing and abnormal teardown
validation, not another one-buffer transport.

uDroid already has Lorie handle transport markers `1255`, `1256`, and `1257`
and the receiving/import code. The Tensor route does not yet carry the
gfxstream presenter's explicit cross-process fences and generation retirement,
so keep those protocols separate and add only behavior proven necessary by a
failing lifecycle control.

Do not fabricate an AHardwareBuffer by placing an arbitrary DMA-heap FD in a
`native_handle_t`. `AHardwareBuffer_createFromHandle()` is a VNDK API that
registers a gralloc handle through `GraphicBuffer`; a plain DMA-BUF lacks the
vendor gralloc handle metadata that import may require.

Android-native AHB import is the portable principle, but the current broker and
producer are Tensor/Panfrost-specific and not broad-device-qualified.
Allocation inversion is an A/B experiment, not a prerequisite.

### Route C: direct vendor/SPHAL EGL

The Pixel Mali blob appears to implement raw DMA-BUF EGL import even though
META-EGL does not expose it. A device-specific experiment may load the vendor
EGL implementation through the SPHAL namespace and create a fully vendor-owned
display, context, and import path.

This route must not mix a META-EGL display/context handle with direct vendor
entry points. It also must not assume that vendor EGL ABI, linker namespace,
SELinux access, or extension exposure is consistent across devices.

Minimum probe before any integration:

1. Load the vendor implementation without replacing Lorie's active renderer.
2. Create and initialize an independent vendor EGL display and GLES context.
3. Confirm the extension strings on that exact display/context.
4. Query and import one known linear XRGB/ARGB DMA-BUF.
5. Render a checksum-known pattern to an independent target.
6. Destroy all objects repeatedly with no TLS corruption, FD leaks, or crash.

Only after this isolated probe passes may the route be considered for an
experimental per-device backend. It is not the default implementation plan.

### Route D: Vulkan consumer

A Vulkan Lorie renderer could import external memory and composite to the
Android surface, but only if the active vendor Vulkan implementation exposes a
compatible external-memory FD, DRM modifier, and synchronization contract.
This is a renderer rewrite and is larger than either Route A or Route B. Keep
it as a fallback research route, not the first patch.

## Texture target selection

For Route A, call `eglQueryDmaBufModifiersEXT` when available:

- `external_only == EGL_FALSE`: bind the EGLImage to `GL_TEXTURE_2D` and use
  `sampler2D`.
- `external_only == EGL_TRUE`: require `GL_OES_EGL_image_external`, bind to
  `GL_TEXTURE_EXTERNAL_OES`, and use `samplerExternalOES`.

Lorie already has external-texture shaders for its AHardwareBuffer path; reuse
them. Cache one `EGLImageKHR` and texture view per `LorieBuffer`. Never create
or upload a texture per frame.

Use clamp-to-edge and explicit min/mag filters. Preserve the existing BGRA/RGBA
swizzle decision, but base it on the negotiated FourCC and the colored-pattern
probe rather than the private transport marker.

## Synchronization contract

Correct synchronization has two independent directions.

### Acquire: producer finished writing

Lorie must not sample a buffer until Panfrost has completed all writes.

Preferred order:

1. An explicit producer `sync_file` FD transported with the frame.
2. Import it as `EGL_SYNC_NATIVE_FENCE_ANDROID` and issue
   `eglWaitSyncKHR()` before the sampling draw.
3. Transfer FD ownership exactly as specified: successful
   `eglCreateSyncKHR()` owns the supplied FD.

An implicit-sync path is valid only after a probe proves that kbase/Panfrost
attaches the producer fence to the DMA-BUF reservation object and that the EGL
importer waits on it. The current custom kbase path has no demonstrated
`dma_resv` producer-fence attachment, so implicit synchronization must not be
assumed.

For the first correctness proof, a bounded producer-side GPU completion wait
before Present is acceptable. It reduces parallelism but establishes that
format and lifetime are correct before adding an explicit fence transport.

### Release: Lorie finished reading

The producer must not render into or free a buffer while Lorie's GLES draw is
still sampling it.

After queuing the last read of a source buffer:

1. Create `EGL_SYNC_NATIVE_FENCE_ANDROID` with no imported FD.
2. Flush the GLES command stream.
3. Duplicate the native fence FD with `eglDupNativeFenceFDANDROID()`.
4. Send or attach that release fence to the producer-side reuse state.
5. Reuse the image only after that fence signals.

Until release-fence transport exists, Lorie may block on its EGL fence before
signalling Present idle. That is a safe control, not the final performance
path.

### What does not provide this contract

- `DMA_BUF_IOCTL_SYNC` brackets CPU cache access. It does not exclude another
  CPU or device user and does not by itself order two GPUs.
- `present_event_notify()` reports the Present flip event. Calling it
  immediately after scheduling a redraw does not prove Lorie's GPU finished
  reading the old pixmap.
- Present 1.2 `IdleNotify` and X Sync fences do not provide the same GPU-idle
  guarantee as a native `sync_file`.

The embedded X server currently advertises DRI3 1.2 and Present 1.2. Its bundled
protocol descriptions contain newer DRI3 syncobj and `PresentPixmapSynced`
requests, but those are not active server capabilities. DRM timeline syncobjs
also do not directly solve synchronization for a non-DRM kbase producer.

## Lifetime and threading

Per imported buffer, own:

- one duplicated DMA-BUF FD or one acquired AHardwareBuffer reference;
- one `EGLImageKHR`;
- one `GL_TEXTURE_2D` or `GL_TEXTURE_EXTERNAL_OES` texture;
- pending acquire/release fence state;
- immutable dimensions, FourCC, pitch, offset, and modifier;
- a generation/serial so stale renderer messages cannot bind a recycled ID.

Create and destroy GL textures on a thread with the owning context current.
Destroy the texture before the EGLImage, then release the native buffer/FD.
Surface detachment, Activity recreation, X server disconnect, pixmap
destruction, and import failure must converge on the same one-time teardown.

Never release a `LorieBuffer` merely because Present completion was emitted.
Release it only after the renderer has finished sampling or has discarded the
frame without accessing it.

## Runtime selection and fallback

The fast path is selected per buffer, not globally:

```text
valid metadata
  AND supported EGL/Android import route
  AND supported FourCC/modifier/texture target
  AND working acquire/release synchronization
    -> native GPU sample
otherwise
    -> current DMA_BUF_IOCTL_SYNC + CPU upload fallback
```

Do not use `TERMUX_X11_FORCE_FLIP=1` as product behavior. It bypasses a known
guard without establishing the missing synchronization and import contracts.

Log one structured reason when falling back: missing extension, unsupported
format, unsupported modifier, image import error, fence import error, invalid
metadata, or resource exhaustion. Avoid per-frame log spam.

## Mandatory observability

The implementation is not testable if it only emits an FPS number. Add bounded
counters and trace slices at the actual ownership boundaries:

| Signal | Required fields |
| --- | --- |
| Buffer import | generation, buffer ID, frame ID, dimensions, FourCC, modifier, pitch, offset, import route, texture target |
| Import result | success/fallback reason, EGL error, one-time import duration |
| Acquire | fence present, fence FD ownership transfer, wait start/end, timeout/error |
| Draw | frame ID, source buffer ID, damage extent, draw start/end |
| Release | release fence creation, duplicated FD owner, delivery, signal/retirement |
| Reuse | previous release complete, next producer-write start, violation count |
| Presentation | Present serial, completion, idle, Android buffer submission, SurfaceFlinger presentation |
| CPU fallback | invocation count and uploaded bytes |

Emit Android ATrace slices for import, acquire wait, Lorie draw,
`eglSwapBuffers`, release-fence creation, and buffer retirement. Counters must be
read/reset atomically for one bounded test interval. Never enable per-frame
logcat output in normal builds; it changes scheduling and can manufacture the
stutter being measured.

Every captured frame must be identified by the tuple:

```text
connection generation + resource/buffer ID + buffer generation + frame ID
```

An FPS counter that cannot distinguish a newly displayed frame from a repeated
or prematurely reused buffer is diagnostic noise.

## Reproducible test and performance system

### Evidence manifest

Every result directory must record:

- uDroid Git SHA and APK version/signing variant;
- Termux:X11 source SHA plus applied patch list;
- Mesa/Panfrost SHA and build options;
- device model, build fingerprint, Android release, kernel, and GPU driver;
- selected graphics profile and every relevant environment variable;
- resolution, Android refresh mode, X compositor state, and workload command;
- battery state, thermal status before/after, and whether the device was
  charging;
- test start/end timestamps and warm-up duration.

Do not compare runs with different resolution, refresh rate, compositor,
thermal state, or artifact SHA.

### Controls

Every qualification includes these paths from the same APK and workload:

| Control | Purpose |
| --- | --- |
| Synchronized CPU upload | Known-correct visual baseline and current performance baseline. |
| Native import with blocking acquire/release | Isolate format/import correctness from asynchronous synchronization. |
| Native import with explicit fences | Candidate production path. |
| Software rendering | Separate Lorie/Present faults from Panfrost faults. |
| Delayed producer fence | Prove Lorie never samples before producer completion. |
| Delayed consumer fence | Prove Panfrost never reuses before Lorie completion. |

The two delayed-fence cases are required negative controls. The probe must
detect a deliberately early wait/reuse implementation; otherwise it is not
capable of validating the real implementation.

### Microprobes

Run these before a desktop environment:

1. **Format:** four colored quadrants, alpha strip, single-pixel border, and
   padded stride. Compare captured pixels, not human color judgment.
2. **Ordering:** encode frame ID in pixels and telemetry; detect duplicate,
   missing, decreasing, or overwritten IDs.
3. **Acquire delay:** submit a buffer with a fence delayed by known intervals;
   its pixels must not be sampled early.
4. **Release delay:** hold Lorie's consumer fence; producer reuse must remain
   blocked.
5. **Ring pressure:** run two, three, and four buffers at a producer rate above
   display refresh; memory must remain bounded and only a documented queueing
   policy may drop frames.
6. **Damage:** animate one small rectangle over an otherwise static image and
   verify unchanged pixels remain unchanged.
7. **Resize:** alternate two sizes and strides while old frames are in flight.
8. **Lifetime:** destroy/recreate pixmaps, surface generations, and renderer
   connections while fences are pending.
9. **Rejection:** exercise invalid size, pitch, offset, plane count, FourCC,
   modifier, and closed/non-DMA-BUF FD without crashing.
10. **Soak:** at least 30 minutes with periodic resize and surface detach;
    require bounded RSS, FD count, and buffer count.

The test pattern must be deterministic and machine-checked. Screenshots remain
useful evidence but are not the oracle for channel order, frame order, or
single-frame corruption.

### Existing repository runners

- `python3 tools/udroid-debug.py repro-damage` is the embedded Lorie desktop
  reproduction gate. Use the embedded APK; a standalone Termux:X11 APK tests a
  different `libXlorie.so`.
- `tools/profiling/venus-perfetto.pbtxt` already captures the portable Android,
  SurfaceFlinger, scheduling, frequency, and uDroid ATrace sources needed here.
  Reuse it initially and name the output for the tested backend; do not create
  another trace configuration until a required data source differs.
- `docs/graphics/VENUS_PERFETTO_PROFILING.md` contains capture commands,
  FrameTimeline cautions, and baseline SQL. The methodology applies to this
  path even though the renderer is Panfrost.
- `dumpsys SurfaceFlinger --timestats` provides a bounded per-layer cadence
  control when FrameTimeline attribution is incomplete.

Capture shape:

```sh
adb shell dumpsys SurfaceFlinger --timestats -clear
adb shell dumpsys SurfaceFlinger --timestats -enable

adb shell perfetto --txt -c - \
  -o /data/misc/perfetto-traces/udroid-panfrost-import.perfetto-trace \
  < tools/profiling/venus-perfetto.pbtxt

adb shell dumpsys SurfaceFlinger --timestats -disable
adb shell dumpsys SurfaceFlinger --timestats -dump \
  > udroid-panfrost-import.timestats.txt
adb pull /data/misc/perfetto-traces/udroid-panfrost-import.perfetto-trace .
```

Confirm available device data sources before capture:

```sh
adb shell perfetto --query --long
```

Missing vendor GPU tracepoints are not a reason to abandon the test. Preserve
scheduling, uDroid ATrace, FrameTimeline, and SurfaceFlinger evidence and record
the missing source in the manifest.

### Metrics and interpretation

Report distributions, not only averages:

- displayed unique frames and SurfaceFlinger present intervals;
- FrameTimeline on-time/missed frames and jank types;
- Lorie draw, acquire-wait, release, swap, and retirement p50/p95/p99;
- process/thread CPU time and runnable-but-unscheduled time;
- GPU frequency/busy evidence when the device exposes it;
- CPU upload count/bytes and native-import/fallback counts;
- frame-ID duplicates, gaps, reorderings, and reuse violations;
- RSS/PSS, FD count, active buffer count, and fence count;
- input-to-visible-response latency for the deterministic drag probe.

Producer FPS measures how quickly the client submits work. It is not physical
scanout FPS. Likewise, a stable 16.67 ms interval on a 60 Hz display is normal
frame pacing, not proof that rendering consumed 16.67 ms.

### Acceptance gates

Correctness is absolute:

- zero wrong pixels, stale frames, frame reorderings, and reuse violations;
- zero unexpected fallback for an eligible buffer;
- zero CPU uploads on the eligible native-import path;
- balanced FD/object ownership after every teardown and soak;
- all rejection probes fail closed without process or display loss.

Performance is a matched comparison. Run at least five alternating
baseline/candidate pairs after warm-up and report each run, median, p95, and
p99. Accept the candidate only when:

- displayed cadence and jank are no worse than the CPU baseline;
- Lorie CPU time and uploaded bytes materially decrease beyond run-to-run
  noise;
- no new long acquire/release stalls appear;
- surface detach/reattach and resize latency do not regress;
- the visual and lifetime gates remain clean.

Do not define one universal FPS threshold for every device. Relate cadence to
that device's active refresh period and keep the exact raw data. A desktop
macro-test cannot waive a failed microprobe.

## Specification-to-test traceability

| Contract | Primary specification | Required evidence |
| --- | --- | --- |
| DMA-BUF image description | Linux “Exchanging pixel buffers” | Width/height, FourCC, modifier, plane FD, offset, and pitch logged and rejection-tested. |
| EGL DMA-BUF import | `EGL_EXT_image_dma_buf_import` | Exact active-display extension plus successful known-pattern import. |
| Modifier negotiation | `EGL_EXT_image_dma_buf_import_modifiers` | Queried FourCC/modifier pair and correct `external_only` target. |
| EGLImage lifetime | `EGL_KHR_image_base` | Repeated create/destroy and source/target lifetime test. |
| GLES binding | `GL_OES_EGL_image` and `GL_OES_EGL_image_external` | Correct target/shader with exact pixel result. |
| Acquire/release fences | `EGL_ANDROID_native_fence_sync`, `EGL_KHR_wait_sync` | Delayed-fence tests and audited FD ownership. |
| DMA-BUF cache versus device sync | Linux DMA-BUF documentation | CPU fallback uses `DMA_BUF_IOCTL_SYNC`; GPU path proves fence ordering separately. |
| Android-native buffers | AHardwareBuffer, BufferQueue/Gralloc, GLConsumer | Handle identity, acquire-before-read, release-after-read, and generation teardown. |
| X reuse semantics | Present protocol | Present serial/complete/idle correlated with actual renderer retirement. |
| Physical presentation | Perfetto FrameTimeline and SurfaceFlinger TimeStats | Unique-frame cadence correlated with Lorie frame IDs and traces. |

## Implementation checkpoints

### G0: metadata and capability probe

- Log the active EGL vendor/version and relevant EGL/GLES extensions.
- Query FourCC/modifier support when available.
- Record whether a combination is `external_only`.
- Validate one real FD's dimensions, pitch, offset, size, and transport marker.
- Confirm the producer allocation is truly linear before translating `1274`
  to `DRM_FORMAT_MOD_LINEAR`.

Pass condition: an evidence report selects Route A, B, or C without attempting
an unsupported import. On the present Pixel snapshot, Route A must report
unsupported.

### G1: correlate the existing Panfrost AHB route

- Trace broker allocation id, Mesa resource/export FD, Present
  serial/pixmap/slot, `IdleNotify`, resource destruction, and broker release.
- Re-run the existing pattern and width probes without `glTexSubImage2D`.
- Check orientation, pitch padding, RGB/BGR order, alpha behavior, and checksum.
- Repeat create/destroy 1,000 times while checking FD and EGL object counts.

Pass condition: exact expected pixels, balanced lifecycle events, and no leak,
crash, or fallback.

### G2: synchronized ring

- Run at least three buffers with monotonically increasing frame IDs.
- Start with blocking producer and consumer completion controls.
- Add explicit acquire fence transport.
- Add explicit release fence transport.
- Introduce measured delays on each side and verify no early access or reuse.

Pass condition: no stale, reordered, partially written, or reused frame under
delay injection.

### G3: X Present integration

- Tie renderer completion, not redraw submission, to pixmap idle/reuse.
- Test copy Present and flip Present separately.
- Exercise window resize, occlusion, minimize/restore, surface detach/reattach,
  and renderer-process restart.
- Keep CPU fallback available for each failure.

Pass condition: deterministic damage runner is clean and buffer serials never
overlap producer write with consumer read.

### G4: desktop qualification

Run in this order:

1. colored-pattern and moving-frame microprobes;
2. `glxgears`/`glmark2` with repeated hold-and-drag;
3. Alacritty movement plus character-by-character typing;
4. XFCE without a compositor, then with one;
5. GNOME and Plasma damage runners;
6. SuperTuxKart only after desktop gates pass.

Collect CPU time, memory bandwidth proxy/copy counters, renderer frame time,
Android frame timeline/jank, Present completion/idle order, and dropped or
repeated frame IDs. Client-reported FPS alone is not a pass condition.

## Files the eventual patch must reach

| Area | Likely files | Purpose |
| --- | --- | --- |
| Buffer model | `lorie/buffer.h`, `lorie/buffer.c` | Metadata, import, texture target, FD/image/fence ownership. |
| DRI3 import | `lorie/InitOutput.c` | Translate private transport metadata, validate, and retain pixmap lifetime. |
| Renderer | `lorie/renderer.c` | Wait acquire fence, sample direct texture, emit release completion. |
| Renderer protocol | existing Lorie server/view messages | Carry serials and fences without ID reuse races. |
| Present | `xserver/present/*`, only if required | Delay idle/reuse until consumer completion; avoid protocol-wide hacks. |
| Producer | Panfrost DRI3/WSI and kbase code | Export accurate metadata and acquire completion; consume release completion. |
| Tests | embedded damage runner and native microprobe | Format, stride, ordering, teardown, and fallback gates. |

Patch the shared buffer/import code once; do not add application-specific fixes
for GNOME, Plasma, Alacritty, or SuperTuxKart.

## Primary references

### Khronos

- [EGL_EXT_image_dma_buf_import](https://registry.khronos.org/EGL/extensions/EXT/EGL_EXT_image_dma_buf_import.txt)
- [EGL_EXT_image_dma_buf_import_modifiers](https://registry.khronos.org/EGL/extensions/EXT/EGL_EXT_image_dma_buf_import_modifiers.txt)
- [EGL_EXT_image_implicit_sync_control](https://registry.khronos.org/EGL/extensions/EXT/EGL_EXT_image_implicit_sync_control.txt)
- [EGL_KHR_image_base](https://registry.khronos.org/EGL/extensions/KHR/EGL_KHR_image_base.txt)
- [GL_OES_EGL_image](https://registry.khronos.org/OpenGL/extensions/OES/OES_EGL_image.txt)
- [GL_OES_EGL_image_external](https://registry.khronos.org/OpenGL/extensions/OES/OES_EGL_image_external.txt)
- [EGL_ANDROID_get_native_client_buffer](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_get_native_client_buffer.txt)
- [EGL_ANDROID_image_native_buffer](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_image_native_buffer.txt)
- [EGL_ANDROID_native_fence_sync](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_native_fence_sync.txt)
- [EGL_KHR_wait_sync](https://registry.khronos.org/EGL/extensions/KHR/EGL_KHR_wait_sync.txt)

### Linux

- [DMA-BUF sharing and synchronization](https://docs.kernel.org/driver-api/dma-buf.html)
- [Exchanging pixel buffers](https://docs.kernel.org/userspace-api/dma-buf-alloc-exchange.html)
- [`drm_fourcc.h` format and modifier ABI](https://gitlab.freedesktop.org/mesa/drm/-/blob/main/include/drm/drm_fourcc.h)

### Android/AOSP

- [Synchronization framework](https://source.android.com/docs/core/graphics/sync)
- [BufferQueue and Gralloc](https://source.android.com/docs/core/graphics/arch-bq-gralloc)
- [NDK AHardwareBuffer reference](https://developer.android.com/ndk/reference/group/a-hardware-buffer)
- [AOSP META-EGL extension allow-list](https://android.googlesource.com/platform/frameworks/native/+/master/opengl/libs/EGL/egl_platform_entries.cpp)
- [AOSP GLConsumer reference flow](https://android.googlesource.com/platform/frameworks/native/+/master/libs/gui/GLConsumer.cpp)
- [VNDK AHardwareBuffer handle API](https://android.googlesource.com/platform/frameworks/native/+/master/libs/nativewindow/include/vndk/hardware_buffer.h)
- [Perfetto FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline)
- [Perfetto GPU tracing](https://perfetto.dev/docs/data-sources/gpu)
- [Perfetto ATrace](https://perfetto.dev/docs/data-sources/atrace)

### X11 protocol and local implementation

- `third_party/termux-x11/lorie/src/main/cpp/xorgproto/dri3proto.txt`
- `third_party/termux-x11/lorie/src/main/cpp/xorgproto/presentproto.txt`
- `third_party/termux-x11/lorie/src/main/cpp/xserver/include/protocol-versions.h`
- `third_party/termux-x11/lorie/src/main/cpp/xserver/present/`
- [Termux:X11 PR #1148](https://github.com/termux/termux-x11/pull/1148) for the damage and CPU-access-sync control, not as proof of GPU-native import.

## Decision before coding

G0 is complete on the connected Pixel and rules out Route A on its active
META-EGL display. Continue by tracing and stress-testing the existing Panfrost
Route B implementation in `tensor-g1-proot-gpu`; Route C remains separate
vendor-specific research.
