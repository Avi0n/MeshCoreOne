# WP-207 correlation recovery successor

Repository: `cbattlegear/MeshCoreOne-Android`; owner: `services-porter`.
Initial base/trusted main: `0a7a84e388393557e4b66ac7fd322248e28611fd`.
Source pin: `db14559b39d32322b06477c6ae676112f583db50` (unchanged).
Manifest semantic digest:
`977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
Policy revision:
`70f65b1205f1581c0f1a86107ccdc20f794421a6cf9b172e17d8a3e544569a66`.
Managed branch: `cbattlegear-psychic-invention`; actual project session/attempt:
`c7207c38-1af6-491f-87f8-08f11418c5ea`.
The successor PR's current head identifies implementation bytes; this document
is not a second reproducible CI-result bundle or an acceptance receipt.

The trusted reservation CLI acquired the real WP-207 advisory reservation,
revision 1, with `wp-owned`/`modify` for runtime, its deviation and its evidence.
Initially no protocol, messaging, App, shared policy or build/dependency edits
were admitted or made; the later App support admission is recorded below.
The original prerequisite implementation merge commits for WP-107
(`#19`), WP-202 (`#23`), WP-204 (`#21`) and WP-205 (`#18`) are ancestors of base.
Diagnosis context is existing issue #261, not a claim to close its remaining
cross-WP integration work.

## Normally merged CI budget integration

At the coordinator's direction, the branch ordinarily merged actual main
`f81d2f56d897b022b6f6c4cf38551448691eae02`, whose parents are the initial base
and WP-003 producer `e138ac0fe27c501604a98ba4c6dfadefc686d584`.
WP-003's merged implementation supplies the 3,600-second selected Gradle batch
and 75-minute hosted job budget. This worker did not independently change those
shared build/workflow surfaces. The integration preserves original candidate
`8b555dfe3e371d97a0ea7fc8b4cbe522884a9c15` and its failed hosted history; it
does not rerun or relabel that head as successful.

The App-edge assertion repair remains with its assigned WP-501 producer.
The exact-candidate hosted job/log, not this scope record, determines whether
the runtime regressions have actually executed.

## Behavior and acceptance

`WP-207-behavior`: the existing public correlation diagnostic now drives a real
generation-owned physical close and the normal retry policy, rather than an
App-only workaround or a protocol-quarantine bypass.

`WP-207-boundaries`: ten deterministic real-session regressions assert timeout,
quarantine, DISCONNECTED/token withdrawal, single physical/service close,
fresh-generation READY, exact state ordering, diagnostic coalescing, stale/late
isolation, manual stop, cancellation and WiFi cooldown. These are authored
assertions until the exact candidate's hosted job proves execution.

`WP-207-source-test-parity`: no source family or original assertion is removed.
The source-derived watchdog/retry policies stay unchanged; the native recovery
event and dispatch boundary are documented in deviation A-08.

## Commands and execution limits

- `python .\tools\android-port\controller\validate.py`: passed; pinned reference
  and manifest valid (1,866 reference inputs, 64 WPs, 178 dependency edges).
- `python .\tools\android-port\portmap.py`: passed; traceability only, not parity.
- `git diff --check`: passed.
- `python .\tools\android-port\controller\ci.py preflight`: blocked, exit 2;
  explicit `--state`/`ANDROID_CI_STATE` was not supplied by this execution host.
- `python .\tools\android-port\local\check.py --stages preflight`: blocked,
  exit 1, `Malformed installed reservation runtime manifest`, before Gradle
  execution. The runtime was not replaced and the native lock was not bypassed.
- The normal `git push -u origin cbattlegear-psychic-invention` advisory hook
  published the candidate explicitly UNVERIFIED. Its Python scaffold 15/15
  and parity-accounting 108/108 passed; local-tool tests passed 42/43, with
  the existing WSL/canonical-Windows-ledger diagnostic expectation failing
  before Gradle. No native runtime replacement was performed.

The declared relevant module command is `:core:runtime:test`. The candidate's
normal hosted workflow supplies readiness, strict dependency verification and
the actual discovered/executed test results. No local Kotlin test count or pass
is claimed. Feature publication is explicitly unverified pending that run;
hosted exact-head job/log remains authoritative without retaining another CI
result bundle here.

WP-503's existing native execution reservation remains untouched. No emulator,
hardware, signing, release, settings, schedules or merge-to-main action was
performed. Merge remains serialized behind the coordinator's native END/cleanup.

WP-208 owns persistent per-envelope channel-query failure progress across graph
replacement; WP-303 owns its App wiring. Runtime requires no new producer signal
to request physical loss. The separately discovered accounting-completion
contract is unresolved as described below.

## Deferred-ordering support transfer; production ordering still blocked

WP-501's PR discussion transferred the three-path App support change:
https://github.com/cbattlegear/MeshCoreOne-Android/pull/277#issuecomment-6346049979
and explicitly corrected its acceptance limits:
https://github.com/cbattlegear/MeshCoreOne-Android/pull/277#issuecomment-6346079024 .
Trusted `reserve.py admit` and same-owner `evolve` admitted AppContainer,
ContainerHarness and QueueChannelConsumerIntegrationTest through
`app-build-launcher` / `add-direct-support-wiring`; reservation revision 2 keeps
the actual session, attempt, authorization context and initial bindings.

