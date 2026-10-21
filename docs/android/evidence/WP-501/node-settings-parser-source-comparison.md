# WP-501 complete NodeSettingsResponseParserTests source comparison

LOCAL documentation-only successor to
`ebf6c1db43c87a165208a6766bd995209fa66b95`, base/main
`dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`, existing ready PR165/issue134.
Frozen specification: `db14559b39d32322b06477c6ae676112f583db50`.
This records a worker source comparison, not independent acceptance or execution.

## Selection and result

The historical accounting worklist has twelve `single` originals from
`MC1Services/Tests/MC1ServicesTests/NodeSettingsResponseParserTests.swift`,
all with `no-explicit-family-mapping`; the source is absent from historical
review proposals. Those missing joins did NOT establish missing executable
tests. Reading the entire149-line frozen file, every complete existing native
case body, its helper/factory, both parser implementations and their consumers
established existing equivalent assertions for all twelve originals.

No assertion/fixture defect was found for these exact inputs. Accordingly this
candidate changes only this comparison and the additive WP-501 deviation:
no duplicated tests, speculative production fix, archive/denominator
regeneration, new original-family credit or passing-test tally.

## Complete case and input/predicate comparison

All rows are `NodeSettingsResponseParserTests::<exact name>()`. Their existing
destination is `com.meshcoreone.android.core.services.remote.NodeSettingsResponseParserTest`,
`sourceCases()`: five `recoveryCases()` plus seven `clockAndTextCases()`.
Its `case(name, body)` passes the body to `DynamicTest.dynamicTest` and retains
that exact original ID as the display name. The Kotlin line is the unchanged
registration anchor, not proof that a runner discovered/executed the body.
The Swift suite has no private fixture/helper; Foundation/Testing provide its
optional requirements, calendar and expectations.

| Exact original name | Frozen complete body / Kotlin anchor | Preserved inputs and complete observable predicate |
| --- | --- | --- |
| late reply recovers for the single unanswered query it parses for | 10-22 / 26 | `> 22` with `{get tx}` returns query `get tx` and full `TxPower(22)`; `> 915.000,250.0,10,5` with `{get tx,get radio}` returns query `get radio` and full `Radio(915.0,250.0,10,5)`. |
| a bare double is ambiguous when both coordinates are unanswered | 25-34 / 35 | `38.5` with `{get lat,get lon}` is null; with only `{get lon}` its value is full `Longitude(38.5)`. No extra query assertion is attributed to the original. |
| query-independent shapes are never recovered | 37-43 / 39 | The complete inline loop over `OK`, `ERR: not allowed`, `MeshCore v1.11.0 (2025-04-18)`, each with `{get tx}`, yields null. One original, not three new families. |
| radio CSV never recovers as TX power | 46-50 / 44 | `> 910.525,62.500,7,7` with only `{get tx}` yields null, not a leading-integer power. |
| empty and free-form query sets recover nothing | 53-58 / 47 | `22` with an empty set and `Alpha Repeater` with `{get name}` each yield null. |
| Firmware clock response parses to the exact UTC date | 63-74 / 55 | Require nonnull parsed `06:40 - 18/4/2025 UTC`; compare UTC Gregorian year2025/month4/day18/hour6/minute40. Java `ZoneOffset.UTC`/ISO calendar replaces the Swift required UTC TimeZone/Gregorian Calendar. Independent calendar arithmetic gives Unix1744958400; production parsing did not generate an expected date. |
| Non-clock text returns nil | 77-80 / 63 | Both `Alpha Repeater` and `06:40 - 18/4/2025` without UTC return null. |
| clock response text is extracted from a bare clock line and from an OK sync line | 83-94 / 67 | Exact bare clock retained; `OK - clock set: 15:35 - 14/8/2026 UTC` extracts exactly `15:35 - 14/8/2026 UTC`; `OK - clock set` and `Alpha Repeater` each return null. |
| clock drift is node minus reference and nil when the text has no clock | 97-112 / 73 | Require original node date; reference is node+600 seconds; exact drift is -600.0 seconds, not reversed; `OK - clock set` gives null with that same reference. |
| Clock sync outcomes classify OK, clock-ahead, generic error, and unexpected text | 117-126 / 80 | Exact `OK - clock set` -> Synced; `ERR: clock cannot go backwards` -> ClockAhead; `ERR: invalid time` -> full Failed(`invalid time`); `hello` -> Unexpected. |
| Password change succeeds on OK or the firmware echo, fails otherwise | 131-136 / 87 | `> password now: hunter2` and `OK` are true; `ERR: bad password` and `Alpha Repeater` are false. These are frozen synthetic strings, not credentials. |
| Owner info wire and display forms round-trip | 141-148 / 94 | `KD7ABC\|ch 31` maps exactly to `KD7ABC\nch 31`, reverse exactly; full three-line `line one\nline two\nline three` display survives wire/display round-trip. |

