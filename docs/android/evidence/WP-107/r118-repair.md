# WP-107 R118 repair

Repository: `cbattlegear/MeshCoreOne-Android`. Issue #380; session-owned support
for #378 / PROTOCOL-R118-G05. Owner: `protocol-porter`. Actual implementing session:
`f8b1ae3a-f9b8-4115-a277-8a37f52cca74`. No additional workers were launched.

| Binding | Value |
| --- | --- |
| Inspected/initial candidate base | `5935af530743662e0caf95338f0d881ab58382c0` |
| Read-only Swift | `db14559b39d32322b06477c6ae676112f583db50` |
| Manifest SHA-256 | `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8` |
| Policy revision at reservation | `79f6c46a4192aeeac807d1326126eac57c6db70b36a6f011afe00ca002a48f3b` |
| Reservation | WP-107, same session/attempt, revision 2; `wp-owned`, `create`/`modify` |

The existing managed branch was initially named for WP-104. No product edits or
PR were made under that assignment. The user explicitly reassigned this unchanged
worktree to WP-107 after handing its session-owned encoder concern to #380.
The branch name is not an ownership receipt. Historical WP-107 evidence and
producer identities in this directory are preserved, not reattributed.

## Scope and acceptance mapping

The user approved retaining strict native legacy validation, reconnect-only
unanswered-channel recovery, explicit route-restoration failure, conservative
contact completeness and strict URI/device-error matching. Their exact
source/native consequences are recorded in [the deviation record](../../deviations/WP-107.md).
These decisions do not waive required execution evidence.

| Concern | Current producer/consumer and discriminating assertions |
| --- | --- |
| G05, #378 | `legacyContactFrame` via actual `updateContact` and `changeContactFlags`: empty/short/overlong keys, validation order, 0/1/63/64/65-byte paths, 1/2/3-byte hash modes, `0x80`/reserved/`0xFF` bytes, independently constructed 144-byte frames and errors before writes. `changeContactPath` has separate complete-hash/max-hop checks. The legacy UTF-8 byte prefix and modern grapheme-safe name layout are asserted separately, including coordinate/date saturation and bare OK/device rejection. |
| G01 / CM-01 | Existing `OriginalChannelCasesTest` dropped-write family retains window 8, idle 0.1s, hard 5s, grace 0.02s and default 10s. New real `ChannelSessionOps` cases assert true partial results, no new same-index write, rejection with/without an orphan, failure of same-link restart, real transport disconnect, fresh handshake and the fresh acknowledged result. |
| G02 | Five existing `CrossComponentSessionCasesTest` region families retain raw-type/zero-hop/two-phase/result/error/timeout assertions, now with original default 10s and `Test` client identifier. Existing `SessionBoundaryCasesTest` and `ReviewedSessionRegressionTest` cover restoration error after success, primary-error preservation, timeout/untagged-error isolation and caller cancellation. No new credit is assigned to those tests without a current run. |
| G08 | Original partial/no-header contact families retain header counts and results with original 1s/10s windows. Existing reviewed regressions distinguish incomplete full fetch, valid incremental baseline, missing baseline and unsolicited/late contactsEnd. Their current run is required. |
| G09 | Existing original exact-key/contact-URI families retain 10s and `MCTst`. Existing embedded-key rejection is retained. New actual `ContactSessionOps` cases distinguish NOT_FOUND from device errors 0/1/3/4/5/6/127/128/255. |
| G10 | Four original `OriginalCorrelationCasesTest` families restore 0.2s command/import deadlines, 1s/10s progressing contacts and 0.08s/1s inactivity. Virtual-time assertions check 199/200ms, the original 70ms cadence/280ms end and the original 140ms observation. A header after the initial watchdog sample changes its progress generation; the next unchanged 80ms sample expires at 160ms, matching the frozen algorithm rather than inventing a sliding deadline. A separate no-progress case asserts 79/80ms. A supplemental 999ms-progress case discriminates the full one-second window from the former 0.1s fixture. |

These are WP-107 behavior/boundary/source-test-parity repair assertions, not a
claim that every historical original family has passed. The four #378 families
remain cross-referenced to WP-104, not added to the 17 direct #380 family count.
The modern builder tests under WP-104 remain unchanged. The asynchronous
`LegacyUpdateContactHardeningTests` scenario uses the actual legacy session
send/ACK seam and its frozen input values.

WP-101 repair #431 and WP-108 repair #434 are merged ancestors of the initial
base. Existing crypto/parser/builder/event implementations are present in that
ancestry; a closed issue is not itself implementation evidence. #378 is not
silently treated as complete because its session-owned policy was resolved here.

## Verification contract

Use the repository's declared affected launcher on the committed candidate:

```powershell
python .\tools\android-port\local\check.py --commit <candidate-SHA> --affected-base <current-origin-main-SHA>
python .\tools\android-port\controller\validate.py
python .\tools\android-port\portmap.py
```

The affected launcher selects the complete module/consumer checks rather than
an invented task or method filter. The unchanged historical manifest
`configured:false` is not a full-WP acceptance waiver.

The initial environment check
`python .\android\scaffold\check_environment.py` was BLOCKED by inherited
non-allowlisted variables; no Android readiness was established. Actual committed
launcher outcome and exact commands/results belong in the PR. Hosted checks on
the exact candidate and their logs remain authoritative; do not copy/hash/upload
a second CI-result bundle here. No hardware, real BLE, physical device,
iOS/macOS, legal, signing or full-WP acceptance is claimed.

The real ChannelService/store/controller composition is outside these session
tests. The approved reconnect boundary must be honored by those downstream
owners; this change does not pretend their same-connection retry now succeeds.
