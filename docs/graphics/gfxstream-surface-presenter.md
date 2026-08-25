# gfxstream Android Surface presenter

Status: experimental, dev-build probe only.

This checkpoint proves the public Android presentation boundary needed by the
optional gfxstream graphics profile:

```text
AHardwareBuffer -> EGLImage -> GLES GPU blit -> ANativeWindow -> SurfaceFlinger
```

The existing Termux:X11 desktop remains the default and is not changed by this
experiment. `AhbSurfacePresenterView` is reusable production code, while
`GfxstreamPresenterProbeActivity` exists only in the `dev` source set.

The probe renders a deterministic checkerboard and moving scan line into an
AHardwareBuffer from an independent producer EGL context, then samples that
buffer into an app-owned Surface from a presenter EGL context. Every frame
crosses explicit Android native acquire and release fences. The producer does
not reuse the AHB until the presenter release fence has been consumed. Its
overlay reports the actual GL renderer, recent frame rate, Surface generation,
current buffer geometry, swap failures, and fence failures.

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
- GPU rendering into and sampling from the same AHB;
- app background and Surface recreation;
- live display-size replacement from 1080x2400 to 720x1280 and back;
- more than 9,000 presented frames with zero swap or native-fence failures.

This remains a controlled same-process producer, not a gfxstream frame. The
next checkpoint gives the presenter a production submission API for an actual
renderer-owned `AHardwareBuffer`, resource generation, and acquire-fence fd,
then returns the release fence to the renderer's scanout lifecycle. It must not
substitute a CPU upload or depend on private native-handle reconstruction APIs.
