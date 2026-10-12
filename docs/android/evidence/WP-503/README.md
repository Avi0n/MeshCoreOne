# WP-503 attempt evidence

**Incomplete, not accepted.** Native suites execute through the existing App unit runner.
The baseline ran 24, passed 6, failed 18; later focused cohorts ran 26 (18 passed/8 failed)
and 10 (8 passed/2 failed), with zero errors/skips, at their distinct candidates below.
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
| `python .\tools\android-port\controller\ci.py preflight` | Exit 2, BLOCKED: `Explicit --state/ANDROID_CI_STATE is required; failed cloud setup is not readiness` | Initial invocation lacked explicit state; not proof of absent tools |
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

## Actual native execution

The session-local targeted launcher uses the installed `local/run.sh` serialized
bundle/ext4 snapshot, pinned state and `candidate_environment`, then invokes existing
Gradle tasks. It is not a new canonical manifest runner. The installed adapter does not
reject the verified project-handle/CLI alias; no identity guard blocked execution.

| Exact task / candidate | Actual result |
| --- | --- |
| `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.* --console=plain --dependency-verification strict --build-cache --no-parallel --max-workers=2 -PscaffoldTestHeap=512m`, `b42c9a41e8ccc62b3c82f255dca7dc7065a2c472` | Native preflight succeeded; compilation failed on test-only `CustomActions` key location; zero discovery explicitly rejected, not success. Fixed to `SemanticsActions.CustomActions`. |
| Same task/options, `86993ee65ca43fb5f7da26f537b3857e1cd4c1a9` | Compiled; discovered/run 24, passed 6, failed 18, errors/skips 0, SDK31/37. Passing families: disconnected diagnostics explanation, sent-room no retry, mixed CJK/Hebrew direct retry/font/targets. |
| Same task/options, `4f8b6a5d324d2d13dcbbce2502c200992c7e325a` | Discovered/run 24, passed 6, failed 18, errors/skips 0. Added paragraph/viewport diagnostics; partial grid-target artifact is not promoted to an intrinsic control defect. |
| `:core:designsystem:testDebugUnitTest :app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeAppearanceAccessibilityTest --tests com.meshcoreone.android.accessibility.NativeDiagnosticsAccessibilityTest --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.direct* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.rendered* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.audit* --tests com.meshcoreone.android.accessibility.NativeMessagingAccessibilityTest.roomFollowUp* --tests com.meshcoreone.android.core.designsystem.ThemeContrastTest --console=plain --dependency-verification strict --build-cache --no-parallel --max-workers=2 -PscaffoldTestHeap=512m`, `22309ff1eb9a1ccddecdd3375ec2de1aee77a812` | Discovered/run 26, passed 18, failed 8, errors/skips 0. Existing theme-role families 10/10 and actual-timeline guard controls 4/4 pass. Remaining failures: legacy room follow-up author, painted-span contrast, appearance allocation flags and populated diagnostics. |
| `:app:testDebugUnitTest --tests com.meshcoreone.android.accessibility.NativeAppearanceAccessibilityTest --tests com.meshcoreone.android.accessibility.NativeDiagnosticsAccessibilityTest --tests com.meshcoreone.android.accessibility.NativeFontAuditRegressionTest --console=plain --dependency-verification strict --build-cache --no-parallel --max-workers=2 -PscaffoldTestHeap=512m`, `1fad28d89f750675285c354c28f91239bc04890e` | Discovered/run 10, passed 8, failed 2, errors/skips 0. Appearance 2/2, font regressions 4/4 and disconnected diagnostics 2/2 pass; populated diagnostics 0/2. |
| Same focused task/options, `abc86cb0dfd885d4dabd14a07f637ea031ad9b29` | Same 10 run/8 passed/2 failed, zero errors/skips. Rejects empty line measurements and records all three graph widgets' zero heights before failing. |
| Same focused task/options, `d825ea3d2ec82a270012890767097c77a3aa95c9` | Same 10 run/8 passed/2 failed, zero errors/skips. Compiles native scroll-reachability support; current non-scrolling product still has all three numeric widgets at zero height. No normative failure reclassified. |

