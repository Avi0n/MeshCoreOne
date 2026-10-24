# WP-502 mounted action availability and available-width acceptance

## Candidate and boundaries

Repository: `cbattlegear/MeshCoreOne-Android`; issue #135; owner
`test-parity-engineer`; canonical session/attempt
`3bc8c1b8-20ec-4647-88ae-db3775268489`; runtime session
`fb112832-0a71-4f6d-abc0-f07f80eaeb8b`.

Base: `ae349b875e8d00b93eeaf66687649f997ba17dc4`; source:
`db14559b39d32322b06477c6ae676112f583db50`.
The publishing commit identifies this three-path candidate; head/tree must be
read from Git/PR, not inferred from an older run. WP-407 prerequisite PR297 is
actually merged at `e1f0a7c81a59896a46020ad230310937b8239e0e`, an ancestor of
the base. Manifest semantic SHA-256:
`977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`;
policy UTF-8 revision:
`46a1aebeace81c8719901030e859569df4c2008e19cb841d80e887b5801077d8`.

Same-owner typed receipt evolved 73->74 for all 39 incoming main paths using
`reconcile-current-base`, then 74->75 for this owned test, evidence and
deviation through `create`, `modify`, `record-evidence`. Identity, attempt,
original binding, authorization context, branch, worktree and full existing
hard-lock records compare equal before/after both evolutions. Revision-75
receipt canonical JSON SHA-256:
`a234c6f9690f955875e8f270dd389e0e406d6056c85a0a84f7b723c837a5f2ea`.
No ledger recreation, foreign lock release, policy update or incoming owner
source rewrite.

Acceptance IDs: `WP-502-behavior`, partial software support for
`WP-502-boundaries` and `WP-502-source-test-parity`. This does not configure
full WP verification or complete issue #135. No production change is made:
this batch supplies the missing complete mounted availability cohort, not
25 newly discovered product defects.

## Complete frozen family

Original file:
`MC1Tests/Views/Chats/MessageActionAvailabilityTests.swift` at the source pin.
Production comparison:
`MC1/Views/Chats/Reactions/MessageActionAvailability.swift`,
`MC1/Views/Chats/Reactions/MessageActionsSheet.swift`,
`MessageDTO`, native `MessageActionAvailability.forMessage`,
`MessageActionsStateHolder.loadDetails`, `MessageActionsRoute`,
`ActionButtons`, `PathDetails`, and `BlockSenderDialog`.

All 25 original test functions retain their distinct inputs and predicates;
duplicate-looking original classifications remain distinct tests rather
than being deduplicated into fewer source cases.

| Frozen original (in source order) | Mounted Kotlin method |
| --- | --- |
| flood nonempty path, canViewPath true | `floodNonEmptyPath` |
| flood empty path, canViewPath false | `floodEmptyPath` |
| flood nil path, canViewPath false | `floodNilPath` |
| direct route, canViewPath false | `directPath` |
| outgoing flood, canViewPath false | `outgoingPath` |
| channelIndex 0 overrides direct route | `channelOverridesDirectRoute` |
| incoming flood, no extras, canViewPath and showsPathDetail true | `incomingFloodWithoutExtras` |
| incoming empty path/heard1, canViewPath false/showsPathDetail true | `incomingEmptyPathWithExtra` |
| incoming nil path/heard1, canViewPath false/showsPathDetail true | `incomingNilPathWithExtra` |
| outgoing heard2, canShowRepeatDetails and showsPathDetail true | `outgoingWithRepeats` |
| outgoing heard0, showsPathDetail false | `outgoingWithoutRepeats` |
| flood route isFloodRouted true | `floodRouteClassification` |
| tcFlood route isFloodRouted true | `tcFloodRouteClassification` |
| direct route isFloodRouted false | `directRouteClassification` |
| tcDirect route isFloodRouted false | `tcDirectRouteClassification` |
| unknown route/channel0 isFloodRouted true | `unknownChannelRouteClassification` |
| unknown route/pathLength FF isDirectRouted true | `unknownSentinelDirectClassification` |
| unknown route/pathLength 02 isFloodRouted true | `unknownNonSentinelFloodClassification` |
| direct route isDirectRouted true | `directRouteIsDirect` |
| tcDirect route isDirectRouted true | `tcDirectRouteIsDirect` |
| named incoming channel canSendDM true | `incomingNamedChannelCanSendDM` |
| outgoing channel canSendDM false | `outgoingChannelCannotSendDM` |
| incoming DM canSendDM false | `incomingDMCannotSendDM` |
| incoming channel/prefix AA canSendDM false | `incomingPrefixOnlyChannelCannotSendDM` |
| incoming channel/prefix AA canBlockSender false | `incomingPrefixOnlyChannelCannotBlock` |

