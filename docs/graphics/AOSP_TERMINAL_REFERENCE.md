# AOSP Terminal graphics reference

Status: implementation contract for the optional gfxstream profile.

uDroid must reuse the public design lessons from AOSP Terminal and crosvm
without assuming their privileged VM APIs are available. AOSP Terminal runs a
real VM through Android Virtualization Framework; uDroid runs an ordinary,
rootless PRoot process. The VM transport cannot be copied, but the Android
display, lifecycle, buffer-ownership and input contracts still apply.

Primary references:

- [TerminalApp DisplayProvider](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/android/TerminalApp/java/com/android/virtualization/terminal/DisplayProvider.kt)
- [TerminalApp DisplayActivity](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/android/TerminalApp/java/com/android/virtualization/terminal/DisplayActivity.kt)
- [TerminalApp InputForwarder](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/android/TerminalApp/java/com/android/virtualization/terminal/InputForwarder.kt)
- [crosvm Android display backend](https://android.googlesource.com/platform/external/crosvm/+/refs/heads/main/gpu_display/src/gpu_display_android.rs)
- [crosvm direct-surface refactor](https://android.googlesource.com/platform/external/crosvm/+/2eb9276383416d3b6e57fc8030d43d1e18ab4145%5E%21/)
- [AOSP custom VM graphics notes](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/docs/custom_vm.md)

## Contracts to adopt

| Contract | AOSP behavior | uDroid requirement |
| --- | --- | --- |
| Surface ownership | Attach and remove the main and cursor surfaces without tying them to guest lifetime. | The display Activity may detach while X11, PRoot and the optional renderer remain supervised. |
| Surface lifecycle | Surface lifetime follows view attachment. | Never retain or render through a destroyed Android `Surface`. |
| Last visible frame | Save the main frame before the display becomes invisible and restore it on reattach. | Avoid a black display while the guest is still producing or reconnecting. |
| Cursor plane | Use a separate RGBA cursor surface positioned with a `SurfaceControl.Transaction`. | Keep cursor motion independent of desktop-sized presentation work where public APIs permit it. |
| Buffer ownership | Lock/dequeue an Android surface buffer, use it exclusively, then post it and stop accessing it. | Every AHardwareBuffer or fallback buffer must have explicit acquire, release and reuse states. |
| Allocator geometry | Treat Android's returned stride as authoritative. | Never infer row pitch from logical width. Validate format, stride, bounds and generation at import. |
| Input delivery | Batch multi-touch, request unbuffered pointer dispatch and distinguish mouse, trackpad and keyboard devices. | Preserve Android event timing and device identity instead of synthesizing slow per-contact streams. |
| Failure isolation | Display-service errors do not silently redefine guest state. | Fail the selected graphics profile and retain the Standard fallback; never corrupt the distro. |

## Boundaries that cannot be copied

AOSP Terminal can call privileged `VirtualMachine` and internal display-service
APIs and its guest has a kernel virtio-gpu device. uDroid has none of those
capabilities. Its replacement boundary is a same-UID, app-private Unix socket:

```text
Linux process in PRoot
  -> Mesa gfxstream ICD
  -> Kumquat protocol socket
  -> Android-host gfxstream and vendor Vulkan
  -> AHardwareBuffer or a documented fallback buffer
  -> embedded X11 presenter
  -> Android Surface
```

The socket transport, PRoot mounts and X11 DRI3 integration are uDroid-specific.
They must nevertheless preserve Android's buffer ownership, stride, lifecycle
and synchronization rules.

## Current correctness boundary

The standard Vulkan loader, gfxstream host and AHardwareBuffer-backed X11 WSI
now render ordinary Vulkan and Zink/GLX applications through Android's vendor
Vulkan driver. Termux:X11 imports the physical Android buffer and performs the
final Present copy on the GPU; the former linear/raw DMA-BUF upload remains a
fallback rather than the primary gfxstream path.

The first normal uDroid supervisor launch also validated an AOSP lifecycle
lesson that manually attached display tests missed. When the Display page was
closed, a GPU-only imported AHardwareBuffer fell into EXA's CPU copy path and
terminated X11 because Android correctly refused to map it. The X server now
defers that Present request until the Android renderer reconnects. A packaged
Pixel 6a run survived detached startup and three detach/reattach cycles without
restarting X11, Kumquat, PRoot or the Vulkan client.

Four forced portrait/landscape surface recreations now also preserve the app,
X11 and Vulkan-client processes while keeping every measured Present copy on
the GPU. A simultaneous Vulkan plus Zink/GLX run also rendered both clients
correctly through one Kumquat host and offloaded every measured Present copy.
A small X Composite/Damage workload with shadows and fades then kept both
redirected clients correct at the display's 60 FPS while offloading every
measured Present copy. An opt-in desktop session is now the next system-level
test; the existing gfxstream protocol and virtual-feature warnings remain
visible promotion blockers rather than launcher workarounds.

## Checkpoints

### 1. Raw DMA-BUF correctness

- Work in a `RandomCoderOrg/termux-x11` fork.
- Add the standard CPU-read synchronization around imported DMA-BUF access.
- Treat `ENOTTY` and other non-DMA-BUF descriptors as the existing fallback.
- Test a root-color control and `vkcube` in the same X server session.
- Require stable pixels across repeated resize, detach and reattach cycles.

### 2. AHardwareBuffer-native presentation

- Preserve the actual Android buffer identity across the host boundary.
- Import it through Termux:X11's AHardwareBuffer path instead of raw mmap.
- Carry format, stride, usage, generation and acquire/release fences.
- Require GPU-offloaded presentation and zero CPU frame uploads in diagnostics.
- Retain the raw synchronized and Standard profiles as capability fallbacks.

### 3. AOSP lifecycle parity

- Make surface attach/detach independent of desktop and renderer lifetime.
- Preserve and restore the last valid frame.
- Move the cursor to an independent surface when the device supports the
  required public APIs.
- Audit unbuffered pointer delivery, batched touch and physical-keyboard mode.

### 4. Performance and device qualification

- Measure producer, transport, import, presentation and end-to-end frame time.
- Track CPU time, uploaded bytes, queue depth, missed vsyncs and stale frames.
- Qualify the Standard fallback first, then gfxstream on Tensor, Exynos and
  MediaTek devices.
- Start KDE only after the micro-probe is correct and stable; compositor
  behavior must not be used to debug the transport.

## Promotion rule

The gfxstream profile remains optional until it produces correct pixels,
survives lifecycle tests, demonstrates lower presentation cost than the
fallback, and passes the device matrix. Unsupported devices must continue to
receive the existing Standard profile without changed distro files.
