# Complete MessageBubbleConfiguration suite: channel-avatar producer gap

WP-501, cbattlegear/MeshCoreOne-Android. This is a source-backed blocker,
not a new passing family, test scaffold or exhausted-inventory claim.
Authoring base is actual merged main
`fb9639bfd28ee77443b2a79b316d673ac21a5e1c`, tree
`55edb4f4440913a80b9581f5253d1fde8a107057`. The managed branch normally
fast-forwarded from its identical-tree PR312 head80f9676; no incoming files
or source changes were needed. Closed PR312 and its results stay historical.

## Frozen complete suite and helper inputs

Read the complete twelve standalone declarations and both helpers in
`MC1Tests/Views/Chats/MessageBubbleConfigurationTests.swift`, blob
`18b9aa2850c2f34bfb551ae83611d88837d96fb5`, at the unchanged reference
`db14559b39d32322b06477c6ae676112f583db50`. There are no parameterized
declarations in this suite. Its original cases remain in the existing
inventory; absence of proposal/annotation text was only triage, not the
behavioral finding below.

Every contact uses a fresh contact/radio UUID; its supplied prefix is padded
with zero bytes to exactly32 bytes. Defaults are CHAT, flags0, outPathLength0,
empty outPath, supplied advert time, latitude/longitude0, lastModified0,
nil lastHeard/nickname/lastMessage/avatar, false blocked/muted/favorite and
unread0. An explicit nickname or avatar overrides only that default.
Messages have fresh IDs/radio, nil contact, channel0, Test/timestamp0,
ambient createdAt, incoming/delivered/plain, nil ack/SNR/reply/RTT,
pathLength0, supplied sender prefix/name, readtrue, heard0/retry0/max0.
No substituted fixture, changed default or Kotlin expected-value generator
was used to assess these cases.

## All twelve original predicates against the actual native boundary

Native names in this table refer to existing production code, not new
lookalikes. "Present" is source comparison only, not exact-original execution.

| Original declaration | Exact inputs/predicates and current disposition |
| --- | --- |
| `directMessage factory disables incoming avatars` | DM.showsIncomingAvatars=false AND channel(public=true).showsIncomingAvatars=true. Native timeline has no such configuration/gutter: absence on DM does not satisfy the channel-positive predicate. BLOCKED product behavior. |
| `channel sender resolver marks short prefix match as fallback` | Older AA01/advert100 then Newer AA02/200; message prefix AA. Require Newer/FALLBACK. Existing App `bubbleSenderResolution` delegates to real `PathNodeResolver.resolution`; its no-location tie-break orders adverts descending and marks multiple distinct short-prefix matches FALLBACK. Present; no new original execution claim. |
| `channel sender resolver marks unique short prefix match as exact` | Alpha AA01/100, message prefix AA. Require Alpha/EXACT. Same native resolver marks one distinct matched public key EXACT. Present, not a new executed case. |
| `buildNicknameLookup maps a unique name to its nickname` | Alpha AA01/100/nickname Rico; lookup alpha=Rico. Existing `rememberMessageBubbleLabels` groups contacts by lowered name and includes the single nonempty nickname. Present. |
| `buildNicknameLookup drops ambiguous colliding names` | Bob AA01/100/First plus bob AA02/200/Second; no bob key. Existing singleOrNull name grouping omits the collision. Present. |
| `buildNicknameLookup ignores contacts without a nickname` | Alpha AA01/100/nil nickname; empty lookup. Existing mapNotNull drops nil. Present. |
| `channel sender resolver attaches unverified nickname on name match` | Alpha/Rico lookup, nil prefix/name Alpha; require Alpha/EXACT/unverified Rico. Actual `bubbleSenderResolution` parsed-name branch reads the lowered-name map. Present; existing mounted Alice nickname label is overlapping behavior, not this exact source fixture. |
| `channel sender resolver has no nickname when name does not match` | Alpha/Rico contact and lookup, nil prefix/name Charlie; require nil unverifiedNickname. Existing parsed-name branch misses Charlie. Present. |
| `incomingAvatarIdentities maps a unique name without a nickname` | Alpha AA01/100/nil nickname/avatar. lookup alpha must contain contact.id, name Alpha, nil imageRevision. No production contact-to-avatar identity table exists at the actual channel consumer. BLOCKED. |
| `local avatar replace changes imageRevision when lastModified is unchanged` | Alpha AA01/100,64 bytes01 replaced with64 bytes02; equal lastModified0; rebuilt alpha identities must have different imageRevision. Core content revision exists, but no production contact-table build/reload/row patch consumes it. BLOCKED; direct revision tests are not this predicate bundle. |
| `incomingAvatarIdentities keeps a unique name when another name collides` | Alice AA01/100, Bob AA02/100, bob AA03/200; alice matchedContactID=Alice.id, no bob identity. No production unique-name identity table. BLOCKED. |
| `incomingAvatarIdentities keys on contact name not nickname` | Alpha AA01/100/Rico; alpha matchedContactID=contact.id, no rico key. No production table at this boundary. BLOCKED. |

Seven sender/nickname cases have inspectable native implementations; five
original cases include concrete missing avatar behavior. This is not a
seven-pass/five-fail execution report. No original credit is added.

