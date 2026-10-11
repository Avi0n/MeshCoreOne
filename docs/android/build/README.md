# WP-002 native Android scaffold

**Supervised dependent draft, not accepted architecture or a completed port.**
The parent is `cbattlegear-android-architecture-contracts` at
`1214b5cf009705232907790d49d966823e7f410a`. WP-000/001 remain unmerged;
dispatch, activation and protected/human gates are unchanged.

## Inputs and setup

[`android/`](../../../android/settings.gradle.kts) is a separate native
Kotlin/Material 3 project beside the read-only Swift tree. The exact candidate is
JDK **21.0.12.1**, Gradle **9.8.0**, AGP **9.4.1**, Kotlin plugins/compiler
**2.3.20**, Compose BOM **2026.03.01**, Room **2.8.5** and KSP **2.3.12**.
Java/Kotlin bytecode is **17**; compile platform is **37.2** (major37/minor2),
minSdk **31**, targetSdk **37**, build-tools **37.0.0**. The application is
`com.meshcoreone.android`; debug adds `.debug`.

Provide licensed installations, not a committed `local.properties`, SDK, JDK,
keystore or private path. SDK IDs are `platforms;android-37.2` revision1 and
`build-tools;37.0.0`; do not substitute the assumed `platforms;android-37`.
Command-line tools23 use the Android CLI with `--no-metrics`. Provisioning and
license acceptance are human/environment responsibilities, not done by this scaffold.

In a fresh PowerShell process at the repository root:

```powershell
$env:JAVA_HOME = '<installed Temurin 21.0.12.1 directory>'
$env:ANDROID_HOME = '<licensed SDK directory>'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = '<private per-session Gradle cache>'
$env:ANDROID_USER_HOME = '<private per-session Android user directory>'

& .\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 768m `
  -GradleArguments @(':app:assembleDebug', '--dependency-verification', 'strict')

& .\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 640m `
  -GradleArguments @('verifyScaffoldTests', 'verifyRoomSchema', 'validateModuleGraph', `
    'runtimeDependencyInventory', 'resolveScaffoldDependencies', `
    '--dependency-verification', 'strict')

& .\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 768m `
  -GradleArguments @('lintScaffold', '--dependency-verification', 'strict')

