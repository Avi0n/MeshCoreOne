# WP-311 NM-OUTPATH-PICKER-CONSUMER

Repository: `cbattlegear/MeshCoreOne-Android`; owner: `nodes-map-ui-engineer`.
Assigned base: `23c2d985dca3d27dd2b819fb54babe66280077f5`.
Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
Branch: `cbattlegear-verbose-telegram`; candidate head is the enclosing commit.
Session/attempt: `869123e3-46d4-474e-94aa-4bc2d026143b`.

## Scope and admission

This is the allocator's single production-defect group, not a new family investigation:
the mounted path editor bypassed the existing Add-Hop policy. The 17 retained matching,
favorite and recents families are from
`MC1Tests/ViewModels/PathManagementViewModelEditingTests.swift`; producer findings
`P-PICKER` and the separate persistence-evidence limitation remain distinguishable.
The original picker and matching implementation were also read at the frozen pin.

The same owner's typed reservation was evolved from revision 8 to 9 using
`tools\android-port\local\reserve.py evolve`, preserving historical authorization and
binding. Operations: `modify`, `test`, `record-evidence`, `reconcile-current-base`.
This change uses only `android/feature/nodes/`, this evidence directory, and
`docs/android/deviations/WP-311.md`. No shared paths, other worktrees or WP-503 tests changed.
The existing managed branch fast-forwarded to the assigned main before implementation.
Unrelated lock/license paths shown by status were not reverted, staged or included.

The base contains prerequisite merge commits for PR49 (WP-209), PR293 (WP-303),
PR33 (WP-304), and PR140/229 (WP-312). PR229 is a later map follow-up, not a replacement
for original PR140. The allocator also reported recovery277; a merge-title grep did not
independently locate that reference, and no additional prerequisite claim is inferred.

Current checkout manifest SHA-256:
`949598d9d070f14378dcda6150f67ead1bb70d29fc261c685cadf6649c4aa175`;
policy SHA-256:
`c6319cc5ad88b2c70fbab7df6a5e55873aa3f49f884e50119c680332b69ef906`.
These describe current checkout bytes, not a rewrite of the reservation's historical hashes.

## Implementation and original correspondence

`ContactDetailScreen.kt` now mounts `AddHopPickerDialog.kt`. It delegates section
partitioning/deduplication, name/key matching, sorting and empty states to `HopPicker.kt`
and `HopNodeMatching.kt`, with the actual `PathManagementStateHolder` as source.
It exposes existing localized filters and section/row labels. Rows and wrapping filters
retain minimum 48dp targets; content scrolls within the current dialog window.

Recents are snapshotted at presentation, as in frozen `AddHopPickerView.swift`, and next
presentation reads the holder's persisted keys. Existing `RecentHopsStore` owns radio
scoping, lowercase serialization, move-to-front, deduplication and the eight-key cap.
Single-node additions leave the chooser open until Done/full. Parent bulk entry, path-width
adoption, save rejection and encoding are unchanged.

The production chain remains `AndroidAppContainerFactory` ->
`SharedPreferencesStringLists` -> `createNodesFeatureDependencies` -> holder ->
`AddHopPicker.buildResults` -> mounted rows. App adapter files were read only. No new
feature dependency, provider, service action, resources or license inputs are introduced.

## Declared checks and actual outcomes

Environment: Windows, installed JDK21/SDK37, strict dependency verification, one Gradle
worker, in-process Kotlin. Final Gradle runs set `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2`
to bound JVM thread demand after actual shared-host native-memory failures.

From the repository root:

```powershell
python tools\android-port\controller\ci.py preflight --state <session>\files\qr237-env\environment.json --output <session>\files\nm-picker-preflight --local
python -B tools\android-port\portmap.py
python -B tools\android-port\controller\validate.py
git diff --check -- android/feature/nodes docs/android/deviations/WP-311.md docs/android/evidence/WP-311
```

Preflight, traceability and controller validation passed (exit 0). Scoped whitespace
validation passed. An initial preflight invocation with the obsolete
`tools\android-port\ci.py` path failed because that file does not exist; the current
documented controller entry point above was then used, without provisioning.

From `android`, final regression command:

```powershell
.\gradlew.bat :feature:nodes:testDebugUnitTest --tests '*AddHopPicker*ComposeTest' --tests '*ContactPathActions*ComposeTest' --tests '*PathManagementViewModelMatchingTest' --tests '*PathManagementNativeTest' --dependency-verification=strict --no-daemon --quiet --max-workers=1 "-Pkotlin.compiler.execution.strategy=in-process"
```

Passed, exit 0. JUnit XML discovery:

| Suite | Tests | Failures/errors/skipped |
| --- | ---: | --- |
| AddHopPickerApi31ComposeTest | 4 | 0/0/0 |
| AddHopPickerApi37ComposeTest | 4 | 0/0/0 |
| ContactPathActionsApi31ComposeTest | 2 | 0/0/0 |
| ContactPathActionsApi37ComposeTest | 2 | 0/0/0 |
| PathManagementViewModelMatchingTest | 15 | 0/0/0 |
| PathManagementNativeTest | 13 | 0/0/0 |
| Total | 40 | 0/0/0 |

The new native consumer assertions cover actual Detail -> Edit -> Add Hop reachability;
separate name/key branches for hex text; nonhex and empty search; favorites and discovered
filtering/labels; multi-add without dismissal; Done/full; reloaded ordered/capped recents,
lowercase hex and radio isolation. Existing path suites retain validation-before-encoding,
width, save and discovery behavior. Editing does not send radio calls.

Lint command, run successfully together with the preceding 36-test scoped selector set
(the same command without the `ContactPathActions` selector):

```powershell
.\gradlew.bat :feature:nodes:testDebugUnitTest --tests '*AddHopPicker*ComposeTest' --tests '*PathManagementViewModelMatchingTest' --tests '*PathManagementNativeTest' :feature:nodes:lintDebug --dependency-verification=strict --no-daemon --quiet --max-workers=1 "-Pkotlin.compiler.execution.strategy=in-process"
```

Passed, exit 0, 36 tests and no failed/errored/skipped tests. Lint HTML/XML/SARIF are under
`android/feature/nodes/build/reports/`; existing `NodesUiText.kt` resource-read warning
remains, not repaired by this group.

Earlier attempts found an incorrect test-only `NodesNavigation` import (corrected), and
native host OOM/converter subprocess failures before the updated tests could execute.
Those failed attempts are not passing evidence. The final runs above completed after
resource recovery; no checks were disabled and no dependency/host settings changed.

## Limits and readiness

This is bounded local regression evidence, not whole-WP completion or independent review.
Manifest WP-311 verification remains `configured=false`, `commands=[]`; whole-WP acceptance
is blocked pending supervised command/check configuration. No hardware/device execution,
TalkBack session, process-death/disk-durability test, source-family credit, adaptation
approval or protected-gate approval is claimed. In-memory preference recreation is not
disk persistence evidence. No copied hosted CI-result bundle is committed; exact-candidate
hosted results remain authoritative when produced.
