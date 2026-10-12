# WP-503 accessibility audit

**Status: native suites executed with failures; acceptance incomplete; device TalkBack unavailable.**
This is not accessibility acceptance, a completed WP, or permission to close issue #136.
The inspected native tree is `d958471ab4835813881a5e7af36e8249a8b273a4`; the Swift
specification remains `db14559b39d32322b06477c6ae676112f583db50`.

## Evidence boundaries

Source inspection can establish missing labels/actions and identify the actual consumers.
It cannot measure a hit rectangle, prove text is unclipped, establish TalkBack traversal,
or certify the colors painted by a native feature. Existing test assertions are reusable
coverage, not tests executed by this worker. No numerical health score is assigned without
those observations. The native measurements below are automated evidence, not device
announcements/traversal. Performance and visual redesign are outside this audit.

The [execution matrix](execution-matrix.md) records the required assertions and observations.
[WP evidence](../evidence/WP-503/README.md) records actual commands and the execution gate.
Follow-up investigation found installed Linux build inputs and admitted App support
operations. The coordinator verified that the canonical project-session handle and actual
CLI ID resolve to this same worker. This is a valid alias relationship, not an identity gate.
The same reservation now admits additive App test registration. Native suites mount the real
room, timeline/rich-message and appearance content; they do not change product code or
WP-501's parity bindings. Their results must be read from actual discovery, not this inventory.
The product retains native Material navigation, five sections, all ten unlocked themes,
original identity algorithms, app localization and original message text. Dynamic color,
translation, billing and new palette choices are not proposed.

## Source-confirmed findings

