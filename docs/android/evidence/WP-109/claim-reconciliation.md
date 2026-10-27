# WP-109 R118 protocol claim reconciliation

## Identity and interpretation

Repository: `cbattlegear/MeshCoreOne-Android`; owner: `test-parity-engineer`.
Issue #379; concern `PROTOCOL-R118-G13`; ten distinct original families.
Repair branch: `wp109-acceptance-claims`; actual session:
`37f7ca3e-00c8-4c3d-adc8-3a9ac72e4313`, reservation revision 2.
Inspected native base: `5935af530743662e0caf95338f0d881ab58382c0`.
Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
Manifest SHA-256: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
Policy revision: `79f6c46a4192aeeac807d1326126eac57c6db70b36a6f011afe00ca002a48f3b`.
WP-105 PR #10 and WP-107 PR #19 are merged ancestors of the inspected base;
their later repair issues are not assumed complete.

This is a current assertion/consumer map, not a replacement for original
execution reports or an award of source-family acceptance.
Historical [source-cases.json](source-cases.json)
and [baseline-native.json](baseline-native.json) remain byte-for-byte unchanged.
Their outcomes belong to their recorded revisions. The canonical
`docs/android/parity/accounting.json` already labels these candidate mappings
`historical-mapping-not-current-proof` and retains
`independent-family-review-required`; this repair does not edit that accounting.
Canonical reconciliation remains a main/coordinator operation.

The checkout's correlation-test blob differs from the original pin only under
the existing [approved amendment](../../reference-amendments.json), explained
by [ADR-003](../../adr/003-frozen-swift-pin-exception-flaky-correlation-test.md).
It changes `binary request errors release the serializer for following requests`,
not the startup family below. No pin, reference bytes or amendment is advanced.
Source links below resolve the original frozen declarations; native links
resolve the exact inspected base, not moving HEAD.

## Exact original/native bindings

All ten source parameter families are `single`. Native identities retain the
full `Suite::case()` names below, including the two separate packet formats.
The six parser bindings are direct fixture-to-parser cases, not domain
encoder/parser or device-request round trips.

| # | Exact original family | Frozen assertion body | Native assertion body |
| --- | --- | --- | --- |
| 1 | `RxLogTypesTests::RouteType displayName()` | [RxLogTypesTests.swift:57-62][source-rx] | [OriginalRxLogCasesTest.kt:92-95][native-rx] |
| 2 | `AutoContactRefreshTests::auto-refresh coalesces bursty contact invalidations()` | [AutoContactRefreshTests.swift:8-42][source-refresh] | [OriginalContactsAndSettingsCasesTest.kt:16-30][native-refresh] |
| 3 | `BinaryRequestTimeoutTests::defaults use a 40 second overall budget and 1 second retransmit floor()` | [BinaryRequestTimeoutTests.swift:8-13][source-defaults] | [OriginalLifecycleCasesTest.kt:86-91][native-defaults] |
| 4 | `MeshCoreSessionCommandCorrelationTests::session start ignores unrelated errors until selfInfo arrives()` | [MeshCoreSessionCommandCorrelationTests.swift:109-131][source-start] | [OriginalCorrelationCasesTest.kt:56-63][native-start] |
| 5 | `RoundTripTests::AllowedRepeatFreq round trip()` | [RoundTripTests.swift:802-826][source-freq] | [CrossComponentParserCasesTest.kt:183-188][native-freq] |
| 6 | `RoundTripTests::CoreStats round trip()` | [RoundTripTests.swift:413-436][source-core] | [CrossComponentParserCasesTest.kt:172-175][native-core] |
| 7 | `RoundTripTests::CustomVars round trip()` | [RoundTripTests.swift:920-934][source-vars] | [CrossComponentParserCasesTest.kt:189-192][native-vars] |
| 8 | `RoundTripTests::PacketStats round trip (28-byte format with receiveErrors)()` | [RoundTripTests.swift:501-533][source-packets28] | [CrossComponentParserCasesTest.kt:182,195-202][native-packets] |
| 9 | `RoundTripTests::PacketStats round trip (legacy 24-byte format)()` | [RoundTripTests.swift:468-498][source-packets24] | [CrossComponentParserCasesTest.kt:181,195-202][native-packets] |
| 10 | `RoundTripTests::RadioStats round trip()` | [RoundTripTests.swift:439-465][source-radio] | [CrossComponentParserCasesTest.kt:176-180][native-radio] |

