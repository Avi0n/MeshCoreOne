# WP-309 selected-arrival endpoint repair candidate

Repository: `cbattlegear/MeshCoreOne-Android`; owner: `chats-ui-engineer`.
Integrated actual main/base: `f539e0ac5454d9099ec2d1b0e8d0f93e9a7c0e54`.
Pinned source: `db14559b39d32322b06477c6ae676112f583db50`.
The commit containing this document is the successor candidate; its exact head is reported in the PR.
Manifest SHA-256: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
Current trusted policy revision: `0f92a7d147068702a2e0b07e33700e4960bb1315b799f24dd2d07d4636ab7a16`.
Existing canonical session/attempt: `c2707711-5b8b-4bfb-8d51-ada7caaf12a4`;
App session: `d5b384f6-0bf0-4729-9241-c43c31f20094`;
managed branch/worktree: `cbattlegear-laughing-barnacle`. Prior PR #276 is merged, not edited.

## Confirmed missing behavior and implementation

Frozen `MessagePathContent.swift` renders sender, every intermediate hop, then receiver/SNR for
every available arrival, including zero-hop; only true `isPathUnavailable` suppresses rows.
Frozen `PathHopRowView.swift` supplies localized roles and node-ID/signal feedback.
`MessagePathViewModel.swift`/its tests specify outgoing local identity, incoming channel name,
incoming DM exact/fallback/unknown resolution and the first prefix byte in uppercase hex.
`MessagePathDetailBlock.swift` uses the connected device's name regardless of its location,
falling back to localized You. The source input comparisons below found no drift.

Android's prior `PathHopList` returned only Flood for zero-hop and only intermediate rows otherwise;
`MessageMetadata` was not an endpoint equivalent. Its App input adapter retained the radio name
only inside a located `PathEndpoint`. This successor restores the missing consumer rows:

- Every available arrival shows sender and receiver surrounding the unchanged intermediate list.
  Flood summary and First/#2 selection are preserved; unavailable arrivals still suppress rows.
- Sender reuses existing directory resolution/first-byte ID, preserving name confidence.
  Possible matches expose a localized explanation via a real 48dp control.
- Receiver shows same-radio identity, independent of GPS/GMS/permission, or localized You;
  selected-arrival SNR uses existing quality thresholds and generated localized resources.
  Null echo SNR stays absent, never replaced by the message's canonical SNR.
- The existing real `AppMessageActions` caller threads `messagePathLocation.localDeviceName` into
  an optional trailing route/content input. Existing required arguments remain compatible.
  Geometry, maps, phone-location policy, path resolver, arrival selection, durable reads,
  radio lifecycle, reaction/block/repeat actions and original message text are unchanged.

No new control/fixture framework, module/dependency/resource generation, clipboard/map/capsule work,
translation/provider, foreign producer path or frozen source edit is introduced.

Acceptance touched, not completed: `WP-309-behavior`, `WP-309-boundaries`, `WP-309-source-test-parity`.
Native adaptations: D-10 through D-12 in `docs/android/deviations/WP-309.md`.

## Automatic ownership admission

The existing leased/advisory WP-309 reservation revision 2 was read and preserved. Before App wiring
edits, the actual trusted CLI successfully evolved the same owner to revision 3:

```powershell
python tools\android-port\local\reserve.py evolve --wp WP-309 --attempt c2707711-5b8b-4bfb-8d51-ada7caaf12a4 --session-id c2707711-5b8b-4bfb-8d51-ada7caaf12a4 --expected-revision 2 --path android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppMessageActions.kt --operation add-direct-support-wiring
```

Result: admitted under trusted `app-build-launcher`. The existing admitted App actions repair test
path is reused, and production actions/deviation/evidence remain `wp-owned`. The historical binding,
authorization text, identities and branch/worktree are retained, not silently rewritten to the new base.
No lease recreation, raw-ledger edit, runtime installation, foreign identity or new user permission
request occurred. The App edit is only the endpoint-name adapter and its direct call-site argument;
no overlapping producer semantic edit was discovered in this baseline.

