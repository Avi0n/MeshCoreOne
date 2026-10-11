# MeshCore One Android port

The approved scope and WP owners/dependencies are in `docs/android/PORTING_PLAN.md` and
`docs/android/port-manifest.json`. Use `.github/skills/android-port-wp/SKILL.md` and the
Swift/Kotlin and Compose skills as applicable. Installation is not fleet activation.

- Implement one authorized WP in one dedicated worktree/branch/PR. Do not launch additional
  agents, factories or sessions without separate authorization. Models inherit user defaults.
- Swift, tests, user-facing inputs and licenses remain the read-only specification at
  `db14559b39d32322b06477c6ae676112f583db50`. Report drift; never silently advance this pin.
- The later project is native Kotlin/Material 3 Compose under `android/`, with pure JVM
  protocol, minSdk31, target/compileSdk37, root package/applicationId `com.meshcoreone.android`
  and debug suffix `.debug`. WP-000 creates no Android/Gradle product code.
- Preserve GPLv3 app and MIT MeshCore notices and dependency/model/data/asset obligations.
  Google services are optional, not a license exception or prerequisite for mesh messaging,
  persistence or core navigation. Message translation is removed from active scope, not deferred or
  required for future builds/releases; adding it needs a new user request and scope/admission decision.
  Preserve original message text and app localization; never add translation providers/models implicitly.
- All themes are unlocked. No billing, new account/backend, analytics, updater or APK-installation
  behavior. Distribution is sideload APKs through GitHub Releases only at WP-506.
- Backups require real bidirectional iOS/Android codec/restore evidence: envelope v1, Unix seconds,
  Codable bytes/UUIDs, zlib, 50MiB compressed/512MiB expanded. Do not manufacture compatibility.
- Process database/preferences survive connections; radio services are per connection.
  WP-207 implements factory-based lifecycle; WP-303 assembles the graph after actual prerequisites.
- A WP/session assignment authorizes ordinary implementation, tests, evidence, generated outputs and
  directly necessary support edits inside manifest-declared capabilities. The worker/coordinator
  automatically acquires and evolves typed reservations; never ask the user to rewrite authorization
  prose for a path, checksum, fixture or generated output.
- Admit a discovered support path only through a trusted path+operation capability rule and enforce its
  invariants/tests. Reconcile disjoint shared edits automatically; serialize or transfer real semantic
  overlap to the current producer. Unknown capabilities fail closed with one actionable blocker.
- Authorization prose is immutable audit context. Receipts bind identities/revisions/capability IDs/paths;
  implementation evidence binds bytes and tests. Escalate only unresolved ownership collisions, drift,
  protected/human gates, missing authentication/tool capability or substantive product decisions.
- File ownership/provenance is traceability, not parity. Port original cases/parameter families,
  typed errors, cancellation and consumer behavior; no silent failure, dropped data or fake success.
- Use only actual declared verification commands; fail on missing/malformed/zero-test evidence.
  Never claim iOS, Android, hardware, license or signing verification that did not occur.
- The exact-commit hosted job result and log are authoritative for reproducible automated checks; do not
  commit, upload, hash, or revalidate a second CI-result bundle. Retain only non-reproducible hardware,
  device, signing, release, human, or legal evidence.
- Feature-branch publication is processor-neutral. A missing or failed local fast check records an
  explicit unverified candidate but does not block push. Merge verification is change-scoped and
  exact-candidate bound; full repository verification is reserved for broad/unclassifiable Android,
  dependency/toolchain/build-graph, release, or maintainer-requested changes.
- Candidate CI has read-only tokens, ephemeral runners, no persisted checkout credentials and no
  dispatch/merge/signing secrets. Trusted controllers execute default-branch code, not PR scripts.
- Reviewer profile/skills/policy are staged from the trusted base. `parity-reviewer` has only
  read/search tools; it cannot execute, edit, access the network, post checks, merge or approve gates.
- Pause, missing budgets/authentication, unknown execution identities, stale SHAs and human/protected
  gates block their affected live or merge actions. Closed issues and idle sessions never unlock dependencies.

Use `[WP-xxx] <bounded title>` and the existing PR template. Do not create an unrelated issue
for `Closes`. Keep deviations/evidence per WP. Bootstrap and WPs001-003 are supervised; stop for
review rather than enabling schedules, changing repository settings or starting the next WP.