## Stimuli, assertions and observable boundaries

### 1. Route names: protocol properties, not a rendered RX label

Source and native assertions read the four enum `displayName` properties and
require `TC_FLOOD`, `FLOOD`, `DIRECT`, `TC_DIRECT`. The native producer is
`core/protocol/.../event/RxLogTypes.kt:6-7`; no packet, service, locale or
screen is constructed. This establishes four protocol strings, not UI labels.
The actual RX row in
`feature/tools/.../diagnostics/rxlog/RxLogPresentation.kt:104` instead uses
`entry.routeTypeSimple`, whose `core/model/.../RxLogEntryDTO.kt:62` collapses
the enum to `FLOOD`/`DIRECT`. Those production consumers exist; this case
does not execute them. Protocol ownership remains WP-103; RX ingestion and
presentation evidence belongs separately to WP-212/WP-316.

### 2. Contact coalescing: advertisement invalidations and empty responses

The source starts a real session over MockTransport, clears the startup write,
enables auto-refresh, then injects the same advertisement three times:
response `0x80` plus a 32-byte `0x22` key. It requires exactly one initial
getContacts write, injects a zero-contact start/end with lastModified=1 Unix
second, observes 50 ms, permits one additional refresh, conditionally responds
with lastModified=2 and observes another 50 ms, then requires at most two writes.
It does not inject three new contact rows or persist any application contacts.

The native case uses the real MeshCoreSession/PacketParser/ContactManager via
`SessionRadioTransport` wrapping MockTransport. Its fixture/start helpers are
`core/protocol/.../session/SessionTestSupport.kt:45-94,122-157`.
Startup is not cleared, so its counts 2, 2..3 and <=3 correspond to the source's
1, 1..2 and <=2. It asserts wire `04`, and, if another fetch occurs,
`0401000000`; empty start/end responses carry lastModified=1/2. It additionally
asserts `isContactsDirty == false`, then stops the session.

