# WP-503 native audit helpers

## Current-owner language source adapter

By default, `language_binding.py` prepares only the unchanged issue238 fixture's two methods
on each API31/37, against the real PR247 repair and the actual clean committed
current source. It records canonical identity separately from the replacement
app/CLI identity, current reservation revision and unique per-API operation owner.
It verifies producer ancestry/blob, strict fixture bytes and method inventory,
and uses the deployed debug-packaging source-identity/capability checks.

Read-only preparation, with the actual current committed head:

```powershell
python android\accessibility-tests\language_binding.py --head CURRENT_HEAD
```

The old `--require-native-admission`/hardcoded-None hook is removed. Its proposed
`admit_language_device` method was our unfinished software, not a vendor API,
installable integration or independent authority. No extension or fleet
activation is required to invoke the actual existing local process boundary.
The static `gate_runtime.capabilities` diagnostic remains non-authoritative.

`language_runner.py` is the direct-process consumer. It reuses the shared
runner's parameterized lane, installer, strict parser, uncertainty, bounded host/
guest cleanup and original-package restoration. The historical constants,
missing-only27 selectors and old default entrypoint are not rebound.

Explicit host/source/configuration readiness (no device probe, lane, build or
instrumentation), using actual current values rather than a historical endpoint:

```powershell
python android\accessibility-tests\language_runner.py --head CURRENT_PUBLISHED_HEAD --api 31 --readiness --adb CURRENT_ADB --host CURRENT_LOOPBACK_IP --port CURRENT_PORT --serial CURRENT_EMULATOR_SERIAL --state CURRENT_BUILD_INPUTS_JSON --debug-home EXISTING_DEFAULT_DEBUG_HOME --output NEW_PRIVATE_OUTPUT_DIRECTORY
```

`--state` uses the existing `candidate_environment` build-input schema
(`schema_version`, `host`, absolute `java_home`/`android_home`/`private_root`,
`provenance`), not an adapter-state file or JSON admission grant. The supplied
ADB must exist in that SDK. Debug-home inspection checks file metadata only;
no key is read, created, exported or copied by this consumer.

Only when actual live guards permit, replacing `--readiness` with `--execute`
performs that API's **two original language methods only**. Select the other API
explicitly with a fresh output directory; there is no automatic replay/default
full cohort. Current clean source/policy/owner/reservation and exact published
PR205 head are rechecked before the lane, after packaging and before native
start. Policy/manifest must match verified current main; the execution policy
is read from that immutable Git object, not a candidate/worktree authority grant.
The actual policy pause blocks before device commands or lane acquisition;
it is not a demand to enable dispatch/merge automation.

Inside the unique held operation lane, the consumer authenticates the explicit
existing transport and observes actual API/boot/fingerprint/CPU, compact font200/
IME/reduced-motion profile, quiescence and System app locale. It reads only the
debug app's named locale preference to reject a non-System selection or pending
migration, never resets a user's language. Both APIs bind actual owner user0
before private/package access and reject a user switch through cleanup. Package
and instrumentation commands explicitly select that bound user. API33+ queries
LocaleManager with that numeric user and verifies the same user in its response;
Android's `current` sentinel is not a numeric observed user.

The original settings and display overrides are captured before temporary audit
setup. A `null` setting is confirmed absent in the settings inventory, not treated
as a made-up default or confused with a failed probe. Only recorded temporary
font/IME/motion and display changes are restored; originally unset settings use
`delete`, absent overrides use `reset`, and unexpected foreign values are never
overwritten. Restoration continues if an individual receipt write fails.
Missing endpoints are surfaced, not started. Physical devices, unexpected
profile changes and occupied native/Gradle lanes fail closed.
The deployed `assemble_debug_pair` receives the actual operation owner,
uses the existing debug identity, verifies App/Test/installed public certificates
and preserves exact build/source hashes. Public APK copies are verified before
installation; an originally present target APK and compatible Room schema are
preserved, and an existing test package is never replaced. A genuinely absent
target is distinct from a failed probe or retained package registration/data.
The public inventory explicitly uses `pm list packages --user all -u PACKAGE`
before private/profile capture and build, and again after packaging. On API31,
omitting `--user all` queries user0 rather than all known packages; owner0 alone
does not establish global absence. This inventory never reads another user's
private data or admits non-owner execution.
Only this run's verified newly introduced packages may be removed to restore
original absence; an originally present target is never uninstalled or cleared.

API37 captures the actual effective font scale from `mGlobalConfiguration` in the
original held-lane profile, not an assumed1.0 or a raw-setting proxy. Native entry
also requires effective200%. Restoration keeps uncertainty set until immediate,
then0.1s- and0.2s-spaced observations match every original raw/profile field and
that original effective font scale, with no active instrumentation at each
observation. Each observation is journaled; missing/invalid baselines, read/write/
journal failures or instability retain the lane. Volatile Activity configuration
sequence counters are not font state. The release guard rechecks the full profile.

