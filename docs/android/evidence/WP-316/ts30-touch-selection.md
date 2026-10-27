# WP-316 TS-30 touch-selection candidate

This is a bounded implementation/admission handoff, not a CI-result bundle or
whole-WP acceptance. Reproducible hosted results belong to the exact-commit job
and log; no second result bundle is produced here.

## Identity and first implementation operation

- Repository: `cbattlegear/MeshCoreOne-Android`; owner: `tools-ui-engineer`.
- Actual session: `7b1abaa2-bfa1-45b5-ac0b-b4f598bc9372`.
- Loaded-role evidence: the injected agent configuration's ownership, feature
  requirements and verification/stop text match the existing
  `.github/agents/tools-ui-engineer.agent.md`, read in this worktree. The creator
  also reports successful explicit selection of that role. No alternate
  profile/model was selected; no independent host profile-inspection tool is
  available here.
- Managed branch: `cbattlegear-friendly-chainsaw`.
- Initial HEAD/base/main/origin-main: `23c2d985dca3d27dd2b819fb54babe66280077f5`.
- Source: `db14559b39d32322b06477c6ae676112f583db50`; the relevant CLI
  sources and original test file have no diff from this pin.
- Trusted manifest digest: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
- Trusted policy revision: `46a1aebeace81c8719901030e859569df4c2008e19cb841d80e887b5801077d8`.
- Dirty reconciliation: the own worktree was clean; no prior edits were replaced.
- First actual implementation operation: `apply_patch` added
  `CliToolStateHolder.applySuggestion(String)` and replaced the relative-Tab
  chip callback in `ToolsDiagnosticsScreen.kt` with direct choice plus cursor
  synchronization. Session creation/busy state was not counted as implementation.
- Candidate HEAD is the Git commit containing this file, recorded by the PR;
  publication must not present the initial base as the implementation head.

Creator `bcb17a74-5fa6-47d0-a4be-b6b595e20559` and sole allocator
`fbbcfbcc-1115-4f05-94f9-9109c347734a` were requested recipients. This session's
tool catalog has no supported cross-session messaging operation. This own-branch
artifact and its PR are the truthful handoff; delivery/acknowledgment is not claimed.

## Admission and historical intent

Before implementation the local helper evaluated the trusted default-base
`wp-owned` capability for `modify` on both production files and the deviation,
and `create` on the new test and this evidence file. All returned the invariants
`one-wp-branch-pr`, `pinned-source`, `declared-verification`,
`evidence-provenance`.

An initial `reserve.py claim --wp WP-316 --session-id
7b1abaa2-bfa1-45b5-ac0b-b4f598bc9372 --base
23c2d985dca3d27dd2b819fb54babe66280077f5` with the exact intended paths and
bounded authorization returned:

```text
BLOCKED: Existing WP lease is bound to different source/base/manifest/backend
```

The inspected record is advisory revision8, attempt/session
`bcb17a74-5fa6-47d0-a4be-b6b595e20559`, historical base `e0b6a36e...`,
branch `cbattlegear-android-port-planning`. It is not this session's reservation.
The current trusted `AUTOMATION.md` permits isolated authoring after this
historical-binding failure; it does not grant merge or execution authority.
No release/recreate, identity substitution, runtime installation or direct
ledger edit occurred.

The first transfer attempt used an unsupported operation
`transfer-current-producer` and failed without mutation. The supported
`reserve.py transfer --capability wp-owned --operation modify` for
`ToolsDiagnosticsScreen.kt`, naming that historical origin and this actual
producer with user-assignment evidence, returned both attributions,
`advisory=true`, `file_edit_blocked=false`. It does not create a successor lease.
The read-only audit lane is not promoted. WP-314 trace, AppContainer, radios,
maps, policy and build/dependency paths are untouched.

## Retained original families

All are exact single families in
`MC1Tests/Views/Tools/CLI/CLIToolViewModelTests.swift`, already represented by
unchanged `@OriginalCase` methods in `CliToolStateHolderTest.kt`:

1. Apply selected suggestion returns false when not in selection mode
2. Apply selected suggestion returns true when in selection mode
3. Clear tab state clears suggestions and selection
4. First tab shows suggestions without selection
5. Second tab enters selection mode
6. Tab completion multiple matches returns suggestions
7. Tab cycles wrap around
8. Third tab cycles to next suggestion

The native adaptation adds `CliSuggestionTouchTest` (four methods) through
the real mounted diagnostics consumer, not a reconstructed completion algorithm:
pointer choice for each entry after one through four hardware Tabs; unchanged
keyboard/IME acceptance; argument prefix and caret followed by typed input;
expanded window at double font. `CliSuggestionSelectionTest` adds two
holder-boundary methods for direct choice and stale/dismissed callbacks.
No original expectation, fixture or case credit is changed.

## Observed checks and remaining acceptance

| Actual command | Result |
| --- | --- |
| `python tools\android-port\controller\ci.py preflight` | BLOCKED, exit2: explicit `--state`/`ANDROID_CI_STATE` required |
| `python tools\android-port\controller\validate.py` | exit0, valid pinned manifest/ownership |
| `python tools\android-port\portmap.py` | exit0, traceability only |
| `git diff --check` | exit0 |
| `git diff --stat db14559b39d32322b06477c6ae676112f583db50 -- MC1/Views/Tools/CLI MC1Tests/Views/Tools/CLI/CLIToolViewModelTests.swift` | empty; no relevant source drift |

WP-316 verification remains `configured=false`, `commands=[]`. No invented
Gradle/native command ran. Discovered/executed/passed Kotlin tests: **not
measured**, not zero-test success. The new test's compilation and native event
behavior remain unverified until an authorized runner executes them. The
existing native-execution hard-lock record is untouched and grants this session
no native execution authority.

TS-30 candidate acceptance and merge are blocked on real exact-candidate
verification. No Swift/iOS, physical device, radio reception/administration,
TalkBack, license, hardware, signing or human approval is claimed.
