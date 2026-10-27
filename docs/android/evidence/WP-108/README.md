# WP-108 transport implementation evidence

## R118 timing-contract repair (#376)

Repository: `cbattlegear/MeshCoreOne-Android`; owner: `protocol-porter`.
Repair branch: `wp108-pending-connect-contract`, dedicated worktree.
Actual repair session: `37f7ca3e-00c8-4c3d-adc8-3a9ac72e4313`.
Base: `8defec5919f5d6e0aee15d5305add42589421f05`.
Swift source/test pin: `db14559b39d32322b06477c6ae676112f583db50`, unchanged.
The existing WP-101 foundation and its #375 repair are merged prerequisites.

The repair targets `PROTOCOL-R118-G12`, with all four original families:

| Original family | Current discriminating observation |
| --- | --- |
| Cancelling connect does not leave it parked | Controlled pending connect at 199/200 virtual ms; cancellation completes under the existing three-second real budget, socket closed once, no blocked IO or reader left; 100 ms callback-suppression interval |
| Initial connect failure does not notify | Original invalid host; actual typed failure; callback awaited for 100 real ms before disconnect clears its registration |
| User disconnect does not notify | Both never-connected and established loopback variants; 100 real ms callback observation; real EOF/closed stream and once-only close retained |
| Idempotent connect does not create another connection | First real accept, redundant connects and 500 ms actual kernel accept timeout; separate extra-connection positive control rejects a queued second accept |

Related coverage adds concurrent callers during a pending connect, then
successful completion on the same socket, and strengthens the replacement
handler's late-old-generation failure observation. Existing peer-close
notification is the positive callback control. Existing mock, framing,
deadline, IO-failure and lifecycle cases are retained, not replaced by these
four cases. Production transport bytes and APIs are unchanged.

See the corresponding [adaptation](../../deviations/WP-108.md) for controlled
versus real-kernel boundaries. This does not claim Apple Network.framework,
external-network, physical-device/radio, Windows or full-platform-matrix
execution. Reproducible hosted results stay on the exact candidate's CI log,
not in another committed result bundle.

### Pre-push execution

Linux, pinned Python 3.12.4 and the existing JDK/SDK installations from
`/ai_cache/meshcoreone/toolchains/a8c624bc-20261026/environment.json`.
The declared `ci.py local-inputs` helper supplied an isolated private root;
no toolchain, dependency, runner or task was added.

From the dedicated worktree, with
`GRADLE_USER_HOME=/ai_cache/meshcoreone/cache/gradle`:

```sh
/ai_cache/meshcoreone/python/3.12.4/bin/python3 tools/android-port/controller/ci.py preflight \
  --state /home/cbattagler/.copilot/session-state/37f7ca3e-00c8-4c3d-adc8-3a9ac72e4313/files/wp108-local-inputs/environment.json \
  --output /home/cbattagler/.copilot/session-state/37f7ca3e-00c8-4c3d-adc8-3a9ac72e4313/files/wp108-local-check
/ai_cache/meshcoreone/python/3.12.4/bin/python3 tools/android-port/controller/ci.py run --stage protocol --scope protocol \
  --state /home/cbattagler/.copilot/session-state/37f7ca3e-00c8-4c3d-adc8-3a9ac72e4313/files/wp108-local-inputs/environment.json \
  --output /home/cbattagler/.copilot/session-state/37f7ca3e-00c8-4c3d-adc8-3a9ac72e4313/files/wp108-local-check
```

Result: readiness passed; declared `:core:protocol:test` discovered and ran
**4,720 tests, all passed; zero failures, errors or skips**. This includes all
**75 transport cases**: 12 mock, 17 frame codec, 25 TCP contract and 21 TCP IO
cases. Current XML confirms the 100 ms callback windows and 500 ms listener
window executed, and the extra-connection positive control failed the
negative assertion as intended.

