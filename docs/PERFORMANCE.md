# Performance

uDroid measures interaction performance with an Android Macrobenchmark instead
of judging it from debug builds. Compose debug builds include tooling and do not
run the same R8 optimization or ART compilation path as an APK distributed to
users.

The first benchmark covers the first-run Linux catalogue:

- expand **Show all images**;
- compose the newly visible image cards;
- scroll the catalogue;
- record frame CPU duration and frame overrun in Perfetto.

Run it on a connected device:

```sh
./gradlew :baseline-profile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
```

Generate the profiles shipped in the release APK:

```sh
./gradlew :app:generateReleaseBaselineProfile
```

The startup profile covers only app launch. The broader baseline profile also
covers opening and scrolling the Linux catalogue. Keeping those workloads
separate prevents catalogue code from inflating the dex startup layout.

## Pixel 6a baseline

Measured on Android 17 using the optimized APK on 25 July 2026:

| Interaction | P50 CPU | P90 CPU | P95 CPU | P99 CPU | P99 overrun |
| --- | ---: | ---: | ---: | ---: | ---: |
| Expand catalogue and scroll | 4.8 ms | 6.3 ms | 7.2 ms | 16.6 ms | 7.1 ms |

The benchmark uses five repeated iterations and stores a Perfetto trace for
each iteration under the benchmark module's build output. This Pixel result is
a regression baseline, not proof that lower-end devices are fast enough. New
catalogue UI work should be checked on a slower physical device before a stable
release.

## Current safeguards

- Release builds use R8 code optimization and resource shrinking.
- Stable item keys and content types let the lazy list reuse composed rows.
- Catalogue-derived lists are cached instead of filtered during recomposition.
- A distro row uses one metadata text layout rather than several small layouts.
- The distro mark uses primitive drawing rather than first-use font glyph work.
- Baseline and startup profiles are generated from real device journeys.

Keep expensive runtime scenarios small and reproducible first. Add focused
Macrobenchmarks or microbenchmarks for a suspected cost, inspect traces, then
validate the resulting change in the complete user journey.

References:

- [Compose performance](https://developer.android.com/develop/ui/compose/performance)
- [Compose lazy lists](https://developer.android.com/develop/ui/compose/lists)
- [Macrobenchmark overview](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
- [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview)