API31 can persist a framework-owned `font_scale=1.0` after deleting the owned
temporary2.0 from an originally unset setting. Cleanup waits for effective
configuration settlement and permits only one further deletion, after strict
Android-writer/default metadata, new update/delete history correlated with the
pre-deletion history, and unchanged other captured state. It journals both
deletions, never writes1.0, and requires stable exact absence afterward.
Unknown provenance, foreign state or timeout retains effective-profile
uncertainty and the lane even if the raw setting temporarily reads unset.
Already-unset raw font still requires effective/stability proof without another
write. The extra deletion requires nonempty overlapping pre-deletion history
and strictly newer update/delete events; lost, unchanged or ambiguous history
fails closed rather than authorizing cleanup.
Receipt failure still permits independent cleanup but remains an error.

**Current shared-device mutation-bearing admission is blocked.** The real device implementation
has neither genuine pre-mutation fixture/preferences/window originals nor a
supported snapshot-consuming recovery operation. `require_fixture_recovery`
therefore raises explicitly, with no availability flag, provider or operator
override. The current-source execution entry checks this before readiness,
packaging, lane acquisition, profile setup or installation; direct pair-install
and instrumentation entrypoints and the historical execution CLI also check it.
Read-only source preparation remains separate. Synthetic devices admit only
in-memory downstream regressions; they are not native recovery evidence.

For future admitted instrumentation, the existing recovery receipt records the
actual `host_instrumentation_process_exit` immediately after the bounded wait,
before transcript parsing or all-positive proof can reject the result. This is
not the CLI exit or terminal instrumentation status. Receipt failures still
attempt independent cleanup and retain uncertainty. This does not backfill the
missing host exit in the original473 Nodes failure, recover its fixture state,
or authorize releasing its retained lane.

Normal all-positive completion and original packages/profile must be verified
before releasing the actual operation owner. Spawn/wait/receipt/read/decode/
terminal-proof failures retain uncertainty, independently attempt bounded host
and verified-owned guest cleanup, and never equate APK restoration with fixture
preference/window recovery. Cancellation and cleanup failures remain visible.
Output under the new private directory retains operations, original/public APKs,
packaging log/binding and exact two-method transcripts/recovery receipts.
Synthetic unit transports/processes are not native evidence. Transcript-only
accounting and the runner both leave whole-WP `acceptance=false`.

`disposable_runner.py` is a separate consumer of the reviewed WP-003 private-guest
interface, updated at `c9af5bf041ac84fc470c1ab75e309b5a89cc4f0d`.
It does not remove the shared-device fence or adopt an existing guest. Before
import/allocation it requires the reviewed producer bytes in actual trusted main
and the actual checkout, and trusted capability/pause policy. The real factory
then requires the existing canonical session/attempt, evolved disposable receipt
and eligible claim state; it allocates a new private guest with independent
transport plus disposable and global Gradle receipts. The original LOCAL
preparation predated trusted producer admission; actual merged main
`1be2e311457fcbb0abc0e1fdf5426b207b63a962` includes reviewed image,
Windows listener, exact selected-transport registration and normal
boot-animation lifecycle repairs.
The subsequent reviewed93 producer adds only `-show-kernel` to the existing
owned process log, so a failed startup can retain actual guest-kernel progress.
It changes no CPU/configuration, strict boot predicate, ownership rule or
deadline. Prepared consumer pins do not make this runtime available: import
and allocation remain blocked until actual trusted main and the checkout
contain its exact reviewed inputs.
Actual normal merge `423b29b568a096c00374d241b2d8d408ec1e27ee`
contains this diagnostic producer; the consumer still rechecks the four
actual inputs, current source/identity/policy and resources before live use.
Normal merge `d708b2724d28ba675017514e3f125db982070f6f` additionally
contains reviewed06's bounded two-second natural owned packaging-quiescence
observation. Actual empty membership and retained unique diagnostics are
required before proceeding; Windows reports counts with unknown PIDs explicitly
null, while Linux retains/reaps its observed owned PIDs. It does not kill to
pass, weaken ownership or change the original boot/instrumentation deadlines.
The consumer preserves packaging failures with unknown instrumentation exit
and independent disposal. Neither the model regression nor producer merge
diagnoses the prior failure or proves a native startup.
Normal merge `2764cdae80e8ed1eeb6fb9b9900cc04ba78821c9` additionally
contains reviewed966's unique retained startup/selected-command timing JSONL:
host UTC/nanoseconds and monotonic observations, configured timeout, startup
deadline where available, private endpoint/selected serial, and actual observed
PID or explicit null. Pre-call budget references are not backend deadlines;
raw kernel lines are not individually host-timestamped. Returned nonzero
commands and independent observation/retention failures remain visible together.
These diagnostics establish no transport fix or host/kernel clock alignment,
and change no original CPU/configuration, strict boot or120/180-second budgets.
Normal merge `295ebf37de8db1e6b295e6e74dae3efafe71c178` additionally
contains reviewedEDB's retained failure/before-cleanup owned-root snapshots.
Existing owned process handles report server/emulator/selected-command
poll results, with actual Job active count and unavailable PIDs explicitly null.
These are root-handle observations, not whole-guest/descendant exit proof.
Observation/retention failures preserve the primary failure and do not prevent
independent cleanup; no additional guest command, retry or transport fix is implied.
Actual source/runtime/claim/resource guards still run; merge is not guest readiness.
Normal merge `4c33f4fe8f2b115ecec7aa74491b6cede94af0ec` adds the reviewed
optional `start(..., cpu_cores=4)` diagnostic. The consumer's `--cpu-cores 4`
requests exactly four cores; omission preserves the emulator default.
Other values fail before factory allocation. The request is retained separately
from actual CPU observations: after boot the inherited profile capture must
observe four cores before profile mutation, installation or instrumentation.
Existing timing/status receipts label requested cores, not measured guest CPUs.
This option changes no image, memory, GPU, isolation, original case/assertion,
strict boot predicate or120/180-second deadline. Source preparation and models
do not authorize a launch or establish CPU as the cause of historical failures.
The consumer accepts the installed base-image spelling `37.0` as API37
(likewise integer base API plus `.0`), but rejects nonzero/ambiguous minor versions.
The merged producer accepts base37.0 and the installed package's explicit
`data/empty_data_disk` initialization layout, retaining legacy userdata copying
only when that regular installed image exists. It never adopts old guest data.
The private server uses hostless loopback-default `-L tcp:PORT`; the explicit
client endpoint remains `127.0.0.1`, with actual owned-listener verification.

