# Embedded Termux:X11 patches

`tools/prepare-termux-x11-source.sh` applies this series to the pinned
Termux:X11 submodule. The patch order is part of the protocol: producers,
Lorie's X server, and the Android renderer must agree on buffer type, channel
semantics, ownership, and synchronization.

## Patch inventory

| Patch | Purpose | Applied by default |
| --- | --- | --- |
| `0000` | Makes upstream nested Xserver patching idempotent when later uDroid patches extend the same files. | Yes |
| `0001` | Restores Android's `xlocale.h` include order for the embedded libX11 build. | Yes |
| `0002` | Replaces Termux:X11's JNI server entry point with uDroid's native bridge. | Yes |
| `0003` | Connects the embedded renderer and X server through uDroid's JNI bridge. | Yes |
| `0004` | Batches touch frames so one Android gesture reaches X11 as one ordered update. | Yes |
| `0005` | Adds DMA-BUF CPU access synchronization around fallback mapping and copies. | Yes |
| `0006` | Samples imported AHardwareBuffers as external textures instead of assuming ordinary 2D texture storage. | Yes |
| `0007` | Defines private RGBA/BGRA content semantics, bounds socket handshakes, and preserves AHardwareBuffer ownership on failure. | Yes |
| `0008` | Publishes the private buffer-transport contract on the X11 root window. | Yes |
| `0009` | Defers GPU-only Present work while the Android renderer is detached so Xorg does not CPU-map inaccessible storage. | Yes |
| `0010` | Diagnostic rollback that disables GPU Present copies. It is retained for A/B testing and explicitly reverted during source preparation. | No |
| `0011` | Adds Perfetto slices around Lorie draw, fence, and swap operations. | Yes |
| `0012` | Logs raw-FD import dimensions, layout, mapping, and preserved `errno`. | Experimental build only |
| `0013` | Corrects the full-screen flip height comparison. Direct flip remains unsafe for the current GPU-only root contract, so this patch is retained but not applied. | No |
| `0014` | Logs Present scheduling, acknowledgement, client lifecycle, and buffer identity. | Experimental build only |
| `0015` | Exports CPU-mappable redirected pixmaps through DRI3 using aligned DMA-heap storage. | Yes |
| `0016` | Accepts existing DMA-BUF and AHardwareBuffer pixmaps as GPU-copy sources instead of forcing a CPU fallback. | Yes |
| `0017` | Exports existing AHardwareBuffer pixmaps through DRI3 without replacing their storage. | Yes |

Set `UDROID_EXPERIMENTAL_GFXSTREAM_X11=1` only when building the additional
`0012` and `0014` diagnostics. It does not change the production buffer
transport. The complete common contract is `0005`-`0009`, `0015`-`0017`; these
patches must remain enabled together for composited desktops and SPHAL/Zink.

## Validation

Run source preparation twice for each profile. The second run verifies semantic
idempotence rather than only a clean first apply:

```sh
./tools/prepare-termux-x11-source.sh
./tools/prepare-termux-x11-source.sh
UDROID_EXPERIMENTAL_GFXSTREAM_X11=1 ./tools/prepare-termux-x11-source.sh
UDROID_EXPERIMENTAL_GFXSTREAM_X11=1 ./tools/prepare-termux-x11-source.sh
```

Then rebuild the APK normally. A device qualification must include a composited
desktop, normal channel colors, window movement, and non-zero GPU-offloaded
Present copies; a visible first frame alone is insufficient.