## Mounted regression coverage authored

Four additional methods in `MessagePathEndpointComposeTest`, in the existing admitted
`android/app/src/test/kotlin/com/meshcoreone/android/app/actions/MessageActionsRepairComposeTest.kt`:

1. Nil and empty canonical zero-hop with delayed reads, same-radio no-location/invalid-geometry identity,
   selected real echo path and SNR, sender-hop-receiver order, First/#2 switching, unchanged original SNR,
   recomposition and exactly one contacts/discovered/repeats read sequence.
2. Ambiguous short-prefix DM resolution at 200% Chinese font, actual advert ordering, first-byte leading
   zero ID, non-overflowing wrapping, possible-match explanation/dismissal and no extra reads.
3. Incoming unresolved/missing prefix and other-radio rejection use Unknown/You; nonzero-length
   empty-path true-unavailable suppresses both endpoint rows and Flood.
4. Outgoing time-ordered echoes use local sender and receiver identity without GPS, omit canonical
   arrival, show only selected echo SNR (including missing SNR), and retain selection/read counts
   through recomposition.

All prior methods in that file are retained. These are supplemental consumer regressions, not newly
inventoried original cases, full App/radio graph acceptance or execution evidence.

## Actual verification

| Exact command/check | Actual result |
| --- | --- |
| `git merge --no-edit origin/main` | Passed; clean fast-forward from the prior merged successor to actual main `f539e0ac5454d9099ec2d1b0e8d0f93e9a7c0e54`. |
| `git diff db14559b39d32322b06477c6ae676112f583db50 -- MC1\Views\Chats\Components\MessagePathContent.swift MC1\Views\Chats\Components\PathHopRowView.swift MC1\Views\Chats\Components\MessagePathViewModel.swift MC1\Views\Chats\Components\MessagePathDetailBlock.swift MC1Tests\ViewModels\MessagePathViewModelTests.swift` | Passed; no drift in these source/test inputs. |
| `python tools\android-port\controller\validate.py` | Passed; valid pinned manifest/source/ownership, 64 WPs, 178 edges. |
| `python tools\android-port\portmap.py` | Passed; provenance only, not behavior acceptance. |
| `python tools\android-port\controller\ci.py python --output C:\Users\camer\.copilot\session-state\d5b384f6-0bf0-4729-9241-c43c31f20094\files\wp309-endpoint-source-checks` | Passed; 15 scaffold plus 108 parity-accounting tests discovered/run/passed, zero failures/errors/skips; source validators passed. |
| `python android\scaffold\check_environment.py` | BLOCKED, exit 2: inherited non-allowlisted environment. No direct build, install or restoration. |
| `python tools\android-port\controller\ci.py preflight` | BLOCKED, exit 2: explicit state / `ANDROID_CI_STATE` required. No readiness inferred. |
| `git diff --check` | Passed before commit. |

Relevant existing Android tasks: `:feature:chats:testDebugUnitTest` and `:app:testDebugUnitTest`.
The new mounted class, incumbent `MessageActionsRepairComposeTest`, `MessagePathComposeTest` and
`MessagePathRegistrationTest` are the directly relevant App consumers. Kotlin compilation/UI tests
were not run locally: **zero new Android tests discovered/run, not a pass**. Exact-commit supported
fast-check/publication outcomes are recorded in the successor PR after commit.

The coordinator reports six unrelated historical HEAD assertions failing in WP-503's
`test_language_binding.py` on this base; that producer owns the repair. No duplicate, disabled,
repinned or bypassed test is introduced here, and those failures are not labeled a product defect.

Publication is explicitly UNVERIFIED pending actual exact-head checks and coordinator review/merge.
No native guest/probe/device, TalkBack, screenshot/golden, hardware, signing, release, performance,
runtime-installation or execution-allowance action was taken. Hosted exact-head result/log is
authoritative; no second CI-result bundle is committed, uploaded or revalidated.
