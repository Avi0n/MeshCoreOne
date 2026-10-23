# WP-504 executable performance harness

This is measurement infrastructure, **not measured performance acceptance**.
Budgets must be established from actual measurements with WP-001/WP-504 review
([plan](../PORTING_PLAN.md), lines 543-544); no numeric budget is invented here.
Source remains pinned to `db14559b39d32322b06477c6ae676112f583db50`.

## Targets and workload boundaries

Ordinary `release` uses R8 full mode (the AGP default), resource shrinking and
optimized default/consumer rules. There are no blanket package keeps.
`benchmark` copies release optimization, remains nondebuggable, is shell-profileable
and test-only, and uses the isolated `com.meshcoreone.android.benchmark` package.
Its ephemeral debug signature is for an internal test APK, not release signing.
`profile` has the same isolated identity but disables shrinking/obfuscation so
collected class names can be consumed by the ordinary release R8 build.
Fixture code/manifest exist only in those two non-shipping variants.

The independent `:benchmark` instrumentation library has a distinct
`com.meshcoreone.android.benchmark.runner` host identity. AGP installs its test
host; it deliberately measures an externally installed target. Keeping the
existing library plugin avoids changing the product module graph or requiring
a baseline-profile Gradle plugin merely to execute `BaselineProfileRule`.
AndroidX Benchmark **1.5.0** is the stable Google Maven publication, not the
newer alpha. Exact resolved metadata is locked and SHA-256 verified.
See [`NOTICE`](../../../android/benchmark/NOTICE) and the actual
`benchmarkDependencyInventory` output for all test-runtime license inputs.
The generated [`dependency-notices.tsv`](../../../android/benchmark/dependency-notices.tsv)
records the complete 51-component development runtime and every declared/inherited
license POM. All legal-review fields remain pending; this is dependency input
metadata, not a duplicate CI-result bundle or compatibility approval.
Generated app locks preserve every existing version binding. Only the producer's
three instrumentation classpaths move JetBrains annotations from 13.0 to the
actually resolved 23.0.0 alongside the newly introduced benchmark runtime.

`Workload` is the single generator used by producer and target. Version `wp504-v1`
has 10,000 uniquely identified messages at fixed Unix seconds, alternating
directions, six text families (plain, long, CJK, RTL, multiline and URL/mention),
and 1,000 uniquely identified points on a fixed coordinate grid. A canonical
SHA-256 is asserted by the on-screen readiness contract. These counts are
workload sizes, **not performance thresholds** or captured user/radio data.

| Journey | Actual exercised behavior | Not claimed |
| --- | --- | --- |
| Cold startup | Actual manifest launcher, real process graph, `StartupTimingMetric`, cold process launch | A full cached-production-database startup or TTFD where the launcher does not report it |
| Timeline | Actual Room schema/mappings, repository paging, `ChatViewModel`, production `ChatTimelineRoute` and gestures that must load older pages | Connected radio, composer/send/retry or WP-308 rich-content parity |
| Map | Real MapLibre Native, local style, clustered GeoJSON points, fully rendered frame and camera gestures | Production feature-map/provider/offline-tile parity or provider latency |

The timeline database is separately named `wp504-wp504-v1.db` in the isolated
package. Preparation upserts deterministic rows in a real transaction and
checks every stored ID/text/timestamp/read flag **outside measurement**.
The screen checks the stored count and loads real pages; it never seeds during
composition. Its process-owned store uses the existing container's process
scope, not a radio connection scope. It cannot send to a radio.
The self-authored map style has no external tiles, fonts, sprites or network
dependency; readiness requires a fully rendered native frame and an actual
rendered dataset cluster/point within the viewport, not just a loaded style.
Pan gestures alternate left/right over the same grid instead of repeatedly
moving away from all dataset points.

## Build and discovery

At the repository root, the existing serialized WSL adapter supports these
explicit optional stages without changing the normal four-stage merge check:

```powershell
python .\tools\android-port\local\check.py --distribution Ubuntu-22.04 `
  --commit HEAD --stages preflight,benchmark