The test gate delays only recovery scheduling, preserves every old assertion,
and then asserts physical retirement. It is supplemental LATE-ordering coverage,
not an ungated production repair or the missing A-terminal/B-survival witness.
In particular, the original App failure is NOT established to be merely an
obsolete assertion: actual ChannelService retry suspension and queue lease
retirement expose a genuine unrecorded-failure ordering window.

The runtime worker requested the exact producer completion seam on WP-208:
https://github.com/cbattlegear/MeshCoreOne-Android/pull/278#issuecomment-6346190391 .
No speculative lifecycle API, arbitrary grace delay or failure-on-cancellation
accounting was added. Accounting must settle before retirement and, where
threshold terminal cleanup requires current ownership, before token withdrawal.
Manual stop, unrelated cancellation, stale/new generations and pending-row
replacement must not manufacture or duplicate credit.

Any successor hosted result is exact-head only. A green gated test alone leaves
combined acceptance BLOCKED pending the producer fix and WP-501's ungated real
App/Room A-terminal/B-literal-three-second-survival evidence.

## Producer completion contract

WP-208 published the real queue observation
`ChatSendQueueService.awaitChannelQueryAccounting()` at
`34dc3d1a12009d32e06cd627cc2c03e7fc780dc3`. Runtime's corresponding mandatory
`RuntimeServices.awaitChannelQueryAccounting()` port has no default
implementation. WP-303 must delegate it to the actual queue; no concrete
messaging dependency is introduced into runtime.

Recovery coalesces a generation-owned observation before awaiting it, while
keeping the original token and connection snapshot live. The observation
finishes before token withdrawal, service retirement/cancellation or physical
close. No protocol or runtime lock is held across that suspension. Detach,
another connection loss, replacement and shutdown cancel the observation; the
recovery rechecks its current owner after suspension. Cancelling an observation
does not cancel the queue's query or create failure credit. A producer
observation cancellation on an otherwise current runtime generation cannot
strand its quarantined link: physical recovery still follows the original
diagnostic, without counting cancellation as a query failure. Runtime caller
cancellation itself continues to propagate.

Six additional runtime cases cover blocked accounting with repeated real
diagnostics, manual stop, replacement, process shutdown, producer cancellation,
and surfaced accounting failure. They supplement, rather than replace, the ten
original recovery cases and remain authored until exact-head execution.

Reservation revision 3 admitted only the existing data and connectivity
`RuntimeServices` test doubles through trusted `merge-integration` /
`merge-disjoint-semantic-edits`. Those fixtures have no channel-query producer;
their explicit implementations represent that empty fake graph. No production
data, BLE or messaging path was edited. Existing assertions remain unchanged.
The App graph delegation was requested from its current WP-303 producer:
https://github.com/cbattlegear/MeshCoreOne-Android/pull/281#issuecomment-6347137708 .

This resolves the runtime port/ordering requirement, not combined acceptance.
The ungated App/Room witness and explicit reconciliation of the inherited
30-second BLE watchdog with the original 30-second A deadline remain required.

The exact `c48f98a1320bca0f6dc4382f16ddf8757866efb3` hosted run
39820735424 / Linux job 119971154605 compiled runtime and executed 271 runtime
cases, with two failures: the written-query cancellation regression and the
existing cleared-authentication-latch case. The authoritative log identifies
those methods but does not include assertion values; no JUnit artifact was
retained. Source inspection established two coupled issues: a written request
deliberately retains its response owner after caller cancellation until its
actual deadline, and loss coalescing must not suppress a retained connection's
explicit authentication re-alert after the latch is cleared.

The cancellation case now asserts the unchanged 999/1000ms timeout boundary
before expecting uncertainty; it does not change the protocol. Repeated
authentication notifications may re-alert the same still-relevant retained
owner, without repeating graph teardown or connection-loss notification. The
original latch assertions remain, with additional single-teardown/loss checks.
These successor corrections require fresh exact-head execution; the failed
run remains failed and proves neither the new accounting port nor these fixes.

The mandatory runtime port intentionally requires the WP-303 concrete graph
implementation before the combined App can compile. Its published signature
and ordering request are the actual consumer prerequisite, not an absent
WP-107 signal or a reason to install a success-shaped default.

## Source-only timing-contract investigation

Deviation A-11 records the frozen simulator's genuine query-timeout semantics
and the additional native physical-recovery contract of the full-App witness.
Existing foreground, companion-presence and BLE callback routes do not supply
an automatic fast reconnect for that unchanged continuously-active fixture.
The source-grounded findings and explicit reconciliation options are recorded
on the existing PR:
https://github.com/cbattlegear/MeshCoreOne-Android/pull/277#issuecomment-6349080065
and the frozen ERROR 2 versus query-timeout clarification:
https://github.com/cbattlegear/MeshCoreOne-Android/pull/277#issuecomment-6349306808 .

No new production behavior or App/harness change is authorized or implemented
by this investigation. App support remains serialized to the current WP-303
consumer. Runtime acceptance remains unverified, not inferred from the gated
supplement or an offline surviving row.

The infrastructure owner subsequently identified the local preflight blocker
more precisely: the installed runtime is valid legacy schema 1 with all 41
hashes, whereas the current checker requires repository-bound schema 2/current
runtime (four missing modules and 23 changed files). Safe atomic upgrade is
blocked by the protected native-execution `66bd` lock. Absent process IDs do
not authorize clearing it. The failed preflight was not repeatedly rerun, no
alternate runner or lock bypass was used, and this session started no local
Gradle/native execution.
