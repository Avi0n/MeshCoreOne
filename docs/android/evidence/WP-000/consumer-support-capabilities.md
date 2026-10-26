# WP-000 bounded consumer-support capabilities

<!-- android-port-wp:WP-000 -->

This is one trusted-policy support batch, not implementation or acceptance of WP-405,
WP-201, WP-214, WP-211 or WP-003. It adds six exact-path, WP-specific semantic capabilities,
positive/negative admission regressions and a reversible projection. It does not extend
generic `wp-owned` operations, rewrite primary ownership or change product files.

## Assignment and binding

The initial assignment was `WP000-SUPPORT-WP405-URI-NORMALIZATION`. Oversight's delegated
maintainer authority extended this same WP-000 batch before product edits to WP-201 DATA01
and WP-214 active-link forwarding, then WP-211 regional-label locale wiring and finally
WP-003 scoped local verification wiring. These are
distinct purposes in one support PR, not permission for another WP or a new worker.

| Binding | Actual value |
| --- | --- |
| Repository / owner | `cbattlegear/MeshCoreOne-Android` / WP-000 / tracked `port-orchestrator` role |
| Session / branch | `b2b6f2ee-7c0e-4d51-92b1-9b2ec4e771da` / `cbattlegear-wp000-wp405-uri-normalization` |
| Start trusted base | `263d30332940f09082709b9d38ee8877dfb9fedd` |
| Integration trusted base | `8a14eb2d90014f793de8e16e32fbcc5af5becee3`; disjoint WP-313 source/tests/docs integrated without changing this support block |
| Immutable reference | `db14559b39d32322b06477c6ae676112f583db50` |
| Manifest digest | `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8` |
| Candidate semantic policy revision | `b79a4f28dfbff3185bbcf62604bfbcf4cad160a633e4d57135f1638c8e495ce2` |
| Candidate capability revision | `f4223ec0389a02e4395632d0a4c4a92fcc7d9abb79611948b038eeca2ec578d0` |
| First admitted edit / coding start | Trusted `reserve.py admit`: WP-000, `docs/android/automation-policy.json`, `modify`, `wp-owned`; implementation began with the exact capability/projection definitions after scope and consumer inspection |
| Advisory reservation | Claim returned `BLOCKED: Existing WP lease is bound to different source/base/manifest/backend`; no new receipt acquired, historical authorization/identities unchanged, no release/recreate or private database edits |
| Runtime | Default tracked role/common skill; no custom profile injection, runtime installation, native/Gradle execution, settings or schedule changes |
| Head / PR | Git commit and PR metadata bind the final candidate; no self-referential head or duplicate CI-result bundle is committed |

The four edited docs/tools paths were independently admitted as `wp-owned` before edits.
Read-only `dispatch.py --dry-run status` reported local capability admission required,
advisory file intents, no pre-edit approval requirement, and no merge authorization.
Fleet activation/readiness remains separate and unapproved. All four new semantic
initial purposes reproduced a trusted-main admission denial before this amendment;
WP-003 `local/check.py` / `wire-scoped-local-verification` likewise returned no
trusted path+operation rule after its producer reported the actual denied paths.

## Exact capability paths and operations

The canonical full paths are in `automation-policy.json` and independently asserted in
`test_consumer_support_capabilities.py`. The prefixes below expand literally:

- `CHAT` = `android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/list`
- `RUNTIME` = `android/core/runtime/src/main/kotlin/com/meshcoreone/android/core/runtime`
- `RUNTIME_TEST` = `android/core/runtime/src/test/kotlin/com/meshcoreone/android/core/runtime`
- `APP` = `android/app/src/main/kotlin/com/meshcoreone/android/app/container`
- `APP_TEST` = `android/app/src/test/kotlin/com/meshcoreone/android/app/container/onboarding`
- `UI_TEST` = `android/ui-tests/src/test/kotlin/com/meshcoreone/android/uitests`

