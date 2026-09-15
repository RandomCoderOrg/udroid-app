# Temporary semaphore imports and Weston frame pacing

Status: semaphore lifecycle tests and a one-shot external-image release probe
pass on Pixel 6a; continuous rendering and performance qualification remain
open. All changes are local and uncommitted.

## Measured failure

Pixel 6a, Debian Trixie, 720x720 Weston GL renderer with `weston-simple-shm`:

- Host runtime: `15-async-present-85eb290-1f2939dc-dirty`.
- Instrumented guest: `14-sync-wait-probe-fb349a2d3b-dirty`.
- Capabilities: `nativeSync=1 fencePassing=1 externalSync=1`.
- Stable 120-submit windows contained 114-115 signal-side sync FDs and the
  same number of `vkQueueWaitIdle` calls.
- Post-submit time averaged about 2.8 ms, almost entirely queue-idle waiting.
- The retained Weston log ended at 8,280 presents, with an average repaint
  interval of 19.120 ms (about 52.3 repaints/s). These are compositor timings,
  not a measurement of unique frames displayed by SurfaceFlinger.

`ResourceTracker` stored temporary imported SYNC_FD payloads and legacy
export payloads in the same semaphore field. It waited on an imported payload
but never restored the permanent semaphore payload. Zink returns imported
wait semaphores and export signal semaphores to the same pool. Reusing one as
a signal therefore entered legacy signal emulation and drained the GPU queue.

## Required behavior

Keep temporary imports separate from the legacy export payload. The first
submitted wait consumes the temporary payload and restores the permanent
payload; later waits in the batch must use that restored state. Submission
failure must retain ownership correctly. Importing `fd=-1` represents an
already-signaled temporary payload and requires no wait syscall. Re-export
must export the active payload and restore the permanent state.

Kumquat must wait with `poll`/`sync_wait`, which does not consume an eventfd
counter and also supports Android sync-file FDs. The previous read/write
waiter assumed every FD was an eventfd and ignored its timeout argument.

