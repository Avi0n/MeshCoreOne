# WP-501: all six incoming blocked-sender deletion originals

## Identity and actual missing inputs

- Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
- Implementation/execution parent: `6155fb43879e2d79bc423c792e010023bef11b64`;
  actual main/base: `85871f40b1520a89ced6b9bec74aa8eec2f4f4de`.
- Manifest: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`;
  semantic policy: `7f757bb6dbde98ab8af6da189f6e9273d4bc45ddb8e903703bee8b43c9b372f6`.
- Same WP-501 attempt/stored session
  `d6056690-3a5d-453e-b67e-36866c676ac5`, reservation75->76;
  existing issue134/PR165. No new actor, reservation replacement or scope change.
- New test:
  `android/parity-tests/src/test/kotlin/com/meshcoreone/android/parity/BlockedSenderIncomingOriginalPredicateTest.kt`,
  executed blob `52096c5c88edf188d48e86fc47b2c713fba2dfcc`.

Read all six complete frozen `BlockedSenderMessageDeletionTests` bodies,
both message helpers, the nil-sender constructor, source deletion/enum
definitions, existing native destinations/defaults and actual native
save/delete/cascade/fetch consumers.

The existing `BlockedSenderPersistenceTest` does cover both slots0/1,
DMs, another radio, other names, a nil sender and the missing-name branch.
It is not missing that slot parameter. However, every message uses
`RepositoryTestValues.message`'s OUTGOING/SENT defaults rather than the
source's INCOMING/DELIVERED inputs. Its retained reaction also repeats the
spam thumbs-up/AABB1122/:thumbsup: tuple instead of the distinct source
heart/CCDD3344/:heart: tuple. Those useful unchanged tests were not replayed
or relabeled as the complete incoming originals.

## Complete original-case and parameter accounting

All source messages use fresh UUIDs, a fresh radio UUID, incoming raw0,
delivered raw3, plain text, pathLength0, isReadfalse and
heardRepeats/retryAttempt/maxRetryAttempts0. Ack, SNR, sender-key prefix,
reply and round-trip time are nil. Channel messages have nil contactID,
default slot0 and `"test"` text; the DM has a fresh contactID, nil channel
and `"dm"` text. The nil-sender message has slot0 and `"system message"`.
All these helper inputs are preserved. No source body has a separate
parameterized argument list beyond the literal members below.

| Complete frozen original | New native method | Full scenario and required outputs |
| --- | --- | --- |
| `Deletes all channel messages from a named sender()` | `incomingSenderDeletionRemovesBothOriginalChannelsAndKeepsLegit` | Spammer/slot0, Spammer/slot1, Legit/slot0; delete Spammer on selected radio; both spam rows absent and required Legit row retained. |
| `Does not delete DMs from the same sender()` | `incomingSameSenderDirectMessageSurvivesChannelDeletion` | Spammer/slot0 plus Spammer/DM with fresh contact UUID and `"dm"`; delete Spammer; channel absent and required DM retained. |
| `Scopes deletion to the specified device()` | `incomingOtherRadioMessageSurvivesSenderDeletion` | Fresh message IDs, same Spammer/slot0 on selected and fresh other radio; delete only selected radio; selected absent and required other-radio row retained. |
| `No-op when sender has no messages()` | `incomingLegitMessageSurvivesMissingSenderDeletion` | Incoming Legit/slot0; delete Ghost; required Legit row retained. |
| `Deletes reactions associated with deleted channel messages()` | `incomingSenderDeletionCascadesOnlyOriginalTargetReaction` | Incoming Spammer and Legit/slot0. Spam reaction: U+1F44D, Reactor, AABB1122, `:thumbsup:`. Legit reaction: U+2764 U+FE0F, Reactor, CCDD3344, `:heart:`. Both slot0/current radio. After deleting Spammer, spam reactions empty and legit count exactly1. |
| `Preserves messages with nil senderNodeName()` | `incomingNilSenderSystemMessageSurvivesNamedSenderDeletion` | Incoming nil-sender/slot0/`"system message"`; delete AnyName; required original row retained. |

Every case first requires each actual stored message and its original
identity, radio, contact/channel, sender/text, literal direction/status
raw0/3 and source scalar/nil fields. Thus an absent seed or accidentally
outgoing/sent fixture cannot satisfy a later absence assertion.
Survivors are re-fetched with those same required-field guards.

The reaction case additionally requires both complete actual reaction
DTOs before deletion, the exact retained heart DTO afterward, spam message
absence and preserved Legit message. It cannot pass because a reaction
was never saved, the wrong retained row was substituted, or all messages
were retained. These additional guards do not add runner cases/originals.

## Actual persistence boundary and native adaptations

The unchanged `RoomProcessTest` opens real in-memory MeshCoreDatabase with
API31 Robolectric **native SQLite** and production RoomPersistenceStore.
The fixture owns its SupervisorJob/Default scope and closes the store,
resets DebugLogBuffer.shared, cancels that scope and closes the database.
No fake collection, test-written deletion or direct DAO manipulation is used.

Public store saves/fetches delegate to MessageRepository and real Room
queries. The actual ContactRepository sender deletion uses the selected
radio's production backup-page query, matches non-nil channel and exact
sender, deletes dependents by actual message/radio, deletes target rows
and saves in the production write transaction. Reaction fetches use the
real scoped reaction DAO, not message-only synthetic filtering.

Source UUIDs remain fresh, including separate IDs across radios and a
fresh DM contact UUID without inventing a contact parent row. Android
EntityKey adds the actual radio partition to source message-ID lookups.
Source incidental Date.now/Unix-now inputs and reaction receivedAt are
fixed at Unix1704067200; no original predicate depends on elapsed time.
Unicode escapes preserve the exact source emoji scalars/variation selector
without platform glyph/font assertions. Native default sendCount1 matches
the source DTO initializer; no translation behavior is introduced.

## Exact newly affected execution

Readiness command `python android\scaffold\check_environment.py` **PASS**:
explicit allowlisted environment/private Gradle and Android caches,
JDK21.0.12.1, compile37.2/build-tools37.0.0. No tool/dependency installation.

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.BlockedSenderIncomingOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

**PASS6/6**, zero failures/errors/skips; exact six identities in the table.
Started `2026-10-21T00:49:34.529194Z`, completed `00:52:00.161215Z`;
fresh XML timestamp `00:51:52.392Z`, suite execution5.219s.
Executed source52096c5c unchanged before/after execution on parent6155/base858.
XML:
`android\app\build\test-results\testDebugUnitTest\TEST-com.meshcoreone.android.parity.BlockedSenderIncomingOriginalPredicateTest.xml`.
Local primary log:
`C:\Users\camer\.copilot\session-state\27f3c368-787a-4cad-8050-8c7c57b25eb4\files\wp501-blocked-incoming-f3376f7207e244ac93a82c3bb92d30b9.log`.

After observing empty actual records, acquired/released the existing
`Ledger.hard_lock("gradle-execution", ...)` using this stored owner's
unique suffix `blocked-incoming:f3376f7207e244ac93a82c3bb92d30b9`,
reservation76, parent6155 and exact selector/source. Final records empty.
No native lock, packaging, device action, foreign release or cohort replay.

## Traceability checks, source bytes and limits

| Actual read-only command | Result |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS |
| `python tools\android-port\controller\validate.py` | PASS |
| `python tools\android-port\portmap.py` | PASS |
| `python android\parity-tests\audit.py --check` | PASS |
| `python android\parity-tests\semantic.py --check` | PASS |
| `git diff --check` | PASS |

| Read source/consumer | Git blob |
| --- | --- |
| Frozen `BlockedSenderMessageDeletionTests.swift` | `64c676150d57ae92b5f9a418f90f280c4c905508` |
| Frozen `PersistenceStore+Messages.swift` | `02c50a9a0fda2aaf6b719a5fa410a0c5a0413c7f` |
| Frozen `Message.swift` | `27caa780e5d8ee1dcf4fd5dab661ef9945a5fe26` |
| Unchanged native `BlockedSenderPersistenceTest.kt` | `8a49c34a4999a0ae37f3921a1dc8c6cd1c1d613a` |
| Unchanged native `RepositoryTestValues.kt` | `ca3c37ab4bfa149f63bdd687469b8cf9e9f15446` |
| Native `ContactRepository.kt` | `8da5513b403198a44f673addf443c5411b58a80c` |
| Native `MessageRepository.kt` | `937380545fe91d5b77e4a1552be0180f34b8612f` |
| Native `RepositoryCascades.kt` | `0cf0462fec5c2a33ee2ba330c00db4bcb134d47f` |
| Unchanged `ContainerHarness.kt` | `c6fb962fca19d1ff87c48eaa05868514a08d614a` |

This establishes the six complete incoming deletion/reaction originals at
the native persistence consumer boundary. It does not assert new pending-send/
repeat scenarios, UI ingestion, physical process death, API37/hardware,
Swift execution or whole-WP acceptance. Historical outgoing/compound and
unsigned/snapshot successes are not added to this six-case execution.
Independent source comparison, exact successor hosted verification, reviewed
merge and broader assigned software/helper/consumer/resource work remain
separate. Parent6155's automatic CI39522320785 was still in progress during
local implementation; neither that pending result nor the older67df quiet
command timeout is reinterpreted as this family passing or failing.
