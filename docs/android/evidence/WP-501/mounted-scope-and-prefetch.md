# WP-501 mounted channel scope and receive admission

## Binding and scope

Implementation follows the user-directed repair after PR260, not the earlier
witness-only assignment. Merge `2594d3dedce0f742b507ffba48a476acc84bdf80`
has parents `ec6bbb5b9ffef9f7e13480433ea8c2319579b566` and landed main
`ea67cd867e7fc178046ee2829ff059b1734ef1b3`. Its single workflow conflict keeps
both parity-test discovery and main's immutable candidate-event inputs.
The product commit immediately following that merge contains this evidence.

Frozen specification remains `db14559b39d32322b06477c6ae676112f583db50`.
Manifest remains `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`;
landed policy is `70f65b1205f1581c0f1a86107ccdc20f794421a6cf9b172e17d8a3e544569a66`.
Same actual owner/attempt/session `d6056690-3a5d-453e-b67e-36866c676ac5`
automatically evolved reservation104 through109 before the respective edits:
`channel-open-flood-scope`, `receive-prefetch-admission`, narrow
`app-build-launcher` support, and wp-owned tests/evidence. Historical identity,
authorization and binding are retained, not rewritten.

## Actual production consumers

`ChatViewModel.open` populates first, then calls the channel scope seam even
when population fails, before its loaded/unread/failed-send bookkeeping guard.
Ordinary scope errors are reported through existing diagnostics; cancellation
propagates without caching success. Direct-message open, refresh and send do
not call this seam.

`AppChatsFeature.timeline` reads the saved channel selection and actual
connected device, calls the existing resolver, and reaches the private real
protocol session through `RadioSessionContainer.applyChannelFloodScope`.
The process adapter serializes commands and caches only acknowledged success,
keyed by actual session token, preference, device default and capability.
Sharing the cache across timeline adapters matters: A -> B -> retained A
must restore A rather than deduplicate against A's obsolete private history.
The cache is invalidated before attempting a new command. Foreign-radio,
non-ready and superseded sessions cannot supply acknowledged success.

`ChatTimelineDependencies.content` is bound to the already process-owned
`AppChatContent`, not an additional fetcher/store. Incoming URL-bearing
events await its real `InlineImagePrefetcher` with the production 3,000ms
cancellable deadline; plain text, master-off and a genuinely absent optional
seam retain the immediate path. Outgoing events append before launching
screen-owned asynchronous prefetch. Conversation filtering and actual session
identity are checked before and after suspended incoming admission.

The prefetcher uses the existing image HTTP/cache/header decoder, shared
dimensions file, real preview cache, DataStore preferences and Room persistence.
Master and DM/channel auto-resolve gates remain separate. Disabled automatic
card resolution can consult existing caches without triggering malware-feed
or metadata requests. Enabled automatic requests retain the native malware
filter and original URL-safety default. The constructor-only image safety
seam delegates to the existing producer's test injection point; the production
default is unchanged and the actual HTTP adapter retains redirect safety.

The real Compose row now reads the shared extraction/classifier result,
including Giphy shorthand, and the actual cached aspect. Process dimensions
and HTML verdict updates invalidate that presentation. Known HTML image URLs
take the existing card holder/port path on reentry instead of reconstructing
an inline shimmer. The row reserves the cached aspect while image bytes load.
Shared preview workers keep their existing process lifetime: cancellation of
an admission waiter does not cancel another consumer's metadata fetch.

## Source and executable witnesses

Complete frozen `ChatViewModelAdmissionTests.swift`, all nine bodies and invoked
setup/persistence/image/preview helpers, `ChatViewModel+Prefetch.swift`, and the
channel loading/scope implementation were compared with the real native
contracts/ViewModel/App adapter/session, cache/dimensions/extraction/preview
services and mounted `AppMessageContent` consumer. The earlier resolver and
seven-original orchestrator comparisons remain intact; they are not replaced
by these mounted counterparts.

`ChatPrefetchAdmissionAcceptanceTest` initially added fourteen methods. The nine frozen
admission scenarios retain their operative inputs and predicates:

