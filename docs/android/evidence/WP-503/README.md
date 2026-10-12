# WP-503 attempt evidence

**Incomplete, not accepted.** Native suites now exist and are registered additively with the
existing App unit runner. Actual execution is pending on the serialized Linux Gradle lane.
No screenshot golden comparison, device TalkBack observation or independent review occurred.

## Identity and revisions

| Field | Actual value |
| --- | --- |
| Repository / issue | `cbattlegear/MeshCoreOne-Android` / [#136](https://github.com/cbattlegear/MeshCoreOne-Android/issues/136), open and assigned to `cbattlegear` |
| WP / profile | `WP-503` / `design-system-engineer` |
| Workspace creation handle / CLI session | `1666502a-0420-46ef-930f-e78651ba8b9f` / `d2980f28-620c-408b-9947-067e2da8eedf` |
| Managed branch | `cbattlegear-issue-136-wp-503-accessibility-audit-807238` |
| Inspected base/head before audit documentation | `d958471ab4835813881a5e7af36e8249a8b273a4` |
| Documentation candidate head | The Git commit containing this document; exact published head belongs in PR/issue handoff, not a self-referential file hash |
| Frozen source | `db14559b39d32322b06477c6ae676112f583db50` |
| Manifest SHA-256 | `39bd73ab7759d7f65f7ecc681cd472e4763d8c8d3e9f5973bc0c515323611dda` |
| Policy revision | `3fc0afa19ad31f0210a5d427f655ef90b50a8e64e818fe3420b188303b99fb20` |
| Typed reservation | Same canonical attempt, revision 3; `wp-owned` create/modify on the four WP-503 roots plus `dependency-resolution-metadata` / `update-directly-required-build-wiring` on `android/app/build.gradle.kts`; no release/recreate |

The advisory receipt correctly preserves canonical project handle
`1666502a-0420-46ef-930f-e78651ba8b9f` as `session_id`. The coordinator independently
resolved BOTH it and CLI/runtime alias `d2980f28-620c-408b-9947-067e2da8eedf` to this same
worker, branch/worktree, creation timestamp and PR205. The earlier hypothetical identity
mismatch is withdrawn; no guard requiring replacement was encountered. No second claim,
immutable identity replacement, release/recreate or manual ledger edit occurred.
Issue assignment and PR205 are independently observable GitHub facts, not invented ledger
bindings. No hosted publisher task or acceptance receipt is claimed.

WP-503 depends only on WP-407. GitHub reports PR164 merged on `2026-10-11T16:22:30Z`
as `250dd50ed259c5d9fe1006d4f45a637e4aea0aff`, and that commit is an ancestor of the
inspected head. The coordinator additionally identified WP-407's
`advisory-terminal-merged` receipt: session `524fad60`, PR164, head
`e9f0febc7e53b09adb00ac56928e7073366023f6`, merge as above. Lease/completion fields are
not that terminal receipt and are not a pre-edit blocker. No device/source acceptance is
inferred from it, and no ledger was manually repaired.
WP-501 is not a dependency and its existing repair/parity surfaces were not edited.

The only reference-tree differences reported by Git are the two test-only paths enumerated
in `reference-amendments.json` (correlation serializer ordering and advertisement waits).
The source pin is not advanced. Manifest validation passes against those explicit exceptions.

## Actual commands and outcomes

Commands ran from this worktree on Windows on 2026-10-12.

| Exact command | Result / discovery | Claim boundary |
| --- | --- | --- |
| `gh issue view 136 --repo cbattlegear/MeshCoreOne-Android --json number,title,body,comments,assignees,labels,state,url` | Passed; original issue had no assignees and no comments | Required assignment context, not verification |
| `gh issue edit 136 --repo cbattlegear/MeshCoreOne-Android --add-assignee cbattlegear` | Passed; subsequent view confirmed assignee | Account owns tracking; dedicated worker identified in comment |
| `gh pr view 164 --repo cbattlegear/MeshCoreOne-Android --json state,mergedAt,mergeCommit,headRefOid,url` | Passed; state MERGED, exact merge recorded above | Not a WP acceptance attestation |
| `git merge-base --is-ancestor 250dd50ed259c5d9fe1006d4f45a637e4aea0aff HEAD` | Exit 0 | Actual merge ancestry only |
| `python .\tools\android-port\controller\ci.py preflight` | Exit 2, BLOCKED: `Explicit --state/ANDROID_CI_STATE is required; failed cloud setup is not readiness` | Native environment not ready; no fallback installation |
| `python .\tools\android-port\controller\ci.py run --stage prepare` | Exit 2, same explicit-state blocker | No strict dependency preparation occurred |
| `python .\tools\android-port\controller\validate.py` | Exit 0 | Manifest/source/ownership validation, not feature acceptance |
| `python .\tools\android-port\portmap.py` | Exit 0 | Traceability only |
| `python .\tools\android-port\theme_convert.py --check --self-test` | Exit 0; discovered/run/passed 22, failed/error/skipped 0; 10 themes, 44 color assets, 89 primary inputs, 23 generated outputs, 12 recovery locales | Python frozen converter/resource checks, **not Kotlin contrast or rendered feature tests** |

No test counts are inferred from filenames, source declaration counts or historical results.
Reproducible hosted checks remain authoritative at their exact candidate commits; no second
CI-result bundle is persisted here.

## Follow-up readiness and admission investigation

The initial missing-state errors above do not prove no toolchain is installed. A follow-up
WSL probe with `bash -lc` confirmed both the installed local adapter's declared
`$HOME/meshcoreone-work/toolchain/environment.json` and pinned Python executable exist.
An earlier quoted-variable probe was inconclusive and is not evidence of absent tools.

The documented `ci.py preflight --state <installed environment.json> --output <private
WP-503 directory> --local`, invoked through the installed Python in WSL, exits 2:
`Git command failed: rev-parse HEAD`. A direct `git rev-parse HEAD` in that WSL-mounted
worktree explains the failure: its Windows `.git` pointer is not a Linux Git path.
No `.git` pointer, shared checkout or global Git configuration was rewritten. The existing
Git-bundle/ext4 snapshot adapter is the appropriate execution route; its optional full
pre-push attempt previously remained queued on the shared Gradle lane and did not run.
This is not an absent-SDK claim or a native-test failure.

The trusted admission helper also confirms `android/app/build.gradle.kts` is admitted for
`update-directly-required-build-wiring` (`dependency-resolution-metadata`) and
`update-build-or-manifest` (`app-build-launcher`). A generic `modify` probe fails because
it is not the typed support operation. Therefore App runner support is **not** an unknown
path capability, and does not require new authorization prose. Same-attempt CAS evolution
revision 2 -> 3 admitted additive registration before the App edit.

The hosted result for initial documentation head
`ea4300e0e457dd9ad63767c244d908722f1e6984` is
[run38261041154](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38261041154):
scope/controller/android-ci succeeded; Linux native build and both Swift oracle jobs were
skipped for the classified documentation change. These are not native accessibility passes.
Only that hosted result/log is referenced; no CI bundle is retained.

## Acceptance and resumption gates

| Acceptance | Current evidence | Remaining requirement |
| --- | --- | --- |
| `WP-503-behavior` | Actual feature-consumer source review; four WP-310 findings; reusable tests identified | Native target/contrast/font/semantics measurements across actual flows and device focus/announcements |
| `WP-503-boundaries` | Textual alternatives and missing message semantics documented; no golden changes | Data-bound graph/status assertions, approved deterministic screen evidence and distinct device observations |
| `WP-503-source-test-parity` | All four WP-503 original test cross-references read, 23 source declarations inventoried | Executed acceptance, not a duplicate of WP-501's parity accounting |

**Identity blocker withdrawn:** the coordinator verified the valid alias relationship.
Implementation resumed with canonical identity/history unchanged. A session-local targeted
launcher uses the existing `local/run.sh` bundle/ext4 snapshot adapter, shared Gradle lock,
installed state and `candidate_environment` credential stripping. It invokes the declared
`:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.*` task with strict
dependency verification. It checks actual JUnit discovery and rejects missing/zero/skipped
tests. It is local diagnostic execution, not a manifest acceptance runner or hosted CI result.
An initial launcher call exposed CRLF in the mounted shell script; the adapter is copied
with LF into the private control directory, just as `check.py` already does. Product/tool
source files were not changed to work around host paths.

Separately admit WP-503's real runner/output acceptance contract: its manifest verification
still has `configured=false` and no commands. Existing App task registration executes owned
sources without claiming that the manifest acceptance contract has become configured.
No Gradle task was invented.

The coordinator routed A503-01 through A503-04 to the dedicated WP-310 producer in issue
#207, worker `5ca13f75-8466-4dd5-b630-02b30841a5d0`. Re-audit actual repaired bytes once
published/merged. There is no protected product decision requesting new colors or
relaxed thresholds. No nested worker, signing/release action or repository setting was changed.

The audit and execution contract are in [accessibility documentation](../../accessibility/README.md).
The live ownership/execution blocker was posted to
[issue136](https://github.com/cbattlegear/MeshCoreOne-Android/issues/136#issuecomment-6125830322).
The native cross-session messaging tool is not exposed in this worker; this issue comment
and the draft PR are the persistent coordinator handoff, not a fabricated message receipt.
