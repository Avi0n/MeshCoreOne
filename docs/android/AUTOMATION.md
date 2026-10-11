# Android port automation: WP-000 bootstrap

**Installed, not activated.** WP-000 adds traceability and testable control logic, not an Android
application, production CI, reviewer execution, signing, repository settings or a fleet.
`automation-policy.json` is committed with dispatch **off**, **paused**, activation unapproved,
no trusted publisher/workflow IDs and branch rules unproven. The wrappers cannot override this.
The 64 active macro WPs require actual merged PR and acceptance evidence; the retired translation
WP-406 is neither pending implementation nor completed. Historical installation counts remain history.

## Pinned inventory and authority

The [approved plan](PORTING_PLAN.md) supplies every macro owner/dependency. The canonical
[manifest](port-manifest.json) freezes **1,866 exact tracked paths and Git blob SHAs** from
`db14559b39d32322b06477c6ae676112f583db50`. Main, origin/main and the live GitHub main matched this
pin during bootstrap. The tree is not advanced by installation.

| Inventory kind | Exact count |
| --- | ---: |
| Production Swift | 1,058 |
| Original tests / support | 428 / 40 (468 combined) |
| Generated Swift localization output | 1 |
| Apple `Package.swift` manifests | 2 |
| User-facing resources / data | 234 |
| License / acknowledgement inputs | 43 |
| Other reference / configuration / documentation inputs | 60 |
| Primary-owned / explicitly excluded files | 1,786 / 80 |
| Active macro WPs / dependency edges / explicit human gates | 64 / 178 / 7 |

The plan's approximate 1,060 production figure includes the two package manifests. The test/support
directory classification is **not** an assertion count. WP-004 inventories actual original cases and
parameter families against pinned blobs, including assertion-bearing helpers.

Every path has exactly one `primary_owner` or a matching entry in [not-ported.json](not-ported.json).
`cross_references` supports shared inputs; `implementations` and `portmap.py` support many-to-many
Kotlin mappings. App/widget strings and plurals (all twelve app/widget locales), theme/color assets,
icons, map styles, URL filtering data and all license inputs are included. Settings.bundle also contains
license translations beyond the twelve application locales; they are accounted for, not silently dropped.
Pure purchase/refund/entitlement files are excluded; mixed theme/support files remain owned so their
non-billing behavior survives. Generated Swift and Apple-only glue have explicit native adaptation WPs.
**Message translation is removed from active scope, not deferred or required for future builds/releases.**
The 26 exact `removed-translation` sources/tests/helper retain original blob identities and historical
case/parameter-family inventory; WP-000 accounts for this user-approved exclusion, not a feature port.
Mixed chats/rooms/settings/localization/backup/rendering remain owned. A new user feature request and
scope/admission decision are required to add it again; no provider/model/placeholder is required.

`bootstrap.py` is a one-pin bulk installer/expander, not an upstream resync heuristic. Its expansion
rules are reviewed bootstrap inputs; the runtime reads only frozen exact paths, never broad ownership
globs. Unexpected upstream additions, missing paths, duplicate/unknown owners and source/hash drift block.
`verification_config.py --check` compares complete generated structures through the exact protected
overlays with canonical JSON and fails on drift; `bootstrap.py` checks the raw expansion, not the
installed overlays. Neither check overwrites files or merely compares counts.
Do not rerun installation with a different plan/pin. A changed macro/child WP or source revision requires
a protected, human-reviewed amendment. Preserve the GPLv3 app and MIT MeshCore licenses.

## Local, dependency-free verification

Python 3.12 and Git are sufficient. No pip packages, JDK, Android SDK, Gradle or physical radios are
needed for these bootstrap checks. From the worktree on Windows:

