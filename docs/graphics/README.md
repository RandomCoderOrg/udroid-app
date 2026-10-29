# Graphics documentation map

Start with the document matching the question. Do not infer one graphics path
from results produced by another.

| Question | Read first |
| --- | --- |
| Why does an AHardwareBuffer or DMA-BUF path damage, repeat, or reorder frames? | [`PRESENTATION_CONTRACT_REFERENCE.md`](PRESENTATION_CONTRACT_REFERENCE.md) |
| What must the next Lorie GPU-import patch prove? | [`LORIE_DMABUF_GPU_IMPORT.md`](LORIE_DMABUF_GPU_IMPORT.md) |
| What is the gfxstream resource/fence/reuse contract? | [`GFXSTREAM_WINSYS_CONTRACT.md`](GFXSTREAM_WINSYS_CONTRACT.md) |
| What did the app-native AHB presenter prove? | [`gfxstream-surface-presenter.md`](gfxstream-surface-presenter.md) |
| How is the remaining gfxstream work staged? | [`GFXSTREAM_SHIPPING_TODO.md`](GFXSTREAM_SHIPPING_TODO.md) |
| How should Venus/gfxstream frame latency be captured? | [`VENUS_PERFETTO_PROFILING.md`](VENUS_PERFETTO_PROFILING.md) |
| Which Android Linux Terminal implementation details are reusable? | [`AOSP_TERMINAL_REFERENCE.md`](AOSP_TERMINAL_REFERENCE.md) |
| What did the semaphore and ownership investigation establish? | [`2026-09-06-semaphore-lifecycle.md`](2026-09-06-semaphore-lifecycle.md) |
| What did the barrier/reuse investigation establish? | [`2026-09-10-barrier-route.md`](2026-09-10-barrier-route.md) |
| Where was the GNOME/Venus investigation paused? | [`GNOME_VENUS_CHECKPOINT_2026-10-04.md`](GNOME_VENUS_CHECKPOINT_2026-10-04.md) |

Evidence retained under `docs/graphics/evidence/` belongs to its named
checkpoint. A client FPS number, screenshot, or standalone Termux:X11 run does
not establish the uDroid embedded presentation route.

The experimental SPHAL/Zink profile and its documentation live on the separate
[`feat/sphal-zink-profile` branch](https://github.com/RandomCoderOrg/udroid-app/blob/feat/sphal-zink-profile/docs/graphics/SPHAL_ZINK_PROFILE.md), not in PR #30.
