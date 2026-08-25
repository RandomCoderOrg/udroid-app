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
AHardwareBuffer, then samples that buffer into an app-owned Surface. Its overlay
reports the actual GL renderer, recent frame rate, Surface generation, current
buffer geometry, and swap failures.

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
- more than 9,000 presented frames with zero swap failures.

The probe currently uses same-context GL ordering. The next checkpoint imports
gfxstream-owned buffers and their acquire fences into this presenter, retaining
each AHB until the Android release fence completes. It must not substitute a
CPU upload or depend on private native-handle reconstruction APIs.
