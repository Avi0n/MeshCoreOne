# WP-309 selected-arrival path copy increment

Repository: `cbattlegear/MeshCoreOne-Android`. Same open successor PR #305 and existing managed
WP-309 branch/worktree/session. Parent implementation: `d7004794b9a8707d18e51512ed3e5d038cfc9534`;
trusted main: `f539e0ac5454d9099ec2d1b0e8d0f93e9a7c0e54`; frozen Swift:
`db14559b39d32322b06477c6ae676112f583db50`. Acceptance touched, not completed:
WP-309-behavior, WP-309-boundaries, WP-309-source-test-parity.

The continuation explicitly adds path clipboard behavior to this bounded successor; maps/capsules
remain excluded. Canonical advisory lease `c2707711-5b8b-4bfb-8d51-ada7caaf12a4` remains leased at
revision 3. Before edits, `python tools\android-port\local\reserve.py status` confirmed wp-owned
actions/deviation/evidence and the already admitted App repair-test path. No new support path or
reservation evolution is needed; no ledger/runtime/authorization change occurred.

## Source and real consumer

Frozen `MessagePathContent.swift` renders a copy control after the receiver iff hops are nonempty;
it writes `arrival.pathStringForClipboard`, not message text or the arrow display.
`MessagePathArrival` already preserves uppercase comma-separated serialization. Its formatter is
unchanged. No drift was found by:

`git diff db14559b39d32322b06477c6ae676112f583db50 -- MC1\Views\Chats\Components\MessagePathContent.swift`

The actual App calls `MessageActionsRoute` -> content -> `PathDetails` -> `PathHopList`. The latter
now uses the same `LocalClipboardManager` platform convention already used by App message-text copy.
No additional App interface/dispatcher is needed, so existing message-copy dismissal cannot affect
path copy. Source `pathString` is displayed separately. Recomposition captures the currently selected
arrival, with no effect-triggered clipboard write, success toast, service read or dismissal.
Generated copy button/accessibility/hint strings are reused unchanged. See D-13 for native layout.

## Mounted assertions and limits

The four existing endpoint methods are preserved. Two additional methods in the already admitted
App test file mount the real route/holder/data source and inspect Android `ClipboardManager`:

- `selectedPathCopyWritesPlatformClipboardWithoutDispatchingMessageCopyOrLeavingDetails`: English and
  Chinese at 200%; canonical `0A,FF` -> selected echo `A3,7F` -> recomposition -> canonical; clipboard
  differs from message text/arrow display; localized label/hint, 48dp target, receiver/SNR and unchanged
  read counts. The route's existing message-action callback throws if accidentally invoked.
- `zeroHopAndUnavailableNeverOfferOrWritePathCopy`: nil/empty zero-hop and true unavailable retain an
  existing clipboard value, omit the control, and preserve read counts through recomposition.

These are authored mounted regressions, not physical device/TalkBack proof or full App graph evidence.
The platform clipboard consumer is real; no mock clipboard or notification substitutes for the write.

## Checks

`git diff --check`: PASS.

`python tools\android-port\controller\validate.py`: PASS, 64 WPs / 178 edges / 1,866 tracked reference
files, manifest SHA-256 `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.

An initial mistyped `python tools\android-port\controller\portmap.py` failed with exit 2 (file absent).
The declared runner below executed the correct `tools\android-port\portmap.py` successfully.

`python tools\android-port\controller\ci.py python --output C:\Users\camer\.copilot\session-state\d5b384f6-0bf0-4729-9241-c43c31f20094\files\wp309-copy-source-checks`:
PASS; 15 scaffold + 108 parity-accounting tests discovered/run/passed, zero failures/errors/skips.
Traceability, pinned notices, parity and semantic checks passed. This is source/accounting evidence,
not Kotlin or mounted-test execution; runner revision still identifies parent d700 before commit.

`python android\scaffold\check_environment.py`: BLOCKED, exit 2, inherited non-allowlisted environment.
No dependency/runtime install or execution allowance requested. At fresh read, binding-fix PR #304
remains OPEN/unmerged, so it has not been integrated and no fresh Android execution is claimed.
Exact commands/outcomes after this increment's commit and ordinary publication are recorded in PR #305.
Exact hosted results/logs remain authoritative; no second CI-result bundle is retained.
