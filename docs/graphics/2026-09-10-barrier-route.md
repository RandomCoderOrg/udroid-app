# September 10: follow the release barrier

Goal remains visible, accelerated Weston and KDE above 59 FPS. No new
performance pass is claimed here.

## Starting evidence

The [September 6 ownership capture](2026-09-06-semaphore-lifecycle.md)
showed that Weston/Zink does acquire and release its output image, submit the
release batch, and successfully import the exported fence into the DMA-BUF.
Output was still black. Do not patch Zink to add another release based on the
earlier, disproved missing-release hypothesis.

The phone was reconnected September 10. The app was stopped and its host
socket absent, so the app-managed presenter was started (not a duplicate
manual host). Installed host15 SHA remained
`da0229a3edfd00d36c0ff3110faa9f0a96367da97620805d9b76716731c39533`.
The existing factor probe SHA remained
`c595ca35d727edee18b69dfdc3638ae4637601ee00c780b51af7040ed4fe4be1`.

A fresh ownership-only legacy-barrier probe displayed blue, with one completed
frame, no acquire/release fence failures, and normal exit 0. The screenshot
was captured during its 15-second hold. Android surface is now landscape
2400x1080; the probe resource remains 720x1280. This revalidates basic direct
presentation, not desktop correctness or frame rate. Thermal status was 1 at
a subsequent check, so this is not a matched performance measurement.

Evidence: `evidence/2026-09-10-barrier-route/udroid-ownership-baseline-20260910.*`.

## Next discriminator

The host has separate legacy, core synchronization2, and KHR synchronization2
decoder paths. Its KHR path bypasses handling performed by the core path,
but the outer Mesa guest aliases the two public names to the core entrypoint.
Equal GetDeviceProcAddr pointers were therefore not evidence of distinct
transported commands. Trace the compiled entrypoint mapping and wrapper rather
than relying on the inner encoder's separate function names.

The next standalone control keeps image, presenter, synchronization feature
enablement, initial transition, clear, and fence lifetime fixed. Only the final
release call changes: legacy, public core2, public KHR2. All explicit arms must
enable synchronization2 with the same Vulkan 1.3 device setup; old default
probe behavior stays unchanged. KHR2 is a public-alias control, not a forced
test of the host KHR opcode.

- If legacy works and synchronization2 fails, trace the actual core2 barrier
  through the transport and host before changing shared behavior.
- If both work, return to the imported render-target/alias identity and real
  render-pass path; another blanket layout or release change is not justified.

## Compiled route review

The inspected diagnostic guest artifact SHA `1387021c...` and Zink artifact
SHA `0bbdf8f...` match the September 6 trace identities. In the actual guest:

- `gfxstream_vk_device.cpp:632` builds the compact device table;
  `:715` routes GetDeviceProcAddr through Mesa's common lookup.
- Generated `build-udroid-aarch64/src/vulkan/util/vk_dispatch_table.c:6259`
  maps core/KHR entry indices 484/485 to slot 414. The ascending fill loop at
  `:9787` keeps the first non-null entry, the core wrapper.
- The core wrapper in `vulkan_enc/func_table.cpp:2325` calls the core encoder;
  `VkEncoder.cpp:14683` encodes `OP_vkCmdPipelineBarrier2` (296709912).
- With queue-submit-with-commands enabled, actual host
  `vk_sub_decoder.cpp:1962` sends this opcode through the common global-state
  handler. `vk_decoder_global_state.cpp:6665` performs the ownership conversion
  and then dispatches the vendor core2 barrier.

This compiled-source/artifact chain explains equal public pointers and rules
out the separate KHR decoder bypass as the cause of the matched Weston run.
The packaged guest also has the same alias structure. It is not a claim that
the unused KHR decoder implementation is correct. The standalone comparison
still tests whether the active synchronization2 release behaves like legacy
for an otherwise identical direct image.

## Matched API probe results

Probe SHA
`b290caf50ba5eec5da5810c74ef8b785723bf97c3862b89f157d173a405cda1a`
was built with the cached `udroid-gfx-build:mesa-v13-20260902` Docker image,
reviewed, deployed separately, and SHA-verified on device. The prior factor
binary remains intact. `git diff --check` and invalid-selector validation pass.

Each arm used fresh app-managed host15, packaged guest15, ownership-only
TRANSFER_DST_OPTIMAL to the same layout, transfer-write source access,
graphics-to-FOREIGN release, a 720x1280 resource, and the 2400x1080 GLES
presenter surface. All arms requested API 1.3, reported instance/physical API
1.4.0, and enabled the same KHR synchronization2 extension and feature.

| Final release public API | Captured frame | Android fence failures | Exit |
| --- | --- | --- | --- |
| vkCmdPipelineBarrier | Blue | 0/0 | 0 |
| vkCmdPipelineBarrier2 | Blue | 0/0 | 0 |
| vkCmdPipelineBarrier2KHR | Blue | 0/0 | 0 |

