# Android/X11 presentation contract reference

Status: evidence baseline, 2026-10-20

This is the local decision record for uDroid graphics presentation. Read it
before changing the Panfrost, SPHAL/Zink, gfxstream, or embedded Termux:X11
paths. It records what the specifications require, what this repository
implements, what device tests observed, and what remains unknown.

Do not use `maybe`, `should`, or `works` without one of these evidence labels:

- **Specified**: required by a linked platform or API specification.
- **Implemented**: present in the cited local source.
- **Verified**: passed a named repeatable test with retained evidence.
- **Observed**: seen in one run, but not yet a qualification result.
- **Inferred**: consistent with evidence, but not isolated by a control.
- **Unknown**: no evidence yet. This is a test requirement, not a conclusion.

## The four separate contracts

A shared buffer does not by itself define correct presentation. Every route has
four independent contracts:

1. **Storage**: allocation, format, dimensions, planes, stride, offset, modifier,
   usage, color order, and alpha semantics.
2. **Transport**: handle/FD ownership and stable resource identity across
   process boundaries.
3. **Synchronization and lifetime**: producer completion, consumer completion,
   reuse, retirement, resize, and Surface replacement.
4. **Presentation**: damage, X Present completion/idle, Android transaction
   completion, frame pacing, and physical display cadence.

Producer FPS proves none of the other three contracts. A GPU-backed allocation
proves neither zero-copy presentation nor correct synchronization.

## Architecture correction

Android BufferQueue is consumer-owned: the consumer creates the queue, the
producer dequeues and queues slots, and the consumer acquires and releases
them. That is the Android framework model, not a universal rule that Lorie must
allocate every AHardwareBuffer.

A custom broker-owned AHardwareBuffer transport is valid when it carries
authoritative allocation metadata and implements the complete synchronization
and lifetime protocol. uDroid has such a protocol in the gfxstream presenter,
and `SaicharanKandukuri/tensor-g1-proot-gpu` already implements the Panfrost
AHB broker and private DRI3 transport. Therefore:

- do **not** invert allocation merely because BufferQueue normally does;
- do **not** treat `AHardwareBuffer_sendHandleToUnixSocket()` as a complete
  presentation protocol;
- reuse the existing resource/generation/frame and fence semantics before
  designing another allocator or transport;
- change allocation ownership only if an isolated import/layout test proves it
  necessary.

## Route matrix

| Route | Storage and transport | Synchronization/lifetime | Current evidence | Decision |
| --- | --- | --- | --- | --- |
| Raw Panfrost DMA-BUF in Lorie | One FD through private DRI3 marker `1274`; Lorie mmaps and uploads with `glTexSubImage2D` | `DMA_BUF_IOCTL_SYNC` protects CPU access; X Present orders pixmap use | **Implemented** CPU fallback; not GPU-native import | Retain as synchronized fallback and control |
| Panfrost AHardwareBuffer in Lorie | Tensor G1 broker allocates the AHB; Panfrost imports its DMA-BUF; private DRI3 marker `1255` lets Lorie recover the native handle | Mesa blocks for producer completion; its three-slot ring retains each resource until X Present `IdleNotify`; no explicit cross-process fence or correlated teardown trace | **Verified** by broker probes, 33-width lifecycle runs, and full Plasma runs in `tensor-g1-proot-gpu`; desktop qualification remains incomplete | Extend and trace the existing route; do not rebuild it |
| SPHAL/Zink AHardwareBuffer in Lorie | Private DRI3 markers `1255`/`1257`; Lorie receives an AHB and imports an `EGLImage` | Relies on Vulkan external queue ownership, implicit DMA-BUF synchronization, and X Present ordering; no explicit per-frame acquire/release/reuse/retire protocol | **Observed** Pixel runs offloaded Present copies; desktop damage has also been observed | Instrument and adopt the validated lifecycle contract before qualification |
| Gfxstream app presenter v4 | Public AHB socket helpers plus `(resource, generation, frame)` packets | Producer fence, presentation signal, release signal, reuse-ready, retirement, and Surface generation | **Verified** by local validators and retained Pixel stress results | Reference implementation for lifecycle semantics |
| Direct app EGL DMA-BUF import | FourCC plus plane FD/offset/pitch/modifier through `EGL_EXT_image_dma_buf_import` | Needs an explicit acquire/release design in addition to EGLImage lifetime | **Verified unavailable** on the tested Pixel META-EGL display | Capability-gated only; not the Pixel implementation |
| Direct vendor/SPHAL EGL DMA-BUF import | Independent vendor EGL display/context and queried formats/modifiers | Unknown until an isolated vendor-display probe proves it | **Unknown** | Device-specific research route, never mix with META-EGL objects |