## Actual parser and consumer boundary

The public core parser is NOT the feature's runtime import. WP-313's admitted
feature-local mirror is internal; its existence/placement is documented in
WP-313, not a new discrepancy. Whole-file comparison after removing only file
headers/package lines and the object/interface visibility modifier found equal
parser, CLIResponse and RemoteSwiftText bodies between core and feature.
This is source equivalence, not execution of the internal parser by the core test.

Read the full frozen131-line parser and214-line CLIResponse and both native
copies plus numeric/text helper. Structured-only queries, prompt stripping,
full typed result payloads, singleton-match ambiguity, universal
OK/error/version exclusion, CSV-vs-TX delimiter, UTC extraction/date conversion,
signed-second drift, typed clock failure payload, password echo and exact
separator replacements agree for ALL selected inputs. Swift64-bit Int maps to
Long; Date/TimeInterval maps to Instant/Double seconds. No selected original
injects a thrown transport error, cancellation, transaction or asynchronous
fixture: none is invented as its coverage. Invalid-date/Unicode/calendar
parameter domains outside these twelve bodies are not newly certified.

Frozen NodeSettingsViewModel parser-consuming functions were compared at
lines31-51,327-351,454-476,487-518,555-584,651-724 with full native
NodeSettingsStateHolder/NodeSettingsPresentation and existing consumer tests:

- Real late recovery removes the sole unanswered query, remembers the response
  and invokes its registered field applier; duplicates/ambiguity are ignored.
  Existing NodeSettingsLateRecoveryTest proves radio fields/error transition,
  latitude-vs-longitude ambiguity/duplicate rejection, late clock handling and
  ignoring replies after cleanup. These use a fake CLI callback, real production
  holder/parser, not a test-only recovery algorithm or a Room store.
- Real device-clock application stores extracted text and node-minus-injected-
  now drift; presentation returns unparseable text unchanged. Existing
  NodeSettingsApplyTest asserts fixed-now drift, OK clock update, bare-OK warning
  clearing with no second command; AdminSettingsTest asserts locale display
  and actual ClockAhead/generic failure/unexpected UI messages. Its `busy`/
  `weird` consumer inputs are NOT relabeled as the original `invalid time`/
  `hello` parser inputs; those exact originals remain in the core mirror.
- Real owner-info fetch converts wire text; apply converts display text and
  updates the original only on OK. Existing apply tests assert exact
  `set owner.info KD7ABC|ch 31`, stored display, and rejection preserving old
  state. Password change uses the raw-matching sender, clears both fields only
  on parser success, and flashes through the injected clock. Existing
  AdminSettingsTest checks validation, echo acceptance, cleared draft and exact
  command; the four original password classifier predicates remain in core.

Read the invoked CommandRecorder/TestFaults, VirtualClock and runSuspend helpers:
transport errors remain exceptions, sleep advances the injected clock and yields,
and the existing blocking test runner has its unchanged safety cap. No real radio,
database, native device or cancellation execution is claimed. Native holder
cancellation propagation/finally cleanup remains the documented WP-313 adaptation
to Swift catch-all behavior, not a newly passing original or a change here.

## Exact input binding

Four frozen canonical Git blobs below also equal the checkout's Git-normalized
blobs; the initial raw-byte check differed only because Windows checkout uses
CRLF. Entire selected test canonical SHA256:
`1b230fdf3e9e02d225df23314e07ce9260033efda87fc76fd99583e1f95f665a`.
All fourteen native input blobs are at direct parent ebf6; none changes here.
`C` = `android/core/services/src/`, `F` = `android/feature/remotenodes/src/`;
`K` means `kotlin/com/meshcoreone/android/`, followed by the stated package.
Frozen production consumer binding is whole-file, but its comparison scope is
the complete consuming functions above, NOT all unrelated view-model methods.