```text
python .\tools\android-port\controller\validate.py
python .\tools\android-port\controller\test_runner.py
python .\tools\android-port\controller\dispatch.py --dry-run status
python .\tools\android-port\controller\dispatch.py files --wp WP-203 --primary-only
python .\tools\android-port\controller\dispatch.py render --wp WP-001
python .\tools\android-port\controller\dispatch.py --dry-run sync-issues --wp WP-001
python .\tools\android-port\controller\dispatch.py --dry-run cloud --wp WP-101
python .\tools\android-port\controller\dispatch.py --dry-run local --wp WP-101
python .\tools\android-port\controller\dispatch.py --dry-run claim --wp WP-101
python .\tools\android-port\controller\dispatch.py --dry-run release --wp WP-101
python .\tools\android-port\controller\dispatch.py report
python .\tools\android-port\portmap.py
```

Linux uses the equivalent platform path separators. `status/files/render/report` are read-only.
This Python-only tooling runs natively on Windows; it has no JDK/Android SDK/Gradle dependency.
`android-ci.yml` only verifies a Linux (`ubuntu-24.04`) build. Once the Android module has real
Gradle builds to run, do that work from WSL (or another Linux environment) rather than native
Windows, since native-Windows Gradle/AGP behavior is not covered by CI.
Every mutating command defaults to dry-run: no network, ledger writes, backlog issues, workers or merge.
Use global `--live` only after separately approved setup; missing capability is a nonzero `BLOCKED`,
never a simulated successful launch. All 64 active handoffs fit the 65,536-character issue-body bound.
Wide read-only inputs (notably WP-501) reference the complete manifest/scope digest and deterministic
`files --wp WP-501 --page N --page-size 100 --expected-manifest-sha <digest>
--expected-source-sha <commit>` retrieval. Every page repeats the pin/scope/selection hashes, total count
and next page. Consume all pages and check unique accumulated paths/counts; pin drift blocks.
`--kind` and `--primary-only` are inspection filters, never coverage exclusions. No input or acceptance is
silently truncated and no new WP/DAG edit is needed just to transport a wide audit list. Oversized
implementation scope or metadata itself still needs human review. `files` never launches a worker.

`report` without artifacts reports **traceability only**, with zero verified feature completions.
An offline report evaluation requires `--wp --binding --pr --evidence --review --approvals`
(and `--catalog` for original tests); it never records completion or grants authority.
Malformed/missing/zero-test evidence fails. A real live report additionally needs an existing
leased task/session/PR, a completed worker and authenticated trusted publisher facts.

No Android CI or Gradle tasks are invented here. Except for WP-000's actual Python commands,
verification entries are explicitly unconfigured and block automatic launch/acceptance until the
supervised build/CI/test WPs supply real commands. UI/service/protocol placeholders never count as parity.
The strict stdlib runner rejects a missing directory, zero discovered tests, errors/failures and skipped
mandatory tests; raw unittest discovery alone would otherwise exit successfully with zero tests.

### Explicit WP-000 scope lineage

`scope-amendments/translation-removal.json` is the immutable, reversible catalog delta for the
user-approved removal. It binds the preceding generated/final digests, new generated/overlay/final
digests and current semantic policy revision, along with exact before/after reference metadata,
profile identities, work-package entries and inventory entries. Full preimage/postimage checks reject
changes outside this amendment, including partial deltas, altered blobs, gates, unrelated acceptance,
ownership or capability paths. Existing verification/content/policy projections still validate their
complete historical catalog; historical evidence is not refreshed as current acceptance. Current
revision helpers return the new candidate identity, never old successful acceptance hashes.

`test_inventory.py --check` continues reproducing the historical WP-004 declaration/case-family
inventory (including excluded translation cases) from pinned source. The current manifest and
`not-ported.json` give those exact files their current scope disposition; retained inventory details
are history, not a current feature-success record. WP-501 must account for both, never silently drop tests.
Upstream comparison retains changed translation exclusions as explicit review records, not automatic
re-port proposals or new translation workers. Changes to upstream text or old headers cannot re-admit
the removed feature without a new user request and scope/admission decision.

## Controller interfaces and persisted state