| WP / capability | Exact paths | Sole semantic operation |
| --- | --- | --- |
| WP-405 / `wp405-uri-region-normalization` | `CHAT/MeshCoreLinkParser.kt` | `repair-uri-region-normalization` |
| WP-201 / `wp201-active-connection-method` | `RUNTIME/ConnectionManager.kt` | `persist-active-connection-method` |
| WP-201 / `wp201-connection-method-regression` | `RUNTIME_TEST/DeviceConnectionMethodPersistenceTest.kt` | `add-connection-method-persistence-regression` |
| WP-214 / `wp214-active-link-channel-sync-input` | `RUNTIME/RuntimePorts.kt`, `RUNTIME/ConnectionManager.kt`, `RUNTIME_TEST/RuntimeOwnershipTest.kt` | `add-active-link-channel-sync-input` |
| WP-211 / `wp211-regional-label-localization` | `APP/onboarding/OnboardingRegionPresets.kt`, `APP/onboarding/AppOnboarding.kt`, `APP/AndroidAppContainerFactory.kt`, `APP_TEST/OnboardingRegionPresetAdapterTest.kt`, `UI_TEST/OnboardingRegionPresetComposeTest.kt` | `repair-regional-label-localization` |
| WP-003 / `wp003-scoped-local-verification` | `tools/android-port/local/check.py`, `tools/android-port/local/fast.py`, `tools/android-port/local/run.sh`, `tools/android-port/local/hook_test.py` | `wire-scoped-local-verification` |

Fifteen path/operation pairs cover fourteen distinct files. The new WP-201
regression operation permits creation, update and testing of that exact regression;
the other purposes modify existing files only. Generic `create`, `modify`, `delete`,
`test` and unrelated semantic operations are not added to these support rules.

## Consumer/source fences and future evidence

**WP-405:** pinned `MC1/Utilities/MeshCoreURLParser.swift` trims then calls
`utf8Prefix(maxBytes: ProtocolLimits.maxDefaultFloodScopeNameBytes)`. The pinned limit
is 30 and the Swift helper iterates `Character`. Actual feature parsing currently
caps 31 and iterates code points. Reuse public protocol `utf8Prefix` and the existing
30-byte constant unchanged. Do not edit protocol helpers or URI security, validation,
encoding, contact/map handling, secrets or failure behavior. Future owned App tests
must exercise actual external/chat/QR joins and region persistence: padded 40 ASCII
characters become 30; 29 ASCII plus combining `e`/accent excludes the whole final
grapheme; nil/empty and explicit-secret behavior survives. Broader Unicode trim-set
equivalence is not asserted.

Retained `APP-G04` was read from `root_cause_groups` in the authorized read-only
`semantic-grouping-global-consolidated-r118-candidate-001.json`, SHA-256
`62c185ffc08b9c38a37261d810147767b33f2a8ae2131a3a47c0622c660686d1`.
It is grouping/provenance context, not a current-head reproduction or passing family.
Its pinned parser family remains primary-owned as before.

**WP-201:** only the live `ConnectionManager.establish` method producer changes:
exhaustively forward the actual Bluetooth endpoint or WiFi host/port into existing
DTO/persistence behavior. The new test uses the existing real-session `RuntimeFixture`
through `DevicePersisting.saveDevice`, not a direct DTO-only proxy. Preserve the three
pinned `DevicePublicKeyDeduplicationTests.swift` families: fresh BLE persistence,
existing WiFi merge, stale BLE replacement. Assert stable radio identity distinct
from endpoints, one saved row and preserved preferences. No runtime helper/build edits.

**WP-214:** required `RuntimeServiceInputs.transportType: TransportType`, without a
default or saved-endpoint inference, receives actual `link.type` at the sole factory
call. Existing `RuntimeOwnershipTest` must assert BLE, WiFi and replacement-generation
inputs. Preserve platform detection, unknown-WiFi fallback, teardown, cancellation,
cooldowns, READY-only heartbeat and WP-201 persistence semantics. WP-201 is the first
`ConnectionManager` product producer; WP-214 waits for its result.

**WP-211:** wire current app-localized resources/locale into production
`ServicesRegionCatalog`, using existing generated `RegionalSubdivisionNames` and
core `RegionalAreas` injection for sorting, labels and selected-region display.
The exact factory path is necessary because it constructs `OnboardingPlatform`;
only its regional resource-provider binding/import is admitted. Resolve resources
per call, including API31/32 `AppLanguages.attach`, not frozen Application/default
locale. Future App adapter and mounted picker tests retain French/German labels,
stable IDs, selection/presets, US/CA short form, nonUS/AU disambiguation,
Pennsylvania/Texas and English fallback. No generated/shared resources, strings,
translation provider, services-to-Android dependency or onboarding redesign.

