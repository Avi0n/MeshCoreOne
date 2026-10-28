# WP-303: mounted pairing consent and structured onboarding completion

Repository: `cbattlegear/MeshCoreOne-Android`.
Base: `5e6ffb716fa12ddbbbe35ebd64c1b81e3638c317`.
Reference: `db14559b39d32322b06477c6ae676112f583db50`.
Manifest: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
Policy: `79f6c46a4192aeeac807d1326126eac57c6db70b36a6f011afe00ca002a48f3b`.
Session: `e45bb8a6-8ba0-4e17-9d76-53886ee8116d`.
Owner: `services-porter`, App-owned producer for issue #386's RADIO-R118-G02/G13;
cross-reference #399. The maintainer explicitly directed completion in this same
session. No worker, schedule, runtime installation or policy change is included.

## Repair and adaptation

`AppStateOnboardingHost` previously confirmed every pending setup prompt without
waiting for user input. The root now mounts `SystemPairingSetupHost` above both
first-run onboarding and the completed shell, including Settings radio setup.
Only the currently presented prompt's Forget action starts removal. Cancel and
system dismissal preserve associations and an existing connected radio. Prompt
identity prevents stale actions from consuming a replacement.

The Material 3 modal bottom sheet preserves the frozen
`SystemPairingSetupSheet.swift` title, accessory list, explicit destructive action,
Cancel and conversation-preservation copy. The existing localized setup footer
is used instead of the source's iPhone-specific explanatory message; it states
the same forget-before-add requirement without making a false Android platform
claim. Content scrolls at large text sizes; native Back/dismissal is cancellation.
No localization source, provider, generated resource or license is changed.

Onboarding now awaits the actual user decision and completion, rather than
returning cancellation while a prompt is pending. A completion counter records
successful fresh-pairing/wiring only, so declining while an old radio remains
connected cannot report that old link as new-pairing success. Failure state stays
visible and maps to the existing typed onboarding result.

Caller cancellation propagates as `CancellationException`. The operation cancels
and joins its scan/confirmation work, cancels and joins its foreground retry and
clears busy/deferral state. The non-cancellable section only completes owned
cancellation cleanup before rethrowing; it does not convert cancellation into
success. User dismissal remains a domain cancellation. No association is removed
before confirmation. Existing service-level cancellation and association-ownership
behavior is retained, not replaced with a generic false result.

## Original-family boundary

The issue's exact 24 families remain attributed to their original owners and
incumbent tests; new App regressions are additional consumer evidence, not new
original-family credit:

| Frozen family group | Count | Existing affected runner |
| --- | ---: | --- |
| `BluetoothScanPairingServiceTests` cancellation/selection race | 2 | `BluetoothScanPairingServiceTest` in core connectivity |
| `ConnectionManagerPairingTests` delayed/exhausted other-app wait | 2 | `ConnectionManagerPairingTest` in core connectivity |
| `PairingStrandedAssociationTests` enumeration, requested removal, declined removal | 5 | `PairingStrandedAssociationTest` in core connectivity |
| `SystemPairingSetupAppStateTests` complete setup, sheet, foreground and re-entry matrix | 15 | `AppStateBehaviorTest` in App |

`PairingConsentTest` adds nine deterministic consumer cases, including cancellation
while timed and activation-triggered retries await suspending picker cleanup.
`SystemPairingSetupTest` adds five mounted Compose cases, including system Back.
`OnboardingConsentIntegrationTest` adds five cases through the real
`AppStateOnboardingHost`, `PairingCoordinator` and Room-backed process graph.
Successful discovery is held at the transport connect boundary: the host must
remain pending until the real graph is ready and App services are wired, then
return `Connected`, not `Cancelled`.
`OnboardingGateTest` additionally proves mounting above both root branches.

## Verification contract

The supported affected launcher is declared in `docs/android/ci/local-checks.md`:

```text
python tools\android-port\local\check.py --commit <candidate> --affected-base <current-default-base>
```

The hosted `.github/workflows/android-ci.yml` selects the declared scaffold for
App changes and runs `ci.py preflight`, `ci.py run --stage scaffold --scope scaffold`
and `ci.py inspect`. The scaffold includes complete App and connectivity unit
runners, App AndroidTest compilation, build, lint and strict graph/dependency
checks. No arbitrary Gradle task or method filter is introduced. These existing
candidate checks establish bounded software behavior, not full macro-WP acceptance;
the historical unconfigured feature-manifest entry is not rewritten or presented
as configured. Issue #446 records that distinction.

Source checks: `verification_config.py --check`, `validate.py`, `portmap.py`
and `git diff --check` passed during authoring. Final candidate execution and
review are recorded by their exact-head job/log and PR, not a second committed
result bundle. Authored tests are not claimed as executed until that job succeeds.

Physical Android dialogs, real radios, OEM/background behavior and full-WP
acceptance are not established by Robolectric or Room tests. The existing
WP-505 hardware workstream remains the authority for those device obligations.