| Frozen scenario | Actual native witness |
| --- | --- |
| Plain text | `hello world, nothing to fetch`; exact row; zero HTTP collaborators |
| Master off | `see https://example.com/cat.png`; zero HTTP/safety calls |
| Fast URL probe | Same cat URL, 50ms; no row before completion |
| Nil prefetcher | Same cat URL; genuine absent App content seam, immediate row |
| Dimensions | `look` + same URL; real 200x100 PNG header, matching row media aspect2.0 and file reopen |
| Slow probe | Same URL, 5s vs injected200ms; admission under1s, joined cancellation/no late dimensions |
| Giphy | `g:abc123`, 5ms; literal `https://media.giphy.com/media/abc123/giphy.gif` |
| HTML image reentry | `pasteboard.co/<UUID>.png`; real HTML verdict and persisted Pasteboard card; no repeated request |
| Outgoing | `outgoing https://example.com/dog.png`; actual pending-send creation, immediate row before10ms probe |

Incoming event fixtures retain timestamp/createdAt1000, delivered status,
fresh UUID identities and DTO defaults. The outgoing witness intentionally
uses the actual native pending-send clock/identity rather than replacing that
production creator with a timestamp fake. HTTP bodies/suspension and DNS
safety are deterministic test inputs; transactions, image/header decoding,
dimensions writes, event adapter, resolver and protocol exchange are real.
No external HTTP/DNS request is made by the fixtures.

Additional methods prove the unmodified production 3s default, independent
late preview completion into the actual native card holder and Room cache,
screen cancellation/disconnection, distinct DM/channel privacy gates, and
actual protocol polling -> Room -> App event -> delayed row admission.

`ChatOpenScopeAcceptanceTest` adds six methods: real V12 `36 01` before unread
clear; old-firmware zero-key reset; saved preference/default/capability and
A/B/A changes; typed population failure; actual device-error retry; held
command cancellation/new session; and foreign-radio/DM isolation. The
`#test` key literal was independently derived with Python SHA-256:
`36009cd8fcf22a47333b591d96a2b848b73f`. Disabled is `36 00` + sixteen zero
bytes. Expectations are not generated by candidate packet/resolver code.

The common firmware fake receives only one additional supported opcode:
`0x36 -> 0x00` acknowledgement. It still records exact bytes and allows
per-test held/error responses; no primary original test body/assertion changes.
All incumbent originals, ec6 witnesses, rollback/writer fixtures, diagnostic
support, deadlines/oracles and prior evidence/history remain unchanged.
These twenty supplementary methods do not add original-family credit.

### Post-admission dimensions review correction

Exact5f source review5607099115 identified that the original
`Dimension resolution rebuilds matching message` appends first, then saves
200x100 dimensions and rebuilds the matching item. The existing pre-admission
header/aspect test is preserved as a useful positive control, but its revision
assertion alone did not establish that original's late stream predicate:
end-of-prefetch invalidation could make it pass with the subscription broken.

One additional focused method, `lateDimensionsStreamRebuildsTheAdmittedMatchingRowBeforePrefetchCompletes`,
admits the exact `look https://example.com/cat.png` message before dimensions
exist. A different admitted dog-image row supplies a matching-key negative
control. After enabling automatic probes, it observes the actual process
resolution flow while a real second HTTP dimension probe keeps the actual
prefetch operation suspended. The real cat PNG header saves200x100 into the
process dimensions store; the observer must advance and the matching native
row media must become aspect2.0 while prefetch is still incomplete and its
unconditional completion invalidation is unreachable. The other row remains
unresolved. Actual Room identity/content and reopening the dimensions file
are also checked; held work and the observer are joined in cleanup.

Only the owned test/helper and this evidence/deviation change. All fourteen
earlier method bodies, their assertions and all production bytes remain
unchanged. There are now fifteen admission methods plus six scope methods,
not another original-family credit. Native execution remains pending this
successor's own CI; source review is not execution or protected acceptance.

## Actual verification and boundaries

Exact5f own39687541104 is terminal FAILURE, not a watcher-printing verdict.
Complete official Linux119527065578 was decoded in memory: App1149 discovered,
1144 passed,5 failures,0 errors/skips. All five named failures are scope
methods: `cancelledHeldOpenDoesNotClearUnreadAndANewSessionCannotInheritItsDedup`,
`realDeviceErrorIsReportedAndNeverMemoizedAsSuccessful`,
`mountedOpenPopulatesThenSetsV12AllRegionsBeforeClearingRealUnread`,
`storedSelectionFirmwareDefaultAndOtherConversationInvalidateActualSessionScope`,
and `failedPopulationStillSetsScopeButRetainsUnreadAndTheTypedLoadError`.
Classifier/controller/scoped codec succeeded; SwiftData was SKIPPED and
aggregate119536087421 failed. This is not a timeout or whole-run pass.

