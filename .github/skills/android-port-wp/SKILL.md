---
name: android-port-wp
description: Execute or review a bounded MeshCore One Android port work package using approved ownership, source provenance, acceptance evidence and fail-closed automation.
---

# Android port work-package protocol

## Fixed product boundaries

Native Kotlin/Compose; pure JVM protocol; Android 12/API 31 minimum and Android 17/API 37 target.
The Android project sits beside the read-only Swift reference. ApplicationId/root package:
`com.meshcoreone.android`; debug adds `.debug`. Sideload APK releases only, no billing, all themes unlocked.
Mesh messaging, data and core navigation work without internet or Google Play services.
Google services may support optional features only after license/runtime/privacy approval.
Bidirectional iOS/Android backup compatibility remains a release requirement. Message translation is
removed from active scope, not deferred, implemented or required for future builds/releases. Re-admission
requires a new user feature request and scope/admission decision. Preserve original message text and
app localization. Exact `removed-translation` exclusions are not ported features or passing tests;
inert existing contracts/helpers are not WP-407 completion blockers.
Preserve GPLv3 application and MIT protocol notices; do not assume a proprietary optional SDK is compatible.

## Before execution

1. Establish whether this is planning, authorized implementation, review or coordination.
   Planning does not authorize repository/settings/issue/schedule changes or worker launches.
2. Read the approved plan, ADRs and the trusted `docs/android/port-manifest.json` entry for this WP.
   At WP-000 bootstrap only, the approved plan/drafts substitute for the not-yet-installed manifest.
3. Verify the owner, merged prerequisites, source/manifest/policy revision and assigned capabilities.
   A manually closed issue, worker summary or idle session is not a completed dependency.
4. Read all relevant source/test/resource inputs and nearby consumers. Prefer actual code over stale docs.
   Source paths in provenance are Git identifiers; resolve them to the execution host's path convention.
5. Inspect the current worktree and integrate existing in-scope edits. Work only in the assigned worktree,
   never another checkout. Do not revert unrelated work or rename an already managed branch.
6. Run the scaffold's documented environment-readiness check. Missing SDK/tool/authentication must be
   reported; a failed cloud setup step does not guarantee the cloud agent stopped.

Assignment authorizes ordinary implementation, tests, evidence, generated outputs and directly necessary
support edits inside manifest-declared capabilities. The worker/coordinator automatically acquires the
initial typed reservation and evolves the same owner's scope before a newly discovered edit when a trusted
path+operation rule maps it to an assigned capability. Never ask the user to restate permission, rewrite
authorization prose or predict exact checksum, fixture, annotation or generated-output bytes.

## Ownership and implementation

One WP, one branch, one reviewable PR. A macro WP too large for coherent inspection must be split by
an approved manifest amendment before dispatch, not implemented from partial context.
Do not autonomously nest agents or start another fleet.

A worktree-local branch may perform capability-admitted work without another permission ceremony. For
supported shared surfaces, reconcile disjoint semantic edits by an automatic transaction or deterministic
merge. Serialize or transfer actual semantic overlap to the current producer while retaining originating
WP evidence. If automatic reconciliation cannot resolve the ownership collision, report that concrete
collision once.

Unknown paths/operations and work outside assigned capabilities fail closed with one blocker naming the
WP, path, operation and missing rule. Also stop for source/policy drift, protected human gates, missing
authentication/tool capability or a substantive product decision. A newly discovered checksum, fixture,
annotation, generated output or validator mapping is not itself a stop condition.

Authorization prose is immutable audit context, not capability state. Receipts bind actual identities,
revisions, capability IDs, operations and paths; evidence binds bytes and tests. Never release/recreate a
reservation or manually edit its ledger to evolve scope. The current worktree remains authoritative.
If another owner must produce a dependency, record a bounded blocker and request that prerequisite.
Do not introduce fake implementations to bypass dependency ordering.

Every in-scope source/test/resource has one primary owner; cross-references and many-to-many Android
implementations are permitted. Source headers do not replace behavioral acceptance.

Ported production/test Kotlin files declare one or more:

`// PortedFrom: <git-relative source path>@<reference commit>`

Android-only code declares:

`// AndroidOnly: WP-xxx <purpose>`

Generated outputs identify their generator and source map rather than pretending to be handwritten ports.
Keep copyright/license notices. Preserve source IDs, raw enums, units, defaults, ordering, validation,
retry, deduplication, concurrency and failure behavior unless an approved native adaptation specifies otherwise.

Use the approved contracts and existing helpers. Expose typed failures and localized recovery at the UI;
do not use broad catches, empty substitutes, silent key/database recreation or unconditional success.
Cancellation propagates except for a narrowly documented post-commit completion rule.
Read the Swift-to-Kotlin skill and, for UI, the Compose skill.

## Verification

- Port each assigned original case/parameter family, or document a reviewed equivalent/exclusion.
  Generated filenames and line counts are not coverage.
- Run the actual module verification tasks declared by the scaffold/manifest; do not invent Gradle tasks.
- Prove the runner discovered tests and assertions. Zero tests or a skipped mandatory suite fails.
- Use pinned independent vectors/oracles, actual Room transactions/migrations and deterministic clocks.
- Do not change golden expectations or lower coverage policy to make a candidate pass.
- For UI, verify flow and state transitions plus deterministic native screen evidence.
- For platform-specific behavior, test the required API/device, not an unrelated proxy.
- Actual radio/background/upgrade and license evidence remains human-gated where declared.
- Run the trusted manifest/traceability/ownership validators. Record missing evidence as BLOCKED.

## Evidence and deviations

Record per-WP evidence and `docs/android/deviations/WP-xxx.md`, avoiding shared progress-file edits.
Include repository, WP, base/head/source revision, acceptance IDs, exact commands/test-discovery counts,
artifact locations and outcomes. Sanitize device identifiers, keys, content and credentials from logs.
Native adaptations state original behavior, Android behavior, rationale and tested consequences.
Unexpected source behavior or an unresolved parity gap is not silently marked out of scope.

Completion requires implemented behavior, real assertions, preserved consumer contracts and verified
evidence. Compile-only scaffolds may satisfy a scaffold WP, never a feature WP.

## PR and gate handling

Use title `[WP-xxx] <bounded change>` and the repository's required template, source/acceptance references,
deviations and test evidence. Link the existing issue when provided; do not invent an issue number.
Use host-native issue/PR tools where present; otherwise the supported GitHub interface.
Commit on the managed worktree branch, preserving the requested co-author attribution when applicable.
Do not claim human review, hardware verification or authorization that has not happened.

Candidate workers never receive signing/dispatch/merge credentials. Required checks and reviewer output
bind to the exact base/head/policy revision. Do not publish a privileged status or approve your own gate.
The controller repairs the same task/session with a bounded attempt counter; do not relaunch duplicates.

## Stop conditions and handoff

Stop on a protected human gate, unknown capability, absent authentication/tool capability, unresolved
semantic ownership collision, stale inputs, inaccessible relevant source, ambiguous live task identity
or unverified acceptance. Do not stop merely because capability-admitted support work discovers another
path or future byte value.
Return: WP and head, implemented scope, acceptance evidence, explicit blockers/deviations and PR identity
if one exists. "Blocked" is a valid outcome; a success-shaped fallback is not.
