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

One pre-stress run produced a persistent black source image while EGL swaps and
fence counters continued normally. A cold process restart recovered it. The
failure did not recur across the lifecycle, geometry, or reinstall matrix above,
so this checkpoint records it as an unresolved visual-correctness observation
rather than claiming that clean transport counters alone prove correct pixels.

This remains a controlled socket pair in one process, not a gfxstream frame.
The next checkpoint replaces the producer endpoint with an app-private Unix
listener, authenticates the peer process, and connects a forked Kumquat
scanout/flush producer. It must not substitute a CPU upload or depend on
private native-handle reconstruction APIs.
