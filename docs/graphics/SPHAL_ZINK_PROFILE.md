# SPHAL Zink profile

The hidden `SPHAL Zink (experimental)` profile runs guest Zink on the Android
vendor Vulkan HAL and presents through uDroid's embedded Termux:X11 renderer.
It is disabled by default because the guest runtime is not yet installed by the
app and vendor HAL compatibility is device-specific.

## Runtime path

```text
Linux OpenGL application
  -> patched Mesa Zink in /opt/udroid/graphics
  -> /opt/proot-sphal/bin/proot-vulkan-x11-run
  -> Android vendor Vulkan HAL
  -> AHardwareBuffer/private DRI3 transport
  -> embedded Termux:X11 GPU Present copy
  -> Android Surface
```

The app exposes the profile only after **About > Experimental > SPHAL graphics
bridge** is enabled. Compatibility requires AArch64, Android Vulkan 1.1,
AHardwareBuffer support, exactly one non-default Vulkan HAL under
`/vendor/lib64/hw` or `/odm/lib64/hw`, and these guest files:

- `/opt/proot-sphal/bin/proot-vulkan-x11-run`
- `/opt/udroid/graphics/lib/mesa/libGL.so.1`
- `/opt/udroid/graphics/lib/mesa/dri/zink_dri.so`

The launch profile binds the Android vendor trees, selects Zink and Kopper's
XCB surface, and places the patched Mesa libraries before the distribution
libraries. Terminal and desktop launches use the same profile so applications
started from either path inherit the same driver selection.

## Display-server contract

SPHAL is selected at runtime, while the embedded display server is compiled
once. Therefore the AHardwareBuffer/DRI3 contract is present in every build,
not conditionally patched when SPHAL is selected. See the
[Termux:X11 patch inventory](../../patches/termux-x11/README.md).

The essential behavior is:

1. Import producer AHardwareBuffers with explicit RGBA/BGRA content semantics.
2. Preserve GPU-backed redirected pixmaps and export their existing storage
   through DRI3.
3. Accept both DMA-BUF and AHardwareBuffer sources in the GPU Present path.
4. Keep the CPU fallback synchronized, but never force a CPU map of GPU-only
   storage.
5. Revert the old `0010` GPU-copy disable patch; otherwise composited windows
   can remain black even though the vendor driver is running.

On the Pixel 6a qualification run, repeated standard/experimental source
transitions remained idempotent, XFCE rendered with normal colors, and Lorie
reported `8/8 present copies offloaded to GPU`. This proves the intended
transport was used for that run; it is not broad device qualification.

The private Lorie AHardwareBuffer route is not equivalent to the app's
validated gfxstream presenter protocol. It relies on Vulkan external queue
ownership, Android DMA-BUF implicit synchronization, and X Present ordering;
it does not carry explicit per-frame acquire/release fences, reuse-ready
acknowledgement, or generation retirement. Compare the shared
`docs/graphics/PRESENTATION_CONTRACT_REFERENCE.md` on
`improvements/zink-requirements-check` before changing this path. The offload
run and the observed desktop damage are both evidence; neither isolates the
missing contract by itself.

## Current boundary

uDroid currently consumes a manually installed `proot-sphal` guest runtime.
The standalone Termux/PRoot distribution and its paired Termux:X11 artifact are
maintained separately and must be updated in their own repository before this
profile can be offered as a complete end-user install path.