```

The declared `benchmark` stage runs the actual
`:benchmark:verifyBenchmarkHarness validateModuleGraph` tasks with strict
dependency verification. The harness runs eight workload test cases and synthetic host-reader tests, compiles
the instrumentation producer, and assembles benchmark/profile/ordinary release.
The reader rejects missing/zero/skipped/failed/malformed JUnit, absent R8 mapping,
a debuggable/non-profileable/non-test-only measurement target, wrong identity/SDK
bounds, fixture/profileable leakage into ordinary release, missing MapLibre ELF
and failed APK/ELF 16 KiB alignment in every target and the instrumentation
producer. Both fixture APKs require their correct typed profile-source flag;
ordinary release must not be test-only. Build-only success explicitly reports that
performance, profiles, StrictMode, ANR, battery and native runtime are unverified.

The CI executor also declares `ci.py run --stage benchmark`, using the existing
explicit state/output, environment stripping and task-graph guard. These are
executable infrastructure commands, not an amendment declaring the entire
WP accepted. The deployed
[WP-003 software-command amendment](../evidence/WP-003/software-verification-manifest-amendment.json)
now declares preflight and the benchmark stage in the WP-504 manifest entry.
`configured:false` intentionally denotes unfinished **full WP acceptance**,
not unavailable software commands. The WP-003 owner maintains that supported
configuration; device acceptance is separate from a bounded tooling/build merge.
The existing hosted scaffold executes `verifyBenchmarkDataset` through
`verifyScaffoldTests`; zero dataset assertions cannot silently pass. It now also
executes `:benchmark:verifyBenchmarkMeasurements`, the strict existing Python
runner over `android/benchmark/tests`. Missing/zero/skipped/failed reader suites
fail the Gradle task. Run just those deterministic **synthetic** reader tests with:

```powershell
python .\tools\android-port\controller\test_runner.py --tests .\android\benchmark\tests
```

After an admitted dependency change only, the optional
`--stages benchmark-dependencies` runs that same actual build with
`--write-locks --write-verification-metadata sha256`. It is checksum/lock
generation, not a strict verification result. Return only its generated
in-scope metadata to this producer's worktree, then run `benchmark` strictly.
Do not edit locks by hand or use another worker's caches/worktree.

Because this changes the dependency/build graph, merge requires exact-candidate
complete verification, not only the optional harness stage. Run the existing
complete local check when the serialized lane is available:

```powershell
python .\tools\android-port\local\check.py --distribution Ubuntu-22.04 --commit HEAD
```

The default remains `python,preflight,scaffold,inspect`; the benchmark stage does
not replace its actual module tests, lint, traceability and APK inspections.

Ordinary feature-branch publication still uses the installed pre-push hook.
Missing, failed or queued local checks record the exact commit/tree as
**UNVERIFIED** and do not block normal publication. Advisory checks wait up to
10 seconds for the shared lane and allow 180 seconds of execution, followed by
owned-child cleanup while retaining the lane. These are publication latency
bounds, not Android performance budgets. Full/manual checks remain strict and
serialized without those advisory deadlines; hosted exact-head verification
and applicable human/protected gates remain mandatory before merge. Synchronize
helpers only through the supported reservation runtime installer; never bypass
the hook or disturb a foreign lane holder.

## Device execution and genuine profiles

Use the installed pinned JDK/SDK, explicit private directories, and the existing
shared native/Gradle/device execution locks. The runner must have an actual
authorized device endpoint. Do not suppress emulator, debuggable, low-battery,
thermal or profile errors. Performance measurements need physical supported
devices; emulator profile generation/runtime observations remain labeled emulator.
The following are the module's actual AGP tasks; execute them only in the
supported runner and record the discovered cases, device/build and raw outputs.

1. Install the **test-only profile APK** with the authorized device tooling
   (`adb install -t -r`), then run `:benchmark:connectedDebugAndroidTest` with
   `-Pandroid.testInstrumentationRunnerArguments.class=com.meshcoreone.android.benchmark.BaselineProfileGenerator`.
   Both startup and timeline/map cases must execute and produce nonempty stable
   profile output. The target metadata rejects collecting from the obfuscated
   benchmark APK. Startup-only rules are separate; fixture classes are filtered.
2. Retain the generated, sanitized profile inputs and bind them to the exact
   source/dataset/device. Admit the resulting nonempty application baseline rules
   at `android/app/src/main/baseline-prof.txt` and genuine startup rules at
   `android/app/src/main/startup-prof.txt`; rebuild the ordinary release and benchmark
   targets. Do not commit a fabricated or empty profile.
3. Install the **R8 benchmark APK**, then run `:benchmark:connectedDebugAndroidTest`
   with `-Pandroid.testInstrumentationRunnerArguments.class=com.meshcoreone.android.benchmark.PerformanceBenchmark`.
   Nine cases compare `None`, `Full` and `Partial(Require)` for startup, timeline
   and map, with ten iterations each. Missing embedded baseline profiles fail
   the required Partial mode rather than falling back to warmup/JIT success.
   APK metadata also requires a real application-specific source input before
   Partial cases start: built-in dependency profiles alone cannot satisfy this.
   A present input without application rules fails the build. Input presence
   does not replace device provenance/admission or prove actual profile collection.
4. On an actual 16,384-byte-page runtime, separately select
   `com.meshcoreone.android.benchmark.NativePageRuntimeTest`. Its native-rendering
   assertion must execute. The kernel shell check and the target process's native
   `Os.sysconf(_SC_PAGESIZE)` must both report 16,384; a kernel-only value is not
   sufficient. A native rendered dataset feature must also satisfy readiness.
   Static `zipalign -P 16`/ELF checks are not substitutes.
   Record OS/API/ABI/page size and compatibility-mode facts, and distinguish
   emulator observations from physical-device evidence.

Install commands operate on an isolated test APK only; no updater or APK
installation behavior is added to the product. No human-owned signing key is
accessed. Do not uninstall/clear an existing user's ordinary application data.

## Importing actual measurements

[`measurements.py`](../../../android/benchmark/measurements.py) consumes the raw
AndroidX **1.5.0** `BenchmarkData.json` plus the corresponding successful
instrumentation JUnit XML and the exact installed benchmark APK. Its schema
and compilation labels come from the published
[`benchmark-common` source](https://dl.google.com/dl/android/maven2/androidx/benchmark/benchmark-common/1.5.0/benchmark-common-1.5.0-sources.jar)
(`json/BenchmarkData.kt`, `MetricResult.kt`, `Arguments.kt`, `ResultWriter.kt`) and
[`benchmark-macro` source](https://dl.google.com/dl/android/maven2/androidx/benchmark/benchmark-macro/1.5.0/benchmark-macro-1.5.0-sources.jar)
(`Macrobenchmark.kt`, `CompilationMode.kt`, `Metric.kt`). In particular,
`Partial(Require)` with no warmup is named **`BaselineProfile`**, not `Partial`,
in the parameterized output. Frame metrics are nested per-iteration sample
arrays under `sampledMetrics`, not scalar frame percentiles.

Before the authorized nine-case device run, record the actual candidate commit,
benchmark APK SHA-256, and canonical workload SHA-256 from the verified
`WP504_READY:prepare:wp504-v1:<digest>` readiness contract. Do not substitute a
made-up digest. Forward these values through AndroidX's actual
`androidx.benchmark.output.payload.*` instrumentation arguments. For example,
inside the authorized runner/device lane, with `$datasetSha` obtained from that
actual readiness contract:

```powershell
$sourceCommit = (git rev-parse HEAD).Trim()
$benchmarkApk = '.\android\app\build\outputs\apk\benchmark\app-benchmark.apk'
$targetSha = (Get-FileHash $benchmarkApk -Algorithm SHA256).Hash.ToLowerInvariant()
$payload = '-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.'
.\android\gradlew.bat -p .\android :benchmark:connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.meshcoreone.android.benchmark.PerformanceBenchmark' `
  "${payload}wp504.commit=$sourceCommit" "${payload}wp504.apkSha256=$targetSha" `
  "${payload}wp504.dataset=wp504-v1" "${payload}wp504.datasetSha256=$datasetSha"
```

