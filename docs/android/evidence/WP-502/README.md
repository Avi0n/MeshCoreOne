# WP-502 execution-readiness handoff

**Status: implementation in progress; full WP-502 acceptance remains BLOCKED.**

The initial readiness handoff below records the Windows failure and missing
WP-specific CI admission. Subsequent user direction resumed implementation using
the installed Linux runner, whose existing pre-push checks passed at
`3fce7297eaf47b97ccf9f5ff9359e1699ac1ab01`. Owned production-content Compose
cases and test-only app source-directory wiring are now being validated.
Do not treat this initial handoff or a JVM rendering result as device acceptance.

Issue: [#135](https://github.com/cbattlegear/MeshCoreOne-Android/issues/135).
Owner: `test-parity-engineer`; dedicated project session
`3bc8c1b8-20ec-4647-88ae-db3775268489`, CLI session
`fb112832-0a71-4f6d-abc0-f07f80eaeb8b`.
Branch: `cbattlegear-issue-135-wp-502-compose-flows-and-deterministic-534de7`.
Repository: `cbattlegear/MeshCoreOne-Android`.

## Input and reservation identity

- Inspected base: `d958471ab4835813881a5e7af36e8249a8b273a4`.
- Read-only Swift reference: `db14559b39d32322b06477c6ae676112f583db50`.
- Manifest SHA-256: `39bd73ab7759d7f65f7ecc681cd472e4763d8c8d3e9f5973bc0c515323611dda`.
- Policy revision: `3fc0afa19ad31f0210a5d427f655ef90b50a8e64e818fe3420b188303b99fb20`.
- Typed reservation acquired through `tools\android-port\local\reserve.py claim`
  and evolved through `evolve`, without release/recreate or changing authorization.
  Revision 2 admits `create`, `modify`, and `record-evidence` on WP-owned paths.
  The shared ledger, not a copied receipt, remains authoritative.

WP-502 depends only on WP-407. Git ancestry confirmed that
`250dd50ed259c5d9fe1006d4f45a637e4aea0aff` is contained in this base.
GitHub reports [PR #164](https://github.com/cbattlegear/MeshCoreOne-Android/pull/164)
merged at `2026-10-11T16:22:30Z` with that merge commit.
This verifies the prerequisite implementation merge, not independent device or
parity acceptance. WP-501 is not an added dependency.

## Observed readiness

On 2026-10-12, the documented credential-stripped launcher was attempted with the
installed JDK, the default local Android SDK, and explicit private cache locations:

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $PWD '.local\wp502\gradle'
$env:ANDROID_USER_HOME = Join-Path $PWD '.local\wp502\android'
& .\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -GradleArguments @(':app:testDebugUnitTest', '--dependency-verification', 'strict')
```

**Result: blocked at preflight; Gradle did not start and no tests were discovered
or executed.** `platforms\android-37.2\package.xml` is absent. The installed
platform directory is `android-37.0`; it was not substituted for the pinned
compile platform. No SDK provisioning or license acceptance was performed.
The earlier direct `check_environment.py` call rejected the unsanitized agent
environment; the launcher removed those variables before producing this more
specific SDK failure.

Installed `platform-tools\adb.exe devices -l` reported no attached devices.
Installed `emulator\emulator.exe -list-avds` reported `Medium_Phone` and
`Pixel_10_Pro_XL`; system-image directories exist for API 31, 37.0, and 37.2.
Names/directories alone do not establish a bootable device, its actual API,
GMS absence, permissions, or executed assertions. No emulator was launched.

`python tools\android-port\controller\validate.py` returned `valid` for the frozen
manifest/reference inventory. This is an ownership/provenance check, not UI,
device, screenshot, or feature-parity evidence. Reproducible checks belong in
the exact-candidate hosted result/log, not a second checked-in CI-result bundle.

## Required coordinator admission

The WP-502 manifest entry has `verification.configured = false` and no commands.
The installed trusted `ci.py` scope table admits only `scaffold`, `protocol`,
`backup`, and `external-oracle`; it supplies no WP-502 UI/device/screenshot scope.
Neither `android\ui-tests\` nor `android\screenshot-tests\` is wired into the
current build. An existing app unit-test task does not authorize inventing a
device task or crediting missing WP-502 tests.

The coordinator needs to arrange trusted WP-502 verification admission and
source-set/module wiring, serialized with the current shared build producer.
The observed WP-501 reservation includes `android\app\build.gradle.kts` and
controller/workflow verification surfaces. This worker has not changed those
files, its parity tests, source accounting, or execution binding. A missing
command is a tooling prerequisite, not a request to rewrite user authorization.
Provisioning the pinned platform and actually available required API devices
is also necessary before execution.

Cross-session messaging tools were unavailable to this worker. The actionable
handoff is recorded on
[#135](https://github.com/cbattlegear/MeshCoreOne-Android/issues/135#issuecomment-6125779339)
for coordinator session `bcb17a74-5fa6-47d0-a4be-b6b595e20559`.

## Acceptance remaining

| Acceptance | Required evidence, all still unverified |
| --- | --- |
| WP-502-behavior | Real production Compose flows with deterministic fake-radio/service inputs and local media/map fixtures; screenshots for all ten themes and their actual effective schemes, both normal and 200% font, and current compact/medium/expanded windows. |
| WP-502-boundaries | Actual API 31/37 device flows and targeted API 33/34/36 behavior; resize/fold and retained draft/detail/focus; keyboard/Back/insets; supported locales, long/CJK/RTL text; denied/revoked permissions, cached offline content, loading/empty/failure/retry states. |
| WP-502-source-test-parity | Read and map exact original assertion/parameter families and resources, distinguishing new Android flow assertions from existing prerequisite tests and user-approved exclusions. |

The future matrix must record pair coverage and state coverage, rather than
claiming an arbitrary sample is pairwise or expanding a full Cartesian product.
Existing WP-301/302 render tests and WP-407 instrumentation are relevant prior
art, not newly executed WP-502 evidence. Screenshots alone cannot prove send,
retry, persistence, connection lifecycle, or permission isolation.

No device results, physical-radio evidence, or reviewed golden baselines are
delivered by this initial handoff. All themes remain unlocked, original message text/localization
remain in scope, and message translation remains excluded.