python .\android\scaffold\inspect_apk.py
python .\android\scaffold\sync_notices.py
python -m unittest discover -s .\android\scaffold -p test_*.py -v
```

The launcher allowlists environment names before any candidate Gradle execution.
GH/Copilot credentials, arbitrary service keys, agent sockets and unrecognized
variables are not inherited. The Python preflight independently rejects them,
missing private-cache declarations and mismatched JDK/SDK inputs.

`-ConstrainedMemory` is **only a local build-process option**, not a product JVM
setting or a hosted-CI budget. It uses one worker, in-process Kotlin, SerialGC,
two active processors, bounded startup heap, 512MiB build metaspace, 96MiB build
code cache and 256MiB test heap/metaspace. The shared Windows host required
separate assembly, test/graph and lint invocations. A full `checkScaffold` aggregate
exists for sufficiently provisioned hosts; it does not turn failures into skips.

The Unix wrapper is tracked executable and both wrappers check the binary
distribution SHA-256:
`bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c`.
The generated wrapper JAR matches the publisher checksum
`238e777fcddd7e34f9708186085def2abd6e08e658505b38718d79d74c21abd5`.
**Linux execution has not occurred**; WP-003 must provide hosted cross-host evidence.

## Modules and deliberately incomplete behavior

The [module contract](../architecture/modules.md) is the parent draft specification.
There are 29 product/dev shells plus an isolated `:scaffold:room-verification`
module. Protocol, model, contracts, runtime, services and dev meshcli use JVM
conventions. Platform/resource/UI/store shells use Android conventions only where
appropriate. Runtime and services depend on neutral contracts, not each other.

The app registers `onboarding.root`, `chats.root`, `nodes.root`, `remotenodes.root`,
`map.root`, `tools.root`, `settings.root`; only chats/nodes/map/tools/settings are
tabs, preserving source indices0-4. Setup and remote nodes are auxiliary routes.
All entries show localized **Not yet ported**, immutable incomplete semantics
and disabled capability actions. Navigation/Back, current-width bar/rail selection,
resize and activity recreation are scaffold behavior, not WP-302 parity.
No radio, repository graph, permission request, send/login/translation success,
contact/message fixture or map provider is constructed.

Scaffold appearance uses the incumbent blue and system light/dark Material roles,
not dynamic color or a rebrand. Incumbent icon PNGs are copied verbatim from the
reference pin into native adaptive/themed-icon resources. Only shared English
scaffold resources exist: twelve-language conversion, ten unlocked themes and
full typography/identity/accessibility parity remain WP-005/301 and later work.
There is no billing, account/backend, analytics, updater, GMS or translation SDK.
The historical empty `platform:translation` shell has no provider, models, runtime or navigation entry.
Message translation is user-excluded, not deferred or required for any future build/release. Retained
inert contracts/helpers are not WP-407 completion blockers and must not become registered placeholders.
Automatic cloud/device data extraction is explicitly excluded; this is **not**
the bidirectional backup codec/restore implementation.

`core:database` only configures Room; it contains no pretend production database.
The unpackaged fixture generates its single three-column/partitioned-key schema
with Room/KSP and tests real upsert/Flow isolation and transaction rollback on
simulated SDK31. Its schema and assertions are not migrations, DTO/persistence
parity, an iOS oracle or real-device evidence.

## Implemented task/output contract

| Task | Actual checks/output |
| --- | --- |
| `:app:assembleDebug` | `android\app\build\outputs\apk\debug\app-debug.apk`; local debug signing only |
| `validateModuleGraph` | Actual production configurations, inherited/generated inputs, cycles, forbidden project edges, plugins/sources/resolved artifacts leaking Android into JVM; `build\reports\scaffold\module-graph.tsv` |
| `verifyScaffoldTests` | Four nonzero, unskipped XML suites; discovered case counts must match actual testcase/outcome nodes; `build\reports\scaffold\test-discovery.tsv` |
| `verifyRoomSchema` | Real Room/KSP export shape, fixture fields and partitioned primary key |
| `runtimeDependencyInventory` | Exact resolved runtime component/POM license inputs and POM hashes; `build\reports\scaffold\runtime-dependencies.tsv`; not legal approval/SBOM |
| `resolveScaffoldDependencies` | Resolve component graphs without guessing AGP secondary artifact views; unresolved components fail |
| `lintScaffold` | All 24 Android `lintDebug` targets, including fixture/dev shells |
| `checkScaffold` | Aggregate graph, discovery, schema, runtime-input and lint tasks; not a gate/receipt |

Required suite tasks are `build-logic :convention:test`, `:core:contracts:test`,
`:app:testDebugUnitTest`, `:scaffold:room-verification:testDebugUnitTest`.
JVM assertions use JUnit5 aligned with the pinned Kotlin POM; Android assertions
use JUnit4/Robolectric4.17. The exact SDK31 artifact
`org.robolectric:android-all-instrumented:12-robolectric-7732740-i7` is separately
Gradle-locked/checksummed and loaded offline from the declared private cache;
it is neither a production dependency nor a proxy for real API37 instrumentation.

## Dependency and notice maintenance

Module/buildscript locks live under `android\gradle\dependency-locks`; the included
convention build also tracks its settings/project locks and verification metadata.
Strict lock mode and SHA-256 metadata are enabled. Only explicitly reviewed pin
changes should run `--write-locks --write-verification-metadata sha256`, exercising
assembly, required suites and **lint itself** so late SDK configurations are locked.
Checksum bootstrap is trust-on-first-use except the independently checked wrapper
distribution/JAR and the [reviewed publication-gap inputs](../evidence/WP-002/verification-publications.json);
signatures and human dependency/legal approval are not claimed.

Root invocation controls verification for the included build; a standalone
`-BuildLogic` invocation uses that build's own metadata. Both publication formats
must be covered: a cached `.module` resolution is not evidence that a POM is pinned.
`test_verification_metadata.py` checks the pinned JUnit BOM POM/module pair and
that root metadata covers every standalone artifact/checksum. Missing, changed,
duplicate or disabled verification evidence fails. The [fresh-cache proof](../evidence/WP-002/fresh-cache-verification.md)
uses separate initially absent user/project caches for both invocation topologies,
strict mode, `--no-build-cache` and `--rerun-tasks`, without copying binary caches
or rewriting metadata/locks. Use the same procedure for future protected pin changes.

Gradle's extra local-file catalog resolver emits empty
`settings-gradle.lockfile` bookkeeping. It is narrowly ignored and graph-guarded:
any remote coordinates fail and require an approved source-scope amendment.
Real dependency state is not ignored.

`sync_notices.py --write --gradle-archive '<verified gradle-9.8.0-bin.zip>'`
regenerates only declared pinned GPL/MIT/incumbent artwork and Apache notice outputs.
It checks source drift and the distribution/license checksums. Generator inputs:
`LICENSE`, `MeshCore/LICENSE`, `AppIcon.icon/Assets/*.png` at
`db14559b39d32322b06477c6ae676112f583db50`, plus that pinned Gradle distribution.
Room schema inputs are the fixture Kotlin and Room2.8.5/KSP2.3.12 catalog pins;
APT resource IDs come from the tracked resource XML/PNG inputs and SDK37.2.

Root legacy ignore rules hide `docs/android/build/`, so this leased guide is
explicitly staged; no product source is placed under an ignored `build/` package.
See [evidence](../evidence/WP-002/README.md) and [deviations](../deviations/WP-002.md).
Canonical manifest verification remains unconfigured until protected WP-003
handoff review. Installation, code compilation and draft publication do not
unlock dependencies, approve licenses, enable automation or authorize a release.