| Input path | Git blob |
| --- | --- |
| `MC1Services/Tests/MC1ServicesTests/NodeSettingsResponseParserTests.swift` (frozen) | `ffefc6670bdcc8719768f6255c4d5acf155c2bfe` |
| `MC1Services/Sources/MC1Services/Services/NodeSettingsResponseParser.swift` (frozen) | `bc98553110a5784c8f6c833f7aa3e68c7e5d06fc` |
| `MC1Services/Sources/MC1Services/Services/CLIResponse.swift` (frozen) | `944ec236d49f6ae172a8b08ba04a0cc1befb46db` |
| `MC1/Views/RemoteNodes/NodeSettingsViewModel.swift` (frozen) | `873c3e67bbf5cea94270acca163b1643acd70c74` |
| `C/main/K/core/services/remote/NodeSettingsResponseParser.kt` | `21b37ded41a7ef7b4248b84abcfdd54f0c934e76` |
| `C/main/K/core/services/remote/CLIResponse.kt` | `fa10d4b0f2e82b76143d9230c010bddace2ee9ff` |
| `C/main/K/core/services/remote/RemoteSwiftText.kt` | `aad6807ddc17aa0122757f694b30c4d69f37a470` |
| `C/test/K/core/services/remote/NodeSettingsResponseParserTest.kt` | `a08754a5aeca28201aebe49fc4bab58f030103fd` |
| `F/main/K/feature/remotenodes/cli/NodeSettingsResponseParser.kt` | `f8da167239e4634f9431fd9c4a6d6262b990549d` |
| `F/main/K/feature/remotenodes/cli/CLIResponse.kt` | `1f546338e2ba38d1fbd8d72f6516001545124b3a` |
| `F/main/K/feature/remotenodes/cli/RemoteSwiftText.kt` | `3311f0ad427e4929814104c9e718ca1bdde1bebb` |
| `F/main/K/feature/remotenodes/settings/NodeSettingsStateHolder.kt` | `3ba5a9c3d053aa8e9eee8e93ef9e8c63edd4418f` |
| `F/main/K/feature/remotenodes/settings/NodeSettingsPresentation.kt` | `ed43e3eb23579c9c264b90c45e5e467139dd4fe3` |
| `F/test/K/feature/remotenodes/settings/NodeSettingsLateRecoveryTest.kt` | `969b4cb4e5b54b965212223f55091a217d35e915` |
| `F/test/K/feature/remotenodes/settings/NodeSettingsApplyTest.kt` | `e0aefa42f670be11c88a65514b2f77d1a6b94360` |
| `F/test/K/feature/remotenodes/settings/AdminSettingsTest.kt` | `627e26c69bf0e9ac7d40200621b9051e9bc4130e` |
| `F/test/K/feature/remotenodes/settings/SettingsTestSupport.kt` | `3a7eccd6fc475739f1cfa075f61f78c7a7d6312e` |
| `F/test/K/feature/remotenodes/support/RemoteNodesTestSupport.kt` | `0c003e0b837f3935f2f2a46fe7f1a351e0a61470` |

## Admission and verification boundary

Before edits, same stored owner/attempt/session
`d6056690-3a5d-453e-b67e-36866c676ac5` evolved98->99 using Ledger.evolve_scope CAS:
six existing `wp-owned` path+operation admissions (`create`, `modify`,
`record-evidence`) for this new evidence path and `docs/android/deviations/WP-501.md`.
Only revision/paths/capabilities changed; identity/authorization remained intact.
The WP000 docs overlap at actual producer053db8 had neither path: disjoint.
Fresh actor916234, clean exact ebf6/ready165/base-mainDBB, unpaused policy and
trusted raw blobs held before evolution. Canonical manifest
`977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`,
policy `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.

Source-only validation completed successfully:

| Exact command | Result |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS, exit0 |
| `python tools\android-port\controller\validate.py` | PASS, exit0 |
| `python tools\android-port\portmap.py` | PASS, exit0 |
| `python android\parity-tests\audit.py --check` | PASS, exit0 |
| `python android\parity-tests\semantic.py --check` | PASS, exit0 |
| `git diff --check` | PASS, exit0 |

Exact twelve existing registration IDs equal the original inventory IDs;
complete frozen text/Git blobs and normalized mirror equality passed source checks.
This is NOT compilation, positive discovery, twelve passing tests, a new executed
family, or protected/full-WP/native acceptance. No local runtime/Gradle/WSL,
device/lease/recovery, CI dispatch/rerun, publication, merge or CI-result bundle.
Parent ebf6's own outcome and independent source review remain prerequisites
to any later publication; no parent execution verdict transfers to these notes.