References: [Vulkan temporary SYNC_FD imports](https://docs.vulkan.org/refpages/latest/refpages/source/VkImportSemaphoreFdInfoKHR.html),
[semaphore FD export](https://docs.vulkan.org/refpages/latest/refpages/source/vkGetSemaphoreFdKHR.html),
and Mesa's `src/vulkan/runtime/vk_queue.c` temporary semaphore ownership.

## Validation gates

- [x] Build the matched arm64 guest with its source revision embedded.
- [x] Probe temporary import, wait, signal/reuse, `fd=-1`, and re-export.
- [x] Repeat the unchanged Weston workload; temporary waits and consumption
  counts must match, without signal-side legacy FDs or queue-idle drains.
- [ ] Check frame content and SurfaceFlinger presentation cadence.
- [ ] Re-test an accelerated Wayland client and Plasma after the narrow probe.

This correction does not yet make imported-fence waits asynchronous. The
remaining CPU wait and command staging costs need separate measurements after
the lifecycle fix passes. Do not claim the >59 FPS goal from submit counters.

## Device results, September 6

Guest runtime `15-sync-fd-lifecycle-fb349a2d3b-dirty`, SHA-256
`91c084d684ee47ffee6f739677d2ef76a5240eb14524b93e2735f27f10c41e36`,
passes the direct Vulkan lifecycle probe on Mali-G78. It includes an actual
signaled sync FD, temporary re-export, permanent signal/wait reuse, a single
queue call containing temporary-wait / permanent-signal / permanent-wait,
and the already-signaled `-1` payload. Guest 14 fails the same probe at
temporary re-export, so this is a regression-sensitive test.

The queue-idle removal is not a frame-rate improvement:

| Metric | Guest 14 control | Guest 15 lifecycle fix |
| --- | ---: | ---: |
| Guest post-submit average | 2.492 ms | 0.302 ms |
| Guest queue-idle average | 2.488 ms | 0 ms |
| Zink post-submit housekeeping | 0.552 ms | 4.048 ms |
| Weston repaint interval | 18.220 ms | 20.579 ms |

Temporary waits and consumption matched, with zero wait errors and no legacy
signal FDs in the fixed run. Zink housekeeping grew instead: the next probe
separates host semaphore export, Kumquat sync-FD handoff, DMA-BUF fd acquisition,
and the implicit-sync import ioctl. Zink's submit-worker wait is a CPU worker
completion wait, not a direct GPU completion measurement.

### Visual gate and controls

External output was black with both guest 14 and guest 15 on host 15, including
the standalone Vulkan colour producer without Weston. Android's internal AHB
checkerboard producer rendered visibly at about 60 submissions/s. This narrows
the investigation but does not prove the external presenter path is correct.
The standalone image-readback attempt is not a valid pixel oracle: the host
rejects `transfer-from-host-3d` with `ComponentError(-22)` and the probe times out.
Older-host comparisons stalled before their first frame and cannot be counted
as visual passes or failures.

The retained colour-probe binary (`786501c40508faaf28791506bd53ed8a310df1f753a913cfcd7aad80951d0de2`)
predates the source's `UDROID_GFXSTREAM_WSI_SCANOUT` mode. It silently ignores
that environment variable. The apparent scanout run was therefore only another
default-path run, not evidence about the three-slot WSI scanout path. Rebuild
and verify the probe's options before repeating that comparison.

Use one producer and one host for follow-up tests. Verify installed binary
hashes rather than trusting runtime directory names: the retained host-13
directory also contains a modified executable and a `kumquat.pre-alwaysblob`
backup. Weston appends to its log across launches; separate sessions before
calculating timings. A short bind at `/run/weston` creates a reachable Wayland
socket for independent clients; the earlier long physical socket path did not.

Local evidence: `/tmp/udroid-sync-lifecycle-probe-multisubmit-20260906.log`,
`/tmp/udroid-sync-lifecycle-probe-control-20260906.log`,
`/tmp/weston-sync-lifecycle-after-20260906.stdout`,
`/tmp/weston-sync-lifecycle-control-20260906.stdout`, and
`/tmp/udroid-native-present-control-20260906.png`. These temporary artifacts
are not release assets or durable CI evidence.

## Split-export measurement

The 30-second diagnostic run in `/tmp/weston-split-export-20260906.stdout`
contains eleven 120-call Zink windows:

| DMA-BUF synchronization operation | Mean per helper call |
| --- | ---: |
| `vkGetSemaphoreFdKHR` | 3.483 ms |
| DMA-BUF fd acquisition | 0.020 ms |
| `DMA_BUF_IOCTL_IMPORT_SYNC_FILE` | 0.459 ms |

No export/import errors were reported. The export operation accounts for about
88% of the sum of those three measured scopes. This locates the cost; it does
not establish whether it is GPU execution, stream draining, socket service,
or scheduling. Guest export timers also distinguish `vkGetSemaphoreGOOGLE`
from `acquireSync`, but their per-thread windows have no thread identifiers;
do not combine the two observed timing streams as if they were one workload.
A phone call and Surface recreation occurred around these experiments, so
this is diagnostic localization, not a controlled frame-rate comparison.

Diagnostic guest SHA: `1387021ca2fe0fe3d9b535751254f4a041674f9b04733690edd796fd4f77b068`.
Diagnostic Zink SHA: `235c885cf316e42b0e2250e12a88d67af24755d381af455c856952806eddc85f`.
The packaged runtime-15 library remains unchanged. Diagnostics live separately
under `/opt/udroid-diag/sync-export-20260906` in the test rootfs.

Launcher requirements: include the diagnostic DRI directory in the dynamic
library path (`libEGL` needs `libgallium`), but keep the socket-aware
`/root/udroid-gbm-contract/lib` ahead of the diagnostic stock GBM library.
Otherwise Weston reports no DMA-BUF output allocator. The corrected Zink
accumulator is thread-local and labels helper invocations as attempts, not
resources exported per submit.

## External ownership one-shot

The current producer probe cleared an exclusive Vulkan image and presented it
while it remained queue-owned in `TRANSFER_DST_OPTIMAL`. Its custom present
entry point does not supply the implicit transitions of `vkQueuePresentKHR`.
The opt-in probe enables `VK_EXT_queue_family_foreign`, clears one fresh image,
then records a graphics-to-FOREIGN release barrier from transfer-write to
`GENERAL`. Its exported completion fence covers that release. It presents once
and holds the image; no reacquisition or reuse is involved.

This run displayed the expected blue frame through the existing GLES presenter
with unchanged host 15 and guest 15. It exited successfully after waiting for
the release fence. Source is
`mesa-gfxstream/src/gfxstream/guest/vulkan/tests/udroid_kumquat_present_probe.cpp`;
the opt-in environment is `UDROID_GFXSTREAM_WSI_SCANOUT=1` plus
`UDROID_GFXSTREAM_FOREIGN_OWNERSHIP=1` and a visual hold duration.

Probe SHA: `d410dfce385efa5c01f7a930daa14d58d1b00c66cffefaabebf02216986afdd6`.
Evidence: `/tmp/udroid-foreign-one-shot-20260906.log` and `.png`.
This is a positive visual result, not a frame-rate result. Its printed
one-frame FPS is meaningless.

### Matched negative/positive control

The subsequent A/B probe (`1329900bcbd8da330bc66b42ba571a58ed13cae48d2c2fd191e2d321bdc5871b`)
separates `UDROID_GFXSTREAM_ONE_SHOT=1` from foreign ownership. Both runs use
one fresh image, one frame, the same binary, the same 15-second hold, and a
fresh host/app session with resource 4. Both report one completed Android
presentation and no fence errors:

| Probe option | Visible result |
| --- | --- |
| WSI scanout + one-shot, no foreign release | Black |
| Same + foreign extension and GENERAL/FOREIGN release barrier | Expected blue |

Evidence pairs: `/tmp/udroid-one-shot-negative-20260906.log` / `.png` and
`/tmp/udroid-one-shot-positive-20260906.log` / `.png`. Both processes exited
successfully after waiting for release. The comparison fixes the earlier
three-slot/180-frame confound. It establishes the missing external ownership
and layout release for this producer; it does not separately distinguish the
layout change from the queue-family release, or prove the Weston path fixed.

The matched A/B logs/screenshots, split-export log, and semaphore lifecycle
probe log are preserved in [the local evidence folder](evidence/2026-09-06-sync-ownership/).
They remain uncommitted with the implementation.

Do not apply a blanket layout/queue-idle workaround. Next inspect the Weston
output's Zink export and gfxstream presenter handoff for the same ownership
contract. Repeated reuse must import the Android release sync FD into a Vulkan
semaphore before reacquiring ownership; CPU polling alone is not the complete
production synchronization contract.

## Host native export diagnostic

A follow-up 30-second run used the actual host tree described below, rebuilt
into diagnostic Kumquat SHA
`2917f66be0634b6a00b8583a30323b5482809a1af4327c90c4277ea679db3af1`.
It ran with `UDROID_WINSYS_TIMING=1`, host PID 30520, 720x720 output, the
GLES presenter, thermal status 0, battery temperature 37.1C, and the same
verified guest/Zink diagnostic hashes as the split-export run. The `timeout`
exit 124 is intentional for this timed capture; it is not an application
failure.

Host-side `getSemaphoreGOOGLE` produced two thread-token streams. The table
below reports the first 120-sample aggregate separately, then weighted
averages for the remaining windows on that token:

| Host thread token / TID | Window | Samples | setup avg | native export avg | descriptor add avg | errors |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `0xb400007708019818` / 30616 | first 120 | 120 | 174.968 us | 13.797 us | 11.592 us | 0 |
| `0xb400007708019818` / 30616 | steady | 1200 | 204.588 us | 18.841 us | 10.854 us | 0 |
| `0xb400007708018f78` / 30571 | first 120 | 120 | 142.937 us | 12.756 us | 5.691 us | 0 |
| `0xb400007708018f78` / 30571 | steady | 1200 | 167.203 us | 14.568 us | 7.450 us | 0 |

`virtioGpuContextId` was 1 throughout, and all feature/device/context/handle
unsupported counters were zero. The measured native `exportSemaphore` scope is
tens of microseconds on both host threads, so it does not explain the guest
Zink helper's multi-millisecond `GetSemaphoreFdKHR` scope by itself. The setup
scope includes the verbose per-export capability/request logging present in
this diagnostic build, so treat that number as diagnostic overhead-inclusive.

The released-ColorBuffer wait probe confirmed the upstream gate was active:

| Host thread token / TID | Window | Samples | submit infos | `GuestVulkanOnly` | released CBs | fence waits | flush calls | wait errors |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `0xb400007708019818` / 30616 | first 120 | 120 | 355 | 120 | 0 | 0 | 0 | 0 |
| `0xb400007708019818` / 30616 | steady | 1200 | 3543 | 1200 | 0 | 0 | 0 | 0 |

This rules out the host released-ColorBuffer `vkWaitForFences` and
`flushColorBuffer` loop for this Vulkan-only launch. It does not prove the
remaining guest-side export time is pure transport: the guest's synchronous
export request can wait behind preceding host queue-submit processing, and the guest
ResourceTracker timing stream has no thread/request ID for exact pairing with
the host thread tokens.

For orientation only, the same guest stdout still reported the Zink
DMA-BUF-semaphore helper at 3.226 ms for `GetSemaphoreFdKHR`, 22.816 us for
DMA-BUF fd acquisition, and 447.862 us for `DMA_BUF_IOCTL_IMPORT_SYNC_FILE`
after excluding its first 120-sample window. ResourceTracker's external-sync
logs lack thread IDs, so their interleaved windows cannot be assigned exactly
to these host tokens or cold-start-filtered per thread. Preserve those raw
windows rather than reporting a combined guest average as a matched scope.

The final appended Weston session starts at 13:31:04. Its last 1320-present
tail reports `inter-repaint-us(avg)=22160` and
`finish-interval-us(avg)=22105`, which is about a 45.2 Hz internal compositor
cadence. It is not an Android display FPS or visual-success claim.

Raw capture files are preserved in
[the evidence folder](evidence/2026-09-06-sync-ownership/):
`kumquat-syncdiag-20260906.logcat`, `kumquat-syncdiag-20260906.log`,
`weston-host-syncdiag-20260906.stdout`, and
`weston-host-syncdiag-20260906.weston.log`.

## Full queue-submit and decoder diagnostic

The next 30-second run used host SHA
`5772bb797e60865f1850cd8ddfa4b877221ed89b5f50acad1499824492a62747`,
PID 4013, the same guest/Zink libraries and 720x720 workload, and thermal
status 0. The original host-15 assets remain unchanged. The diagnostic host
was stopped after the bounded guest run (guest timeout 124; host TERM 143).

Instrumentation now includes the whole successful host submit, the outer
decoder submit packet, and the summed processing duration of preceding
non-submit packets. Mere elapsed time between submits is reported separately:
it includes idle time and must not be mistaken for decoder work. Partial
packets are excluded until complete. Timers and spin counts are opt-in;
submission behavior is unchanged. Both timer layers passed peer review and
the native backend/offline Kumquat relink passed.

Steady means exclude the first 120-sample window per group. Host submit and
decoder groups below each have 1320 steady samples on TID 4116:

| Scope | Steady average | Relationship |
| --- | ---: | --- |
| Whole host queue-submit function | 2196.9 us | Inside decoder submit packet |
| Pre-dispatch preparation | 13.6 us | Part of whole host submit |
| Queue-lock and dispatch block | 2142.4 us | Part of whole host submit |
| Native dispatch | 2140.4 us | Inside dispatch block; do not add twice |
| Post-dispatch work | 40.5 us | Part of whole host submit |
| Outer decoder submit packet | 2218.3 us | Includes whole host submit |
| Preceding non-submit packet processing | 666.0 us | Disjoint from current submit packet |
| Preceding packet sequence wait | 7.5 us | Inside preceding packet processing |

There were about 2.952 submit infos and 3.100 preceding packets per submit.
Submit-packet sequence waits were zero; queue-lock overhead was about 2 us.
All submissions were dispatched, none deferred, and all reported error
counters were zero. Cold host total/dispatch were 1961.5/1915.0 us; cold outer
packet/preceding processing were 1994.5/1099.4 us.

Runtime feature output explicitly reports `VulkanVirtualQueue: disabled`.
Consequently the dispatch wrapper's virtual-queue signal bookkeeping is
skipped: its measured duration is effectively the native vendor
`vkQueueSubmit` wall duration, not a virtual-queue scheduling cost. Wall time
alone does not distinguish CPU processing from driver/kernel waiting.

Native semaphore export remains small (steady 16.2 us on TID 4116 and 13.3 us
on TID 4058). The guest Zink helper reports 2991.7 us for semaphore export,
20.7 us for DMA-BUF fd acquisition, and 404.1 us for importing its sync file
(1200 steady samples, no errors). Do not subtract these unpaired windows to
claim an exact transport residual: a synchronous export request can wait
behind previously queued native submissions.

The final Weston completion interval was 20534 us, about 48.7 Hz internally.
The screenshot captured during the run is still black beneath the diagnostic
overlay; correctness and actual displayed cadence remain unqualified. The
overlay's stopped-host message refers to the original app-managed host, not
the manually launched diagnostic PID 4013. Its frame counter is cumulative
across runs and is not a fresh-run displayed-frame counter.

Evidence in [the evidence folder](evidence/2026-09-06-sync-ownership/):
`kumquat-queue-diag-20260906.logcat`, `.log`,
`weston-queue-diag-20260906.stdout`, `.weston.log`, and `.png`.

## Submission CPU time and production ownership reference

Host `a5cf43e37ff19d78c21e28ad0015c122e02c2d8feb4be6b3ec26b5f04fcf01d9`
adds calling-thread CPU time and submit-shape counters to the existing
dispatch aggregate. The matched 30-second run (PID 5039, TID 5128, thermal
status 0) produced 1320 steady samples after excluding the first 120:

| Per dispatch | Steady average |
| --- | ---: |
| Wall time | 2303.5 us |
| Calling-thread CPU time | 1049.7 us |
| Submit infos | 2.952 |
| Command buffers | 1.195 |
| Wait semaphores passed to the host | 0 |
| Signal semaphores | 2.905 |

All CPU samples were valid. CPU time includes kernel CPU work on the calling
thread, not just vendor userspace code. Its current measurement also includes
one post-dispatch steady-clock read: a small positive bias, not a pure native
instruction-cost measurement. The roughly 1.25 ms wall-minus-CPU difference
can include waiting and descheduling; it does not identify a particular wait.
Some long Android log messages truncate trailing error fields, so this run
does not establish values for fields absent from the capture.

The final Weston internal completion interval was 21145 us (about 47.3 Hz),
not a displayed-FPS pass. Guest timeout 124 and host TERM 143 were intentional.
The `kumquat-cpu-diag-20260906.*` and `weston-cpu-diag-20260906.*` captures are
preserved in the evidence folder. No packaged runtime was overwritten.

Launch-profile caveat: manual `adb shell run-as` diagnostics inherit cpuset
`/`, whereas the foreground app is in `/top-app`; a follow-up read showed both
allowed CPUs 0-7. This does not prove scheduling caused the delay, but final
app performance must be measured with the app-managed host, not inferred from
these manual-host runs.

For the black-output investigation, production code contradicts a blanket
claim that every AHardwareBuffer release must change its layout to GENERAL.
[Android HWUI's ownership release](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-qpr3-c-s12-release/libs/hwui/AutoBackendTextureRelease.cpp)
asks Skia to preserve the current layout while releasing to FOREIGN (its
UNDEFINED state argument means preserve, not a Vulkan transition to UNDEFINED).
The [Vulkan AHardwareBuffer contract](https://github.khronos.org/Vulkan-Site/spec/latest/chapters/memory.html#memory-external-android-hardware-buffer)
also distinguishes foreign access from matching-driver/device Vulkan or GLES
access. These references justify testing ownership and layout independently,
not changing all Zink DMA-BUF exports based on the combined one-shot result.

## Matched ownership/layout factorial (app-managed host)

The standalone probe SHA
`c595ca35d727edee18b69dfdc3638ae4637601ee00c780b51af7040ed4fe4be1`
was verified in the guest and run once per arm with a fresh app-managed host15,
stock guest15, and the GLES presenter (`directSurfaceControl=false`). All four
explicit modes enable the same foreign-queue extension and retain identical
image parameters. Each presents one 720x1280 frame on the 1080x2400 Android
surface, holds it for 15 seconds, waits the release fence, and exits normally
(exit 0). Screenshots were captured after the `[hold]` marker while the producer
was alive. The overlay reports one completed frame and zero acquire/release
fence failures in each arm.

| Release mode | Final layout | Queue handoff | Observed frame |
| --- | --- | --- | --- |
| none | TRANSFER_DST_OPTIMAL | none | Black |
| ownership | TRANSFER_DST_OPTIMAL | graphics to FOREIGN | Blue |
| layout | GENERAL | none | Black |
| both | GENERAL | graphics to FOREIGN | Blue |

Evidence: `evidence/2026-09-06-sync-ownership/udroid-factor-*-20260906.{log,png}`.
These are single trials, not a stability soak or frame-rate measurement.
The one-frame `wall_fps` log is not displayed FPS and must not be reported as
desktop performance.

For this direct-producer path, ownership release is sufficient to make the
frame visible; changing to GENERAL is neither necessary nor sufficient.
Layout-only also emits a memory barrier, which controls for merely adding
an extra barrier. This does not yet prove that Weston misses ownership release:
Weston uses an EGL-imported render target and a presenter alias. Next trace
that exact resource through import, barrier selection, `dmabuf_exports`, batch
release, and semaphore export. If the expected release is present, investigate
gfxstream image/backing/ColorBuffer identity instead of changing Zink layouts.

A subsequent 25-second Weston baseline using the same app-managed host also
remained black (720x720 output, existing diagnostic guest/Zink libraries,
thermal status 0). The final internal finish interval was 20282 us, roughly
49.3 Hz; the screenshot overlay reported 48.9 completed submissions/s. This
confirms that switching away from the manually launched diagnostic host alone
does not restore visible output or meet 59 FPS. It is not a matched performance
comparison between host binaries. The intentional timeout exited 124.
Evidence is `weston-apphost-baseline-20260906.{stdout,png,weston.log}`; the
Weston file is append-only, so only its final run applies to this observation.

Static follow-up identified a barrier API asymmetry in the actual host tree.
`vk_decoder_global_state.cpp` routes legacy and core `CmdPipelineBarrier2`
through common FOREIGN-to-EXTERNAL conversion and ColorBuffer tracking.
The KHR opcode instead directly invokes the vendor entrypoint in both
`vk_decoder.cpp` and `vk_sub_decoder.cpp`, bypassing that common handler.
This is not yet established as the Weston failure: Zink names the core
dispatch-table member, and its loader may alias core/KHR entrypoints.
The ownership diagnostic must identify the selected entrypoint before a
behavior change is justified by this run.

## Actual Weston ownership trace

The reviewed, separately deployed Zink diagnostic SHA
`0bbdf8f6899a8e4ff54eef03809115f56ab8a36e1aa9c8dbfbad815d1ffc1c0b`
was run for 15 seconds with `UDROID_WINSYS_OWNERSHIP_TRACE=1`, the existing
diagnostic guest ICD, and a fresh app-managed host15. Original runtime files
were not replaced. The run remained black and ended with intentional timeout
124. Logs/screenshot/launcher are preserved as
`weston-ownership-20260906.*` and `launch-weston-ownership-diag.sh` in the
evidence folder. Timing with this verbose diagnostic is not a benchmark.

For the first 720x720 output image, resource `0x3000ab3800`, Vulkan image
`0x3000a81a00`, BO `0x3000c41db0`, batch-state `0x3000130580`:

1. DMA-BUF import sets FOREIGN/PREINITIALIZED, `exportable=1`, `dt=NULL`.
2. The sync2 barrier acquires it for COLOR_ATTACHMENT_OPTIMAL and inserts it
   into `dmabuf_exports`.
3. The batch emits COLOR_ATTACHMENT_OPTIMAL to the same layout, graphics to
   FOREIGN, with an appended exportable signal semaphore.
4. The same batch-state submits as batch ID 1 with `VK_SUCCESS`; semaphore
   FD export and `DMA_BUF_IOCTL_IMPORT_SYNC_FILE` both succeed (`ioctlRet=0`).

Thus this capture contradicts the hypothesis that Zink simply omits the
output-image release. Trace batch IDs are assigned at submission: correlate
the batch-state pointer as well, rather than treating pre-submit ID 0 as a
different batch. The built log's `importOk=0` is a misleading label for the
raw function return: this existing helper returns false on the successful
ioctl path. Source now labels it `rawReturn`; no behavior change or rebuild
was made for that label correction.

Both GetDeviceProcAddr names and Zink's selected table entry resolve to the
same pointer (`0x74bdddfbc0`). Consequently this pointer trace alone does NOT
distinguish the transported core versus KHR opcode. The inner encoder has
distinct functions, but the outer Mesa ICD can alias the entrypoints; inspect
that actual routing before blaming the host KHR bypass.

The optional `color-ownership` one-shot also displays blue after release to
FOREIGN with COLOR_ATTACHMENT_OPTIMAL as final layout. It adds color-attachment
image usage and still renders by transfer clear using a legacy barrier: it is
explicitly non-factorial, not equivalent to Weston's render-pass/sync2 path.
It narrows the issue beyond merely rejecting that final layout, but does not
validate continuous rendering, alias identity, or synchronization2 handling.

## Next measured gates toward Weston/KDE >59 FPS

- Trace Weston's render-image/export/presenter-alias ownership chain before
  transferring the successful direct-probe release into shared code.
- Use the measured mixed CPU/wait result and approximately three-submit-info
  shape for native controls. Zink's separate wait-FD submit can become empty
  after temporary waits are consumed in the guest adapter; measure that case
  before considering any narrowly scoped empty-submit removal.
- Add guest-side stream response/commit wait, notification, and
  `ring_buffer_yield`/call counters. `ring_buffer_yield` is currently
  `sched_yield`, so count waits and notifications before changing policy.
- Run no-op one- and two-thread export controls to separate fixed round-trip
  overhead from queued render work.
- Retain the runtime `GuestVulkanOnly` check for future profiles. This run
  confirmed that upstream suppresses the released-ColorBuffer wait for the
  Vulkan-only guest. Do not introduce a duplicate skip-wait policy.
- Restore correct continuous output and release/reacquire cycling, then
  measure actual Android presentation cadence with a moving workload.
- Re-test nested KWin/Plasma and accelerated clients; a Weston probe alone
  cannot satisfy the desktop/application goal.

The final cadence comparison must record output resolution, refresh rate,
thermal state, binary identities, and active workload. Use actual Android
presentation timestamps as well as compositor timings. The 720x720 diagnostic
and one-frame probes do not qualify a full-screen desktop, and transaction or
submission counters alone do not prove unique displayed frames above 59 FPS.

## Authoritative host build provenance

The installed host-15 binary SHA `da0229a3edfd00d36c0ff3110faa9f0a96367da97620805d9b76716731c39533`
matches `rutabaga-winsys-contract/target-gfx369/aarch64-linux-android/release/kumquat`.
That target's recorded `rutabaga_gfx` link output names
`worktrees/gfxstream-imported-resource-contract/android-host-build/host`,
**not** `gfxstream-forks/gfxstream/android-host-build/host`.

Use `/Users/saicharankandukuri/anyc/worktrees/gfxstream-imported-resource-contract`
for host source/build work. Its HEAD is `540f041251df5fe96620534acf252ba412fc087c`
with local patches; its build enables host GLES and the unstable external-sync
and blob-ColorBuffer options. Its existing Meson 1.8.3 executable is
`/Users/saicharankandukuri/.cache/udroid-gfxstream/venv/bin/meson`.
The packaged manifest's older gfxstream revision alone is insufficient to
identify this source state. Revalidate host findings against this actual tree;
do not infer a compile-time Vulkan-only build from the Vulkan-only launch.