## Real source-to-consumer trace

Frozen `MC1/Views/Chats/Components/MessageBubbleConfiguration.swift` builds
the unique-lowered-contact-name identity table independently of nicknames;
each identity carries original name/contactID and a content revision.
`ViewModel/ChatViewModel+Messages.swift` loads contacts, builds that table for
channel timelines, updates JPEG storage and patches existing avatar-bearing
rows. `ChatMessageBakeState+ItemBuild.swift` resolves cluster-end identities
from the table and passes them into MessageBuildInputs. In
`Components/UnifiedMessageBubble.swift`, configuration enables the avatar
column only for channel incoming rows; actual identity controls the gutter
image/initials. These inspected input blobs equal the frozen reference.

Native `core/services/.../rendering/IncomingAvatarIdentity.kt` already
implements initials, resolution from a SUPPLIED map and byte-content
revision. `MessageBuildInputs.incomingAvatar` defaults null; the real builder
only forwards it to the envelope. Existing `RenderingModelsTest` supplies an
identity by hand for passthrough, supplies a handcrafted map for resolution,
and compares three-byte revision inputs. Those assertions remain valid but
cannot establish contact-table construction, original64-byte replacement,
name collision isolation or an actual rendered channel avatar.

Actual `app/.../navigation/AppMessageBubbleAccessibility.kt` builds only
the nickname table. Its builder projection leaves incomingAvatar at null;
that accessibility projection is not claimed to be a visual renderer.
The visual boundary is `AppChatConversationScreen.kt` -> production
`feature/chats/.../timeline/ui/ChatTimeline.kt::MessageBubble`.
The App supplies MessageBody(text) and attachments. The timeline itself
renders sender text/body/status/retry inside a Surface, with no contact-avatar
table, incoming identity, or gutter input/render branch. MessageBody's full
implementation is text/link styling, not an alternate avatar path.
Rooms' separate avatar-frame test is not the channel-timeline consumer.

This establishes a real missing production path, not a presumed failure
deduced from filenames, headers, annotation counts or a need to satisfy tools.
There was no fresh mounted reproduction or Kotlin failing run.

## Existing producer boundary and bounded disposition

Manifest primary owner is **WP-307 / chats-ui-engineer** for the complete
test suite, MessageBubbleConfiguration, ChatViewModel contact/row baking,
UnifiedMessageBubble and the native timeline. The bounded producer repair
is its actual channel identity-table/contact-refresh/cluster-row/avatar
consumer, retaining the already implemented core identity contract. Any
necessary App contact/identity handoff belongs to the existing App composition
producer; it is not authority for WP501 to implement a second renderer.

WP501's admitted `wp501-bubble-consumer-support` operation repairs original
message-bubble ACCESSIBILITY consumption. Its once-only labels, original
sender/footer/action preservation and no-unrelated-UI-service-build invariants
are not an avatar-product capability. Generic App support wiring likewise
does not move this primary timeline behavior into the test package.
No new actor, permission framework, golden relaxation or product edit is made.
Coordinator can transfer the concrete defect to the existing primary producer.

Until that product path exists, authoring a local mapper or manually filling
an envelope would merely manufacture a witness and leave the same original
consumer gap. Therefore this increment records the complete suite and blocks
its avatar acceptance instead of adding non-executable/scaffold tests.
It does not declare other inventory exhausted, reopen completed
predicate/status/label families, or enter separately owned Path/Rooms work.

## Receipt, preservation and verification limit

Trusted `wp-owned/modify` admission for this evidence evolves the same
D605 owner/attempt r140->141; the existing deviation operation is idempotent.
Immutable identity/binding/authorization, prior capabilities/operations and
every complete protected-lock record remain unchanged. Source db145,
manifest977b9 and actual UTF-8 policy46a1 are unchanged.

Only this evidence and an additive WP501 deviation change. All Android
product/tests, incumbent proposals/order/topmetadata, generated accounting,
closed312 evidence and other-WP entries stay byte-identical to actualfb.
The exact local handoff records source-validator outcomes. Declared
`python tools\android-port\controller\ci.py preflight` returned BLOCKED exit2
because explicit CI state is absent. No new Kotlin declarations, discovered
passing originals, Android compiler, native/device/TalkBack, hardware, iOS,
oracle or whole-WP acceptance is claimed. No publication, closed-log replay,
CI bundle, dispatch/rerun, raw Gradle or native/resource action occurred.

| Exact declared command | Actual local outcome |
| --- | --- |
| `python tools\android-port\controller\validate.py` | PASS, valid, canonical manifest977b9 |
| `python tools\android-port\portmap.py` | PASS,1879 provenance entries, not behavioral acceptance |
| `python tools\android-port\controller\verification_config.py --check` | PASS; feature verification still unconfigured |
| `python android\parity-tests\semantic.py --check` | PASS; no proposal/accounting regeneration or original credit |
| `python tools\android-port\controller\ci.py preflight` | BLOCKED exit2, explicit CI state absent |
| `git diff --check` | PASS |

No unit/Android suite is replayed for this documentation-only blocker.
