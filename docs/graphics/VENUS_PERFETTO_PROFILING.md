# Venus and Zink Perfetto profiling

Use this trace when a Venus or Venus/Zink desktop feels slow, misses frames,
or stalls while the renderer still appears healthy. It separates guest work,
Lorie presentation, and Android composition instead of treating one FPS number
as the cause.

## Capture

1. Select **Venus (experimental)**, start the desktop, and open **Display**.
2. Start a continuously rendered workload. A static desktop or cursor-only
   movement is not a useful graphics baseline.
3. Record the 15-second trace while reproducing the slowdown:

```sh
adb shell perfetto --txt -c - \
  -o /data/misc/perfetto-traces/udroid-venus.perfetto-trace \
  < tools/profiling/venus-perfetto.pbtxt
adb pull /data/misc/perfetto-traces/udroid-venus.perfetto-trace .
```

Open the trace in [Perfetto UI](https://ui.perfetto.dev/). Keep the workload,
resolution, compositor setting, graphics profile, device temperature, and app
build identical when comparing two traces.

The config records the uDroid process, CPU scheduling and frequency, Mali job
submission/completion, GPU frequency and memory, SurfaceFlinger frames and
layers, and FrameTimeline. It intentionally does not request
`gpu.renderstages`: the Pixel 6a used for this checkpoint does not publish that
data source. Check another device with:

```sh
adb shell perfetto --query --long
```

Unavailable vendor ftrace events may be omitted by that device. Keep the
portable scheduling, SurfaceFlinger, and uDroid trace sources enabled.

## Read the trace

Find these tracks in order:

1. The guest workload and `virgl_render_server` threads: look for runnable but
   unscheduled gaps or a saturated CPU.
2. `org.randomcoder.udroid.dev`: expand the Lorie renderer thread and inspect
   `uDroid/Lorie draw`, `root fence wait`, `eglSwapBuffers`, and
   `post-swap fence wait` slices.
3. GPU frequency and Mali job tracks: confirm whether the GPU is busy while a
   Lorie wait is open.
4. The uDroid `SurfaceView` under SurfaceFlinger: compare buffer arrival with
   expected and actual presentation.

A stable 16.67 ms interval is one frame at 60 Hz, not evidence of 16.67 ms of
rendering. Investigate intervals that repeatedly become 33.3 ms or longer,
long fence slices, scheduling gaps, or buffers that arrive after their desired
presentation time.

FrameTimeline does not attribute every `SurfaceView` producer correctly on
every Android release. Treat it as Android composition evidence and correlate
it with the Lorie slices and SurfaceFlinger layer rather than using it alone.

Use **Query (SQL)** for a reproducible summary:

```sql
SELECT name,
       count(*) AS frames,
       round(avg(dur) / 1000000.0, 3) AS avg_ms,
       round(max(dur) / 1000000.0, 3) AS max_ms
FROM slice
WHERE name LIKE 'uDroid/Lorie%'
GROUP BY name
ORDER BY name;
```

```sql
SELECT present_type, on_time_finish, jank_type, count(*) AS frames
FROM actual_frame_timeline_slice
GROUP BY present_type, on_time_finish, jank_type
ORDER BY frames DESC;
```

```sql
SELECT min(value) AS min_khz,
       round(avg(value), 1) AS avg_khz,
       max(value) AS max_khz
FROM counter
JOIN counter_track ON counter_track.id = counter.track_id
WHERE counter_track.type = 'gpu_frequency';
```

## Pixel 6a checkpoint

On Android 17 with uDroid `0.1.4+dev`, a 15-second Venus/Zink `glxgears`
capture produced 898 Lorie frames and 899 on-time SurfaceFlinger presents with
no reported jank. Lorie draw averaged 4.37 ms; the root-fence wait averaged
1.39 ms, `eglSwapBuffers` 0.79 ms, and the post-swap wait 0.73 ms. GPU
frequency averaged 171 MHz and peaked at 434 MHz.

That run held the 60 Hz presentation budget. It also emitted per-frame
`writeReleaseFence ... Broken pipe` messages, so treat that message as a
separate Android buffer-release-channel compatibility problem, not proof of a
slow frame by itself.

## Comparison gate

Use at least three matched captures. A change is an improvement only if it
reduces p95/p99 missed-frame intervals or CPU/GPU work without introducing
corruption, transparent frames, resize failures, or teardown hangs. Do not
promote a Zink environment option from a single FPS result.

References: [Perfetto FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline),
[GPU tracing](https://perfetto.dev/docs/data-sources/gpu), and
[ATrace](https://perfetto.dev/docs/data-sources/atrace).