The actual source establishes a fixture mismatch: `FirmwareRadio.devicePacket`
advertises firmware9, whereas the new helper changed only the manager's DTO
to12. The real `MeshCoreSession` still correctly rejects unscoped send before
transmission with typed UNSUPPORTED_COMMAND1. That accounts for missing
unscoped packets and expected ERR2 versus actual ERR1. The ordinary test
support follow-up makes only the firmware reply version configurable, still
default9, and sets12 through the existing radio-creation callback before
every scope fixture's actual handshake, including reconnect. Its helper now
asserts the resulting manager value instead of cosmetically replacing it.
The explicit manager-capability11 branch remains a metadata/fallback decision,
not proof that the protocol session was downgraded to firmware11.

Same-owner `app-build-launcher` / `add-direct-support-wiring` admits this
existing shared test helper. All six scope test bodies/assertions, all fifteen
admission methods including LOCAL3d2's late stream witness, all production
bytes and the session firmware guard remain unchanged. The new combined
candidate needs its own execution and positive per-method evidence; failed-
command totals alone do not establish individual passing methods.

The first product publication `398187037fa09c51476110503ee78f6e3598ed29`
owns failed run39685755510. Its complete official controller119519331850 log
was read in memory:606 discovered/run,603 passed,3 errors,0 failures/skips.
The local declared controller/local/parity command independently reports the
same606/603/3. All three errors are the strict workflow validator rejecting the
merged command that contains both the inherited parity inclusion and landed
candidate-event arguments. The scope job succeeded; Android and both oracles
were skipped and aggregate119520286092 failed. No Kotlin execution occurred.

The same-owner support follow-up (reservations110->112) changes only that
validator, two regressions and this evidence/deviation. It accepts exactly one
of the trusted main command or its bounded parity-inclusive form, rejects
duplicate runner lines/arguments, unknown families and ignored failures, and
retains immutable candidate selection. The actual workflow and all398 product,
twenty Kotlin witnesses and common firmware support bytes remain unchanged.
This is an established merge-integration validator repair, not an inferred
product, timeout or issue261 fix. The next automatic run owns the follow-up.

At exact repair `2e81f2c9348177df196212b0cd3f1a723a5eddba`,
`python tools\android-port\controller\test_runner.py --include-tests tools\android-port\local --include-tests android\parity-tests --quiet`
PASS608 discovered/run/passed,0 failures/errors/skips, including the two new
workflow regressions. Its own39686705312 reports controller119522569848 and
scoped codec119523595481 success; Linux119523595263 was still running at this
observation. This is parent-only evidence, not a final native verdict.

While that exact local suite ran, main advanced to
`35265173f2ae1765469c751d6d0eb66a82055c00` (PR262). After the suite completed,
reservation113 admitted a disjoint merge of its three unchanged WP-003
disposable-runner/test/deviation paths. Policy/manifest/workflow bytes did not
change upstream. The resulting merge retains the entire2e product/validator/
twenty-witness tree; only upstream's three paths and this WP's evidence/
deviation differ. The merge candidate needs its own automatic exact-head
execution; parent608 does not execute upstream's newly added cases.

The following declared pure-source commands passed during preparation:

| Command | Result |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS |
| `python tools\android-port\controller\validate.py` | PASS |
| `python tools\android-port\portmap.py` | PASS, 1,800 records |
| `python android\parity-tests\audit.py --check` | PASS; no archive regeneration |
| `python android\parity-tests\semantic.py --check` | PASS; not execution |
| `git diff --check` | PASS |

`python tools\android-port\controller\ci.py preflight` exited2:
`Explicit --state/ANDROID_CI_STATE is required; failed cloud setup is not readiness`.
No local Android/Gradle/WSL/native/device/lease/recovery command was run.
New Kotlin and UI assertions are UNCOMPILED/UNRUN locally; ordinary publication
is an explicitly UNVERIFIED candidate pending its own automatic exact-head CI.
Source/controller checks do not substitute for that execution.

Parent ec6's own all-six run39666621137 proves only its earlier bytes, not
these product changes. Its source5605564270 and execution5605880384 remain
distinct. Historical timeout failures/root causes remain unresolved. This
repair does not fix or accept correlation/quarantine issue261, close issue134,
certify UI/device/hardware behavior, grant protected acceptance, or establish
full WP-501/backup/release parity.