Also passed, using the same pinned Python:
`tools/android-port/controller/validate.py`,
`tools/android-port/portmap.py`,
`android/parity-tests/audit.py --check`,
`android/parity-tests/semantic.py --check`, and `git diff --check`.
The provenance map accounts for 1,933 Kotlin files; accounting/semantic checks
are static validation, not additional platform or independent parity proof.
The final candidate head and authoritative hosted result belong to the PR.
Historical counts and revisions below describe only the original execution.

## Historical implementation evidence

Repository: `cbattlegear/MeshCoreOne-Android`. Owner: `protocol-porter`.
Project session: `a61f5311-094f-499d-9e15-6d9d5b40d199`.
Branch: `cbattlegear-friendly-goggles`, one dedicated owning worktree.

| Binding | Revision |
| --- | --- |
| Clean initial / implementation base | `050ac6909c65af8e3fc63e5aaf44b1d240f1d8ac` |
| Historical launch-base tested code | `b245da16290b0e9332bb4814c29f206572f00234` |
| Historical launch-base production/APK code | `e82a8cca0d41d87b8da859db14710929d19dff9d` |
| Reconciled merged integration base | `4331a4dddd13126ab05f4b4d74e654f313c5d414` |
| Rebased transport/test code | `b6659cc476a1a9f1a1e661daf69fde0fab9b75ef` |
| Rebased production code | `bec9d74d8fd28b320b4503234a774f0f5a20e957` |
| Reviewed runtime license-boundary repair | `d8ebb535e6ab05e2bf247d401a952543fb1b2f61` |
| Read-only Swift reference | `db14559b39d32322b06477c6ae676112f583db50` |
| Canonical manifest | `78a22920beaa5899f9618806b5cd2b27d50399a9b29b4d8dbd79f755717ec746` |
| Trusted semantic policy revision | `56bdc53548bc86d631245795dfa38b4fc86048e0e7cbe1c7d5695879b035b42a` |

The base is the actual merged WP-101 foundation, not an in-progress PR.
`validate.py` verifies the complete 1,866-file pinned inventory; Git also reports
no reference-root changes. Canonical manifest/policy hashing, not the Windows
checkout's CRLF byte hash, binds these revisions. The recorded prompt lease
includes every write, including these docs and raw reports. No shared
manifest/Gradle/workflow/model amendment is part of this package.

The launch-base and pre-license-repair assertions/APKs are historical evidence.
`license-boundary-results.json` and `license-junit/` supersede their current
runtime/APK and test results; `rebased-results.json` preserves the actual base
reconciliation. Exact final PR-head hosted checks are
separate and must be inspected for that SHA. No model-written PASS text
substitutes for independent acceptance.

## Implemented behavior and source accounting

| Owned / cross input | Native mapping and evidence |
| --- | --- |
| `MockTransport.swift` | `transport/mock/MockTransport.kt`: connection state, successful-send snapshots, both send routes, capability configuration, signed/large failure thresholds, raw/OK/error injection, history clear, terminal/reconnected queues; 12 mock cases |
| `WiFiFrameCodec.swift` | `transport/tcp/WiFiFrameCodec.kt`: exact delimiters/UInt16 LE, incremental bounded decoder, reset/noise accounting and typed overflow/truncated EOF; 17 codec cases |
| `WiFiTransport.swift` | `transport/tcp/WiFiTransport.kt` and `WiFiTransportError.kt`: configuration, eight errors, timeout constants, real socket connect/send/read/close, idempotence, callbacks, owned cancellation and capabilities; 23 real/baseline and 20 controlled-IO cases |
| `WiFiTransportError+LocalizedError.swift` | Exact eight descriptions/reason interpolation preserved only in unpackaged GPL-provenance `WiFiAppErrorReference.kt`; GPL app-side formatter and localized recovery explicitly deferred to WP-304, not MIT production |
| `WiFiFrameCodecTests.swift` | All six original declarations, plus complete split points, byte/high-bit/zero/max-length, resynchronization/reset and malformed EOF families |
| `WiFiTransportTests.swift` | All twelve original declarations, including equivalent deterministic pending-connect cancellation, actual idempotent TCP, peer FIN and handler behavior |
| `docs/guides/WiFi_Transport.md` | Same plain TCP framing/configuration/capabilities; no added discovery/keepalive/reconnect. Resolver/socket seams support later WP-206 integration |
| `PythonReferenceBytes.swift` (cross input) | Pinned `deviceQuery = 16 03`; independently specified outbound frame `3c 02 00 16 03`, not generated by candidate Kotlin |
| `MeshCore/LICENSE` (cross input) | Unchanged protocol MIT notice; existing app GPLv3/MIT/Apache assets are verified in the actual APK |

