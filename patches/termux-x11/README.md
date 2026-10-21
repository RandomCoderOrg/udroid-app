# Embedded Termux:X11 patch stack

`tools/prepare-termux-x11-source.sh` applies the production series to pinned
Termux:X11 commit `0cb0203`. Order is part of the ABI: guest producers, Xorg,
Lorie, and the Android renderer must agree on buffer type, channel order,
ownership, damage, and synchronization.

Each patch has a kernel-style subject and a short explanation of the problem it
solves. Production patches live here; noisy probes live in `diagnostics/`;
unsafe or incomplete work lives in `experiments/` and is never applied
automatically.

## Patch inventory

The table names the patches; `tools/prepare-termux-x11-source.sh` is the
authoritative application order because some later-numbered patches depend on
Xserver changes applied outside `lorie/`.

| Patch | Purpose | Applied by default |
| --- | --- | --- |
| `0000` | Makes upstream nested Xserver patching idempotent when later uDroid patches extend the same files. | Yes |
| `0001` | Restores Android's `xlocale.h` include order for the embedded libX11 build. | Yes |
| `0002` | Replaces Termux:X11's JNI server entry point with uDroid's native bridge. | Yes |
| `0003` | Connects the embedded renderer and X server through uDroid's JNI bridge. | Yes |
| `0004` | Batches touch frames so one Android gesture reaches X11 as one ordered update. | Yes |
| `0005` | Synchronizes DMA-BUF CPU reads used by GLES upload fallback. | Yes |
| `0006` | Samples imported AHardwareBuffers as external textures instead of assuming ordinary 2D texture storage. | Yes |
| `0007` | Defines private RGBA/BGRA content semantics, bounds socket handshakes, and preserves AHardwareBuffer ownership on failure. | Yes |
| `0008` | Publishes the private buffer-transport contract on the X11 root window. | Yes |
| `0009` | Defers GPU-only Present work while the Android renderer is detached. | Yes |
| `0011` | Adds low-overhead Perfetto slices around draw, fence, and swap operations. | Yes |
| `0015` | Exports CPU-mappable redirected pixmaps through DRI3 using aligned DMA-heap storage. | Yes |
| `0016` | Accepts existing DMA-BUF and AHardwareBuffer pixmaps as GPU-copy sources instead of forcing a CPU fallback. | Yes |
| `0017` | Exports existing AHardwareBuffer pixmaps through DRI3 without replacing their storage. | Yes |
| `0018` | Orders Present damage, ownership, GPU copies, and CPU fallback. | Yes |
| `0019` | Synchronizes nested EXA CPU reads/writes to DMA-BUF pixmaps. | Yes |
| `0020` | Reports the active EGL/GLES import contract, eligible route, and fallback reason once at renderer startup. | Yes |
| `0021` | Keeps padded AHardwareBuffer dimensions out of the logical X pixmap size and rejects undersized imports. | Yes |
| `0022` | Disables Present GPU copies when CPU and GPU views of the same AHardwareBuffer are incoherent. | Yes |

## Non-production patches

| Patch | Purpose | How it is used |
| --- | --- | --- |
| `diagnostics/0012` | Logs raw-FD duplication, mmap, dimensions, and errno. | Applied with `UDROID_X11_DIAGNOSTICS=1`. |
| `experiments/0013` | Fixes an upstream width/height typo in direct flip. | Never applied: it exposes an unqualified direct-flip path whose ownership/fence contract is incomplete. |

The diagnostic profile adds observability only; it does not change the selected
buffer transport. Production builds must not set `UDROID_X11_DIAGNOSTICS`.
Use the upstream `-disable-gpu-present` server option for CPU/GPU Present A/B
tests; no source patch is needed. Perfetto slices and X11 Present tools cover
lifecycle timing without permanent per-frame logging.

## Validation

Run source preparation twice for each profile. The second run verifies semantic
idempotence rather than only a clean first apply:

```sh
./tools/prepare-termux-x11-source.sh
./tools/prepare-termux-x11-source.sh
UDROID_X11_DIAGNOSTICS=1 ./tools/prepare-termux-x11-source.sh
UDROID_X11_DIAGNOSTICS=1 ./tools/prepare-termux-x11-source.sh
./tools/prepare-termux-x11-source.sh
```

The final standard run also verifies diagnostic patches can be removed cleanly.
Before release, replay both profiles from a fresh pinned submodule checkout;
semantic markers prove idempotence but cannot detect stale patch context.
Then rebuild the APK. Device qualification must include a composited desktop,
normal channel colors, repeated window movement, typed repaint, and Present
offload counters; a visible first frame alone is insufficient.