**WP-003:** only the four existing local runner/regression paths above are admitted.
The producer's `controller/local_scope.py` and `tests/test_local_scope.py` already
have separate authority. Preserve trusted repository/runtime namespace, native,
Gradle and installation interlocks, actual supervision/admission and owned cleanup.
Scope selection binds the complete actual candidate diff to immutable current trusted
base/head/tree; requested scopes cannot omit paths or obligations. Only fixed declared
tasks are allowed, with no arbitrary Gradle override. Default/full/pre-push/result
semantics remain unchanged; unclassifiable or build changes fall back to full checks.
Future selector/hook regressions must reject stale bindings, omitted paths, wrong
namespace, unsupported tasks and zero/failed/error/skipped test reports. No local
runner wiring, installation or execution-lane mutation is performed by this support batch.

**Concurrency:** the separate WP-503 source-binding proposal remains untouched.
An earlier peer statement that its new capability IDs already existed at merged
`23c2d985` was corrected after actual refreshed-main inspection; ancestry did not
prove those definitions existed. No foreign policy block or receipt was recreated.
These six new IDs are removed by an exact independent predecessor before the
existing historical chain; complete baseline equality and canonical projection
remain enforced. Disjoint future policy edits reconcile by exact capability IDs;
real semantic overlap serializes/transfers to the current producer.

Relevant pinned inputs were inspected read-only. Existing unrelated reference drift
in `AdvertisementServiceTests.swift` and
`MeshCoreSessionCommandCorrelationTests.swift` was reported to the coordinator;
neither is changed here and the source pin is not advanced.

## Verification and boundaries

Acceptance scope: WP-000 boundaries and source/provenance accounting only, not
completed acceptance of any consumer WP or original test family.
No product-behavior deviation is implemented in this support-only batch.

| Actual local command | Result |
| --- | --- |
| `python .\tools\android-port\controller\validate.py` | PASS: 1,866 tracked reference inputs, 64 WPs, 178 dependency edges |
| `python .\tools\android-port\controller\verification_config.py --check` | PASS: complete canonical configuration/projection |
| `python .\tools\android-port\portmap.py` | PASS: ownership/traceability mapping, no product file changes |
| `$env:PYTHONPATH=(Join-Path (Get-Location) 'tools\android-port'); python -m unittest discover -s .\tools\android-port\tests -p test_consumer_support_capabilities.py -v` | PASS: 7 discovered/run/passed, zero failures/errors/skips |
| `python .\tools\android-port\controller\test_runner.py --quiet` (pre-extension invocation) | Exit 2: 562 discovered/run, 533 passed, 11 failures, 18 errors, zero skips. Scope changed during this invocation; not coherent final-candidate evidence |
| `python .\tools\android-port\controller\test_runner.py --quiet` (stable final scope) | Pending serialized execution; no passing full-suite result is claimed yet |
| `git diff --check` | PASS |

The seven focused methods contain fifteen exact positive path/operation assertions,
all other manifest-WP denial checks, cross-purpose/generic-operation/neighbor-path
negatives, excluded helper/resource/WP-503 paths, exact predecessor equality and
partial/duplicate/reordered/widened-policy rejection. No assertions or validation
requirements were lowered. Full-suite results must pass before completed-batch
publication; hosted exact-commit jobs/logs remain authoritative after publication.

The pre-extension process loaded five-rule definitions before the later approved
WP-003 sixth-rule extension. Subsequent policy reads then failed the exact-block
projection, and an immutable-input check observed the not-yet-committed policy.
The partial result is retained as a failure, not relabelled a passing candidate.
The complete six-rule scope is committed before its serialized full-suite invocation;
no code/policy changes occur during that run. Capacity is coordinated with the other
existing Python suites; no process is terminated or runtime/native lane modified.

Consumers may evolve their existing advisory reservations with these named operations
only after trusted-main landing. This candidate does not make its own support rules
trusted for product edits. Protected exact-head/current-base review and landing remain
the parent oversight decision. No native, Android, iOS, hardware, signing, legal,
fleet activation or full feature acceptance is claimed.