`source-cases.json` contains the exact pinned source declaration-to-passing
JUnit case mapping, all native case names and source blob identities.
`frame-vectors.json` records independent literal source expectations and
unsigned length-boundary headers. Headers alone are not counted as parity.

## Actual local commands and outcomes

The documented launcher uses this session's verified private JDK/SDK and
Gradle/Android user caches, strips credentials, and runs one worker/in-process
Kotlin with a 640m build heap. The initial preflight failed because JDK/SDK
variables were absent. The existing direct-archive provisioner installed the
publisher-pinned tuple privately, without executing the command-line-tools CLI;
the explicit preflight and strict dependency preparation then passed.

| Command / declared arguments | Actual outcome |
| --- | --- |
| `python .\tools\android-port\controller\ci.py provision --root <new-session-private-directory> --accept-sdk-license` | Passed, verified first-party archives only; existing pinned environment terms, not app/dependency legal approval |
| `python .\tools\android-port\controller\ci.py preflight --state <private-environment.json> --output <private-evidence> --local` | Passed JDK21.0.12.1, SDK37.2/rev1 and build-tools37.0.0 readiness |
| Launcher: `resolveScaffoldDependencies --dependency-verification strict --no-build-cache --quiet` | Passed with initially private caches; no lock/metadata generation |
| Launcher: `:core:protocol:test --tests com.meshcoreone.android.core.protocol.transport.* --dependency-verification strict --no-build-cache --quiet` | Initial 51 cases passed; extended 69-case run exposed six regressions, repaired rather than skipped |
| Launcher: `:core:protocol:test --tests com.meshcoreone.android.core.protocol.transport.*WiFiTransport* --dependency-verification strict --no-build-cache --quiet` | Forty TCP cases passed after correcting coroutine-cause identity assertions, test scheduling and strengthening owned cleanup; an intervening test-helper compile error was repaired |
| Launcher: `:core:protocol:test validateModuleGraph resolveScaffoldDependencies runtimeDependencyInventory --dependency-verification strict --no-build-cache --rerun-tasks --quiet` | Passed: 153 cases, actual Android-free module graph and strict runtime/POM inventory |
| Launcher: `:core:protocol:test validateModuleGraph --dependency-verification strict --no-build-cache --quiet` | Final resolver-seam revision passed: **156 discovered/run/passed, 0 failed/errors/skipped** |
| Same final protocol/graph command after enforcing the original three-second cancellation completion bound | **156 discovered/run/passed, 0 failed/errors/skipped**; production code and APK unchanged |
| Launcher: `:app:assembleDebug --dependency-verification strict --no-build-cache --quiet` | Passed; real debug APK, including all three transport classes in DEX |
| `python .\android\scaffold\inspect_apk.py` | Passed actual min31/target37/debug identity, launcher, pinned notices and fixture exclusion |
| Existing `controller.apk_alignment.inspect_alignment(...)` on the actual APK and private SDK | Passed zipalign `-P 16` and all four actual ELF PT_LOAD alignments; not physical-runtime evidence |
| `python .\tools\android-port\controller\verification_config.py --check` | Passed unchanged supervised overlay |
| `python .\tools\android-port\controller\validate.py` | Passed pinned inventory/ownership/manifest |
| `python .\tools\android-port\portmap.py` | Passed all production/test source provenance |
| `python .\tools\android-port\controller\workflows.py` | Passed trusted workflow boundary assertions; no workflow edit |
| `python .\android\scaffold\sync_notices.py` | Passed GPLv3/MIT/Apache/artwork correspondence |
| `python .\tools\android-port\controller\test_runner.py --quiet` | **165 discovered/run/passed, 0 failed/errors/skipped**, controller fixtures only |
| `python -m unittest discover -s .\android\scaffold -p test_*.py -q` | **15 passed**, scaffold validator fixtures only |
| Trusted `validate_writes(WP-108.write_paths, complete_changed_paths)` and `git diff --check` | Passed every actual write and whitespace; no protected/shared-file exception used |