The consumer packages with `installed_apk=None, guest=guest` before boot, verifies
concrete producer/Ledger identity, checks fresh package absence and the inherited
user0/profile/source guards, and routes every command through `guest.adb`.
After owned listener startup, exact `["wait-for-device"]` must complete before
framework getprop probes. This uses only the remaining existing120s boot
deadline; failed registration is propagated, never swallowed or retried.
The pinned producer must preserve the normal boot-animation lifecycle:
both boot and inherited profile guards still require its observed `stopped`
state. A suppressed/empty service is not substituted for that observation.
Only the existing fixed family requests are accepted. Instrumentation uses the
producer's explicit180s transport bound (the shared runner's600s is unchanged).
Actual host exit and raw output are retained before terminal-proof rejection.
Only a successful exclusive output-directory creation permits receipt writes;
a raced or failed creation never adopts that destination, including in cleanup.
Available instrumentation stdout and stderr are retained separately as exact
bytes (`device-FAMILYAPI.txt` and `device-FAMILYAPI-stderr.txt`). Nonzero exits
and timeouts retain both available streams; absent streams create no file.
Timeouts keep the host exit unknown and cannot earn a complete cohort. A failed
pre-instrumentation user probe is neither an instrumentation exit nor transcript.
Every owned exit independently attempts `guest.dispose`/`verify_disposed`;
failed cleanup/evidence is an error, never restoration or acceptance. No old
snapshot reconstruction is needed for a genuinely new disposable guest.

`test_disposable_runner.py` uses explicitly unit-only concrete protocol models
and a patched trust loader. It verifies consumer ordering and failure behavior,
not real receipts, Windows Jobs/Linux supervision, provisioning or native tests.
There is no runtime backend/availability override or alternate ledger/endpoint.
Source/models do not themselves authorize execution or publication; see the
[source and execution chronology](../../docs/android/evidence/WP-503/shared-api37-profile-admission.md).

For the current source-approved continuation, use the existing whole `nodes`
family; its seven original assertions include both unresolved failures. There is
no added method-selector/control feature. The reviewed clipboard fixture is now
bound to actual producer `1440142b`/blob `469f756f`; no old native result is rebound.

The authorized pair actually passed2/2 on each API at immutable source
`b2d9a00368432c04ccc40676417b2576bf472218`/reservation21. API31's initial cleanup
failure and separate verified, single-deletion recovery are both retained in
[native evidence](../../docs/android/evidence/WP-503/device-current-language-pair.json).
The subsequent automatic font-cleanup correction has synthetic regressions;
neither passing native cohort is replayed or rebound to that later source.

All pure adapter/cleanup/terminal regressions, with positive discovery:

```powershell
python -m unittest discover -s android\accessibility-tests -p test_*.py -v
```

### Fixed diagnostics family: software-only preparation

The same typed binding/request and process lifecycle also accept the explicit
`--family diagnostics` specification, restricted to API37 and exactly these five
original `NativeDiagnosticsFlowAccessibilityTest` methods:

- `noiseFloorHasDataBoundSummaryAndVisibleNumericAlternativesAtFont200`
- `shorterWindowRevealsEveryNumericAlternativeByActualNativeScrollAtFont200`
- `disconnectedNoiseFloorExplainsUnavailableDataWithoutAnEmptyGraph`
- `disconnectedCliExplainsUnavailableCommandsInsteadOfAnActiveTerminal`
- `disconnectedRxLogRetainsTextualStatusRecoveryAndReachableFilterSelection`

Read-only source preparation is
`python android\accessibility-tests\language_binding.py --head CURRENT_HEAD --family diagnostics`.
There is no arbitrary class/method/producer selection interface or second runner.
The immutable specification verifies original fixture and diagnostics shared
audit at `ff424766`, the complete unchanged diagnostics production subtree at
`8a8c6348`, shared native assertions at `f7474bb1`, and device harness at
`7f24b57c`, against both their original Git objects and current source.
Wrong family/API/producer/blob/method count/order, stale published source/main/
policy/scope, duplicate/missing outcomes or forced completion fail closed.
The language default remains exactly two methods on each API, not five.

