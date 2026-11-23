# Graphics documentation map

Start with the document matching the question. Do not infer one graphics path
from results produced by another.

| Question | Read first |
| --- | --- |
| Why does an AHardwareBuffer or DMA-BUF path damage, repeat, or reorder frames? | [`PRESENTATION_CONTRACT_REFERENCE.md`](PRESENTATION_CONTRACT_REFERENCE.md) |
| What must the next Lorie GPU-import patch prove? | [`LORIE_DMABUF_GPU_IMPORT.md`](LORIE_DMABUF_GPU_IMPORT.md) |
| How should Venus frame latency be captured? | [`VENUS_PERFETTO_PROFILING.md`](VENUS_PERFETTO_PROFILING.md) |
| Where was the GNOME/Venus investigation paused? | [`GNOME_VENUS_CHECKPOINT_2026-10-04.md`](GNOME_VENUS_CHECKPOINT_2026-10-04.md) |

Evidence retained under `docs/graphics/evidence/` belongs to its named
checkpoint. A client FPS number, screenshot, or standalone Termux:X11 run does
not establish the uDroid embedded presentation route.

The experimental SPHAL/Zink profile and its documentation live on the separate
[`feat/sphal-zink-profile` branch](https://github.com/RandomCoderOrg/udroid-app/blob/feat/sphal-zink-profile/docs/graphics/SPHAL_ZINK_PROFILE.md), not in PR #30.
