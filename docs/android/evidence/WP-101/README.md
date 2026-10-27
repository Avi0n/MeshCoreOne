# WP-101 execution evidence

Reference: `db14559b39d32322b06477c6ae676112f583db50`.
Implementation base: merged foundation `979dd73b2fc3cb5cbeea07b1c809f5c9283289ee`.

The actual primitive suite discovered **84 JUnit cases**, with no failures,
errors or skips:

| Suite | Cases |
| --- | ---: |
| Byte/value/text/display helpers | 44 |
| Path decoding/encoding | 19 |
| Transport capability/forwarding/termination/cancellation | 5 |
| Domain/configuration/identity/errors/derivation | 13 |
| Complete named wire tables and widths against pinned Git objects | 3 |

The four assigned original test files have 54 test declarations, including
parameter-family loops. Their byte padding, UTF-8, formatted hex, path and
transport-default scenarios are represented in the corresponding Kotlin tests.
Additional assertions cover copying/content hash identity, checked truncation,
all 256 path encodings, raw unknown types/codes, signed high bits, grapheme limits,
all named command/response/subtype values and the complete category/width tables.
The 84 Kotlin count is not described as 84 original Swift declarations.

Actual local tasks:

```powershell
& .\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 640m `
  -GradleArguments @(':core:protocol:test', 'validateModuleGraph', `
    'resolveScaffoldDependencies', 'runtimeDependencyInventory', `
    '--write-locks', '--dependency-verification', 'strict', '--quiet')
```

**Result: passed** after the exact runtime-publication metadata additions below.
Dependency locks were updated for the new coroutine API dependency. Final checks
must run without lock/metadata generation; hosted checks bind the committed head.

Wire-table tests read immutable original Git objects, not candidate Kotlin code.
Name/scope SHA-256 expected values were computed independently using Python's
standard-library SHA-256 over explicit UTF-8 strings. No candidate implementation
was used to generate its expected values.

| Publication POM | Independently verified publisher SHA-256 |
| --- | --- |
| `kotlinx-coroutines-core:1.10.2` | `519da5400096f3462a4dae80783ad050413d4bc81ec7ae53fae76aef0ccbed4c` |
| `kotlinx-coroutines-core-jvm:1.10.2` | `658f576b96c832e7382406adb197d64d8e2e97de10e96dfa3700decfa2a60def` |
| `kotlinx-coroutines-android:1.10.2` | `b92d3672e7f9e8f4c4e2ab187c3e31fecc506496396f4a5f27ee1c957a42b319` |

Two earlier runtime-inventory executions failed on missing POM checksums. Those
failures were not converted to success or skipped; the matching publisher
artifacts were verified and the strict complete task set was rerun successfully.
Existing JAR/module pins were unchanged.

This is primitive/library evidence, not BLE, WiFi, session, cryptographic-message,
backup, physical-device or application-feature parity. The independent WP-004
case/vector infrastructure and later integration suites remain separate work.

The first main-targeted hosted run exposed a coupled legacy-bootstrap omission:
WP-003's YAML-dependent controller tests ran without the pinned parser in the
older WP-000 workflow. That workflow now installs the same hash-verified
requirements before running assertions, checks out the actual head and uses the
declared Python version. The failed run is not represented as successful.

`android-ci.yml` selects `:core:protocol:test` in its single Linux Gradle
invocation. Its protocol validation reparses actual
JUnit reports and requires at least 84 successful, unskipped cases. Existing
scaffold-suite counts are not substituted for this new library evidence.

## R118 negative-hop boundary (#375)

The pinned `encodePathLen` source validates hash size, clamps hop count to 63,
then converts to `UInt8`. A negative hop count traps during that conversion;
Swift exposes no recoverable error/result contract for it. Android's encoder
already rejects negative hop counts with `IllegalArgumentException` before
packing, and `PathEncodingTest` now gives this assertion its own test case,
separate from invalid hash-size assertions. This is an explicit native
fail-fast adaptation; it does not change valid bytes, the upper clamp, or claim
an additional frozen original-family mapping.

The 84-case count above is the historical run for implementation base
`979dd73b2fc3cb5cbeea07b1c809f5c9283289ee`. This change splits one existing
test method, so the exact candidate count must come from its hosted run; no
new local test execution or candidate result is claimed here.