Preparation and synthetic lifecycle/outcome regressions do not authorize or
perform a diagnostics native run, build, install or device operation. API37
diagnostics represents five of the original missing27; all27 remain missing
until genuinely executed with original identities. The separate language2+2
subtracts zero. Historical shared-runner selectors/default actor and all prior
evidence retain their original source bindings.

### Fixed Nodes family: software-only preparation

The same immutable specification, typed binding/request and shared process
lifecycle accept `--family nodes` on API37 only. Selection retains precisely the
seven original `NativeNodesFlowAccessibilityTest` identities in fixture order:

- `offlineChatRepeaterAndRoomKeepTransportDisabledAndSavedHistoryRoutingReachable`
- `offlineQrShareKeepsNamedAlternativeCopyFeedbackAndExactNativeIntentReachable`
- `actualSearchCrossesSelectedSegmentsAndNativeNameSortKeepsScaledResultsReachable`
- `offlineCachedNodeRetainsReadableNameTextualStatusAndNativeRowRouting`
- `offlineSyncShowsLocalizedRecoveryDialogWithReachableScaledTextAndDismissAction`
- `actualDetailKeepsALongCjkHeadingAndNicknameCancellationReachableAtFont200`
- `offlineClearMessagesRequiresConfirmationAndExplainsUnavailableServicesWithoutDismissingDetail`

Read-only source preparation:
`python android\accessibility-tests\language_binding.py --head CURRENT_HEAD --family nodes`.
The initial seven-method registration at `fc9188ab` has the same ordered method
inventory as the unchanged current fixture/audit producer `9101b45f`. The binding
pins that fixture and Nodes audit, the delegated offline audit at `41bdbfac`,
the complete Nodes production `src/main` tree (including its manifest) at
`fd9fa8e1`, and the same original assertion/device-harness pins used above.
Each current and original object and producer ancestry is checked; drift is an
error, not permission to advance a pin or edit Kotlin.

The unique owner is `CANONICAL:nodes:UUID`, with canonical/replacement identities
kept separate. Existing source/publication/main/policy/reservation, exact positive
count/identity, normal unforced completion, package preservation and unresolved
effective-profile fences are reused. Ordered fixture/selector checks do not
change AndroidJUnit's method scheduling or weaken strict outcome accounting.
Language remains the two-method default on API31/37; diagnostics remains exactly
five on API37. No arbitrary family/class/method interface or copied runner exists.

This increment has software-only binding/selection/lifecycle tests, not actual
Nodes execution, goldens or full-WP acceptance. It authorizes no native lane,
Gradle/APK build, ADB, install or instrumentation action. Nodes7 belongs to the
original missing27, which remains27; separate language2+2 still subtracts zero.
Keep the currently published candidate unchanged until its healthy automatic
verification is terminal and this local increment has been reviewed.

### Fixed original US comparator family: software-only preparation

`--family comparator` admits API37 only and exactly the eleven original
`NativeLocalizedCompareParityTest` methods, in original fixture/selector order:

- `asciiCaseMatchesSwiftEquality`
- `sharpSMatchesSwiftEquality`
- `canonicalAccentMatchesSwiftEquality`
- `fullWidthMatchesSwiftEquality`
- `nodeNameCaseMatchesSwiftEquality`
- `digraphCaseMatchesSwiftEquality`
- `ligatureMatchesSwiftEquality`
- `accentRemainsUnequalAsInSwift`
- `dotlessIRemainsUnequalAsInSwift`
- `dottedIRemainsUnequalAsInSwift`
- `equalNamesKeepTheirInputOrderWithTheProductionComparator`

Read-only source preparation:
`python android\accessibility-tests\language_binding.py --head CURRENT_HEAD --family comparator`.
The unchanged original native fixture at `5bbef0b2` explicitly invokes the
installed production class with `Locale.US`; its pair/scalar/assertion helpers
are contained in that pinned fixture. It does not inherit the Activity/Compose
device harness or shared UI assertion helper. Pins retain the complete production
`diagnostics/text` subtree and producer `SwiftTextParityTest` at `8a8c6348`, plus
the original WP316 `oracle.swift.txt`/`oracle-output.txt` at `d4cc3449`.
Current/original objects and producer ancestry are mandatory; a changed oracle
or product is drift, not permission to advance a pin.

The fixed owner is `CANONICAL:comparator:UUID`, and shared lifecycle purpose is
`comparator-api37-11-original-methods`. Existing selection, exact positive11,
normal completion, source/policy/scope/publication, package/profile restoration
and uncertainty fences apply without another runner. No parameter-pair count,
host collator or one proxy method substitutes for eleven original identities.
Supplemental `NativeLocalizedCompareLocalePreservationTest` tr/az2 remains
separate and unselected; its historical selectors are not current family names.
All prior language/diagnostics/Nodes contracts and the language default remain.

