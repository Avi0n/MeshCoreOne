# WP-209 CM-04 independent stimulus witnesses

## Candidate and scope

- Issue: https://github.com/cbattlegear/MeshCoreOne-Android/issues/389.
- Owner: `services-porter`; session/attempt
  `c2221378-f97f-47dc-800b-cdc6bd870dcd`.
- Execution base: `a9b30473e1285c55ada38a191f808563aca57005`.
- Read-only source: `db14559b39d32322b06477c6ae676112f583db50`.
- Manifest SHA-256:
  `11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4`.
- Trusted policy revision:
  `b8e50bed0209d18574f4cdbef54dd4fc35370c340c61b153b38c7f07a7df231a`.
- Advisory reservation acquired for this actual session, revision 1. Trusted
  `wp-owned`/`modify` admission covers the contacts test and per-WP evidence
  paths. No historical reservation was rewritten or released.
- WP-207 prerequisite repair PR #449 is merged as
  `91194f3d75b28131d297a78ef604367ded13e52c`, an ancestor of the base.
  This is bounded dependency evidence, not whole-WP or hardware acceptance.

The immutable PR head/tree and current base belong to the GitHub candidate and
hosted run, not a second self-referential committed result bundle. Acceptance
is limited to CM-04's two named families and the supporting native witness.
No production behavior, frozen inputs, ownership catalog, policy, verification
binding or dependency graph changes.

## Exact original correspondence

| Original family | Fixture and independent stimulus witness | Actual consumer and postcondition |
| --- | --- | --- |
| `ChannelServicePipelineTests::a mid-drain stall persists only read slots and never deletes an unread configured slot()` | Read the entire frozen body and helper configuration. Four requested indices; persisted slot 2 named `keep` with sixteen `0xCD` bytes; replies only for 0/1/3, names `chN`, sixteen `0xAB` bytes. Require the complete seed before sync. The service test scripts missing index 2 and requires exactly one entered failing reconcile call, requests `[0,1,2,3,2]`, and the exact slot-2 `TransportError` description. | Real `ChannelService.syncChannels` with scripted `ChannelSessionOps`. Require exactly one commit containing precisely the three received channels, no unconfigured indices and capacity 4; synced count 3, no breaker abort, indices `[0,1,2,3]`, every received name/secret, and the complete original slot-2 DTO unchanged. Persistence is the existing JVM fake, explicitly not real Room. |
| Same channel original, supplemental native boundary | Real `MeshCoreSession` and `MockTransport`, unchanged source timeout/client/window helpers, four actual primed GET_CHANNEL frames and only replies 0/1/3. Observe the actual returned received indices `[0,1,3]` and missing `[2]`, plus the actual service's reconcile call for 2. | Observe typed `ConnectionLost` before any extra send, exact propagated error description, count 3, all four persisted names and full slot-2 identity/secret/metadata unchanged. No armed-but-unentered send failure is credited. This preserves PR #436's approved fail-closed policy, not source same-link recovery. |
| `ChatSendQueueServiceTests::hydrate replays persisted PendingSend rows and drains them()` | Read the frozen body, `makeStore`, both service helpers and source explanation: one default DM envelope in an otherwise empty store; no Contact, Message or Device, and no session start. Existing Room test requires the full literal DTO and full row at assigned sequence 1 before hydrate. New `ContactQueueHydrationWitnessTest.missingContactIsLookedUpBeforeTheHydratedEnvelopeIsDeleted` independently pauses at the actual exact radio/contact-key lookup with that row still present and queue count 1. | Real `ChatSendQueueService` and real `MessageService` over an unstarted real session. Require the contact lookup to return null, then the status-update/delete operation order, empty persisted sends, queue count 0, no wire bytes and disconnected transport. New deterministic witness uses `RecordingStore`; existing `OfflineQueueHydrationOriginalPredicateTest.originalPersistedMissingContactEnvelopeReallyDrainsFromRoom` exercises the actual Room repository and same missing-contact cleanup path. Neither is credited as successful radio sending. |

The unchanged real-Room test is
`android/parity-tests/src/test/kotlin/com/meshcoreone/android/parity/OfflineQueueHydrationOriginalPredicateTest.kt`,
Git blob `870e61f1511ade03985b57e4220be011c0c89c91`, landed in PR #165
(merge `514c451653531da9e3cc47139a9e4e44fb9d6cf0`).
Its historical four-case local pass remains attributed only to its old
execution; current-candidate execution must come from this PR's hosted log.
The existing documented native logical READY snapshot is ownership input,
not observed radio readiness. The new witness makes the same adaptation
explicitly and does not start a transport.

## Cross-workstream reconciliation

- Existing #380 retains fresh transport teardown/handshake and downstream
  channel-recovery composition. Added the concrete CM-04 finding there;
  no duplicate issue or competing implementation is necessary.
- Existing #419 owns the broader CM-06 equivalent-stimulus accounting.
  The separate scripted-send and real-session-rejection dimensions above
  remain explicit; no broad parity or newly approved equivalence is inferred.
- Open PR #453 writes WP-208 messaging tests/production. This candidate
  does not edit those files or replace that producer; it reuses existing
  test interfaces and adds only an admitted contacts-owned witness.
- A proposed additional parity-test path was denied by trusted WP-209
  admission. It was not created, and no capability was widened. The already
  merged real-Room queue counterpart is reused instead.

## Verification and limits

The historical macro-WP-209 entry still has `configured=false`, `commands=[]`.
This candidate uses the existing documented change-scoped candidate CI route,
not an invented task, a new full-WP binding, or a full-WP completion claim.
Services-test changes select the declared services tests and consumer closure,
including the App task that registers the unchanged Room counterpart.

| Exact command | Local outcome |
| --- | --- |
| `python tools\android-port\controller\verification_config.py --check` | PASS; current approved structures and source pin preserved. |
| `python tools\android-port\controller\validate.py` | PASS; 1,866 frozen inputs, 64 WPs, 178 edges. |
| `python tools\android-port\portmap.py` | PASS; source provenance valid, not behavioral acceptance. |
| `git diff --check` | PASS. |
| `python tools\android-port\controller\ci.py preflight` | BLOCKED: no explicit `--state` / `ANDROID_CI_STATE`; no local Android readiness claimed. |
| `python tools\android-port\controller\ci.py --local --affected-candidate run --stage scaffold` | BLOCKED by the same missing explicit state; no Gradle tests started. |

Authoritative current-head test counts, failures/errors/skips and automated
review are the hosted PR checks/logs. Missing or zero-test mandatory results
remain blocking; the local source checks and historical Room results do not
substitute for them. No iOS, hardware, device, signing, release, real Room
channel transaction or complete App connection-recovery acceptance is claimed.
