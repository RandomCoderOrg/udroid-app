# Touchscope

Touchscope is uDroid's small end-to-end native multi-touch probe. It opens one
X11 window, subscribes to XInput 2.2 touch events, and draws every active
contact without requiring a desktop environment or GPU acceleration.

It reports:

- active and peak contact counts;
- `TouchBegin`, `TouchUpdate`, and `TouchEnd` totals;
- update rate;
- duplicate, missing, or unbalanced event sequences;
- contacts rejected after the current 20-contact Lorie limit;
- the XI2 source device and whether a touch emulates the pointer.

## Build and install in a Linux system

Copy this directory into the installed Linux system, then run:

```sh
./install.sh --install-dependencies
```

The dependency switch supports Debian/Ubuntu, Alpine, Arch, Void, and
Fedora-family package managers. Without the switch, the script never changes
system packages.

Launch from uDroid's Linux Apps page after refreshing, or from a terminal:

```sh
DISPLAY=:0 touchscope --fullscreen
```

Use `--verbose` to print Begin/Update/End events. The default output is
aggregated to avoid turning event logging into the performance bottleneck.

The sequence tracker can be validated without an X server:

```sh
touchscope --self-test
```

To produce the ARM64 Linux ELF used for device testing without installing a
compiler in the guest:

```sh
docker build \
  --platform linux/arm64 \
  --file Dockerfile.arm64 \
  --output type=local,dest=out \
  .
./out/touchscope --self-test
```

The compiler and X11 development packages remain in a cached build layer.
Source-only iterations rebuild just the final compile step.

## Expected uDroid behavior

Trackpad and Direct pointer modes deliberately translate fingers into mouse
events. Touchscope must be used with Native touch mode:

```text
Android MotionEvent
  -> uDroid native-touch strategy
  -> Lorie event socket
  -> XInput 2.2
  -> Touchscope
```

The current Lorie touch wire event has no Android event timestamp. Touchscope
therefore reports update rate and sequence integrity, but does not claim to
measure Android-to-X11 latency. Accurate latency measurement requires a
versioned protocol field carrying the source event time.