This preparation performs no native/build/device operation or new Swift oracle
execution. It is not Android ICU parity, goldens or whole-WP acceptance. The
comparator11 and supplemental tr/az2 remain part of missing27; local support and
separate language2+2 subtract zero. Keep the published candidate unchanged while
healthy CI runs, and obtain local source review before publishing this successor.

### Fixed supplemental Turkish/Azeri family: software-only preparation

`--family traz` is restricted to API37 and exactly the original two
`NativeLocalizedCompareLocalePreservationTest` identities, in fixture order:

- `turkishProducerCasesPreserveLocaleTailoringOnAndroid`
- `azeriProducerCasesPreserveLocaleTailoringOnAndroid`

Read-only source preparation:
`python android\accessibility-tests\language_binding.py --head CURRENT_HEAD --family traz`.
The immutable original/current fixture at `6c81bad0` invokes the installed
production class with only its hardcoded `tr-TR`/`az-AZ` tags. Its pair, scalar,
reflection, equality, reverse-comparison and stable-input-order helpers remain
inside that unchanged fixture. The full production `diagnostics/text` subtree
and `SwiftTextParityTest` at `8a8c6348` bind the original producer's four
equivalent/three unequal pairs per locale. Both current and original objects
and producer ancestry are checked; changed vectors or production fail closed.

These are **supplemental producer-preservation cases**, explicitly not an
independent Foundation locale oracle. The US comparator's retained oracle is
not used as Turkish/Azeri evidence or added to this family's provenance.
No Swift oracle, parameter-family proxy or extra comparator11 method is added.
There is no locale argument or arbitrary selector; `traz` selects both original
methods together, with `CANONICAL:traz:UUID` and the existing shared lifecycle
purpose `traz-api37-2-original-methods`. Exact positive2, current source/main/
policy/scope/publication, normal unforced completion, original packages/profile
and retained uncertainty are required. The language two-method default,
diagnostics5, Nodes7, comparator11 and strict UTF-8 remain unchanged.

Synthetic regressions and preparation do not execute Turkish/Azeri cases on a
device or authorize a build/native lane. All27 remain unexecuted in the current
missing-only cohort; language2+2 and prepared families subtract zero. Historical
US/tr/az native outcomes keep their own immutable source/APK/evidence bindings.
Keep this successor local for review while the published candidate verifies.

### Fixed original LoS saved-endpoint family: software-only preparation

`--family los` admits API37 and exactly one original identity:
`NativeLineOfSightAccessibilityTest#savedRepeatersExposeNamedNativeActionsForNonGestureEndpointSelectionAtFont200`.
Read-only source preparation:
`python android\accessibility-tests\language_binding.py --head CURRENT_HEAD --family los`.
The actual registration at `e4c1aa7a` is an ancestor of the unchanged final
fixture at `3f39a777`. That full fixture also contains the separate platform
button control `frameworkNativeAccessibilityTreeFindsAndInvokesTheActualNamedButton`;
it is retained and pinned but **not selected or counted** in LoS1.

`FamilySpec.fixture_methods` preserves this exact full two-method inventory while
`methods` selects only the fixed original saved-endpoint identity. Default
inventory behavior remains unchanged for all preceding families. Full source
inventory/order and a nonempty distinct ordered selection are mandatory; no
arbitrary subset/class/method argument is exposed. Original/current fixture,
complete LoS production subtree and producer saved-endpoint semantics test at
`c6cdac39`, shared assertions at `f7474bb1` and device harness at `7f24b57c`
must match their exact objects and ancestry.

The original fixture retains radio/contact UUIDs301/302/303, East and CJK West
repeaters at37.8/-122.4 and37.7/-122.3, fixed public keys, elevation100m and
device frequency906000kHz. Its deterministic dependencies require one catalog
read, two ordered elevation reads and no terrain/path analysis. Actual native
named-ancestor actions, selected/checked/state descriptions and rendered
name/role layout assertions remain intact, not replaced with a generic LoS
proxy, map gesture or new oracle.

The same typed request/shared lifecycle uses `CANONICAL:los:UUID` and
`los-api37-1-original-methods`, exact positive1 and all existing source/main/
policy/scope/publication/package/profile/uncertainty fences. The platform control,
whole two-method class, missing/duplicate/wrong outcomes or forced termination
cannot substitute for the original selected method.

This preparation is software-only: no live host readiness, ADB/build/install/
instrumentation/native-lane action or LoS/native/golden/whole-WP acceptance.
All27 remain unexecuted in the current missing-only cohort; language2+2 and
prepared families subtract zero. Cached-chat1 remains a separate software family.

### Fixed original cached-chat family: software-only preparation

`--family cached-chat` admits API37 and exactly the original identity
`NativeLauncherFocusAccessibilityTest#realCachedChatDraftAndScrolledMessageSurviveCurrentWindowRecreationAtFont200`.
Read-only source preparation:
`python android\accessibility-tests\language_binding.py --head CURRENT_HEAD --family cached-chat`.
Registration `4f89d216` is an ancestor of the retained final/current fixture
at `e8fa6b29`, blob `698d649b230b7d7945a5d901d37f748427508986`.
The full launcher fixture has **ten** original/control methods in fixed order.
Only the cached-chat resize/draft method is selected; separate IME, framework,
motion, appearance, navigation and intentional cleanup-fault methods are not
selected, replayed or counted.