Its `runCurrent()` calls drain currently scheduled work but do not advance
through the source's post-response 50 ms negative intervals. The conditional
branch is not proof that both outcomes executed. Credit is bounded to this
mock/session/cache scenario, not arbitrary future coalescing or a store/UI
refresh. Temporal evidence remains with WP-107 (#380). The actual downstream
`ContactService.syncContacts` calls `getContactsReportingTotal`
(`core/services/.../contacts/ContactService.kt:93-98`); that WP-209 consumer
is present but not invoked here.

### 3. Binary defaults: seconds and a dimensionless multiplier

Both bodies construct SessionConfiguration and assert overall timeout `40.0`,
retransmit interval `1.0`, and RTT headroom `2.0`. The first two are seconds;
the last is a multiplier, not a two-second duration. The native producer is
`core/protocol/.../config/SessionConfiguration.kt:8-9,18-19`.
This family performs no exchange, simulated clock advance, resend or timeout.
The real binary consumer exists in
`core/protocol/.../session/SessionBinaryOperations.kt:47-60,86-88`, where a
device timeout in milliseconds is divided by 1000 before applying headroom.
Its presence is static context, not execution by this property test.
WP-107 owns behavioral deadline/retry evidence; the neighboring custom-timeout
case is not transferred into this original family's acceptance.

### 4. Startup correlation: code 99 followed by one selfInfo fixture

The source waits for one appStart write, injects firmware error code 99,
injects selfInfo, awaits startup and checks currentSelfInfo.name is `Test`,
then stops. The native case schedules startup, runs current tasks, injects
the same error then `selfPacket()`, drains again, awaits startup, checks
the same name and stops. It does not assert an exact outgoing frame/count
in this body or an intermediate still-pending state before selfInfo.

`SessionRadioTransport.error(99)` delegates to MockTransport.simulateError;
PacketParser produces `MeshEvent.Error(99)`, not an unknown-response
parse failure. Code 99 is outside the recognized ErrorCode values 1..6.
The native observation is successful startup/name with this specific
injected error/selfInfo sequence, not every recognized firmware error,
serializer release, arbitrary late response, cancellation or mounted graph.
The producer is SessionCore.start/query; fixture timing is the scheduler
clock with defaultTimeout=1 second/clientIdentifier=`MCore`, rather than the
source's 10 seconds/`MCTst`. WP-107 (#380) owns broader correlation and any
missing startup-order/pending assertions; WP-303 graph execution is not proven.

### 5-10. Parser fixtures: precise payloads and field assertions

All six source bodies manually construct payload bytes (or UTF-8 text), call a
specific Parsers entrypoint, require the event variant, and inspect its fields.
The native cases likewise call that entrypoint directly. ByteWriter/`le`
only constructs little-endian fixture fields; it is not a domain encoder,
independent wire oracle, full PacketParser/session exchange or hardware peer.

| # | Actual fixture and asserted result | Native producer |
| --- | --- | --- |
| 5 | Three UInt32LE lower/upper pairs: 433000/433000, 869000/869000, 918000/918000 **kHz**. Assert count=3, first lower+upper and the other two lowers. Do not claim assertions on every upper bound, Hz/MHz conversion, RF selection or radio writes. | `Parsers.AllowedRepeatFreq`, `Parsers.kt:315-323` |
| 6 | UInt16LE battery=3750 **mV**, UInt32LE uptime=86400 **seconds**, UInt16LE errors=3, UInt8 queueLength=5. Assert event and all four fields; no display conversion, battery service or device query. | `Parsers.CoreStats`, `Parsers.kt:325-333` |
| 7 | UTF-8 `key1:value1,key2:value2,mode:auto`. Assert event and lookups `key1=value1`, `key2=value2`, `mode=auto`; no exact-map-size/order or malformed-input assertion. This fixture has no `gps` key and proves no GPS readback/setter behavior. | `Parsers.CustomVars`, `Parsers.kt:113-122` |
| 8 | Seven UInt32LE fields: received=1000, sent=500, floodTx=100, directTx=400, floodRx=200, directRx=800, receiveErrors=42. The shared `packetStats(42u)` helper builds 28 bytes, requires StatsPackets and asserts all seven counters. | `Parsers.PacketStats`, `Parsers.kt:346-355` |
| 9 | The same first six counters, with no error field (24 bytes). `packetStats(null)` asserts the six counters and legacy receiveErrors=0 default. This is not a zero-error measurement from a device. | `Parsers.PacketStats`, `Parsers.kt:346-355` |
| 10 | Signed Int16LE noiseFloor=-115, Int8 lastRSSI=-90, Int8 rawSNR=28; assert decoded SNR=7.0 within 0.01. UInt32LE tx/rx airtime=1000/2000 are asserted in the API's `txAirtimeSeconds`/`rxAirtimeSeconds` fields; no milliseconds conversion is exercised. No live RF sample, polling interval, ratio or chart is measured. | `Parsers.RadioStats`, `Parsers.kt:335-344` |

The parser producers live in `core/protocol/.../parser/Parsers.kt`. These six
historical bindings retain primary owner WP-104, with parser implementation
owned by WP-103; neither ownership nor counts changes.

Existing consumers include MeshCoreSession.getRepeatFreq/getStatsCore/
getStatsRadio/getStatsPackets/getCustomVars
(`core/protocol/.../session/MeshCoreSession.kt:77-79,118-128`) and WP-211
SettingsService (`core/services/.../device/SettingsService.kt:178,225-230`).
The latter also derives GPS state from custom vars, while the WP-316
NoiseFloorStateHolder polls getStatsRadio. None is called by these six parser
families. Missing consumer execution in these families is not a claim that
the application, services or other tests lack those implementations.

## Verification and acceptance boundary

Only documentation changes. The historical ten mappings, five native suites,
source fixtures and production consumers were inspected before choosing these
qualifications. Current pre-push commands/discovery are recorded separately
below; final head and authoritative hosted result belong to the PR.
No historical result is relabeled, no new original family is counted and no
canonical `independent-family-review-required` state is promoted. Closing
#379 means correcting this evidence scope, not closing #380 or granting
whole-WP, radio, platform, application or independent parity acceptance.

### Current pre-push execution

Linux; pinned Python 3.12.4 and existing JDK/SDK installations from
`/ai_cache/meshcoreone/toolchains/a8c624bc-20261026/environment.json`.
The declared `ci.py local-inputs` helper supplied an isolated private root;
no toolchain, dependency, runner, task or assertion changed.
Readiness passed using the existing state:

```sh
/ai_cache/meshcoreone/python/3.12.4/bin/python3 tools/android-port/controller/ci.py preflight \
  --state /ai_cache/meshcoreone/toolchains/a8c624bc-20261026/environment.json \
  --output /home/cbattagler/.copilot/session-state/37f7ca3e-00c8-4c3d-adc8-3a9ac72e4313/files/wp109-readiness
GRADLE_USER_HOME=/ai_cache/meshcoreone/cache/gradle /ai_cache/meshcoreone/python/3.12.4/bin/python3 \
  tools/android-port/controller/ci.py run --stage protocol --scope protocol \
  --state /home/cbattagler/.copilot/session-state/37f7ca3e-00c8-4c3d-adc8-3a9ac72e4313/files/wp109-local-inputs/environment.json \
  --output /home/cbattagler/.copilot/session-state/37f7ca3e-00c8-4c3d-adc8-3a9ac72e4313/files/wp109-local-check
```

Declared `:core:protocol:test`: **4,821 discovered/run/passed; zero failures,
errors or skips**. Exact XML identities were checked for all ten bindings
above, not inferred from the aggregate. Their five native suites ran:

| Suite | Discovered/run/passed | Failed/errors/skipped |
| --- | ---: | --- |
| CrossComponentParserCasesTest | 50 | 0/0/0 |
| OriginalRxLogCasesTest | 41 | 0/0/0 |
| OriginalContactsAndSettingsCasesTest | 2 | 0/0/0 |
| OriginalCorrelationCasesTest | 32 | 0/0/0 |
| OriginalLifecycleCasesTest | 10 | 0/0/0 |

This current component execution does not expand any case's asserted consumer
boundary or prove its unobserved timing/stimulus variants.

Also passed with the pinned Python:
`tools/android-port/controller/validate.py`,
`tools/android-port/portmap.py` (1,934 source dispositions),
`android/parity-tests/audit.py --check`,
`android/parity-tests/semantic.py --check`, and `git diff --check`.
These static checks do not replace independent family review. Reproducible
hosted results remain in the exact-candidate job/log, not another committed
result bundle. No iOS, Android device, physical-radio or UI execution is claimed.

[source-rx]: https://github.com/cbattlegear/MeshCoreOne-Android/blob/db14559b39d32322b06477c6ae676112f583db50/MeshCore/Tests/MeshCoreTests/RxLogTypesTests.swift#L57-L62
[source-refresh]: https://github.com/cbattlegear/MeshCoreOne-Android/blob/db14559b39d32322b06477c6ae676112f583db50/MeshCore/Tests/MeshCoreTests/Session/AutoContactRefreshTests.swift#L8-L42
[source-defaults]: https://github.com/cbattlegear/MeshCoreOne-Android/blob/db14559b39d32322b06477c6ae676112f583db50/MeshCore/Tests/MeshCoreTests/Session/BinaryRequestTimeoutTests.swift#L8-L13
[source-start]: https://github.com/cbattlegear/MeshCoreOne-Android/blob/db14559b39d32322b06477c6ae676112f583db50/MeshCore/Tests/MeshCoreTests/Session/MeshCoreSessionCommandCorrelationTests.swift#L109-L131
[source-freq]: https://github.com/cbattlegear/MeshCoreOne-Android/blob/db14559b39d32322b06477c6ae676112f583db50/MeshCore/Tests/MeshCoreTests/Validation/RoundTripTests.swift#L802-L826
[source-core]: https://github.com/cbattlegear/MeshCoreOne-Android/blob/db14559b39d32322b06477c6ae676112f583db50/MeshCore/Tests/MeshCoreTests/Validation/RoundTripTests.swift#L413-L436
[source-vars]: https://github.com/cbattlegear/MeshCoreOne-Android/blob/db14559b39d32322b06477c6ae676112f583db50/MeshCore/Tests/MeshCoreTests/Validation/RoundTripTests.swift#L920-L934
[source-packets28]: https://github.com/cbattlegear/MeshCoreOne-Android/blob/db14559b39d32322b06477c6ae676112f583db50/MeshCore/Tests/MeshCoreTests/Validation/RoundTripTests.swift#L501-L533
[source-packets24]: https://github.com/cbattlegear/MeshCoreOne-Android/blob/db14559b39d32322b06477c6ae676112f583db50/MeshCore/Tests/MeshCoreTests/Validation/RoundTripTests.swift#L468-L498
[source-radio]: https://github.com/cbattlegear/MeshCoreOne-Android/blob/db14559b39d32322b06477c6ae676112f583db50/MeshCore/Tests/MeshCoreTests/Validation/RoundTripTests.swift#L439-L465
[native-rx]: https://github.com/cbattlegear/MeshCoreOne-Android/blob/5935af530743662e0caf95338f0d881ab58382c0/android/core/protocol/src/test/kotlin/com/meshcoreone/android/core/protocol/parser/OriginalRxLogCasesTest.kt#L92-L95
[native-refresh]: https://github.com/cbattlegear/MeshCoreOne-Android/blob/5935af530743662e0caf95338f0d881ab58382c0/android/core/protocol/src/test/kotlin/com/meshcoreone/android/core/protocol/session/OriginalContactsAndSettingsCasesTest.kt#L16-L30
[native-defaults]: https://github.com/cbattlegear/MeshCoreOne-Android/blob/5935af530743662e0caf95338f0d881ab58382c0/android/core/protocol/src/test/kotlin/com/meshcoreone/android/core/protocol/session/OriginalLifecycleCasesTest.kt#L86-L91
[native-start]: https://github.com/cbattlegear/MeshCoreOne-Android/blob/5935af530743662e0caf95338f0d881ab58382c0/android/core/protocol/src/test/kotlin/com/meshcoreone/android/core/protocol/session/OriginalCorrelationCasesTest.kt#L56-L63
[native-freq]: https://github.com/cbattlegear/MeshCoreOne-Android/blob/5935af530743662e0caf95338f0d881ab58382c0/android/core/protocol/src/test/kotlin/com/meshcoreone/android/core/protocol/parser/CrossComponentParserCasesTest.kt#L183-L188
[native-core]: https://github.com/cbattlegear/MeshCoreOne-Android/blob/5935af530743662e0caf95338f0d881ab58382c0/android/core/protocol/src/test/kotlin/com/meshcoreone/android/core/protocol/parser/CrossComponentParserCasesTest.kt#L172-L175
[native-vars]: https://github.com/cbattlegear/MeshCoreOne-Android/blob/5935af530743662e0caf95338f0d881ab58382c0/android/core/protocol/src/test/kotlin/com/meshcoreone/android/core/protocol/parser/CrossComponentParserCasesTest.kt#L189-L192
[native-packets]: https://github.com/cbattlegear/MeshCoreOne-Android/blob/5935af530743662e0caf95338f0d881ab58382c0/android/core/protocol/src/test/kotlin/com/meshcoreone/android/core/protocol/parser/CrossComponentParserCasesTest.kt#L181-L202
[native-radio]: https://github.com/cbattlegear/MeshCoreOne-Android/blob/5935af530743662e0caf95338f0d881ab58382c0/android/core/protocol/src/test/kotlin/com/meshcoreone/android/core/protocol/parser/CrossComponentParserCasesTest.kt#L176-L180