This is the already-declared device task, **not executed by the host-reader
tests**. Its existing profile requirement and unsuppressed device checks still
apply. Select the real files exported by that one run (not a guessed output
filename or another device's report). Then, with `$rawBenchmarkJson` and
`$instrumentationJunit` pointing at those exports:

```powershell
python .\android\benchmark\measurements.py --results $rawBenchmarkJson `
  --junit $instrumentationJunit --apk $benchmarkApk --commit $sourceCommit `
  --dataset-sha256 $datasetSha --device-kind physical
```

Use `--device-kind emulator` for emulator diagnostics; it never becomes physical
timing/battery evidence. There is no implicit device type or output file. The
reader prints the imported context, all raw iterations, descriptive
min/max/P50/P90/P95/P99, and per-iteration plus pooled frame distributions.
Percentiles use the pinned AndroidX linear-interpolation convention. Pooled
frames weight iterations by their frame counts; inspect per-iteration values
and repeated runs rather than interpreting a pooled percentile as a confidence
bound. Negative `frameOverrunMs` is valid headroom, not an invalid duration.

The reader fails on an incomplete/duplicate/foreign nine-case matrix, missing or
failed/skipped/zero/mismatched JUnit, non-ten iteration arrays, empty frame
samples, malformed/nonfinite values, unknown metric families, tampered summary
statistics, per-iteration disagreement between `frameCount` and CPU/overrun sample
counts, reported thermal sleep, or mismatched supplied source/APK/dataset
bindings. Inputs are bounded and duplicate JSON keys/unsafe XML are rejected.
Different frame counts **between** iterations remain valid; only corresponding
counts within one iteration must agree, as in the pinned AndroidX frame model.
All parser controls are explicitly synthetic and are not retained as benchmark
results. The reader has no benchmark generation or numeric-budget fallback.

**Import consistency is not authenticity or acceptance.** JSON payload and the
device label are caller-supplied. Matching files do not independently certify
same-run provenance, the APK's source commit, profile collection, or a physical
device. AndroidX JSON does not encode error-suppression arguments or establish
battery/thermal health; zero macrobenchmark thermal sleep is not proof that
throttling never occurred. Review the actual unsuppressed logs, controlled
conditions, genuine profiles, exact build provenance and repeatability before
deriving budgets or comparing builds. This tool does not sign off ANR, battery,
StrictMode, native runtime, profile provenance, or any performance threshold.
Retain sanitized non-reproducible device inputs as appropriate; do not make
another reproducible CI-result bundle.

## StrictMode, ANR and battery review

Diagnostic-only fixture launches accept `--ez strictMode true`. They enable
detect-all thread/VM policies with log plus death, never swallowed violations.
Run them separately and force-stop the isolated package before normal timing.
That intent covers code after fixture Activity entry, not Application startup.
For a separate **debug-only startup diagnostic build**, the existing
`:app:assembleDebug` task accepts `-Pwp504StartupStrictMode=true`. The property
accepts only literal `true` or `false` (default); invalid values fail configuration.
It changes only the debug resource `wp504_startup_strict_mode`. Ordinary debug
is disabled by default; release, benchmark and profile always retain `false`,
even when that property is supplied. The runtime also requires a debuggable APK.
Never collect profiles or timings from this diagnostic debug build.

When opted in, the real `MeshCoreApplication.onCreate` installs detect-all
thread/VM policies with log plus death **before** `super.onCreate`, widget-owner
installation, lifecycle callbacks, unlock checks and ensuing container startup.
There is no allow-disk/network exemption, caught violation or automatic fallback.
The observation boundary begins after reading that build gate at `onCreate`
entry: it does **not** cover zygote/framework startup, Application construction/
field initializers, `attachBaseContext`, or providers initialized before
`onCreate`. The thread policy applies to the invoking main thread, not a claim
that all pre-existing worker threads have the same policy; the VM policy is
process-wide. Robolectric API31/API37 tests observe both policies at the real
Application super-call boundary, before any of the claimed app startup work,
and verify disabled/nondebuggable gates preserve existing policies. These are
software ordering/configuration tests, not actual-device violation or clean
startup evidence. Genuine diagnostics still require a separate authorized
execution and log review; the original workload intent behavior is unchanged.

Retain sanitized logcat/Perfetto and actual `dumpsys activity exit-info` observations
for the exact APK and scenario, including process lifecycle, repeated paging,
map attach/detach and background/foreground transitions. Absence of an ANR in a
short component run is not proof of whole-application ANR behavior.

Battery work requires a physical supported device, controlled screen brightness,
refresh rate, network/radio state, thermal conditions, charge level and duration,
with before/after batterystats and an unchanged comparison build. A component
workload does not prove BLE/WiFi/background-radio battery behavior. Record API31
and API37 device differences instead of extrapolating from a JVM simulator.

Review medians/tails/frame metrics and repeated runs only after real raw output
exists. Derive and review budgets from those measurements; compare like-for-like
datasets/builds/devices/compilation and retain regressions. No build log, unit
test or static ELF result is a battery/startup/frame measurement.

## Evidence authority and remaining acceptance

Exact-commit hosted job results/logs are authoritative for reproducible automated
checks. Do not commit/upload/hash a duplicate CI-result bundle. Retain only
non-reproducible device/hardware/profile-source/human/legal evidence as required,
with sanitized identities and no messages/credentials/keys.

Device measurements, actually collected/embedded profiles, measured budgets,
physical battery/ANR, actually observed startup StrictMode and 16 KiB native runtime remain
blocked until genuine execution and review. This harness and its build assertions
do not complete WP-504 or unlock WP-505/506.