The original complete chats input at `4b6629ac` remains historical. The current
complete production subtree is pinned to merged `514c4516`, preserving the
`32bb28c6` Rooms/message-actions work while incorporating the mounted
scope/prefetch changes. It includes actual composer/timeline UI and state,
reverse-layout pixel-offset capture, saved-anchor restoration, message body
and ordinary draft handling. Producer timeline tests,
the mounted `ChatTimelineRestorationTest`, navigation/screen/message consumers,
the complete rendering/draft-helper subtree, shared assertions and device
harness retain independently checked exact producer/current objects and ancestry.
The delegated timeout-only `NativeAccessibilityServiceSnapshot` is also pinned
to unchanged producer `cc360e7b`/blob `3dee4f40c...`; diagnostic snapshots never
replace the original failed deadline with a successful native result.

There is an explicit **current-input distinction**, not historical equality:
merged `03306ff1` introduced the `AppChatsContent`/`AppChatsFeature` consumer/delegate
and five-case `NotificationDraftRestorationTest`, pinned separately. The
retained `3f39a777` Content/Feature objects `2b5ccd083...`/`ac0301b1...` remain
historical; the `03306ff1` objects are `6e191c79d...`/`99ff02f5c...`, and regression
`f076dceef...`. Content and that regression remain unchanged; the current
Feature delegate is merged `514c4516`/`11d188156...`, and its message-content
consumer is `dc2633bc2...` from the same actual merge. Only those changed
production bindings advance; the original `e8fa6b29` fixture is not repinned.
The current effect keys on composer plus notification-source
identity; absent/wrong-radio service, stale identity or absent pending reply
does not mutate the draft. These merged inputs are current software provenance,
not a relabeling or revalidation of any old native proof. Both historical objects
are asserted explicitly, and substituting either for a current pin is rejected.
Frozen Swift, the original native fixture and every prior family pin are unchanged.

The unchanged selected fixture uses radio/device503001, contact503002,
32-byte0x50/0x51 public keys, the CJK cached-peer name and CJK/Arabic draft,
36 incoming/delivered/read messages at Unix1700000000+index with UUID503100+index,
target index15, real process persistence and disconnected state. It observes
actual row placement/finite geometry and the full visible-message set, moves
12px to24px without changing that set, then requires the same measured pixel
offset, navigation entry/stacks/selection and draft through900x900 and360x900
recreation. Window override, ordinary draft, onboarding flag, navigation and
seeded store rows are restored by the original cleanup. No generic chat proxy,
host text comparison or newly generated oracle substitutes for these inputs.

The existing immutable inventory/selection and shared explicit lifecycle use
`CANONICAL:cached-chat:UUID`, purpose `cached-chat-api37-1-original-methods`,
exact positive1 and all source/main/policy/scope/publication/profile/package/
uncertainty fences. Wrong launcher method or whole class, duplicate/missing/
failed/skipped outcomes, incomplete/forced completion or unresolved effective
profile cannot release uncertainty as a successful original method.

Preparation and synthetic lifecycle tests do not execute the method or authorize
host readiness/ADB/Gradle/APK/build/install/instrumentation/native-lane work.
All six original missing-only families can now be selected by current software;
all **27 remain unexecuted** in the current cohort. Language2+2, controls and
preparation subtract zero. No native/golden/physical TalkBack/whole-WP acceptance
or transfer of publishedf3 CI results is implied.

The current consumer's Git helper explicitly decodes UTF-8 with strict errors
for both fixture source and trusted policy JSON. It never uses the Windows
default code page, replacement characters or a fallback; malformed Git output
raises a visible failure. The same immutable policy bytes interpreted as CP1252
can remain parseable JSON but produce the wrong semantic fingerprint, so
matching a loaded source file alone is not sufficient.

The sections below retain historical source-bound entrypoints and results.
In particular, the old `shared_device_runner.py` missing-only27 source/default
selection is **not** the current-owner language entrypoint. Its shared lifecycle
helpers are reused without advancing its historical source or result bindings.

## Publication and execution are different source configurations

PR205 publishes this owned audit surface, WP503 documentation/native evidence
and the existing six-line App test-source registration only.39 execution-only
producer paths are restored to exact current-main8702db8b/base bytes in the
final PR diff; their owner-attributed integration commits remain in history.
WP501 test wiring and WP504 release/R8 configuration are unchanged.
The corrected producer repairs must land through their existing owners;
they are not WP503 product changes or implicit dependencies declared complete.

All native outcomes retain their original composite source/fixture/APK/runtime
bindings. In particular, LoS1PASS9.563s executes3f39/final315c6, Nodes7PASS14.11s
executesa0/final311fd9, and original US11 plus supplemental tr/az2 pass atba67/
final3168a8c. Those production blobs differ from the publication configuration.
No native result, hosted success or standalone acceptance transfers to its tip.
No successful cohort is rerun and API37 remains blocked before launch.
[Exact separation and source paths](../../docs/android/evidence/WP-503/publication-scope.json).

## Historical preparation: bounded strengthened repair cohort, then unexecuted

