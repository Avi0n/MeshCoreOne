# WP-501 channel flood resolver: exact originals and missing open-time consumer

Repository: `cbattlegear/MeshCoreOne-Android`; issue134 / ready PR165.
LOCAL increment directly on `b2a2c62d6ecbcbc85dd4e66120a7d9ee3d24c4e2`;
base/main `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`.
Frozen source remains `db14559b39d32322b06477c6ae676112f583db50`;
manifest `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`;
policy `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.

## Complete original family, not new test credit

Selected from the actual WP-004 detailed inventory:
`MC1Services/Tests/MC1ServicesTests/ChannelFloodScopeResolverTests.swift`.
Read all six complete declarations, their nine explicit resolver calls, full
expected tagged results, native dynamic bodies and invoked `channelsCases`
helper, both resolvers/result enums, model defaults and relevant consumers.
These pure originals inject no errors, asynchronous work or cancellation.
The existing helper still runs each body with its original 60-second coroutine
limit. No incumbent test/helper/input/assertion is edited or duplicated.

The complete original display identities (without the native factory prefix):

| Original identity | Exact inputs: channel / device default / capability | Full result |
| --- | --- | --- |
| `.inherit with device default set resolves to .scope(.region(default))()` | Inherit / `"Germany"` / true | Scope(Region("Germany")) |
| `.inherit with no device default resolves to .scope(.disabled)()` | Inherit / null / true | Scope(Disabled) |
| `.inherit with empty-string default treats it as no default()` | Inherit / `""` / true | Scope(Disabled) |
| `.allRegions on firmware v12+ resolves to .unscoped (true override)()` | AllRegions / `"Germany"` / true; AllRegions / null / true | Unscoped for BOTH calls |
| `.allRegions on older firmware falls back to .scope(.disabled)()` | AllRegions / `"Germany"` / false; AllRegions / null / false | Scope(Disabled) for BOTH calls |
| `.region(name) resolves to that region regardless of default or capability()` | Region("France") / `"Germany"` / true; Region("France") / null / false | Scope(Region("France")) for BOTH calls |

Every complete native assertion already matches. This is six original
declarations/nine call combinations, NOT nine new original cases.
Actual parentb2 own39654268819 Linux119411502156 module method inventory was
decoded completely in memory: each of these six identities occurs under
`com.meshcoreone.android.core.services.contacts.ChannelFloodScopeResolverTest`
with prefix `ChannelFloodScopeResolverTests::`. This establishes existing
parent software execution, not execution of a successor or mounted radio use.
No CI-result bundle is retained or regenerated.

## Concrete source gap: resolved scope never reaches the mounted channel-open path

The frozen `ChatViewModel+Channels.loadChannelMessages` primes the timeline,
then awaits `syncFloodScope(for:)` before the loaded-result guard/unread work.
`syncFloodScope` compares channel preference plus device default with the last
successfully pushed state, requires a session, resolves the capability-aware
action, and awaits either `setFloodScopeUnscoped()` or `setFloodScope(scope)`.
Only success updates the cached state; cancellation is benign and another
failure is logged. Prefetch explicitly must not change radio scope.
The selected frozen header projection and native `ChatConversationType`
display predicates agree, but displaying a region is not issuing this command.

The actual native mounted chain is:

1. `AppChatsContent` constructs `ChatViewModel(conversation, feature.timeline(conversation), scope)`.
2. `AppChatConversationScreen` calls `timeline.start(); timeline.open()` in its keyed effect.
3. Complete `ChatViewModel.open` fetches/normalizes/publishes messages, clears unread and marks failed sends seen. There is no scope-application action.
4. Complete `TimelineContracts` exposes data/retry/draft/events/diagnostics, with no scope application dependency. The complete `AppChatsFeature.timeline` adapter likewise has no scope push.

The native production `setFloodScope`/`setFloodScopeUnscoped`/command-code and
resolver call-site search was checked against those complete consumer bodies:
session and packet implementations exist, but no production caller applies
either session command. The other similarly named calls are database preference
writes, not radio actions. `AppChatsFeature.channels.data.setFloodScope` only
persists/reloads a channel; `ChannelSheetsStateHolder.joinLink` uses that
persistence path. These cannot substitute for open-time session application.

Bounded observable example: open a channel with AllRegions on a v12+ radio
whose default is Germany. The frozen path sends command/subcommand `36 01`;
the mounted native open path has no call that sends it. A header or matching
resolver return cannot override the radio's persisted default. This is a
source-established missing consumer operation, NOT an executed failing test,
hardware observation, or claim about a particular radio's resulting traffic.
For older firmware the expected resolver result remains Scope(Disabled), NOT
Unscoped; no firmware gate or safety behavior may be relaxed to repair this.

## Bounded producer transfer and honest acceptance limit

Route to coordinator for the existing timeline producer WP-307
(`chats-ui-engineer`) and its App composition dependency: implement the real
open-time resolved-scope application, preserving frozen command ordering,
device capability, success-only deduplication, benign superseding cancellation,
reported errors and the no-prefetch-side-effect boundary. Keep commands bound
to the conversation's actual radio/session, not a foreign current radio.
Then add a witness against that real consumer and actual emitted commands,
including successful-repeat suppression and failed/cancelled retry behavior.
This is a concrete prerequisite, not authorization to start another WP/worker.

WP-501 does NOT insert product behavior into the timeline/contracts/App graph,
copy the resolver into a test-written consumer, invent a passing wire witness,
or exclude the gap. Existing pure unit assertions are retained; end-to-end
channel-scope acceptance remains BLOCKED on this producer operation and real
consumer evidence. No new test code or original-case credit is claimed.

## Reproducible source bindings and preservation

Git object paths below are identifiers, not host filesystem paths.
The entire six frozen inputs below also equal the current tracked source bytes
after host CRLF normalization; no selected Swift drift or pin advance.

| Frozen input | Git blob at db145 |
| --- | --- |
| `MC1Services/Tests/MC1ServicesTests/ChannelFloodScopeResolverTests.swift` | `e890d70318ef4578b312a456521ca0a1edf06a2a` |
| `MC1Services/Sources/MC1Services/Services/ChannelFloodScopeResolver.swift` | `9f89e45a321c83dd344cac9a78f6b90dd69b257a` |
| `MC1Services/Sources/MC1Services/Services/ResolvedFloodScope.swift` | `02cd548cc24dfbe302ca6710a6583318ec4cf0e0` |
| `MC1Services/Sources/MC1Services/Models/ChannelFloodScope.swift` | `a481801892e09b2afd35cf1b090c5dab888a2173` |
| `MC1/Views/Chats/ChatConversationType.swift` | `0caa389e328ec0c2adab5cef7b8af314e23ca154` |
| `MC1/Views/Chats/ViewModel/ChatViewModel+Channels.swift` | `b5f12b4788008044d049c286083491c0af7f8924` |

| Native input at parentb2 (under `android/`) | Git blob |
| --- | --- |
| `core/services/src/test/kotlin/com/meshcoreone/android/core/services/contacts/ChannelFloodScopeResolverTest.kt` | `79d1e299db3375758b07350de68be36e43b17040` |
| `core/services/src/test/kotlin/com/meshcoreone/android/core/services/contacts/ChannelsTestSupport.kt` | `79d7553664dd63bc0b0e1f99cc7327f2ff17d6e3` |
| `core/services/src/main/kotlin/com/meshcoreone/android/core/services/contacts/ChannelFloodScopeResolver.kt` | `91312ff9c9b16b5fa22687ecea16676d1d10b5c7` |
| `core/services/src/main/kotlin/com/meshcoreone/android/core/services/contacts/ResolvedFloodScope.kt` | `4151ad7a98e2b25b109fd0add247418a1f218707` |
| `core/model/src/main/kotlin/com/meshcoreone/android/core/model/ChannelDTO.kt` | `aca6f253e93097351da74931fcca0652cad75682` |
| `core/protocol/src/main/kotlin/com/meshcoreone/android/core/protocol/model/Destination.kt` | `18a9bd62659e6b7d7af22bea3a04957900bcad79` |
| `core/protocol/src/main/kotlin/com/meshcoreone/android/core/protocol/session/MeshCoreSession.kt` | `3e98cc221bca2af76c273a2e2f88ec005b0f2f29` |
| `feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/list/ChatConversationType.kt` | `5bc83b9ef038fa7121c32f73b17b6cbd42cb0f9c` |
| `feature/chats/src/test/kotlin/com/meshcoreone/android/feature/chats/list/ChatConversationTypeTest.kt` | `564f9f29c31eaf77bfc886ee85bd953cd840d6f6` |
| `feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/timeline/TimelineContracts.kt` | `3b055c9fd8e7740226d5e79e420825112d8b3b27` |
| `feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/timeline/ChatViewModel.kt` | `643c3e9b484b523dc7b533937ce91eb88f425bc8` |
| `app/src/main/kotlin/com/meshcoreone/android/app/container/AppChatsFeature.kt` | `99ff02f5c15359e6260316d6411a5bac7a62518e` |
| `app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppChatsContent.kt` | `6e191c79de3a5cebf3f8e0603956e180f97bd014` |
| `app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppChatConversationScreen.kt` | `59bb797248d7ccf247dd69c3ee93fd8af5412ff9` |

Before either docs edit, same actual owner/attempt/session
`d6056690-3a5d-453e-b67e-36866c676ac5` evolved101->102 through six wp-owned
admissions: create/modify/record-evidence for this file and the existing WP-501
deviation. Historical authorization, identity (including null issue/task/PR
fields), binding and existing capabilities are unchanged. No shared product or
support edits are introduced; only these two owned docs paths differ.
Removing the added deviation section restores the entire parent deviation.
All fixtures, source, diagnostics, prior evidence and incumbents remain exact.

Only declared source validators/whitespace checks are permitted here; exact
committed outcomes belong to the handoff, not a new CI-result bundle. No local
Gradle/native/WSL/device/lease/readiness/recovery/dispatch/rerun/publication.
Parentb2's seven genuine rollback/writer executions remain separately bound;
ebf6's historical timeout cause remains unlocalized. No diagnostic/root-cause,
protected/native/hardware/backup/full-WP acceptance is inferred.