All locations below are relative to `android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/`.
These four findings concern WP-310 production ownership, not WP-501's existing parity
bindings or eight assigned defects. No production repair or acceptance-binding edit was made.
The coordinator routed them to the dedicated WP-310 producer in
[#207](https://github.com/cbattlegear/MeshCoreOne-Android/issues/207).

| ID / severity | Location at inspected tree | Evidence and user impact | Required repair and verification |
| --- | --- | --- | --- |
| A503-01 / P1 | `rooms/RoomConversationScreen.kt:163-179`; `rooms/RoomConversationStateHolder.kt:229-238` | Cluster follow-ups set `showSenderName=false`. The actual row renders the author only when that flag is true and has no per-message author semantics. A user navigating directly to the follow-up loses the speaker context. The frozen `RoomMessageBubbleA11yLabelTests` explicitly requires the author even when visually hidden. | Expose author plus body once per incoming row, independently of visual grouping. Assert both cluster-start and follow-up semantics, then observe TalkBack announcements without making the whole timeline a live region. |
| A503-02 / P1 | `rooms/RoomConversationScreen.kt:163-179` | The row tests status only to show a failed-send Retry button. Nonfailed outgoing rows expose the body but no sending/sent status text or status semantics. The frozen outgoing-label case requires status exactly once. | Bind localized status to each outgoing message, retaining the existing failed-only retry behavior and real callback. Assert each supported room status and exactly-once announcement; do not infer direct-message delivery semantics for rooms. |
| A503-03 / P1 | `rooms/RoomConversationScreen.kt:174` | Room bodies use `Text(row.message.text)` without link annotations or named link actions. The frozen room HTTPS case requires a named open-link action. Direct/channel `composer/ui/MessageBody.kt` already provides annotations and named custom actions, but the room consumer does not use it. | The product owner should reuse the approved formatter/dispatcher with room-appropriate context. Verify the existing link naming/order/deduplication/cap and actual routing, not just a source-string match or an always-successful action. |
| A503-04 / P1 | `rooms/RoomConversationScreen.kt:213-215` | The Remember password switch has an `onCheckedChange` and touch-target modifier, but no label semantics. Its visible label is a separate sibling Text in a Row without merged/toggleable semantics. The switch's own accessible node has no associated purpose label. | Associate localized purpose and checked state with one switch/toggle node. Check merged and unmerged native semantics, toggle by accessibility action, and observe focus/announcement in the authentication sheet. |

These are code-level findings, not claims that TalkBack was run. The actual reachable
consumer is `android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppChatsContent.kt:109-130`,
which calls `RoomConversationRoute` and the authentication sheet. The findings are not
based on the unused dependency-free scaffold registry.

## Measured painted-feature contrast defect

The mounted direct/channel path is `AppChatsContent` -> `AppChatConversationScreen:91-94`
-> `ChatTimeline.MessageBubble` -> `MessageBody`. Its outgoing surface is
`MaterialTheme.colorScheme.primaryContainer`, while colored message spans use
`frame.outgoingText`, `frame.hashtag` and source identity colors. The existing
`ThemeContrastTest` measures source outgoing text against **accent**, and corrected
Material role pairs separately. Neither assertion proves these raw spans clear the
different surface actually used by the feature. Measure every emitted span against
its composited bubble, including self-mention fills, before calling all-theme contrast
complete.

The mounted native audit at exact `86993ee65ca43fb5f7da26f537b3857e1cd4c1a9` measured all
38 effective frames, both message directions and five emitted text ranges on SDK31/37:
**760 measurements, 162 below the unchanged floors, 81 distinct failing segments**.
Minimum: **1.043543:1**, Fern/dark/standard/outgoing hashtag. There are 22 high-contrast
identity records below 7:1. Pixel sampling verifies the feature's actual emitted bubble
surface; link styles, full-range/base overrides and self-mention fills are resolved from
the actual `TextLayoutResult`. Normal text retains 4.5:1.

This confirmed finding is assigned in
[#208](https://github.com/cbattlegear/MeshCoreOne-Android/issues/208). The coordinator owns
repair routing across WP-308 spans, WP-307 bubbles and WP-301 roles. WP-503 makes no new
palette/identity choice and does not edit those production surfaces.

## Qualified font/viewport diagnostics

The room authentication sheet is a non-scrolling Column. Appearance cards constrain
theme-name text to one line. Map's first controls row does not wrap. These merit the
compact-window/200%-font checks in the matrix; source shape alone is not evidence of
clipping, overlap or an undersized effective hit target. Initial native TextLayout overflow
flags on short English labels were disproved as clipping: the native paragraph retains unused
parent width while the widget shrink-wraps actual lines. All ten appearance labels on both
SDKs have visible ink and actual lines within their boxes. The corrected oracle requires
nonempty lines, no truncation/ellipsis and zero-tolerance actual line bounds; a genuinely
clipped 32x4dp negative control still fails. No font product defect or relaxed floor is claimed.
A partially revealed scroll-grid card is not classified as intrinsically
undersized; the audit scrolls cards fully into view before measuring their effective bounds.

The populated noise-floor audit exposes its actual count/minimum/maximum/average in the
chart summary, but **all three numeric widgets have height zero** in a 360x900dp/200%-font
native window: Minimum 253x0, Maximum 260x0, Average 259x0, with zero visible bounds on both
SDKs. The unweighted, non-scrolling Column places statistics after a fillMaxSize chart that
consumes remaining height. This measured finding is assigned in
[#214](https://github.com/cbattlegear/MeshCoreOne-Android/issues/214), claimed by the existing
coordinator in its WP-316 repair scope, not a device observation or a WP-503 product edit. Reachability may use
native scrolling; the audit does not require every metric to fit on screen simultaneously.

At exact `abc86cb0dfd885d4dabd14a07f637ea031ad9b29`, the focused appearance/font/diagnostics
cohort discovered/run 10, passed 8, failed 2, errors/skips 0. All unlocked appearance
selections/real font/targets and font regressions passed. Only populated diagnostics failed.
At exact `22309ff1eb9a1ccddecdd3375ec2de1aee77a812`, the earlier role/guard cohort ran 26,
passed 18, failed 8: existing theme-role families passed 10/10 and actual-timeline live-region
controls passed 4/4. These selected results do not replace the recorded contrast/room failures.

## Existing strengths to preserve

`NativeNavigationShell` uses current available width, Material navigation, tab selection,
localized rail icon descriptions, unread state descriptions, headings, focus restoration
and native Navigation 3 Back. It scales navigation height and suppresses transition
duration when motion scale is zero. These are source observations, not device certification.

The composer supplies localized icon labels, security/limit semantics, explicit shared
touch targets, and a small failure-only polite live region. The direct/channel timeline
uses keyed per-message rows and does not announce the entire chat as a live region.

Map exposes a non-gesture marker list and coordinates. Noise-floor diagnostics expose
current-quality text, a chart summary, numeric min/max/average/trend and keyboard
selection. Line-of-sight result cards expose clearance status/percentage, path loss and
segment metrics as text. These alternatives must be exercised with populated, empty,
offline and failed data rather than accepting the existence of a generic chart label.

## Ownership handoff

Coordinator session `bcb17a74-5fa6-47d0-a4be-b6b595e20559` routed A503-01 through
A503-04 to WP-310 issue #207, worker `5ca13f75-8466-4dd5-b630-02b30841a5d0`.
Re-audit genuinely repaired bytes once available. The span/surface measurement involves the
WP-307/WP-308 consumers and WP-301 roles; any repair must be serialized through their
current producers. WP-503 retains audit evidence. WP-501 retains all original parity
bindings/accounting; WP-502 retains its screenshot/flow ownership.

WP-308's dedicated producer owns #208; the existing coordinator owns WP-316 repair #214.
No nested worker, golden update, threshold relaxation, signing action or repository-setting
change occurred. Required repaired room link APIs must be reconciled in owned fixtures only
against genuinely adopted product bytes; old compilation is not repair evidence.
