# Channel slot-handler originals through real persistence

## Binding and concrete missing boundary

Repository `cbattlegear/MeshCoreOne-Android`, WP-501 / PR #165 / issue #134.
Starts at published `417c7c236e1154470339926e81fc38e7bb582663`,
base actual main `ec0a41e80ac8637baad524b777f774c10cf99f5b`.
Frozen source remains `db14559b39d32322b06477c6ae676112f583db50`.
This increment supports `WP-501-behavior`, `WP-501-boundaries` and
`WP-501-source-test-parity`; it is not an acceptance receipt for those IDs.

Read all three complete bodies in the **Slot occupant changed handler**
subfamily of
`MC1Services/Tests/MC1ServicesTests/Services/ChannelServiceTests.swift`,
blob `10eb95865b0bdfd1b3b0142b15ed3b4750461e31`, including
`makeService`, `slotSecret`, `SlotHandlerCapture`, the real source
persistence/device helper and `MockMeshCoreSession` defaults.
The original `makeService` uses real in-memory persistence.
The original clear case also uses `MessageDTO+Testing.swift`,
blob `9428f0c258f381d24720e61398fcff1418e07acd`.

Existing native services `ChannelServiceTest.slotOccupantHandler`
preserves these three scenario shapes, but its `SlotFixture` uses
`ChannelsInMemoryStore`. Existing App `SessionStateTest` calls the
AppState handler directly; repository integration alone does not execute
the service's callback. These bodies were read, not rerun or counted as
new discoveries. Missing markers were not the reason to add tests.

The new owned `ChannelSlotHandlerOriginalPredicateTest` runs actual
`ChannelService` against production `RoomPersistenceStore` and its real
`ChannelRepository`, using existing `RoomProcessTest` / in-memory Room /
native SQLite. Only the original radio session boundary remains a stub.
There is no test-written save, delete, secret-change, reconciliation or
callback-selection algorithm. The actual handler refetches stored state
before recording each notification, additionally guarding commit order.

## Complete selected original-case accounting

All three originals are single cases without a source parameter expansion.
Method names belong to
`com.meshcoreone.android.parity.ChannelSlotHandlerOriginalPredicateTest`.

| Frozen original | Complete inputs, actions and original assertions, plus native guards |
| --- | --- |
| `clearChannel deletes the row and its messages, then fires the slot handler` | `originalClearSlotFiveDeletesOldRowAndMessageBeforeItsSingleCallback` saves slot5/`Old`/16x11 and actual outgoing SENT channel message `old`, then invokes real `clearChannel`. Requires absent slot5 row, empty stored messages and exactly one callback `{5}`, not just containment. Additionally proves the message ID was actually stored first, verifies correct radio plus absent row/messages inside the callback, repeats the original post-call checks, and requires the single radio write5/empty name/16x00. |
| `setChannel fires the slot handler on a new secret and not on a same-secret rename or first insert` | `originalFirstAndRenamedOneStaySilentThenReplacedTwoCommitsBeforeOneCallback` makes the original slot5 writes `First`/`one`, then `Renamed`/`one`; callback history must remain empty. Writes `Replaced`/`two`, then requires exactly one `{5}` callback. Supplemental real refetches require first/renamed names with equal stored secret; inside the actual replacement callback, correct radio, stored name `Replaced` and changed secret are required. Requires all three session writes in original name/index order. This is production passphrase derivation and relative secret-change behavior, not an independent hash-vector assertion. |
| `syncChannels fires the slot handler for vacated and secret-changed slots, not first sightings` | `originalSerialFourSlotSyncCommitsChangedOneVacantTwoAndNewThreeBeforeOneCallback` seeds1/`One`/16x11 and2/`Two`/16x22. The original stub replies1/`OneB`/16x99 and3/`First`/16x33; unspecified slots0/2 reply empty name/16x00. Calls real serial sync with maxChannels4 and requires exactly one callback `{1,2}`: changed1 and vacated2, never first sighting3. Within that callback, requires correct radio, absent2 and complete committed snapshot exactly1/`OneB`/99 plus3/`First`/33. Additionally requires result synced2/errors empty, reads exactly0,1,2,3 and no radio writes. |