Both executing candidates measured **38 effective frames x 2 directions x 5 emitted ranges
x 2 SDKs = 760** actual foreground/background records. **162 fail** unchanged thresholds,
covering **81 distinct parameter segments**, including **22** high-contrast identity
records below 7:1. Lowest ratio: **1.0435430264922168:1**, Fern/dark/standard/outgoing
hashtag. Default/light/standard/outgoing hashtag: **1.285454842066801:1**. Each background
comes from native window pixels and is verified against the actual emitted bubble role;
effective overlapping base/link/span colors and mention fills come from `TextLayoutResult`.
No source-palette proxy or sampled text foreground is substituted.

Enabled targets on passing flows measured at least 48dp; observed examples include
70x48, 114x48, 165x48, 86x54 and 110x54dp. This is not all-feature target acceptance.
Real Resources font scale 2.0 was asserted; mixed-direction direct text was unclipped.
Short-label width-overflow flags were disproved as clipping: e.g. Default's native widget is
84x30 but paragraph allocation 312x30; actual line left/right 0..84, top/bottom 0..30 and
896 non-background pixels. All 20 theme/API labels have visible ink and contained lines.
The oracle now requires nonempty lines, no truncation/ellipsis and zero-tolerance actual
line bounds. SDK31/37 regression controls demonstrate valid shrink-wrap and reject genuine
32x4dp clipping; no threshold/golden change or font product finding.

Populated noise-floor Minimum/Maximum/Average widgets are 253x0/260x0/259x0, with zero
visible bounds on both SDKs inside a real 360x900 viewport. Count and all three summary
values/current reading are data-bound and pass. Unlike the font artifact, the actual
widgets have height zero. Source's unweighted/non-scrolling Column and fillMaxSize chart
consume the available height before statistics. Assigned
[#214](https://github.com/cbattlegear/MeshCoreOne-Android/issues/214) is claimed in the existing
coordinator's WP-316 repair scope; no production edits or device observation here. A legitimate native scroll ancestor
may reveal metrics for measurement; all metrics need not fit simultaneously.

The confirmed contrast finding was filed/assigned as
[#208](https://github.com/cbattlegear/MeshCoreOne-Android/issues/208) at coordinator direction.
Four room findings remain assigned to #207. No product or WP-501 binding edit occurred.

The coordinator identified a test assertion weakness: checking only the artificial
`audit-root` does not prove actual timelines lack a broad live region. It is corrected in
owned tests to inspect actual ancestors/subtrees of two real rows. A negative control puts
a live region on the real `ChatTimeline`; a positive control permits a small failure-only
region. These are harness regressions, not product bugs or expected-passing room defects.
Actual guard controls and source/Material-role families passed at exact22309ff1e above.
Corrected all-theme name/font/target enumeration passed at exactabc86cb0d. Unchanged
normative room cases await genuinely repaired bytes, including their new required link API;
these passing selected cohorts do not turn legacy room/contrast failures into success.

Hosted [run38264623000](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/38264623000)
for exact b42c9a41e failed at that test compilation error. Its authoritative result is not
transferred to later repaired heads. No automated result/JUnit/image bundle is committed,
uploaded, hashed or revalidated here.

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
The earlier hypothetical ownership/execution blocker was posted to
[issue136](https://github.com/cbattlegear/MeshCoreOne-Android/issues/136#issuecomment-6125830322).
It is withdrawn; the resumed measured handoff is
[comment6126652467](https://github.com/cbattlegear/MeshCoreOne-Android/issues/136#issuecomment-6126652467).
The native cross-session messaging tool is not exposed in this worker; this issue comment
and the draft PR are the persistent coordinator handoff, not a fabricated message receipt.
