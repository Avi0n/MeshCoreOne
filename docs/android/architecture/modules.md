# Module contract and directed graph

**Status:** Proposed; WP-001 supervised dependent draft, pending human review.
This refines the approved module boundaries, not the frozen WP dependency graph.

The primary-owned reference [Architecture.md](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/docs/Architecture.md)
maps its protocol/business/UI layers into these modules. Keep
[Glossary.md](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/docs/Glossary.md)
terminology and source units/roles; it is not an Android feature exclusion list.
Current source takes precedence over stale constructor/lifetime examples.

## Production edges

An edge below means **consumer depends on producer**. Unlisted production project
edges are forbidden. External libraries must also pass the license/build gates.
`core:protocol`, `core:model` and `core:contracts` are pure JVM: no Android,
Compose, Room, Bluetooth or concrete runtime/service types in their public API.

| Consumer | Allowed direct project dependencies |
| --- | --- |
| `core:protocol` | none |
| `core:model` | `core:protocol` |
| `core:contracts` | `core:model`, `core:protocol` |
| `core:l10n` | none |
| `core:designsystem` | `core:model`, `core:l10n`, `core:datastore` |
| `core:database` | `core:model` |
| `core:datastore` | `core:model`, `core:contracts` |
| `core:data` | `core:protocol`, `core:model`, `core:contracts`, `core:database`, `core:datastore` |
| `core:ble` | `core:protocol`, `core:model`, `core:contracts` |
| `core:connectivity` | `core:protocol`, `core:model`, `core:contracts`, `core:ble` |
| `core:runtime` | `core:protocol`, `core:model`, `core:contracts` |
| `core:services` | `core:protocol`, `core:model`, `core:contracts` |
| `core:ui` | `core:model`, `core:contracts`, `core:designsystem`, `core:l10n`, `core:datastore` |
| `core:maps` | `core:model`, `core:contracts`, `core:designsystem`, `core:ui`, `core:l10n` |
| Each `feature:*` | `core:model`, `core:contracts`, `core:designsystem`, `core:ui`, `core:maps`, `core:l10n` as needed |
| `platform:notifications` | `core:model`, `core:contracts`, `core:l10n` |
| `platform:widgets` | `core:model`, `core:contracts`, `core:designsystem`, `core:l10n` |
| `platform:shortcuts` | `core:model`, `core:contracts`, `core:l10n` |
| `platform:translation` (inert historical scaffold only) | `core:model`, `core:contracts`; no provider/model/entry or acceptance requirement |
| `app` | Production core modules, the seven features and three active platform adapters; inert historical translation shell is not a feature |

The seven features are onboarding, chats, nodes, remotenodes, map, tools and settings.
No feature-to-feature or feature-to-platform edge is allowed. Shared map code is
`core:maps`, not `feature:map`. `app` registers entries and injects interfaces.
In particular, **runtime never depends on services**, and services never depend on
runtime/database/data/connectivity. Constructor-injected ports break those cycles.
Database entities/DAOs do not construct repositories or services.

A valid construction order is protocol/l10n; model; contracts; database/datastore/
ble/runtime/services/platform adapters; designsystem; ui/data/connectivity/maps;
features; app. Independent modules at a level need no mutual edge.

The WP-301 coordinator-approved theme adapter consumes the actual process-owned
`PreferenceStore`/`AppearancePreferenceStore`, not another defaults provider.
Its caller supplies a process scope; stopping a screen collector or replacing a
radio connection must not close preferences or the theme service. This narrow
edge does not permit a reverse datastore-to-designsystem dependency, concrete
preference-store dependencies in features, or changes to the WP dependency DAG.

The WP-304 coordinator-approved shared-tip adapter also receives the actual
process-owned `PreferenceStore`. Eligibility and the durable show-once claim use
one atomic DataStore update; UI collectors neither create nor close storage.
This single edge does not permit datastore-to-UI, UI-to-concrete-radio/data
dependencies, or concrete preference dependencies in features. The frozen
active 64-WP/178-edge/seven-gate dependency plan is unaffected by this adapter.

`core:testing` may depend on production libraries and is consumed only through
test configurations. An app test factory belongs to the app's test source set,
not a production `AppContainer.forTesting()` edge back into testing. `tools:meshcli`
is a dev-only JVM consumer of protocol/model/contracts. `benchmark` targets app
through its test/benchmark configuration. None is packaged as a production
dependency or supplies a production success-shaped fake.

## Contract ownership and handoff

| Contract producer | Implementation producer | Consumers / integration |
| --- | --- | --- |
| Values/transport WP-101; events/link errors WP-106; session WP-107 | WiFi WP-108, BLE WP-205 | Runtime and services, dev meshcli |
| Domain DTOs/repository roles: WP-201 | Room/store WP-201/202; backup WP-203; preferences/secrets WP-204 | Services receive narrow roles, not concrete stores |
| Lifecycle/factory/signals specification: WP-001; typed domain seam WP-201 | Runtime WP-207 against injected factories | WP-208 through WP-218 services; WP-303 assembles concrete factories |
| Notification/string-provider seam: WP-001/201, business policy WP-215 | Delivery WP-401; connection status WP-402 | Process adapter called by current-session policy; never the reverse module edge |
| Feature/navigation IDs: WP-001, compile shells WP-002 | Native shell WP-302 and owning feature WPs | App, notifications, deep links WP-405, shortcuts WP-404 |
| Historical translation seam/helpers | No active producer; user-approved scope removal | No chat/room/settings registration or WP-407 completion blocker |
| Shared maps, l10n and visual tokens | WP-312, WP-005, WP-301 respectively | Core UI and features, never protocol/contracts |

WP-002's neutral compile shells reserve these boundaries, not behavioral
ownership. WP-201 supplies DTO/repository signatures after WP-101/106. WP-207
depends on WP-107/202/204/205 and cannot instantiate unfinished service WPs.
WP-303 follows its **actual manifest prerequisites**, including connectivity,
remote/device/log/render/sync/notification/demo/location services and WP-302/304;
an idle session or drafted interface does not satisfy them.

## Enforcement for WP-002

Convention build logic must inspect actual production configurations and fail for
unlisted edges, cycles, feature-to-feature dependencies, Android leakage into JVM
modules and any production dependency on testing/meshcli/benchmark. Check plugin
and generated-source configurations as well as handwritten declarations. Add
negative graph fixtures and positive test discovery; record the real verification
task only after it exists. This document does not invent a Gradle task or amend
the approved active **64-WP / 178-edge / 7-human-gate** graph.

The existing empty `platform:translation` scaffold, its build edge and already-ported shared
contracts/rendering helpers are retained inert to avoid unrelated build-graph or wire/persistence
changes. They have no translation provider, model dependency, executable feature or navigation entry.
WP-407 completes only active features and must not require or register translation. Any later addition
requires a new user feature request and scope/admission decision, not merely filling an old shell.
