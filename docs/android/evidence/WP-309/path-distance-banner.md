# WP-309 path-distance banner increment

Same ready successor PR #305, managed branch/worktree and canonical leased/advisory owner
`c2707711-5b8b-4bfb-8d51-ada7caaf12a4`, reservation revision 3. Parent:
`bea7e4e97344160cabb0ce1eff3b0f71f03ee5aa`; trusted main:
`5f8fb5ded190f1473a2107ac0090e785cbce9475`; Swift pin:
`db14559b39d32322b06477c6ae676112f583db50`. No new support paths: actions/deviation/evidence are wp-owned,
and the App repair-test file is already admitted. `reserve.py status` confirmed actual lease before
edits. No scope/ledger/runtime/authorization rewrite was needed. Shared WP-312 source is read-only.

## Source and behavior

Read frozen MessagePathPreviewMap, MessagePathPreviewMapTests (the original 12,400m overlay case),
shared PathDistanceBanner and FallbackMatchIndicatorView. The source shows distance and/or incomplete
disclosure independently, excludes duplicate hop count, and exposes a localized expand purpose.
`git diff db14559b39d32322b06477c6ae676112f583db50 -- MC1\Views\Map\PathDistanceBanner.swift MC1\Views\Chats\Components\MessagePathPreviewMap.swift MC1Tests\Views\Chats\Components\MessagePathPreviewMapTests.swift`
returned no drift.

`PathMap` previously truncated to literal meters and hid plain incomplete text when distance was null.
It now consumes `MessagePathDistanceBanner` for both its existing placed-map and unplaceable branches.
The banner uses Android's existing platform ICU `NumberFormatter`, meter input, abbreviated width,
explicit current resource locale and public `usage("road")` on API 33+. Public SDK documentation
confirmed `usage` starts at 33 and `forSkeleton` only at 37, so no hidden/unavailable skeleton method is
used. API 31–32 uses public ICU regional measurement system and unit/precision formatting with simple
whole-kilometer/whole-mile transitions, otherwise meters/US feet/UK yards, up to two significant digits.
No feature dependency, new library, shared formatter fork, region/symbol table or Foundation-output clone.
Prior art WP-313 RoadDistanceFormat and oracle cases were read but not imported/copied: this fallback
does not reproduce its 900m/half-mile thresholds or fine/coarse rounding ladders. Short-distance behavior
differs, explicitly documented in D-14; ICU versions may also differ from Foundation.

Incomplete state is independently visible with null distance; neither null nor unplaceable invents
zero. Its localized label/hint opens a real localized title/explanation dialog with 48dp controls,
scrollable text and dismissal. The map-entry accessibility label reuses the source purpose resource.
Geometry, providers, native renderer, network, cache and capsules are untouched. Endpoint/copy behavior
and all six prior mounted methods are preserved.

## Focused assertions

Two added methods in the same admitted App test file:

- `original12400mBannerUsesResourceRoadUnitsAndExposesIncompleteWithoutDuplicateHops` mounts the exact
  production banner on API 31 and 33 at 200% for en-AU, en-US, en-GB and fr-FR while ambient locale differs.
  It asserts km versus mi and numeric scale for original 12,400m (rather than truncated raw meters),
  French 1,500m decimal comma, explicit API31 fallback 950m/100m unit-rounding cases, text layout,
  exactly distance plus incomplete label/no hop count,
  localized click hint/48dp target, actual dialog open/Done dismissal and null/incomplete transitions.
  Unit precision/spacing need not pretend byte-identical Foundation output.
- `incompleteUnlocatedPathOffersRealExplanationWithoutInventingDistanceOrMapSuccess` mounts the real
  App input adapter/public route/holder with an unknown hop and no GPS in Chinese/200%. It activates the
  accessible map entry, verifies real unplaceable state, opens/dismisses disclosure, recomposes, finds
  no numeric map distance, returns to retained endpoint/SNR/copy and checks no redundant reads.
  The unplaceable branch does not instantiate MapLibre or use network/native rendering.

These are authored software assertions, not passing Android/device/TalkBack/map rendering evidence.
No full AppContainer/radio graph, screenshot/golden or whole-WP acceptance is claimed.

## Verification chronology

Parent bea7's own automatic run `39914011295` was still IN_PROGRESS at fresh read: controller success,
Linux Android task executing. That result belongs only to bea7, never the new banner bytes.
No rerun/dispatch was requested.

`python android\scaffold\check_environment.py`: BLOCKED exit 2 (inherited non-allowlisted environment).
`git diff --check`: PASS.

`python tools\android-port\controller\ci.py python --output C:\Users\camer\.copilot\session-state\d5b384f6-0bf0-4729-9241-c43c31f20094\files\wp309-distance-source-checks`:
initial run BLOCKED at traceability, exit 2, because the new banner mixed PortedFrom and AndroidOnly
dispositions. Corrected its header to the source mapping, preserving the shared source ReferenceInput.
Final run PASS: 15 scaffold + 108 parity-accounting discovered/run/passed, zero failures/errors/skips;
validators, traceability, notices and semantic checks passed. These are precommit source checks on the
edited tree; runner revision names parent bea7, not a completed Kotlin/Android test run.

No runtime/hook/SDK installation, native/guest/device/probe, execution allowance or lock bypass.
Current-increment source checks and exact-commit ordinary publication/own automatic run are recorded in
the same PR body after commit. Hosted results/logs remain authoritative; no duplicate CI-result bundle.

## Static formatter-scope correction

Review of published b554 found both private formatter declarations accidentally inside the `remember`
lambda. Kotlin disallows private local functions, and declarations after the nullable formatting
expression also changed the lambda result to Unit. This was a blocking source defect, not an observed
hosted failure. The correction moves both helpers to file scope unchanged and explicitly types the
remembered value as `String?`. The lambda now returns only the nullable formatted string.
API guards, formatter behavior, all endpoint/copy/banner methods and other production files are unchanged.
The corrected changed candidate is committed/pushed normally; its own check/run identity and outcomes
are recorded in PR #305, without borrowing b554 execution or rerunning an unchanged candidate.
