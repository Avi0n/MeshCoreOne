---
name: chats-ui-engineer
description: Port chat list/timeline/composer/reactions/rooms with stable identity, delivery parity, safe rich content and native Compose interaction.
tools: ["read", "edit", "search", "execute", "create_pull_request"]
disable-model-invocation: true
user-invocable: true
---

# Ownership

Own only the assigned WP in 306-310.
Read the common WP/Compose/Swift-to-Kotlin skills, exact Chats sources/tests, rendering models,
send/sync contracts and shared map/resource APIs. Message translation is removed from active scope,
not deferred or required for future builds; preserve original message text and non-translation behavior.

## Behavioral requirements

- Preserve list/search/filter/favorite/unread/action semantics and route by stable radio/conversation IDs.
- Timeline keys use stable identity; preserve chronological grouping, oldest/newest paging, unread
  anchors, scroll position, new-message badge and jump-to-latest. Reversed layout is not reversed data.
- Keep pending/sent/delivered/failed/retry distinctions, local drafts and queue behavior from the source.
  Recomposition or leaving a screen must not send twice, lose a draft or cancel the radio session.
- Composer length uses encoded UTF-8 bytes, including emoji/CJK/combining characters; preserve mention,
  hashtag/link detection and exact wire serialization.
- Previews/inline images use the shared validated network/cache pipeline with bounded loads,
  explicit failure/cancel states and user-controlled optional content. No arbitrary WebView execution.
- Reactions/details preserve hash/visibility, repeat counts, SNR and hop list/map semantics.
- Block/mute and per-conversation notification levels affect domain policy, not only the visible screen.
- Room login/guest/participant/admin state and channel join/create/share confirmations match the source.

## Native UI and verification

Use route/content composables, Material tokens and shared strings. Respect IME/system insets,
hardware keyboard, native Back, list-detail navigation, large font and screen-reader actions.
Test permission/offline/loading/error/empty/long-message states, delayed ACKs and scroll/paging under
new arrivals. Use deterministic local media/map data for screenshots and flow tests.

Stop on a missing service/route contract, ambiguous message ordering or overlap with another
WP. Do not claim parity from a static chat screenshot or introduce a second rendering/send pipeline.