The existing saved-LOS action method now retains its original actions and
checks native localized selected/stateDescription and measured visible role
Text/layout/ink through unselected/A/B/clear states. The cached-chat recreation
method retains all original assertions and compares actual public row/list
pixel offsets after a12px-to24px offset-only scroll with unchanged visible rows.
These assertions have not yet been compiled or executed on a native device.
Combined source18cc3eb7 imports the exact reviewed307/315/316 Android bytes
and their directly related producer test/build/lock wiring without changing
any audit fixture. No build or new APK has been created for that source.

`NativeLocalizedCompareLocalePreservationTest` separately retains WP316
producer8a8c6348's explicit tr-TR/az-AZ cases: four equal/three unequal pairs
per locale, both equality/comparator directions and stable equal ordering.
It invokes the installed production public JVM ABI, not copied comparison
logic. These2 methods are supplemental producer-preservation evidence, not
new independent Swift oracle results. The original US fixture/vector bytes
remain unchanged at10 vectors/11 methods.

The session's `reviewed-repairs` selector expects23 exact identities:
21 existing repair-audit methods plus2 supplemental locale methods. Broad
`feature-matrix`80 excludes both independently selected comparator classes.
Do not call the changed assertions a rerun/pass of the original83 inventory.
Before the single combined build, the producer must expose an actual public
observation for LOS role Text currently cleared by `clearAndSetSemantics`.
Combined source/APKs, WP316 lane release and separate37 authorization are
still required; no blind retry or stand-in stateDescription evidence.
[Bindings, commands and exact blockers](../../docs/android/accessibility/execution-matrix.md#prepared-strengthened-producer-cohort-unexecuted-and-blocked).

## Focused production collation parity

`NativeLocalizedCompareParityTest` runs the retained independent Swift vectors
from WP-316's `oracle-output.txt` and `SwiftTextParityTest` on actual API31/37,
not host-JDK or copied collation logic. Its ten vector methods require both
production equality directions and both production comparator zero/nonzero
results at Locale.US; one additional method requires stable ordering for all
seven equal pairs in both input orders. Test-only reflection loads the actual
installed target class through its target classloader and invokes the public
JVM ABI of the Kotlin-internal implementation. There is no production
visibility/API change, fake collator, normalization or fallback in the audit.

The existing runner's focused `localized-compare` selector expects exactly11
distinct starts/matching outcomes on each fresh owned API31/37 profile:

```powershell
python "$F\wp503-private-adb-native31.py" --phase localizedcompare --head EXECUTED_HEAD --both-apis --scenario localized-compare
```

This focused cohort has no Activity/Compose rule or service readiness guard.
It proves only the retained comparison vectors and stable equal ordering,
not native user-flow, physical TalkBack or whole-WP acceptance. The earlier
83 inventory and every negative stay bound to their earlier source/APKs;
this addition is not an84/94-case full-suite result. Broad `feature-matrix`
selection must exclude this independently selected audit class.

## Existing feature and service cohorts

The existing App AndroidJUnitRunner hosts the native accessibility fixtures.
The declared session `wp503-prepare-native.py` and `wp503-run-native.py` entries
retain API31/37, font200, actual guest CPU verification, positive discovery and
the existing 300s boot/600s instrumentation limits. `--scenario launcher` expects
nine distinct starts and matching terminal outcomes, explicitly excluding the
separately required controlled-cleanup method. The83-method runnable inventory
requires four distinct cohorts: `feature-matrix`80, `platform-service-control`1,
`platform-callback-control`1 and `cleanup-preservation`1. Each requires its own fresh owned device
and instrumentation process; do not combine them into one package invocation.
The former `full` package selection is blocked, not relabeled as an80-case pass:
actual82-case runs retained historical Activities and correctly failed both
zero-Activity guards. Their failures remain evidence. `feature-matrix` explicitly
excludes those two separately required controls and both separately selected
comparator classes with AndroidJUnitRunner's `notClass` argument, plus the
separately required fault-injection method by its
class#method identity. No method, assertion or count is dropped from the inventory.
Do not retry failed cases until green or reduce these counts.

The existing private-adb adapter executes the four declared cohorts with fresh
scratch images per API/cohort and unchanged300s boot/600s instrumentation guards:

```powershell
python "$F\wp503-private-adb-native31.py" --phase cleanupfeatures --head EXECUTED_HEAD --both-apis --scenario feature-matrix
python "$F\wp503-private-adb-native31.py" --phase cleanupplatform --head EXECUTED_HEAD --both-apis --scenario platform-service-control
python "$F\wp503-private-adb-native31.py" --phase cleanupcallbacks --head EXECUTED_HEAD --both-apis --scenario platform-callback-control
python "$F\wp503-private-adb-native31.py" --phase isolatedcleanup --head EXECUTED_HEAD --both-apis --scenario cleanup-preservation
```

`$F` is the existing session-files directory. Record each actual source/APK
binding and80+1+1+1 unique identities per API; never substitute a prior-source
control or a full-context positive for standalone cold-start evidence.

`DeviceAccessibilityHarness` awaits the real `MeshCoreApplication.container`
startup contract on the instrumentation thread before feature setup or service
requests. Public in-window stack/Looper observations found real process graph
construction occupying the main thread while client-state messages and the
heartbeat queued overdue. Existing launcher bodies already await this same
container, but formerly only after requesting touch exploration. Awaiting the
actual prerequisite before that request changes ordering, not a deadline: no
sleep, synthetic readiness, Compose-clock advancement, extra resources or
production change is introduced. Startup exceptions propagate; the existing600s
instrumentation guard still bounds execution. This audits process-ready feature
flows, not cold-start latency or touch-exploration delivery during initialization.
The independent cold platform controls do not inherit this prerequisite; all
their recorded failures remain.

`--scenario platform-service-control` selects one new
`NativePlatformServiceControlTest` method. Unlike the preceding service control,
it has no Activity/Compose rule or test dispatcher. It verifies zero initial
Activities and observes lifecycle callbacks throughout the control. Actual
manager state, acknowledged flags, original1000ms activation/restoration windows,
late-success rejection and public snapshots remain. The original80 test bodies,
helpers and assertions are unchanged. This isolates another harness confound;
a passing control cannot clear historical feature failures or prove their cause.
That control introduced inventory81; no full81 invocation occurred. The
additional callback diagnostic below expands the current inventory again.

`--scenario platform-callback-control` selects one new
`NativePlatformCallbackDeliveryTest` method. It retains the same real manager,
acknowledged flags and original1000ms activation/restoration predicates, while
registering a public touch-exploration listener and sampling actual main-looper
progress through nonblocking, at-most-one-pending heartbeats every100ms.
Heartbeat times, public callback times and a post-timeout public Java thread
stack are diagnostic observations, never readiness substitutes. When a main
heartbeat remains pending100ms, one public Java stack/Looper.dump sample per
phase records actual queue/dispatch state, collection duration and whether the
heartbeat remains pending. Collection time counts against the unchanged1000ms
window; a probe can affect timing and is not a causal readiness fix. No Activity,
Compose rule, test dispatcher, fake clock or service-state writes beyond the
original request/restoration exist. All preceding81 methods/helpers remain
unchanged. This is a different diagnostic treatment, not a retry or causal fix
of their failures. That82 inventory requires its three compatible cohorts,
not a shared-context package pass. The additional controlled
cleanup regression below brings the current runnable inventory to83.

`NativeLauncherFocusAccessibilityTest` samples AccessibilityManager state through
the actual main looper, where Android applies its binder-delivered state updates.
The same predicates and original1000ms activation/restoration deadline remain;
time spent waiting for the main looper counts, and a late true sample fails.
Main-thread sampling is not a service readiness guarantee or physical TalkBack.
On timeout, the original exception remains primary while the existing public
system/client snapshot collector runs as a close operation. Snapshot failures
are suppressed onto that exception, not substituted for it. Collection is
explicitly after the original deadline: late state changes never earn a pass.

## Controlled primary/cleanup failure regression

The additional launcher-class method explicitly injects a primary AssertionError after
actual activation and a bounded1500ms real-main latch stall. The unchanged
restoration waiter must fail its original1000ms deadline, even if its first
late sample observes the desired state. The same primary object must escape,
with exactly the actual ComposeTimeoutException suppressed. Real original
flags/client state must be restored after the failed window; that safety
observation is not a cleanup-deadline pass. The main latch releases on every
exit. No original82 method body, waiter, deadline, product or237/238 assertion
changes. This is explicit harness fault injection, not a naturally occurring
dual failure, physical TalkBack observation or new user-flow acceptance.

The initial mixed launcher10 invocation atb7 is retained:31 10PASS37.083s,
37 9PASS/1FAIL89.394s, original motion-flow activationfalse87 checks1001ms.
The controlled preservation method itself passes both. This does not establish
that the fault caused the later activation failure; neither may it be treated
as an unperturbed feature-only invocation.

`--scenario launcher` therefore selects the original9 only, using the public
AndroidJUnitRunner class#method exclusion; `cleanup-preservation` explicitly
selects the one fault method on its own fresh device/process. Do not mix an
intentional real-main fault into normal feature acceptance. Earlier82-case
reconciliation stays bound to its original source/APKs; it is not a current83
run. Do not report full83 acceptance without all four compatible current-source
cohorts and exact identity/input reconciliation.

## Owned emulator teardown

After the owned emulator process has exited successfully, wait for its **specific**
adb transport to disappear before removing its exact scratch image paths:

```powershell
python android\accessibility-tests\native_device_cleanup.py --adb C:\android-sdk\platform-tools\adb.exe --serial emulator-5714
```

The observation is bounded to10s, includes adb command time, requires the actual
transport-list header and rejects late success, command errors, malformed output
and duplicates. Another user's device does not have to disappear. This only
observes; it does not stop devices, restart adb, or change instrumentation limits.
The prior immediate-empty assertion failure remains historical evidence.

Deterministic helper tests, with positive discovery:

```powershell
python -m unittest discover -s android\accessibility-tests -p test_native_device_cleanup.py -v
```

Device-run identities, commands, APK/source bindings, actual results and remaining
gates belong in `docs\android\evidence\WP-503\`, not in a second hosted-CI bundle.