`controller/engine.py` contains actual decisions and injected backend/authority boundaries;
`backends.py` implements the documented GitHub REST adapter and an explicit `NativeHost` callback adapter.
Tests use fake APIs/callbacks, not actual assignments, sessions, comments or merges.

Execution states are `pending`, `leased`, `dispatching`, `running`, `repair_wait`, `review_wait`,
`gate_wait`, `merge_wait`, `completed`, `blocked` and `uncertain`. Inactivity/draft changes/closed issues
are not completion. Only a real completed task can become ready, and only a real merged implementation
PR with verified evidence can satisfy a dependency. A terminal failed/cancelled task is not a port.

`ANDROID_PORT_LEDGER` names one absolute, access-controlled, **durable shared** SQLite file outside
tracked source. Its parent must already exist. Both native-local and cloud adapters must use the same
ledger/service; separate per-worktree or ephemeral Actions databases are forbidden for live dispatch.
Transactions (`BEGIN IMMEDIATE`) serialize claims, concurrency/budget reservations, repairs, issue
creation intent and the repository-wide merge lane. Hosted runners cannot claim durability merely
by creating a local SQLite file; WP-003 must prove the shared host/service integration.

ADR-006 supersedes whole-path leases as edit permission. Assignment authorizes ordinary implementation,
tests, evidence, generated outputs and directly necessary support edits inside manifest capabilities.
Before editing, the worker/coordinator automatically records the initial typed reservation or evolves
the same owner's reservation when a trusted path+operation rule admits a discovered support path.
Authorization prose is immutable audit context and is not rewritten for checksums, fixtures, annotations,
generated outputs or validator mappings. Unknown capabilities fail closed with one actionable blocker.

Typed shared capabilities cover dependency catalogs/locks/checksums, App build/launcher support,
traceability validators, generated resources, schemas and workflows. Their rules define allowed
operations, invariants and required validation, not future byte tuples. Disjoint semantic edits reconcile
through an automatic transaction or deterministic merge; real overlap serializes or transfers to the
current producer. Only an unresolved semantic ownership collision, drift, protected/human gate, missing
authentication/tool capability or substantive product decision escalates.

Each attempt persists repository, WP, backend, base/source/manifest/policy binding, capability IDs,
operations, actual normalized paths, expiry/budget reservation, bounded repair count and
issue/task/session/PR identities. Dispatch intent is committed **before** an external mutation. Expiry
never forgets a possibly running worker. Unknown/duplicate/conflicting/missing identities or interrupted
mutations retain state and block duplicate dispatch; reconcile authoritative API/host results or report
the one actionable blocker. An independently reconciled existing worker is reused, not relaunched.
Pause never kills workers.

The currently installed helper still models static whole-WP claim/release. WP-003 must add the versioned
capability state and idempotent compare-and-swap migration defined by ADR-006 while preserving historical
authorization bytes and all actual identities/bindings without release/recreate or manual SQLite edits.

Repairs target the same actual task/PR or native session, at most three rounds per attempt; requests
reserve usage before delivery. A pending repair is not ready for another repair. Current-base repair
rebinding invalidates old acceptance/review; source/manifest/policy drift requires review, not silent
rebinding. Delivery receipt is not repair completion. `needs-human` blocks further automated progress.

## Cloud assignment and authentication

Reverified first-party documentation:

- [Issue assignment API](https://docs.github.com/en/copilot/how-tos/use-copilot-agents/cloud-agent/use-cloud-agent-via-the-api)
- [Custom-agent schema](https://docs.github.com/en/copilot/reference/custom-agents-configuration)
- [Task reconciliation and usage](https://docs.github.com/en/rest/agent-tasks/agent-tasks)
- [Enterprise-only agent catalog](https://docs.github.com/en/rest/copilot/copilot-custom-agents)

For an **existing** canonical WP issue, cloud launch uses:

```json
{
  "assignees": ["copilot-swe-agent[bot]"],
  "agent_assignment": {
    "target_repo": "cbattlegear/MeshCoreOne-Android",
    "base_branch": "main",
    "custom_instructions": "complete controller-rendered bounded WP prompt",
    "custom_agent": "approved owner filename stem"
  }
}
```

This is `POST /repos/{owner}/{repo}/issues/{number}/assignees`, with the issues API version
`2022-11-28`. The model field is omitted to inherit defaults. An assignment acceptance only enters
`dispatching` with **task unconfirmed**; the controller never fabricates a launched task ID.
The newer task API is used read-only for reconciliation/usage (`2026-03-10`), not as an alternate
creation route. Multiple matching tasks/PRs or an unrecognized custom-agent ID block inspection.
No enterprise-agent catalog endpoint is invented for this personal repository.

Preflight verifies `/user`, configured login/type, repository/write availability, exact current base,
`suggestedActors(CAN_BE_ASSIGNED)` and the exact approved profile content at that immutable base.
The [documented identifier is the profile filename stem](https://docs.github.com/en/rest/agent-tasks/agent-tasks).
Unsupported profile tools are ignored by GitHub, not new capabilities; native app tools in the
orchestrator draft are only usable in a host that actually exposes them.

User-to-server authentication is mandatory: PAT, OAuth user token or GitHub App **user** access token.
Installation/GITHUB_TOKEN credentials are not substitutes. Fine-grained issue assignment requires
metadata read plus actions/contents/issues/pull-requests read/write; task reconciliation/usage additionally
needs Agent tasks read. GET preflights cannot prove every write scope without mutation; actual 401/403/404
responses fail closed and an uncertain mutation is never blindly retried. No token is committed/logged.

The bootstrap's read-only assignability probe returned HTTP404. That is a documented account/repository
capability blocker; no trial assignment or new issue was used to investigate it. `sync-issues` is separate,
requires explicit live confirmation and deduplicates canonical markers/creation receipts.

## Native local-host boundary

Python/GitHub Actions **cannot** call Copilot app-native `create_session`. The local preview emits its
supported request: worktree, `kickoff.agent`, rendered prompt and notifications, without a model override
or an inferred branch/project. A separately authenticated host injects `NativeHost` callbacks for:

| Callback | Required proof |
| --- | --- |
| `capabilities()` | Bound repository/base, approved profile hashes, authenticated isolated worktrees, shared ledger, path/reference and credential isolation |
| `reconcile(wp, identity)` | Authoritative actual task/session/PR identity and execution result; idle alone is not completion |
| `create_session(request)` / `get_session(id)` | Real host-native receipt, repository/WP/attempt and actual state, never a fake successful subprocess |
| `send_session_message(id, feedback)` | Delivery to the existing session, not another worker |
| `usage(budget, now)` | Complete, fresh supported usage with all active identities |

The command-line `local --live` is intentionally **BLOCKED** without this host adapter. The Python
engine can be embedded by a trusted host; fake callback tests prove the interface and decision logic.
An unrestricted headless `copilot --allow-all` subprocess is not an implemented or approved substitute.
WP-003 must prove native-host integration/tool/path boundaries, receipt/restart recovery and authentication.

## Limits, pause and portable environment

No guessed AI ceiling or concurrent fleet size is supplied. Live dispatch/repair/merge requires:

| Interface | Meaning |
| --- | --- |
| Trusted `automation-policy.json` | Separately approved activation/mode, unpause, proven foundation/branch rules and exact publisher/workflow identities |
| `ANDROID_PORT_DISPATCH_MODE` | `off` by default; approved `cloud` or `local` only |
| `ANDROID_PORT_PAUSED` | `true` by default; environment cannot override a paused trusted policy |
| `ANDROID_PORT_MAX_INFLIGHT` | Explicit positive user-selected concurrency; all active backend identities are counted |
| `ANDROID_PORT_USAGE_POLICY_FILE` | User-chosen JSON usage unit/limit/reservation/window; absent, malformed, unsupported, stale, incomplete or exhausted measurements block |
| `ANDROID_PORT_LEDGER` | Absolute durable shared ledger, not runner-local cache |
| `ANDROID_PORT_AUTH_KIND` | `personal_access_token`, `oauth_user` or `github_app_user` for cloud |
| `ANDROID_PORT_USER_LOGIN` / `ANDROID_PORT_GITHUB_TOKEN` | Explicit verified user credential; never ambient GITHUB_TOKEN or candidate secrets |
| `ANDROID_PORT_LEASE_SECONDS` | Positive lease duration (default 1800s); expiry holds uncertain locks rather than terminating workers |

Usage-policy format is `{"unit":"ai_credits" or "premium_requests","limit":<positive user limit>,
"reserve_per_launch":<positive user reservation>,"window_start":<RFC3339 timestamp>}`.
Cloud reads actual session usage, including archived tasks and conservative active/period history.
Missing usage for even one relevant session blocks; native usage must be equivalently complete.
Session timestamps, not a task's original creation date, determine the window: a recent repair on an
old task is still charged. Missing terminal timing is conservatively included, never silently zeroed.
Meters explicitly declare `covered_backends`. After a backend switch, any recorded cloud/local history
requires an authenticated aggregate covering both; a cloud-only meter cannot claim to know native spend.
Reservations are conservative, post-paid **soft** limits, not a guarantee a running execution cannot
overspend its reservation. Configure an independently enforced billing ceiling if required; do not
misrepresent a launch counter as credit accounting. Pause blocks new launches, repairs and merges
without cancelling active work.

Later build/setup/readiness consumes explicit portable `JAVA_HOME` and Android SDK environment values.
No private coordinator path or binary is embedded here. Stable API37 SDK IDs have minor versions
(`platforms;android-37.0`, `37.1`, `37.2`); the newer Google repository2-3.xml exposes them while the
older repository2-1.xml omits them. Do not assume `platforms;android-37` exists. WP-001/002 must pin a
real stable package and prove the compatible AGP compile-SDK DSL on Windows/Linux. Modern command-line
tools use `android.exe`; an old/deprecated sdkmanager wrapper or JDK11 on PATH is not readiness evidence.
WP-000 does not globally install/change tools or claim an Android build.

`copilot-setup-steps.yml` is deliberately deferred to WP-003 with the proven scaffold. Use the
customize-cloud-agent skill, the mandatory single `copilot-setup-steps` job and supported properties
only; install verified JDK/SDK/Python on supported Ubuntu x64, then prove readiness explicitly since
setup failure alone need not stop Copilot. Never run candidate Gradle code with privileged tokens.
App-specific config is not needed here; any later app config uses `.github/github-app.yml` and its
official schema, not invented repository settings.

## Gates, trusted evidence and personal-repository approval

`gates.py` validates exact repository/WP/base/head/source/manifest/policy identities, known acceptance
IDs/artifacts/checksums, real run IDs/attempts/publishers/workflows and positive discovered/passed tests.
Skipped checks/tests, missing/malformed evidence, original-case gaps and stale review/approval fail.
`policy_revision` hashes the trusted gate semantics **and** manifest/source, binding the unchanged reviewer
schema to all of them. A push/rebase/current-base change invalidates previous evidence.
Operational pause/mode/activation switches are checked independently for every side effect and excluded
from the review-semantic digest, so unpausing does not invalidate already accepted evidence. Actual
gate/owner/acceptance/publisher/workflow changes still require bound revalidation, never silent migration.

The reviewer emits only the strict JSON schema in its unchanged read/search-only profile.
It cannot execute tests or publish its own check. The separately authenticated publisher stages
immutable reference/candidate/diff/evidence and validates the result. PR comments/instructions and
candidate artifacts are untrusted data, never scripts or approval.

`GitHubGateAuthority` reads real PR/files (including rename old paths), checks and Actions run identities.
It accepts structured `<!-- android-port-gate-bundle-v1 -->` output **only** from the separately
configured publisher app. The bundle contains schema1, binding, evidence, review, approvals and the
independently approved original-case catalog. Candidate JSON/PR prose is not that authority.
Candidate android-ci runs must actually target the candidate SHA; privileged review/gate runners
must execute the trusted base SHA on workflow_dispatch, not candidate code via pull_request_target/
workflow_run. The publisher architecture, artifacts/catalog provenance, token scopes and runner proof
are mandatory WP-003 integration acceptance, not bootstrap claims.

Human approval is required for WPs000/001/002/003/006/406/505/506, any protected path and `needs-human`.
Protected policy/build/catalog/manifest/container/schema/signing/oracle paths have no worker exceptions.
CODEOWNERS routes review; it does not itself configure branch protection. Ordinary product WPs with
unprotected changes can pass without a human once all independent gates are actually proven.
The normal approval path verifies an actual current-head APPROVED maintainer review whose body
contains `<!-- android-port-approval:<Binding.key> -->`; the publisher binds that explicit stamp
to current base/policy as well. A stale stamp, self-review, missing reviewer or model-written record fails.

**Known personal-repo blocker:** the sole configured maintainer is `cbattlegear`. A native PR published
as that same user cannot get an independent self-PR review. This bootstrap does not invent a human
attestation or weaken that condition. WP-003 must establish an actually authorized alternate
maintainer/reviewer or publisher identity, or separately design/prove a trusted interactive human
attestation mechanism in a protected amendment. Until then these local/self-authored automated gates
remain BLOCKED. The current WP-000 PR is for human inspection/manual disposition, not controller merge.

This public personal repository has `allow_auto_merge=false`. No native organization merge queue is
assumed. The default engine obtains a durable serialized merge lane, rechecks exact head/current base,
and uses REST merge with a head guard, never admin bypass. Because REST has no base CAS, it additionally
verifies live **strict current-base required-check** branch protection and enforce-admins before mutation.
A concurrent base advance is rejected by those rules, not waved through. Missing rules or a server
failure/unconfirmed merge retains uncertainty; no fake merge receipt or automatic blind retry.
Required checks are `android-ci`, `parity-review`, `gate-integrity` on every PR/merge-group candidate,
including infrastructure changes. WP-003 must prove them before activation.

## Workflow trust and supervised handoff

The path-scoped `controller` job in `android-ci.yml` runs the Python
controller/traceability tests once on an ephemeral `ubuntu-24.04`
PR/merge-group runner with read-only permissions and no persisted checkout
credentials or dispatch/signing/merge secrets.
The four controller wrappers are manual-only, default-branch-only, pinned-action, read-only **dry-run**
previews/audits. They have no schedule, cloud/local launches, required-gate publishing or merge authority.
A successful preview job is not a parity/gate verdict. Protected offline audits without real approval block.
Inherited iOS workflows are left unchanged; unavailable custom macOS runners are not proof of iOS tests.

After reviewing/merging WP-000, supervise WP-001 contracts, WP-002 scaffold and WP-003 gate integration.
Prove shared-ledger durability, native-host capabilities, cloud availability/auth, full usage measurement,
actual positive test discovery, isolated publisher/reviewer, current-head/base human approval, exact
required checks and serialized no-bypass merges. Revalidate any historical bootstrap receipt against
approved policy changes rather than silently importing closed issues.
`report --live --wp WP-000 --pr-number <real merged PR> --import-supervised` is the explicit initial
foundation-receipt interface: no worker launch, no ambient credential, no candidate JSON authority.
It requires a configured authenticated publisher and actual merged PR/CI/review/human evidence,
only accepts WPs000-003, and cannot overwrite an existing live attempt or conflicting completion.
This breaks the startup-ledger circularity without pretending the manual bootstrap was auto-dispatched.

Only then may the human select limits/credentials, configure settings/protection and **separately**
approve activation/unpause. No backlog sync, schedules, upstream monitoring, Android product work,
hardware certification, signing key or release is authorized by this bootstrap.