Each test saves the source helper device with id=radioID, self key32x01,
`TestDevice`, firmware8/`v1.0.0`, maxContacts100, maxChannels8,
multiAcks0 and active=true. The message preserves contact=nil,
channelIndex5, outgoing/SENT/plain, pathLength0, SNR=nil, sender=nil,
unread, repeats0, sendCount1, retryAttempt0/maxRetryAttempts0 and the
remaining absent/default helper fields. Source random entity/radio
identity remains independently allocated. Only its unrelated Date.now
timestamp/createdAt are fixed at Unix1704067200; no selected original
assertion depends on that clock.

The small session stub records real reads/writes and returns the source
configured or empty/zero responses. Like the source mock, a set operation
records without mutating readback fixtures. Unexpected pipelined access
throws AssertionError rather than returning fake success. The handler's
mutable capture is accessed only by these awaited service calls; no
background polling, arbitrary sleep or private state introspection is
needed.

## Execution and source binding

Same existing advisory attempt
`d6056690-3a5d-453e-b67e-36866c676ac5`,
CLI session `27f3c368-787a-4cad-8050-8c7c57b25eb4`;
supported reservation67->68 admitted the two new owned test/evidence
paths. No release/recreate, manual ledger/receipt or authorization edit.

Credential-stripped readiness passed Java21.0.12.1,
compile37.2/build-tools37.0.0 using the existing explicit private caches.
Declared smallest new-class command:

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.ChannelSlotHandlerOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

**PASS3/3**, failures0/errors0/skips0. Fresh native JUnit XML:
`android/app/build/test-results/testDebugUnitTest/TEST-com.meshcoreone.android.parity.ChannelSlotHandlerOriginalPredicateTest.xml`,
timestamp `2026-10-20T22:35:19.055Z`, contains exactly the three table
method identities, once each. Executed new source blob:
`e095540f5a6178a6677df79347dd80385a5e0a4f`.
Generated localization/asset prerequisites are not added to these three.

Unchanged actual production `ChannelService`:
`dd90af41b90319c122140b7f54f538c2bffdffa4`;
`ChannelRepository`: `27d187f0f248774ebe673ab8260b88d5734c6c94`;
real Room fixture: `c6fb962fca19d1ff87c48eaa05868514a08d614a`.
Selected frozen source/test/helper files have no drift from the pin.

Read-only `python tools\android-port\controller\verification_config.py --check`,
`python tools\android-port\controller\validate.py`,
`python tools\android-port\portmap.py`,
`python android\parity-tests\audit.py --check`,
`python android\parity-tests\semantic.py --check` and `git diff --check`
all **passed**. Discovery/proposal counts are not executed or accepted
coverage. No archived accounting or policy output was regenerated.

## Limits and preserved history

This is the complete selected three-case slot-handler subfamily, not the
whole ChannelService test file, pipeline/retry/error families or mounted
App draft-clearing consumer. It proves native persisted service/callback
behavior under API31 Robolectric/native SQLite/in-memory Room, not actual
radio/protocol exchange, device/physical-disk/process-death durability,
decryption-cache behavior or independent hash-vector parity.

Predecessor417's
[automatic run39507338327](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39507338327)
was still in progress at the post-selector checkpoint: classifier,
controller and codec passed, Linux118920684616 was running.
Its outcome is bound to417, not execution of these three new methods.
[COMMENTED review5592584695](https://github.com/cbattlegear/MeshCoreOne-Android/pull/165#pullrequestreview-5592584695)
has no bounded source blocker in the earlier six contact-sync originals,
not this slot-handler increment or a full-WP/gate approval.
Earlier e818/run39503877263 is terminal CANCELLED after normal successor
supersession, not a test defect or successful full run.
The retention/contact-sync classes and earlier predicate/clock/helper/
draft bytes are unchanged and were not replayed.

Normal successor exact-head CI, independent comparison and actual
reviewed merge remain separate from local3/3. No second hosted-result
bundle is retained. No production defect or ownership collision was
established here. Stop this increment after this coherent family for
review; remaining assigned software comparison stays open, not a new
WP503/provider/human prerequisite. No new worker, protected/native action,
self-approval, merge, issue closure or whole-WP acceptance.
