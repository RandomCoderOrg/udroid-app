# X11 diagnostic probes

These small programs isolate one X11 or process-lifecycle contract at a time.
They are developer diagnostics, not application runtime dependencies.

| Probe | Purpose |
|---|---|
| `glx-oml-sample.c` | Read GLX OML UST, MSC, and SBC values for presentation timing. |
| `ppoll-trace.c` | Preload trace for blocking `poll` and `ppoll` calls. |
| `signal-backtrace.c` | Install an opt-in signal handler that prints a native backtrace. |
| `x11-input-probe.c` | Send simple pointer and launcher-key input through Xlib. |
| `x11-interaction-probe.c` | Check display geometry and XTest input injection. |
| `xdamage-trace.c` | Preload trace for XDamage API calls. |
| `xdamage-watch.c` | Count damage events for one X11 window over a bounded interval. |
| `xkb-reply-probe.c` | Distinguish delayed XKB replies, protocol errors, and timeouts. |

Build a probe inside the target Linux system with its matching development
libraries. Keep preload traces and signal handlers out of normal sessions: they
change timing and are intended only for short, reproducible captures.