## Existing validated lifecycle protocol

The packet ABI is in `app/src/main/cpp/ahb_transport_protocol.h`. Protocol v4
uses the following identity:

```text
(connection epoch, resource id, resource generation, frame id)
```

Its lifecycle is:

```text
register AHB generation
  -> acquire frame with producer-completion FD
  -> consumer waits and presents
  -> presentation-completion and buffer-release signals
  -> producer reports reuse-ready
  -> retire generation after all users drain
```

The presenter implementation is
`app/src/main/cpp/ahb_surface_presenter.cpp`. It rejects unknown or stale
generations, non-monotonic frames, premature reuse, and retirement while a
resource is in flight. It also tracks Android Surface generations.

The protocol validators are:

- `tools/graphics/winsys_trace_validator.py`: validates one lifecycle trace;
- `tools/graphics/winsys_dual_trace_validator.py`: correlates producer and
  presenter traces;
- `tools/graphics/test_winsys_trace_validator.py` and
  `tools/graphics/test_winsys_dual_trace_validator.py`: rejection fixtures.

This protocol is a semantic reference, not proof that its exact socket ABI can
be copied unchanged into X Present.

## What the specifications settle

| Topic | Authoritative source | Settled conclusion |
| --- | --- | --- |
| BufferQueue ownership | [AOSP BufferQueue and Gralloc](https://source.android.com/docs/core/graphics/arch-bq-gralloc) | The consumer owns the queue; producer dequeues/fills/queues and consumer acquires/uses/releases. Handles refer to shared storage rather than copying pixels. Gralloc usage can select vendor-specific layouts. |
| Android fence direction | [AOSP synchronization framework](https://source.android.com/docs/core/graphics/sync) | A producer may hand off a buffer before GPU work finishes with an acquire fence; the consumer returns a release fence when it no longer uses that buffer. |
| DMA-BUF CPU access | [Linux DMA-BUF documentation](https://docs.kernel.org/driver-api/dma-buf.html) | `DMA_BUF_IOCTL_SYNC` brackets CPU cache access. It does not provide mutual exclusion against other users and does not replace producer/consumer completion ordering. Exported FDs must be close-on-exec. |
| DMA-BUF image description | [Linux buffer exchange](https://docs.kernel.org/userspace-api/dma-buf-alloc-exchange.html) | An FD is not enough; format, dimensions, planes, offsets, strides, and modifiers define the image. |
| EGL DMA-BUF import | [EGL_EXT_image_dma_buf_import](https://registry.khronos.org/EGL/extensions/EXT/EGL_EXT_image_dma_buf_import.txt) | Width, height, DRM FourCC, plane FD, offset, and pitch are required. EGL retains the imported object, but FD ownership is not transferred to EGL. |
| EGL native fence ownership | [EGL_ANDROID_native_fence_sync](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_native_fence_sync.txt) | Importing a native-fence FD transfers that FD to EGL. Export uses a duplicated FD owned by the caller. Flush is required to materialize a fence created without an input FD. |
| Android EGLImage import | [EGL_ANDROID_image_native_buffer](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_image_native_buffer.txt) | An Android native buffer can become an EGLImage only through the advertised extension and a valid native buffer object. |
| Vulkan AHB memory | [Vulkan external Android hardware buffer](https://github.khronos.org/Vulkan-Site/spec/latest/chapters/memory.html#memory-external-android-hardware-buffer) | Vulkan import/export must use the AHB properties, usage, format, external-memory, and dedicated-allocation rules reported by the implementation. |
| AOSP consumer lifecycle | [AOSP GLConsumer](https://android.googlesource.com/platform/frameworks/native/+/c27d3ddd2f8796963144666aa7d3d006ddcad35e/libs/gui/GLConsumer.cpp) | A real consumer correlates slots and frame numbers, replaces stale EGLImages, waits before sampling, and attaches release synchronization before returning a slot. |
| X pixmap reuse | `third_party/termux-x11/lorie/src/main/cpp/xorgproto/presentproto.txt` | Present completion and idle are different events; producer reuse must follow actual idle/retirement, not redraw submission. |

The links are the source of truth. This file intentionally records conclusions
instead of copying full external specifications, whose revisions and licenses
differ.

## Local implementation map

### Producer and private DRI3 transport

The Panfrost AHB producer is implemented in the companion repository
`SaicharanKandukuri/tensor-g1-proot-gpu`:

- `tensor-g1/ahardwarebuffer/tensor-ahb-service.c`: Bionic AHB owner, pool,
  handle transfer, and dead-client reclamation.
- `src/gallium/drivers/panfrost/pan_resource.c`: broker allocation, Kbase
  DMA-BUF import, and private modifier export.
- `src/gallium/frontends/dri/drisw.c`: blocking producer fence wait and the
  three-resource Present ring.
- `src/egl/drivers/dri2/platform_x11.c`: DRI3 pixmap creation plus Present
  serial, completion, and idle handling.
- `tensor-g1/termux-x11/tensor-g1-dri3.patch`: matching Termux:X11 native
  AHardwareBuffer import.

### Lorie consumer

- `third_party/termux-x11/lorie/src/main/cpp/lorie/buffer.c`: FD mmap/upload
  fallback and AHB EGLImage binding.
- `third_party/termux-x11/lorie/src/main/cpp/lorie/renderer.c`: source texture
  sampling and final Android Surface draw.
- `third_party/termux-x11/lorie/src/main/cpp/xserver/present/`: X Present
  completion and idle machinery.

### App-native reference presenter

- `app/src/main/cpp/ahb_transport_protocol.h`: packet ABI and FD order.
- `app/src/main/cpp/ahb_surface_presenter.cpp`: resource registry, fence
  handling, SurfaceControl callbacks, reuse, retirement, and trace events.
- `docs/graphics/GFXSTREAM_WINSYS_CONTRACT.md`: protocol invariants and gates.
- `docs/graphics/gfxstream-surface-presenter.md`: retained device results and
  presentation history.

## Evidence status

| Claim | Status | Evidence or missing proof |
| --- | --- | --- |
| Pixel META-EGL can import raw DMA-BUF with `EGL_LINUX_DMA_BUF_EXT` | **Verified false** for the tested display | G0 reports no `EGL_EXT_image_dma_buf_import` or modifiers extension. |
| Pixel META-EGL can import AHardwareBuffer as EGLImage | **Verified** | G0 extensions plus existing Lorie AHB route. |
| AHB handles alone prevent damage | **Verified false** | Old private Lorie transport used AHB yet desktop damage was observed. |
| Consumer-owned allocation is required by the relevant APIs | **Not specified** | It is BufferQueue architecture; the validated custom presenter accepts registered producer resources. Allocation direction remains a route-specific design choice. |
| Explicit identity and lifecycle are required for deterministic validation | **Specified and implemented** | Existing contract and validators reject stale/reused/in-flight resources. |
| Implicit synchronization is the cause of current desktop damage | **Unknown** | Successful and damaged runs exist; no paired trace has isolated synchronization from damage/Present ordering. |
| AHB format number determines sampled channel order | **Verified false** | The `1255`/`1257` SuperTuxKart correction required content-semantics testing. |
| Client FPS equals displayed FPS | **Verified false** | Async producer throughput can exceed Android presentation cadence. Use FrameTimeline and frame IDs. |

## Next implementation gate

The one-buffer bridge and three-buffer X Present ring already exist in
`tensor-g1-proot-gpu`. The next gate is deterministic validation of that route:

1. correlate broker allocation id, Mesa resource, Present serial/pixmap/slot,
   `IdleNotify`, resource destruction, and broker release in one trace;
2. prove normal and abnormal retirement on resize, drawable destruction,
   Surface replacement, client disconnect, and X-server restart;
3. verify the known pixel pattern and delayed-producer controls without a CPU
   fallback;
4. only add an explicit fence packet if a control demonstrates that the
   existing blocking producer wait plus Present-idle lifetime is insufficient.

Allocation inversion is a separate A/B experiment, not the starting point.

GNOME, Plasma, Alacritty, and SuperTuxKart are qualification workloads, not
debuggers for G1. Do not run them as the primary test until the known-pattern,
delayed-fence, stale-generation, reuse, and retirement controls pass.

## Required evidence for any future claim

Record all of the following together:

- source commit and APK/build identity;
- device, Android build, GPU, EGL/Vulkan implementation, and refresh rate;
- selected route and fallback reason;
- resource/generation/frame events on both sides;
- all FD creation, transfer, wait, signal, and close ownership;
- FourCC, dimensions, plane count, stride, offset, modifier, AHB usage, and
  sampled color semantics;
- X Present complete/idle and Android presentation/release completion;
- deterministic pixel/checksum result and FrameTimeline cadence;
- CPU fallback and software-rendering controls.

Without that bundle, label the result **Observed** or **Unknown**, never
**Verified**.