The original helper's contactID/channelIndex nil, pathLength 02,
pathNodes A3/7F, incoming direction, nil route/prefix, heard0 defaults remain.
Its entire DTO bundle is explicit: text Test, timestamp 0, delivered/plain,
nil ack/SNR/reply/roundtrip, sender RemoteNode iff channel and no prefix,
read true, retry/maxRetry 0. The sole nondeterministic inputs UUID()/Date()
become fixed message `...0901`, radio `...0900`, and Unix 1700000000.
Channel0, nil versus empty bytes, sentinel FF and all enum inputs are not
normalized. Empty repository read results are intentionally empty, not
invented heard-repeat records; scalar heardRepeats is not fabricated data.

Every row asserts the original predicate plus the independent literal bundle
for path/detail/flood/direct, reply, send-again, DM, block, repeat-detail,
copy and delete. It then mounts the real holder/route, suspends the contact
read if and only if disclosure is offered, completes contacts->discovered
nodes->repeats exactly once with exact radio/message identity and list
identity, and checks no data operation for the undisclosed case.

Actual action clicks must dispatch Reply/SendDM/Copy/SendAgain/Delete in the
expected order, with absent actions absent, not merely disabled. The block
dialog is actually opened and cancelled without block/mute/reaction calls.
The removed translation action remains absent. Recomposition must retain
the identical loaded state, input DTO and read/effect history. Route callback
dispatch is NOT App navigation, a clipboard write, a persisted delete,
completed block, send or radio observation.

## Cohesive adaptive and cancellation controls

Two additional methods bring the authored class to **27 methods**; this is a
source inventory, not runner discovery:

- `everyEffectiveThemeRetainsOffersAndOneReadAcrossLoadingAndLoadedWidthChanges`
  uses the existing independent 19 effective-scheme frames covering all ten
  unlocked themes (including forced-dark Ember). Each mounts the named
  incoming channel family, asserts the actual effective scheme, changes
  available width 360->720dp and LTR->RTL while the contact read is held,
  completes that same read, clicks actions/cancels block, then returns
  720->360dp and RTL->LTR with no duplicate read or effect.
- `disposingTheOwnerDuringAHeldReadCannotPublishLateDetailsOrDispatchAnAction`
  performs the held resize, removes the real route, cancels its supplied
  owner scope, observes cancellation at the suspended port, then releases the
  independent deferred result late. No discovered/repeat read, detail result,
  failure-shaped success or action is allowed after disposal. It preserves
  the holder's actual last loading snapshot, not a fabricated completed state.

All mounts use Robolectric API31 native graphics with a real activity/window
at 1080x1400dp and system fontScale 2.0; the existing strict native-ink,
full-UTF16/text/viewport oracle and 48dp click targets are used unchanged.
The **inner constraints** change; the OS window does not. These are not API37,
OS resize/folding, IME, physical-device, screenshot/golden, contrast-certification,
TalkBack or radio acceptance.

Existing delivery/reaction/arrival/ordering/keyboard/picker tests and all primary
producer provenance stay untouched. The separate preserved
`MessagePathOriginalFamiliesTest.kt` draft remains untracked, unexecuted and
outside this candidate (SHA-256
`d11932e3ec8c6c19dd7fb95217d198b250c4549db2188e89968b2dbf0f4433a0`).
Historical PR313 and other consumed results are not reused as this batch's pass.

## Actual verification and remaining gates

Readiness command actually attempted on the exact base:

```powershell
python .\tools\android-port\local\check.py --distribution Ubuntu-22.04 --commit ae349b875e8d00b93eeaf66687649f997ba17dc4 --stages preflight
```

Result: **exit 1, BLOCKED**:
`Malformed installed reservation runtime manifest`.
Read-only diagnosis finds installed runtime schema 1 with keys
`files,schema_version`; current `check.py` requires the repository-bound schema 2
execution interlock before transport or hard-lock acquisition. No Linux
transport, Gradle/compiler, Kotlin test discovery or Kotlin assertion executed.
This is not evidence that the installed Linux toolchain is missing.
No runtime installation, manual Gradle bypass or resource action was attempted.

The candidate's own ordinary hook/automatic hosted checks must establish
compilation and positive discovery. New Kotlin execution is **UNVERIFIED**;
27 authored methods and 19 theme iterations are not passed-test counts.
The existing declared App/scaffold lane already registers this source directory,
so no build graph, runner, package, pin or toolchain change is needed.
Authoritative exact-head job results/logs stay hosted; no duplicate CI bundle.

`python .\tools\android-port\portmap.py` actually exits 0 on the candidate
worktree: ownership/traceability validation PASS, not behavioral acceptance.
`git diff --check` passes. Read-only preservation checks confirm all three
referenced Swift blobs equal the pin, every old deviation line remains intact,
and the unrelated draft's exact SHA-256 is unchanged. The working class
contains 27 test declarations; no runner discovery is implied by that check.

This does not repair or claim acceptance of the independently researched
detail-error/retry or contact-sheet loading/recovery product gaps. Those
remain a shared action-dialog data/lifecycle product cohort owned by the
primary WP-309/related producers, not bookkeeping defects or silent WP-502
scope expansion. Real API31/37 and targeted33/34/36 current-candidate
observations, approved goldens, actual OS resize/folding, TalkBack and physical
radio evidence remain separate gates. Full WP-502 is incomplete.
