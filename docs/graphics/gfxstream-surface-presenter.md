# gfxstream Android Surface presenter

Status: experimental, dev-build probe only.

This checkpoint proves the public Android presentation boundary needed by the
optional gfxstream graphics profile:

```text
AHardwareBuffer -> Unix socket -> EGLImage -> GLES GPU blit -> SurfaceFlinger
```

The existing Termux:X11 desktop remains the default and is not changed by this
experiment. `AhbSurfacePresenterView` is reusable production code, while
`GfxstreamPresenterProbeActivity` exists only in the `dev` source set.

The probe renders a deterministic checkerboard and moving scan line into an
AHardwareBuffer from an independent producer EGL context. It registers the
buffer through Android's public `AHardwareBuffer_sendHandleToUnixSocket` API;
the presenter receives a separate reference, imports it as an EGLImage, and
samples it into an app-owned Surface. Every frame crosses explicit Android
native acquire and release fence file descriptors via `SCM_RIGHTS`. The
producer does not reuse the AHB until the returned release fence has been
consumed. Its overlay reports the actual GL renderer, recent frame rate,
Surface generation, current buffer geometry, swap failures, fence failures,
and transport failures.

The local protocol is defined in `app/src/main/cpp/ahb_transport_protocol.h`.
The presenter binds a `0600` `SOCK_SEQPACKET` listener below the app's private
no-backup directory and accepts only a peer whose `SO_PEERCRED` uid matches the
uDroid app uid. The current deterministic producer connects from the same
process; the listener boundary is ready for a separately supervised renderer
running under that uid.
A registration packet is followed by Android's public AHB handle message.
Acquire and release packets each carry exactly one sync-file descriptor. Every
packet includes a resource id and generation so stale resize or lifecycle
traffic can be rejected. On Android 12 and newer, the probe also compares the
system-wide AHardwareBuffer id on both sides of the socket and rejects an
identity mismatch. The ancillary-data parser closes excess descriptors and
rejects truncated or malformed fence messages.

## Run the probe

```sh
./gradlew :app:assembleDev
adb install -r app/build/outputs/apk/dev/app-dev.apk
adb shell am start -n \
  org.randomcoder.udroid.dev/org.randomcoder.udroid.gfxstream.GfxstreamPresenterProbeActivity
```

## Pixel 6a checkpoint

The Android 17 Pixel 6a probe passed:

- AHardwareBuffer allocation and EGLImage import;
- public Unix-socket AHB registration and sync-file transport;
- GPU rendering into and sampling from the same AHB;
- app background and Surface recreation;
- live display-size replacement from 1080x2400 to 720x1280 and back;
- 20 repeated Surface detach/reattach cycles with five geometry replacements;
- three repeated APK replacement installs and cold launches;
- more than 12,000 presented frames at 59.8-60.1 FPS with zero transport or
  native-fence failures.

The original moving scan line advanced roughly 3.2 physical pixels per 60 Hz
refresh and the renderer paced itself with a free-running 16.667 ms sleep while
EGL also used swap interval one. A later three-run sample exposed the phase
drift: p95 reached 19.9-20.8 ms and 4-11 of 62 intervals exceeded 20 ms.

The probe now takes frame timestamps from Android `Choreographer`, coalesces a
late callback to the newest pending frame, and moves the scan line by less than
one pixel per refresh. Three post-change samples averaged 16.698-16.719 ms,
p95 was 17.864-18.111 ms, the maximum was 19.238 ms, and no interval exceeded
20 ms. This pacing belongs to the Android presenter; it does not claim that a
genuine gfxstream guest frame has reached the Surface.

One pre-stress run produced a persistent black source image while EGL swaps and
fence counters continued normally. A cold process restart recovered it. The
failure did not recur across the lifecycle, geometry, or reinstall matrix above,
so this checkpoint records it as an unresolved visual-correctness observation
rather than claiming that clean transport counters alone prove correct pixels.

## Forked renderer checkpoint

The external work is isolated in organization forks:

- `RandomCoderOrg/rutabaga_gfx`, branch `feat/kumquat-resource-flush`, defines
  a collision-free resource-flush command, parses its damage rectangle, waits
  for an explicit no-data response, and forwards the resource to gfxstream's
  existing `stream_renderer_flush()` path.
- `RandomCoderOrg/gfxstream`, branch `feat/android-ahb-socket-export`, exposes
  an Android-only unstable function that sends an AHardwareBuffer-backed
  renderer resource with `AHardwareBuffer_sendHandleToUnixSocket`. It does not
  inspect the private native handle or reconstruct allocation metadata.

The Android cross-build exports the new gfxstream symbol, and the portable
Kumquat protocol tests pass. This still does not constitute a gfxstream frame:
the next checkpoint must connect the separately supervised Kumquat peer, carry
a real acquire fence for each flushed resource, and return the presenter's
release fence before gfxstream can reuse that resource. CPU upload and implicit
resource reuse are not acceptable substitutes.