Every launcher invocation uses:

```powershell
& .\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 640m `
  -GradleArguments @(<the exact arguments above>)
```

| Final protocol suite | Actual cases |
| --- | ---: |
| Existing byte/value/text helpers | 44 |
| Existing path encoding | 19 |
| Existing transport defaults | 5 |
| Existing values/configuration | 13 |
| Existing pinned wire tables | 3 |
| WP-108 mock | 12 |
| WP-108 WiFi codec | 17 |
| WP-108 controlled IO/resolution | 20 |
| WP-108 baseline/real TCP | 23 |
| **Historical launch-base total** | **156** |

The 84 pre-existing primitive cases all still pass. The 72 new transport cases
are not described as 72 original Swift declarations: the owned source has
18 declarations and native cases include added boundary/concurrency families.
Complete launch-base raw JUnit is preserved in `junit/`; `local-results.json`
records its actual artifact sizes/hashes. APK and private tool/caches stay
outside Git; only actual inspection/hash evidence is committed.

## Actual base reconciliation and integrated rerun

The first exact-head WP-004 check at `123d163a28f750328f915903415eb79c4eb430b5`
failed because default branch had merged WP-004's new workflow while that
launch-base candidate lacked `tools/android-port/test_inventory.py`. That failed
run is not claimed green. Approved merged main had also gained independent
WP-104 builders and WP-105 LPP.

Only this owning branch was rebased onto actual merged
`4331a4dddd13126ab05f4b4d74e654f313c5d414`. Transport production/tests are
byte-identical to the initial published head; no unowned workflow/tool/model
fix was authored. Source, canonical manifest and semantic policy pins are
unchanged. Publication uses the explicit old remote SHA
`123d163a28f750328f915903415eb79c4eb430b5` as its force-with-lease guard, never an
unconditional force push. The same normal PR is repaired, not replaced.

| Actual integrated command | Result |
| --- | --- |
| Launcher: `:core:protocol:test :core:testing:testDebugUnitTest validateModuleGraph resolveScaffoldDependencies runtimeDependencyInventory --dependency-verification strict --no-build-cache --rerun-tasks --quiet` | **594 protocol cases passed**: 84 primitives + 101 merged builders + 337 merged LPP + 72 WP-108; **35 helper cases passed**, no failures/errors/skips; graph/runtime/strict inputs passed |
| `python .\tools\android-port\oracle\foundation_ci.py junit --junit .\android\core\testing\build\test-results\testDebugUnitTest` | Independently reparsed all **35** actual helper cases and names; no failed/error/skipped output |
| `python .\tools\android-port\test_inventory.py --check` | Passed 468 paths / 5,133 frozen original declarations; not claimed ported |
| `python .\tools\android-port\extract_vectors.py --check` | Passed **49** independent original vectors |
| `python .\tools\android-port\oracle\run_tests.py --quiet` | **83** Python assertions passed, zero failed/errors/skipped |
| Same explicit launcher `:app:assembleDebug --dependency-verification strict --no-build-cache --quiet` | Passed actual reconciled debug APK |
| Same actual APK inspection, zipalign and ELF reader | Passed debug min31/target37/launcher/notices/fixture exclusion, all transport DEX classes and static 16KiB alignment |
| Configuration/inventory/workflow/notice and complete lease validators | Passed; source/policy/manifest pins unchanged |

`rebased-junit/protocol/` contains **all 594** cases, not just a 72-case
selection. `rebased-junit/helpers/` contains all **35** helper cases.
`rebased-results.json` binds their actual XML hashes and the reconciled APK.
This integrated rerun does not transform WP-104/105 cases into WP-108 port
credit, nor controller/helpers into physical or iOS parity.

The Windows runner writes CRLF XML and Git stores text with LF. Artifact
`size_bytes`/`sha256` bind the exact committed XML bytes;
`original_runner_size_bytes`/`original_runner_sha256` retain the original
verbatim runner checksums. Every original checksum was independently matched
with only CRLF restored, and the complete stored testcase/outcome/log nodes
were reparsed. No XML case, outcome or golden packet was edited or omitted.

## Reviewed GPL/MIT runtime boundary repair

Parent review identified a concrete license-boundary defect: the original
candidate placed the GPL app `LocalizedError` English prose and provenance
inside the MIT protocol error file. The reviewed repair removes both from
production, while retaining all eight MIT-source typed cases, associated
reasons, value equality and the wrapped IO cause.

`WiFiTransportDiagnostics.kt` is separately marked native-only and emits
technical `wifi.*` diagnostics, not source app prose or localized recovery.
The GPL-provenance `WiFiAppErrorReference.kt` is **test-only**, retaining all
eight original descriptions/reason interpolation. The actual app formatter is
explicitly deferred to WP-304's GPL app-side localization/recovery adapter;
there is no fabricated deferred-UI implementation or fully-localized claim.
`source-cases.json` records this disposition instead of silently dropping its
assigned source.

The documented strict launcher passed **43 targeted TCP cases**, then the full
**594 protocol + 35 helper cases**, with the **72 transport cases and all 18
original declarations retained**. The real debug APK was rebuilt and inspected:
all eight GPL app prose patterns and the test reference are absent from actual
DEX, while native diagnostic identifiers are present. Its debug identity,
launcher, notices, fixture exclusion and static 16KiB alignment still pass.
Complete current XML and both original-runner/Git-storage hashes are in
`license-junit/` and `license-boundary-results.json`.

Source/policy/manifest, dependencies, framing/capabilities, whole-operation
mutexes, generations, EOF/resource close and cancellation behavior are
unchanged by the license repair. The default native-JVM DNS interruption
limitation and WP-206 network-scoped resolver seam remain explicitly intact;
no injected loopback test claims default `.local` or real Android proof.

## Acceptance IDs and honest limits

`WP-108-behavior`: real mock/framing/TCP implementations, source capabilities,
eight typed WiFi error cases/reasons, separate native diagnostic identifiers
and exact packet bytes are asserted. GPL app descriptions are only test
references; they are not a native localized-UI implementation.
`WP-108-boundaries`: fragmentation/coalescing, empty/unknown/high-bit/max-size
payloads, malformed EOF, FIN/RST, cancellation/deadlines, reconnect, stale
operations and once-only socket/owned-job cleanup are asserted.
`WP-108-source-test-parity`: all 18 assigned declarations and nine owned/cross
inputs are accounted for in the case/source catalog and adaptations.

See [native adaptations](../../deviations/WP-108.md). The default OS resolver's
arbitrary DNS/mDNS interruption/deadline behavior is **not proved**; the injected
interruptible/network-scoped seam is. WP-206 owns real Android network binding,
LAN permissions and `.local` device evidence. WP-106/107/217 and WP-304 own the
documented common-error/Flow-consumer/localization follow-ups. These are not
silently represented as integrated session/application behavior.

No Swift/macOS execution, physical Android/radio, permission-denial/reconnect
HIL, dependency/license admission, privileged reviewer/publisher activation,
release signing or installation occurred. Local controller fixtures do not
prove Linux execution; real exact-head hosted Windows/Linux results are recorded
separately on the normal PR. The coordinator handles final integration/merge.