Each screenshot was captured after the hold marker during the 15-second hold.
Evidence: `evidence/2026-09-10-barrier-route/udroid-api-*-20260910.{log,png}`.
These are single-frame correctness controls, not a stability or performance
qualification. KHR public-name success is not coverage of the host KHR decoder.

The active synchronization2 path successfully releases this direct image.
Do not introduce a blanket legacy-barrier workaround or GENERAL transition
for Weston. The next microprobe should preserve this working release while
changing only producer identity: import the exported DMA-BUF into a second
Vulkan image, write through that imported image, then present the original
backing. Compare against writing through the original image. If that passes,
add render-pass color writes as a separate axis before another full desktop
run. This separates imported-image alias visibility from actual render-pass
handling without changing several libraries at once.

## Imported-writer control design

The next control creates original allocation A and imported image B before
any GPU use, in both arms. Only the selected writer receives the initial
UNDEFINED-to-TRANSFER_DST transition, clear, and the already-passing core2
same-layout ownership release. Android GLES is the reader. The private
present entrypoint uses A only to look up its backing resource; it issues no
Vulkan read or transition of A after B writes. This distinction avoids
assuming that differently created Vulkan aliases are interchangeable readers.

The [Vulkan aliasing rules](https://docs.vulkan.org/spec/latest/chapters/resources.html#resources-memory-aliasing)
and [external-image flags](https://docs.vulkan.org/refpages/latest/refpages/source/VkImageCreateFlagBits.html)
are the reference, not a new arbitrary alias flag. Both images and memories
must survive until Android's release fence signals, not merely until Vulkan
queue-idle.

Import metadata must come from queried modifier/memory-plane properties and
the exported FD: one supported plane, actual offset/row pitch, compatible
format/usage, allocation bounds, and intersected memory-type requirements.
The new probe must use MEMORY_PLANE_0 for DRM-tiling layout queries and zero
the unused explicit plane-layout fields.

Separate confirmed API-validity issue to correct after this controlled test:
`udroid_gfxstream_present.cpp:341` currently sets nonzero `size`, `arrayPitch`,
and `depthPitch` in a one-layer, depth-one explicit modifier import. These
violate VUIDs 02267/02268/02269 in the
[explicit-modifier contract](https://docs.vulkan.org/refpages/latest/refpages/source/VkImageDrmFormatModifierExplicitCreateInfoEXT.html).
This is not proof of the black-output cause; the specification also requires
implementations to ignore the size field. Do not silently change that helper
in the middle of the matched producer-identity experiment.

## Imported-writer runtime: metadata gate fails before rendering

The reviewed standalone control built and was deployed separately. The diagnostic
rebuild SHA is `30d00c8e50c2ced4432483222bfddd046c706d39d71aecf4b557e850be49ee1d`;
the prior API and ownership-factor binaries remain unchanged.

The A/A control exits 1 before creating the imported writer or submitting GPU
work. The host allocation log reports 3,686,400 bytes (the probe did not log
the initial requirements, so this is not proof they were nonzero). After binding, the
MEMORY_PLANE_0 layout query returns zero for every layout field. A repeated
memory-requirements query also returns size 0 and alignment 0 (type bits 0x2).
The format is 37, modifier 0, one plane, extent 720x1280, and the required row
width is 2,880 bytes. The unchanged bounds guard correctly rejects this.

Evidence: `evidence/2026-09-10-barrier-route/udroid-import-aa-layout-20260910.log`.
No screenshot or imported-writer success is claimed: this test did not reach
presentation. Trace probe call correctness and post-bind guest/host metadata
queries, including the unlogged pre-bind values, before changing import behavior.
Zero metadata is observed, but its
cause and relationship to Weston's black output remain unproven.

An expanded diagnostic (`ebe339167b14854d8a78dd3c002042e41cd31b5ce8158ae516060481aeced376`)
then timed out with exit 124 and the host process absent. It combined pre-bind
requirements and layout queries, including a COLOR comparator that is invalid
for the public DRM-modifier image. All logging followed the calls, so the
failing call is unknown. This run is not valid import/rendering evidence. Remove
the comparator and log each call separately; first compare requirements alone
before/after binding and retain only the already-tested post-bind layout query.
Its log is preserved as `udroid-import-aa-phases-20260910.log`.

## Serialized confirmation and source cause

Corrected probe SHA `1ca076fa0174cfb2490cb4f2957a0742b33c1c39afe844e79b0cc9e731f6ab45`
removes the COLOR comparator and logs entry/result per call. On a fresh host15
it exits 1 at the unchanged bounds guard, with the host still alive:

| Query phase | Requirements size | Alignment | Type bits |
| --- | ---: | ---: | ---: |
| Before allocation | 0 | 0 | 0x2 |
| After allocation, before bind | 0 | 0 | 0x2 |
| After bind | 0 | 0 | 0x2 |

Post-bind MEMORY_PLANE_0 layout remains all zero. Evidence:
`evidence/2026-09-10-barrier-route/udroid-import-aa-serialized-20260910.log`.
Binding did not reset valid metadata: none of these public queries returned it.

Independent source review identifies the boundary:

- Guest `vulkan_enc/ResourceTracker.cpp:4971-5037` creates the image with the
  GOOGLE combined creation/requirements command and caches its requirements;
  `:5893-5905` returns that cache for subsequent legacy queries. Binding only
  assigns the backing (`:5954-5970`).
- Actual host `host/vulkan/vk_decoder_global_state.cpp:9295-9315` queries native
  requirements immediately after creation. Android DMA-BUF translation adds
  the AHB handle type (`:10778-10806`), so the native query is before binding.
  That violates the native AHB precondition in
  [VUID 04004](https://docs.vulkan.org/refpages/latest/refpages/source/vkGetImageMemoryRequirements.html).
- Guest `ResourceTracker.cpp:4331-4342` separately substitutes tightly packed
  resource size during allocation; this never repaired the public cache.
- Guest DRM modifier emulation changes tiling to LINEAR (`:4808-4820`), but
  the subresource query forwards MEMORY_PLANE_0 unchanged. That does not meet
  the native linear-color query's [aspect requirement](https://docs.vulkan.org/refpages/latest/refpages/source/vkGetImageSubresourceLayout.html).

Reference for representation-aware queries: Mesa Venus
`src/virtio/vulkan/vn_image.c:753-798` translates aspects for deferred AHB images.
Its conversion is in the opposite direction; copy the design principle, not
the literal mapping. A fix must expose a coherent guest DMA-BUF contract backed
by authoritative allocation metadata, not add a COLOR fallback to applications
or infer that width times four is every allocator's stride.

This proves a public-query translation defect. It does not yet prove the cause
of Weston's black output or slow cadence. The installed GBM backend source SHA
`0926494e53cb1a8c1651f5d6aa490f5782b7738253c4aa9b86724c6037bbdddf`
matches `worktrees/mesa-gfxstream-gbm-contract/src/gbm/backends/gfxstream/gbm_gfxstream.cpp`;
the installed backend binary SHA is
`b15eb49f3a600221f4375469972603454e2be1bbeb1c90bf3f9fd492792b512f`.
Unlike the new probe, that backend creates a LINEAR guest image without the
WSI-scanout chain, then gets its post-bind COLOR layout (`:421-499`). COLOR is
valid for that guest representation; it is not a fallback for a DRM-modifier
image. Trace the exact allocation path before expanding the patch.

The last Weston launch script is preserved in
`evidence/2026-09-06-sync-ownership/launch-weston-ownership-diag.sh` (its temporary
copy no longer exists). Headless output uses XBGR8888, 720x720, with separate
EGL renderbuffer and presenter imports of the same BO. A matched standalone
test should preserve that path, the native EGL fence sequence, and both imports'
lifetimes. Existing GBM/EGLDevice readback probes are useful scaffolding, but
their glFinish/readback must not silently enter a presentation-only baseline.

## Reviewed next step: reproduce Weston's actual imports

Defer the full public WSI metadata redesign. The peer-approved next probe uses
the existing socket GBM/EGLDevice test as scaffolding, rather than guessing
metadata to continue the WSI test:

1. Allocate one 720x720 XBGR8888 BO with the installed socket GBM backend.
2. Select EGL_PLATFORM_SURFACELESS_MESA with a null native display, exactly as
   Weston `headless.c:1483-1485` does. Do not require EGLDevice enumeration.
   Export the descriptor; create the EGL/Zink writer alias first,
   then the presenter import, both before rendering (Weston headless output's
   actual order). Match EGL_IMAGE_PRESERVED_KHR=true,
   EGL_NO_CONTEXT, and Weston's renderbuffer attachment via
   glEGLImageTargetRenderbufferStorageOES (not the older probe's texture
   attachment). Require a hardware renderer and complete FBO.
3. Clear blue; create an EGL native fence, glFlush, duplicate its FD, destroy
   the EGL sync, then hand the FD to the unchanged presenter.
4. Capture during a marked 15-second hold, wait presentation and release,
   and only then destroy either consumer and the allocation.

No CPU mapping, glFinish, or readback belongs in this baseline. The helper's
known explicit-import layout-field defect remains unchanged for parity, not
endorsed as correct. This probe tests visibility, not displayed frame rate.

Revalidated runtime identities: Zink `0bbdf8f6...`, diagnostic guest
`1387021c...`, GBM backend `b15eb49f...`. The original Weston launch selects
`/root/udroid-gfxstream-output-20260901-rfence-v2/libudroid_gfxstream_present.so.0`,
SHA `511b27551ed22567a0c3a97ca46c76757b0910217983b90e8725e18fd4a13f5e`.
Do not accidentally replace it with the September 6 diagnostic helper
(`5b5ae332...`) through link search order.

While the GBM probe was being implemented, the previous core2 ownership-only
control was rerun using Weston's diagnostic guest ICD `1387021c...` instead of
packaged guest15 `91c084d6...`. It displayed blue, with one Android frame,
zero acquire/release failures, and exit 0. A hold-marker-triggered screenshot
removed manual capture timing from this check. Evidence:
`evidence/2026-09-10-barrier-route/udroid-api-weston-stack-20260910.{log,png}`.
This control uses private direct presentation, so it does not qualify the
separate presenter helper or GBM imports. Its one-frame timing is not FPS.

The first compiled GBM probe (`77fe8e32...`) exposed a test-plan mismatch:
it inherited EGLDevice selection from the scaffolding, but the actual Weston
headless backend selects SURFACELESS. It allocated a valid 720x720 XBGR BO
(stride 2880, offset 0, modifier 0, exported size 2,109,440) then exited 1
because eglQueryDevicesEXT returned count 0. No GPU writer or presentation ran.
Both initial planning and code review missed this platform difference.
Correct the probe's platform directly, not with an automatic fallback or
driver patch. Evidence: `udroid-gbm-egldevice-mismatch-20260910.log` in the
September 10 evidence directory.

## Matched GBM clear: visible pass

The corrected probe SHA
`b84d6091447343212c4fecbc86c0e226b5d8ddbe09d2569b9866eb74157ab882`
passed the exact cached-Docker build, a separate `-Wall -Wextra -Werror`
build, and peer review, then was SHA-verified on the Pixel. Its source is
`worktrees/mesa-gfxstream-gbm-contract/src/gbm/backends/gfxstream/gfxstream_gbm_present_probe.c`
(SHA `15eec90323e65911056dd7893c80e198201b851ddee33fec227566c35ff4e502`).
It links the presenter SONAME without RPATH/RUNPATH, allowing the old matching
helper to be selected by the launch environment.

Observed with fresh app-managed host15, diagnostic guest `1387021c...`,
Zink `0bbdf8f6...`, GBM backend `b15eb49f...`, and helper `511b2755...`:

- GBM descriptor: XBGR8888, 720x720, stride 2880, offset 0, modifier 0,
  exported file size 2,109,440 bytes.
- Renderer: `zink Vulkan 1.4(Virtio-GPU GFXStream (Mali-G78) (Driver Unknown))`.
- EGL imported renderbuffer is complete; the independent presenter import
  succeeds before the GPU clear.
- Zink traces its COLOR_ATTACHMENT-to-same-layout synchronization2 FOREIGN
  release, successful submit, and DMA-BUF sync-file import ioctl result 0.
- Presentation and release fence polls each return POLLIN. Android reports
  one presented frame with zero fence failures. The hold-triggered screenshot
  visibly shows blue, and the process exits 0 after release retirement.

Evidence: `evidence/2026-09-10-barrier-route/udroid-gbm-present-surface-20260910.{log,png}`
and `udroid-gbm-present-surface-host-20260910.log`. The passing executable is
also retained as `mesa-gfxstream/build-udroid-aarch64/gfxstream_gbm_present_probe-blue-pass-20260910`.

This proves one clear through Weston's relevant independent-import/presenter
route, not shader drawing, repeated-buffer reuse, desktop correctness, or
frame rate. The next discriminator is a real shader draw with identical
allocation/import/fence handling, then buffer reuse and a paced three-slot ring.
Do not revive blanket missing-ownership or universally broken-import claims.
The public WSI metadata defect stays recorded separately rather than blocking
tests of the actual working allocator path.

All work remains local and unstaged. Packaged runtime libraries are preserved.

## Matched GBM shader draw: visible pass

The opt-in `UDROID_GBM_PROBE_DRAW=1` artifact SHA
`92f384db61c1fabafdf2ce6e8e648000116308b2a91119628c925aa19da8f4bc`
was independently reviewed, hash-verified on the Pixel, and run with the same
diagnostic guest, Zink, GBM backend and old presenter helper as the clear pass.
The only workload change is a real ES2 full-screen spatial RGB gradient with
an explicit 720x720 viewport, without clear, readback, mapping or glFinish.

Both shaders compiled and the program linked successfully. The draw reported
GL_NO_ERROR. Presentation and release fences each signaled POLLIN, and the
process exited 0. The automatically hold-triggered screenshot visibly shows
the spatial RGB gradient; Android reports one frame and zero fence failures.
This qualifies a single shader-written frame through the independent imports,
not repeated reuse or 60 FPS. Runtime driver libraries were not changed.

Evidence: `evidence/2026-09-10-barrier-route/udroid-gbm-draw-20260910.{log,png}`
and `udroid-gbm-draw-host-20260910.log`. Source SHA at review:
`9d094f9c43dac61ae97516faa83c24c854abaf96eaf928899213db518a5442f2`.

Next discriminator: changing shader frames reusing the same BO, with prior
release retired before another write. This serialized correctness probe is
not the final performance design; a bounded multi-buffer test and actual
displayed-frame timing remain necessary before Weston/KDE qualification.

### Weston binary parity check

The device launcher's differently named renderer and backend directories do
not imply mixed binaries: all three SHA256 values match the corresponding
files in `gfxstream-forks/weston/build-contract`:

- `gl-renderer.so`: `e9498fa104e6b4e6c55194b97a1bec902a8710a7b4c69bee53ee99695f627b3a`
- `headless-backend.so`: `703c012afc65ddc13ace96ee58a04dd2f3138a8e63de16683e82b113dafaf37b`
- frontend `weston`: `c5d8e31502d677dc3240aae8427f7742d31f8b76cebd18e363f0f77477efc7b2`

### Displayed-frame measurement guardrails

The Pixel exposes `android.surfaceflinger.frametimeline`. Availability alone
does not prove coverage of our SurfaceView. The [Perfetto FrameTimeline
reference](https://perfetto.dev/docs/data-sources/frametimeline) explicitly
documents a SurfaceView limitation; verify target-layer events before using
it. Application timeline slice ends are GPU/post completion, not on-screen
presentation timestamps. Global SurfaceFlinger slices can include unrelated
UI updates, so those cannot establish our workload's displayed FPS.

The [Android frame-rate guide](https://developer.android.com/games/optimize/framerate?hl=en)
also documents per-layer `dumpsys SurfaceFlinger --timestats` collection and
`presentToPresent` histograms. Capture the exact presenter SurfaceView layer,
not the app's separate overlay/UI layer. Histogram buckets are quantized;
do not qualify a strict >59 FPS threshold from a rounded bucket-derived rate
alone. Actual repeated changing frames, tail intervals, and sustained
Weston/KDE workload evidence remain required.

In the current GLES presenter, `ahb_surface_presenter.cpp` signals the protocol
presentation eventfd immediately after `swapAndRecordFrame()` returns, then
waits the GPU release fence and signals release. Thus even the protocol's
presentation-fence signal is not an independently measured scanout timestamp.
Keep the current HUD wording `completed submissions/s` and qualify actual
display cadence separately.

### Reuse follow-up plan corrected against Weston source

Review identified two remaining differences that the spatial-gradient pass
does not exercise. `weston-simple-shm` provides XRGB8888 CPU pixels; Weston
uploads and samples them, including later partial updates using unpack row
length/skips and `glTexSubImage2D`. Damage is converted into clipped indexed
mesh geometry, not a simple GL scissor call. If serialized single-BO reuse
passes, first test SHM-like texture upload and sampling into that same output,
then partial updates/clipped geometry as needed.

The current backend defines `GFXSTREAM_OUTPUT_SLOT_COUNT` as **6**
(`libweston/backend-headless/headless.c:70`), not 3. A three-slot follow-up
would only be a reduced stress test, not exact Weston parity. Qualifying
multi-output scheduling must use the actual six-slot behavior. This corrects
the earlier proposed three-slot checkpoint without changing the >59 FPS goal.

## Serialized single-BO reuse: 120 iterations pass

Artifact SHA `de2f3264ea0de7aaa5bdf875be967f56ac9ccced0a439bdd20468f8e3f613937`
and source SHA `ce208fe2fee0149490f6c121b58736057b1b09baeb38e1018feee1f384277dfc`
passed normal/Werror builds and independent review. Runtime selectors were
`UDROID_GBM_PROBE_DRAW=1 UDROID_GBM_PROBE_FRAMES=120`. BO, writer EGLImage,
renderbuffer, presenter import, shader program and VBO remain persistent;
each iteration changes an active uniform, creates a fresh native fence, then
retires both completion and release before the next write.

All 120 iterations reported GL_NO_ERROR, successful present, and signaled
presentation/release fences; exit was 0. Mid-run and final captures show
different phase patterns. Sharp wraps in this pattern are intentional shader
`fract()` boundaries, not by themselves evidence of corruption. The final
capture's pattern matches the final phase 0.669291; the independently updated
HUD counter can lag. No runtime driver library changed.

The first launch exited 1 before allocating/rendering because the freshly
started host socket was not ready. After checking host PID and socket presence,
the guest was rerun against that same host; no active test was interrupted.
This is a launch readiness race, not a graphics failure. Future launch scripts
must wait for host readiness before entering PRoot.

The Android surface was portrait 1080x2400 for this run; the imported BO stayed
720x720. Do not compare its timing directly to earlier landscape desktop runs.
Two screenshots and ownership logging were enabled, so this is diagnostic
evidence, not an overhead-free benchmark.

Stage wall latency over non-final frames 0..118 (119 samples):

| Stage | Mean ms | P95 ms | Max ms |
| --- | ---: | ---: | ---: |
| Draw API calls | 0.088 | 0.265 | 2.507 |
| Native fence creation + flush + duplicate | 9.702 | 16.180 | 72.841 |
| Presenter call | 1.063 | 2.268 | 3.289 |
| Presentation completion wait | 1.991 | 4.330 | 8.462 |
| Release wait | 0.564 | 1.120 | 8.204 |

These are monotonic wall durations, not GPU durations or thread CPU time.
Rendering is deferred, so the large native-fence stage cannot be attributed
to the fence syscall alone. Excluding the first ten frames still gives 9.344 ms
mean and 15.469 ms P95 for that stage. The final release wait is excluded
because the preceding capture hold biases it low.

SurfaceFlinger timestats successfully identified the exact SurfaceView BLAST
layer `#4678`: 88 recorded frames, `averageFPS=57.629`, and present2present
buckets 16ms=82, 33ms=5, 50ms=1. This is a short, quantized layer measurement,
not proof that all 120 submissions scanned out. Its `totalTimelineFrames=0`
also means zero reported timeline jank is not a jank-free qualification.
The capture was disabled after collecting the dump. Sustained >59 FPS in
Weston/KDE remains unproven.

Evidence files under `evidence/2026-09-10-barrier-route/`:
`udroid-gbm-reuse-ready-20260910.log`, `udroid-gbm-reuse-{mid,final}-20260910.png`,
`udroid-gbm-reuse-timestats-20260910.txt`, and `udroid-gbm-reuse-host-20260910.log`.
The initial readiness failure is retained as `udroid-gbm-reuse-20260910.log`.

Next: one-shot SHM-like BGRA texture upload and sampling on the proven VBO draw
path. Keep partial uploads, indexed/client-array geometry, and six-slot
scheduling separate so a failure identifies a narrower cause.

## SHM-like texture upload and sampling: visible pass

Final reviewed artifact SHA
`cc71e7946c4f7d51538920b3bf0a34a85997e2d1322849ac5299c0889b193070`
and source SHA `fbc7f24098b14787e2ad043c8c717cceb8582df7f1ace792928ef37f6cba668a`
passed normal/Werror builds and were hash-verified before device use. An
earlier intermediate artifact `954118...` was replaced during review and was
not deployed. The runtime used DRAW=1, TEXTURE=1, FRAMES=1 with the unchanged
matched guest/Zink/GBM/helper stack. Host socket presence was checked before
launch; there was no readiness failure on this run.

The upload uses 257x193 XRGB8888 CPU pixels, tight stride 1028, GL_BGRA_EXT for
both internal and external format, GL_UNSIGNED_BYTE, and unpack row length
257 restored to zero afterward. Tight rows match simple-shm's width*4 stride;
padded rows were deliberately not introduced. NEAREST filtering and clamp
make the non-power-of-two texture complete. The fragment shader samples RGB
and forces alpha 1, ignoring the source X bytes (00/a5).

Both required extensions are present. Shaders compile and link, sampler unit
0 is active, and upload and sampled draw separately report GL_NO_ERROR. Both
output fences signal POLLIN; exit is 0. The hold-triggered screenshot shows
the expected top-red/middle-green/bottom-blue bands, cyan vertical marker,
top-left monochrome checker and bottom-right magenta/white checker. This
proves the full-upload/sampling control, not partial updates, indexed geometry,
multiple output BOs, or desktop frame rate.

Evidence under `evidence/2026-09-10-barrier-route/`:
`udroid-gbm-texture-20260910.{log,png}` and
`udroid-gbm-texture-presenter-20260910.log` (app presenter tag only).
The single cold native-fence/flush sample is 56.457 ms; it includes deferred
rendering and must not be treated as a steady-state performance measurement.

## Client-memory indexed geometry: visible pass

Reviewed artifact SHA
`8a7c3a18b876dbdf4e8638355708a7fa8c025779f034031239a06fc2e0781aa0`
and source SHA `bb55cc7e5f7e0724922b0e8577e01bac3119e98496175fa14a2ee7fd3754cbe6`
were used with DRAW=1, TEXTURE=1, CLIENT_ARRAYS=1 and one frame. The probe
verified an actual OpenGL ES 2 context, bound both array-buffer targets to
zero, and submitted the persistent client-memory quad with
`glDrawElements(GL_TRIANGLE_STRIP, 4, GL_UNSIGNED_SHORT, ...)` and Weston's
index order `{0, 3, 1, 2}`.

Texture upload and indexed draw both reported GL_NO_ERROR. Presentation and
release fences signaled POLLIN, the recovered log ends `[pass]`, and the hold
capture shows the expected colored bands, cyan marker, and checker fiducials.
The original terminal exit status was not recovered. No indexed guest remained
when checked; the host lifecycle is not inferred from its process name. This
combined control changes client vertex sourcing, indexed submission, and strip
topology together; a failure would have required separate indexed-VBO and
client-array controls before attribution.

Evidence under `evidence/2026-09-10-barrier-route/`:
`udroid-gbm-indexed-20260910.{log,png}`.

Next: run the actual Weston scene with repaint diagnostics enabled and inspect
its existing import, draw, damage, repaint, presentation, and release evidence.
The synthetic controls now cover the first-frame upload/sampling and indexed
client-memory geometry paths, so further synthetic expansion is not the next
discriminator.

## Actual Weston scene: opaque fade curtain remains

The unchanged matched Weston stack ran for the bounded 90-second session and
was stopped by the intended timeout (status 124). Its diagnostics reached
4,200 presents and 4,200 releases with `no-free-slot=0`; the rendering and
presenter pipelines therefore continued running throughout this observation.

All three scene-graph samples show the same full-output, fully opaque black
desktop-shell fade surface at the top layer. Beneath it, the panel is mapped,
the 250x250 `weston-simple-shm` XRGB8888 surface is mapped and fully opaque,
and the ARGB8888 desktop background spans the output. The initial and final
captures remain visually covered by the fade surface. This establishes that
the client content reached Weston's scene graph while a compositor overlay
continued to obscure it; the capture alone does not establish why that
animation failed to retire.

Evidence under `evidence/2026-09-10-barrier-route/`:
`weston-scene-20260910.{stdout,weston.log,scene.txt,scene-later.txt,scene-final.txt,png,final.png}`
and `launch-weston-scene-diag.sh`.

Next: use the separately reviewed headless presentation-clock candidate to
test whether supplying a local presentation-clock sample allows the existing
desktop-shell animation and client frame-callback clocks to advance. Treat
that timestamp as an event-processing estimate with no hardware/VSYNC flags,
not a measured Android latch time.

## Headless presentation-clock correction: visible scene and callbacks

The reviewed candidate changes only the successful gfxstream presentation
fence handler. It samples Weston's presentation clock when the event is
processed and calls `weston_output_finish_frame()` with that timestamp and
flags 0, following the nested Wayland backend's fallback contract. It does not
claim a hardware latch or VSYNC timestamp. Candidate
`headless-backend-clock-20260910.so` has SHA
`8b65997b487a64f1d91adcd324db0d7cc3b2e405680827a82d45cb72c7f6fd21`;
the preserved baseline remains SHA
`703c012afc65ddc13ace96ee58a04dd2f3138a8e63de16683e82b113dafaf37b`.
Both require the same five presenter symbols and the same
`libudroid_gfxstream_present.so.0` SONAME. The runtime helper was independently
read back as SHA
`511b27551ed22567a0c3a97ca46c76757b0910217983b90e8725e18fd4a13f5e`
and exports all five.

With the candidate, the live scene sample has no view in the fade layer. The
desktop, panel, and `weston-simple-shm` window are visible; the screenshot
shows the test client pattern. This matched differential supports the
NULL finish timestamp as the cause of the frozen shell fade and client frame
callbacks in the baseline. It does not resolve every rendering issue: panel
text is upside-down and located at the bottom, so an output-orientation defect
remains.

The 90-second diagnostic session ended with the intended timeout status 124.
The last aggregate has 2,400 presents, 2,400 releases, and
`no-free-slot=0`. Client protocol logging recorded 2,420
`wl_callback#11.done` events for the simple-shm frame callback. Across 2,419
strictly positive timestamp deltas, mean spacing was 36.945 ms, P95 50 ms,
and maximum 762 ms; the last 500 averaged 36.894 ms, P95 49 ms, maximum
162 ms. This is about 27 client callbacks per second under cold startup and
verbose diagnostics, not measured display FPS.

Final cumulative diagnostics reported GL `create-sync` averaging 15,979 us,
finish-to-repaint averaging 12,607 us, and full repaint averaging 19,909 us.
The run had verbose diagnostics enabled; their overhead has not yet been
quantified. These are measured wall-time stages, not isolated GPU time. No new
assertion or fault occurred; the EGL query-device warning predates this change.
Renderer, driver, GBM, and presenter runtime libraries were unchanged.

Valid live evidence under `evidence/2026-09-10-barrier-route/`:
`weston-clock-20260910.{stdout,weston.log,scene.txt,png}` and
`launch-weston-clock-diag.sh`. A scene request and screenshot attempted after
the timeout are excluded because no Wayland display remained.

Next: isolate the output-orientation defect and then measure an uninstrumented
sustained workload. Neither the diagnostic callback rate nor HUD completions
qualify the remaining greater-than-59-FPS goal.

## Scheduler A/B measurement gate

The first quieter `repaint-window=7` attempt is not a benchmark. Collection
started about 74 seconds after Weston, so only about 33 seconds of its requested
60-second SurfaceFlinger window overlapped the live compositor. The run is
excluded from scheduler and displayed-FPS comparisons; no rate is derived from
that partial window.

The next comparison keeps the visible clock-corrected stack unchanged and
varies only Weston's configured repaint window between 7 ms and 16 ms. A
single host collector owns launch, a minimum 12-second warmup, the first fresh
complete 120-frame diagnostic aggregate, a bounded 60-second SurfaceFlinger
window, foreground checks, live capture, and PID/start-time-validated cleanup.
This prevents host/tool latency from moving measurement or capture beyond the
guest lifetime. Each arm fails closed if any gate is missing.

This A/B isolates only Weston's scheduler contribution. Even if the 16 ms arm
recovers roughly the previously observed 10 ms finish-to-repaint delay, a
sub-16.95 ms displayed frame remains unproven because native-fence creation in
the visible instrumented run averaged roughly 12--16 ms. After selecting the
better visible arm, re-enable the existing scoped Zink/gfxstream timing probes
on that same workload and compare matched windows. Do not infer a transport
residual by subtracting older, unpaired captures. Queue submission, per-DMA-BUF
implicit synchronization attachment, and the explicit acquire fence remain
part of the correctness contract.

The source-grounded vertical-orientation diagnosis and its opt-in consumer-flip
A/B remain separate. The inverted output does not prevent a visible-frame
scheduler comparison, but it must be corrected before KDE qualification.

## Valid scheduler comparison: 16 ms wins, synchronization remains dominant

The atomic collector randomized the order to 16 ms then 7 ms. Both arms passed
the 12-second warm gate, exact foreground checks, a 60-second SurfaceFlinger
window, live PNG capture, matching frame/present/release counters, zero slot
starvation, and PID/start-time/socket-validated cleanup. The combined manifest
and per-arm evidence are under
`evidence/2026-09-10-barrier-route/scheduler-ab-20260911-run1/`.

| Metric | 16 ms repaint window | 7 ms repaint window |
| --- | ---: | ---: |
| SurfaceFlinger layer frames | 2,305 | 1,655 |
| SurfaceFlinger reported average | 39.078 FPS | 27.784 FPS |
| Weston interval frames | 2,400 | 1,680 |
| Inter-repaint mean | 25.359 ms | 36.122 ms |
| Finish-to-repaint mean | 3.974 ms | 12.780 ms |
| Full repaint mean | 17.854 ms | 19.620 ms |
| GL native-fence creation mean | 14.457 ms | 16.100 ms |

The larger repaint window schedules the next repaint earlier and improves the
displayed layer by 11.294 FPS. It removes 8.806 ms from finish-to-repaint and
is the selected arm for the next probe. It still does not approach the target:
the 16 ms arm's complete compositor interval is 25.359 ms and its full repaint
alone exceeds the approximately 16.95 ms budget required for greater than
59 FPS. Native-fence creation is 81% of that full repaint mean and is the
largest measured stage.

Next, run the selected 16 ms arm with the already implemented Zink/gfxstream
sync timers enabled. Use matched windows to locate the persistent wait among
batch dispatch, submission-worker completion, semaphore FD export, DMA-BUF
sync-file attachment, and cleanup. Preserve submission and both implicit and
explicit synchronization until that evidence identifies a safe narrower
optimization.

## Selected-arm timing: submission completion is the main wait

The opt-in timing rerun of the selected 16 ms arm is valid and archived under
`evidence/2026-09-10-barrier-route/scheduler-timing16-20260911-run1/`. Its
60-second layer result is 2,336 frames / 39.664 FPS, while the matched Weston
interval contains 2,400 frames. Inter-repaint averages 24.867 ms, full repaint
17.574 ms, and GL native-fence creation 14.226 ms.

Subtracting the cumulative 720-frame warm sample from the 3,120-frame final
sample isolates the same 2,400-frame interval. The overlapping (not additive)
Zink stages include 3.102 ms in queue submit, 4.261 ms in post-submit
housekeeping, 2.045 ms ending the sync batch, and 8.443 ms waiting for the
submission worker. Fence-flush dispatch is 11.713 ms and the DRI native-fence
flush is 13.851 ms, consistent with submission completion dominating native
fence creation.

The 20 adjacent 120-sample DMA-BUF reports average 3.512 ms for the complete
`GetSemaphoreFdKHR` path, 0.020 ms to acquire the DMA-BUF fd, and 0.482 ms for
the actual `DMA_BUF_IOCTL_IMPORT_SYNC_FILE` implicit-sync attachment, with no
failures. The full 3.512 ms path includes semaphore export and preceding
submission work; it is not the implicit-attachment cost.

`EGL_EXT_image_implicit_sync_control` selects whether prior accesses are
synchronized when an external buffer is imported as an EGLImage. It does not
replace synchronization needed to export current rendering to the next
consumer. Current Mesa and the checked upstream Mesa tree contain the registry
and header tokens but no implementation, so this extension is not the outbound
optimization.

The next experiment is an opt-in native-fence export when queue submission is
ready, while retaining the DMA-BUF implicit attachment. Keep the default path
and its synchronization behavior unchanged as the baseline.
