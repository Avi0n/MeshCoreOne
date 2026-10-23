# WP-307: live incoming channel-avatar pipeline

Repository: `cbattlegear/MeshCoreOne-Android`. Intake: #314.
Primary owner: this coordinator's existing WP-307 attempt
`bcb17a74-5fa6-47d0-a4be-b6b595e20559`, same-owner reservation revision 16.
Base: `fb9639bfd28ee77443b2a79b316d673ac21a5e1c`.
Reference: `db14559b39d32322b06477c6ae676112f583db50`.
Manifest SHA-256: `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
UTF-8 policy revision: `46a1aebeace81c8719901030e859569df4c2008e19cb841d80e887b5801077d8`.
The candidate head is the commit containing this evidence, bound by its PR and own hosted run;
this file is a source/case map, not a hosted-result certificate.

## Source and real consumer

All 12 original cases and the complete frozen `MessageBubbleConfiguration.swift` were read.
The reviewed production chain is:

`ChatViewModel+Messages.loadAllContacts` -> unique-name identity/JPEG tables ->
`ChatSenderTables` -> `ChatMessageBakeState+ItemBuild` cluster-end identity ->
`UnifiedMessageBubble` -> `IncomingAvatarGutter` / `IncomingBubbleAvatarMetrics`.

On the base commit, core `IncomingAvatarIdentity` existed, but the live App conversation did
not build that table or render the gutter. This repair connects real radio-scoped contact
updates to an atomic App identity/photo snapshot, then to the real timeline's avatar slot.
The native DTO-based row model retains its existing body/accessibility projection instead
of introducing another message/media builder. Shared `ContactAvatar` supplies native
initials/theme/bitmap rendering. Channel incoming cluster ends render; preceding incoming
rows reserve the same column; direct and outgoing rows skip it.

WP-306's actual producer PR #74 is merged at
`95a38aada1151cd289b206c5d8adb78d7664bfbc`, verified in this base's ancestry.
No prerequisite was inferred from an issue closure or idle session.

## Complete original-family mapping

Source: `MC1Tests/Views/Chats/MessageBubbleConfigurationTests.swift` at the reference above.
Native class: `com.meshcoreone.android.app.navigation.MessageBubbleConfigurationTest`.
These are declared tests, not yet claimed passing/discovered results.

| Original case | Native method |
| --- | --- |
| directMessage factory disables incoming avatars | `directMessageFactoryDisablesIncomingAvatars` |
| channel sender resolver marks short prefix match as fallback | `channelSenderResolverMarksShortPrefixMatchAsFallback` |
| channel sender resolver marks unique short prefix match as exact | `channelSenderResolverMarksUniqueShortPrefixMatchAsExact` |
| buildNicknameLookup maps a unique name to its nickname | `nicknameLookupMapsUniqueNameToNickname` |
| buildNicknameLookup drops ambiguous colliding names | `nicknameLookupDropsAmbiguousCollidingNames` |
| buildNicknameLookup ignores contacts without a nickname | `nicknameLookupIgnoresContactsWithoutNickname` |
| channel sender resolver attaches unverified nickname on name match | `channelSenderResolverAttachesUnverifiedNicknameOnNameMatch` |
| channel sender resolver has no nickname when name does not match | `channelSenderResolverHasNoNicknameWhenNameDoesNotMatch` |
| incomingAvatarIdentities maps a unique name without a nickname | `incomingAvatarIdentitiesMapsUniqueNameWithoutNickname` |
| local avatar replace changes imageRevision when lastModified is unchanged | `localAvatarReplacementChangesRevisionWithoutChangingLastModified` |
| incomingAvatarIdentities keeps a unique name when another name collides | `incomingAvatarIdentitiesKeepsUniqueNameWhenAnotherNameCollides` |
| incomingAvatarIdentities keys on contact name not nickname | `incomingAvatarIdentitiesKeysOnContactNameNotNickname` |

Three supplemental methods cover actual direct/public/private route configuration, scalar
lowercase/canonical collision families, and unique nonempty photo retention. They do not
increase the 12-case original denominator.

## Mounted-consumer regressions

`com.meshcoreone.android.app.navigation.ChannelAvatarConsumerTest` mounts production
`AppChatsContent` over the existing real AppContainer/fake-radio/Room harness, not a preview
or test-only configuration adapter. Its six methods cover:

- Actual cluster-end avatar and reserved leading alignment at 200% font: 56 dp diameter and
  6 dp gap; decorative semantics and unchanged stored messages.
- Real local-photo pixels changing from red to green when `lastModified` does not change,
  without replacing the existing bubble's semantics identity.
- Name collision revoking the asserted photo, followed by unique-name recovery.
- Prefix-only resolved initials refusing a contact photo while a simultaneously mounted
  named positive control proves the real table/photo path is populated.
- Direct and outgoing rows omitting both avatar and reserved spacer.
- A real App message event moving the cluster bookend while preserving the prior bubble,
  alignment, stored identity/text and zero sends.

Photos are deterministic, valid in-memory PNGs decoded by the production bounded native
cache. Initials assertions require visible nonuniform glyph rendering, not a blank avatar
box. Each flow asserts no message sends and no HTTP requests.

Both new classes select Robolectric SDK 31 and 37: 15 configuration methods plus six
consumer methods declare 42 SDK-specific executions. This is an expected inventory only;
the candidate's actual selected hosted lane must prove nonzero complete discovery and
results, including the existing mandatory `:app:compileDebugAndroidTestKotlin`.

## Verification and boundaries

| Actual command | Outcome |
| --- | --- |
| `python .\tools\android-port\controller\ci.py preflight` | BLOCKED: explicit `--state` / `ANDROID_CI_STATE` is unavailable. |
| `python .\tools\android-port\controller\validate.py` | Passed: manifest, ownership and dependency validation. |
| `python .\tools\android-port\portmap.py` | Passed after correcting an initially conflicting provenance header on the new consumer regression. Headers establish traceability, not acceptance. |
| `python .\android\parity-tests\semantic.py --check` | Passed read-only binding validation; no archived accounting/review outputs regenerated and no new acceptance asserted. |
| `git diff --check` | Passed before publication; whitespace only, not Kotlin/runtime evidence. |
| Hosted selected Android candidate commands | Not run locally; require the PR's own exact-head terminal job and log. |

No SDK/toolchain installation, native host/guest operation, manual CI dispatch/rerun,
golden change, translation provider, dependency/build-graph change, protected-gate approval
or new actor/session was introduced. Source/manifest/policy pins and authorization prose
remain unchanged. No iOS, device, hardware, TalkBack, incoming-flight-animation or full
WP-307/WP-501 acceptance is claimed. Reproducible hosted results belong to the exact job,
not another committed/uploaded/rehashed result bundle.
