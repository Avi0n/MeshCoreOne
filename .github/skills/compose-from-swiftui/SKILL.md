---
name: compose-from-swiftui
description: Preserve MeshCore One's product/theme behavior while adapting SwiftUI screens to native Material Compose, current window size, accessible states and deterministic flow evidence.
---

# Native Android port brief

**Job:** mesh-radio users need to connect, send/read messages and manage nodes on a phone/tablet,
often without internet. This is an operational tool, not a marketing surface.
**Proof:** correct radio/connection/delivery state and real service actions, not decorative demo success.
**Authority:** retain the incumbent five sections, copy, themes, identity colors and feature behavior.
**Native adaptation:** Material 3 controls, system Back/keyboard/permissions and window-adaptive layout
replace Apple-specific controls. Do not redesign the brand or copy iOS chrome mechanically.
**Boundary:** no billing, new account/backend, unsupported claims, dynamic-color theme or automatic updater.

Read the exact screen/ViewModel/tests and shared theme/component/resource definitions before editing.
The product brief is already settled; do not re-interview about aesthetics for each WP.

## Common mappings

| SwiftUI behavior | Native Compose direction |
|---|---|
| NavigationStack | Approved typed Navigation 3/back-stack contracts and native predictive Back |
| NavigationSplitView/sidebar | Adaptive list-detail plus navigation rail based on current window class |
| TabView, five tabs | NavigationSuiteScaffold with independent destination/back-stack state |
| Observable/MainActor state | ViewModel StateFlow, lifecycle-aware UI observation |
| sheet/detents | Material ModalBottomSheet with correct dismiss/Back/focus state |
| alert/confirmationDialog | Material dialog/menu with localized decision and destructive semantics |
| swipeActions/contextMenu | Native dismiss/menu patterns plus accessible non-gesture alternatives |
| List/ForEach timeline | Keyed LazyColumn with deliberate ordering, paging and scroll anchors |
| onAppear/task | Keyed, cancellable effect tied to the intended owner, not recomposition |
| TipKit | Contract-backed show-once tips without repeating or blocking a task |
| SF Symbol | Licensed Material/custom replacement with equivalent meaning |
| Liquid Glass | Material surface/elevation roles, not an Apple blur skin |
| MapKit/MapLibre | Shared lifecycle-correct MapLibre adapter; no feature-to-feature map dependency |

## Architecture and state ownership

Each screen has a route wrapper that resolves the ViewModel/contracts and a content composable taking
immutable state/actions. Content previews do not instantiate a Bluetooth connection, network downloader,
database migration or product/store service.
Business state comes from services; a composable must not reconstruct retry/persistence/auth policy.
One process/radio owner remains live when a screen is closed or its lifecycle collector stops.

Effects have stable keys and explicit cancellation. Do not send messages, create connections, request
permissions or import data directly during composition. Recomposition and process recreation must not
duplicate hardware actions. Pending external routes wait for initialization/readiness and are consumed
once against the correct radio generation.

## Adaptive navigation and insets

Use current WindowSizeClass/available space, not device name or orientation flags.
Compact uses native bottom navigation; medium/expanded uses appropriate rail/list-detail layouts.
Preserve selected detail, independent tab stacks, scroll/draft state and focus on resize/folding.
Android 17 large-screen resizability is not solved by locking orientation.

Honor system Back/predictive Back, IME action/Back, hardware keyboard and window/cutout/system insets.
Apply each inset once; message composition must remain visible above the keyboard.
Use native surfaces/dialogs/sheets without hijacking background activity launches.
Feature entry points and cross-tab navigation use approved contracts, not direct feature dependencies.

## Theme, text and assets

Use the ten existing themes with their actual effective scheme rules, including forced dark where used.
Retain avatar/name identity algorithms and map roles into reusable Material color/type/shape tokens.
All themes are unlocked; StoreCatalog IDs are not Android entitlements.
Use the shared localization key map/resources for visible strings and accessibility labels.
Keep raw protocol/domain/log strings separate from localized UI copy.
Use source-provided licensed assets or approved replacements; never extract restricted SF Symbol artwork.

Text uses scalable Material typography and survives 200% system font, CJK/long strings and RTL message
content. Do not clip primary actions/errors or use fixed-height text containers that assume English.
Format dates/units for the locale but keep serialization locale-neutral.

## Lists, rich content and performance

List keys include stable message/conversation/radio identity; never array index or mutable display text.
A reversed timeline layout does not justify reversing data/paging/scroll semantics blindly.
Preserve unread boundaries, old-page anchoring, new arrivals, jump-to-latest and source grouping.
Use bounded caches, stable snapshots and the shared validated preview/image pipeline.
Do not spawn duplicate fetchers per row or render untrusted rich content as arbitrary executable WebView HTML.
Maps/charts have explicit owners, bounded visible datasets and meaningful text alternatives.

## Required states and affordances

Keep these distinctions where relevant:

- No paired radio versus paired but disconnected; connecting, syncing, ready and failed.
- No messages versus existing cached data with no active transport.
- Pending, delivered and failed sends; source retry and draft recovery.
- Permission not yet requested, denied, permanently denied or revoked.
- Loading, empty, partial, cancelled, unsupported and failed map/terrain/preview content.
  Message translation is removed from active scope; preserve app localization and original message text.
- Room guest/participant/admin permissions and expired authentication.
- Backup validation/restore outcomes, dropped/merged counts and post-commit completion.

GPS/camera/notification/Google-service failures affect only their optional capability.
Do not gate mesh messaging on internet access, an optional language model or nonessential permissions.
Use explanatory recovery, not a spinner forever, a blank chart or an unconditional success snackbar.
Destructive/device-changing actions require a real confirmation and verified result.

## Accessibility

At least 48 dp touch targets; do not rely on gestures alone.
Expose headings, selection, unread/delivery state, progress, actionable labels and sensible TalkBack order.
Do not convey SNR/clearance/delivery only by color or announce an entire changing chat as one noisy region.
Use shared contrast rules for every theme/effective scheme and honor system animation settings.
Verify dialog/menu/keyboard focus and expanded-screen navigation.

## Evidence and stop

Use deterministic fake-radio/service data, local media/maps and meaningful previews.
Test key flows and transitions, not just screenshots. Cover compact/expanded/resize, effective themes,
large font, supported languages, keyboard and permission/offline/failure scenarios using an efficient
pairwise/state matrix. Update goldens only through the approved process.
UI completion requires a real connected service action, matching source assertions and no reachable
scaffold placeholders. Stop on missing contracts, ambiguous source behavior or ownership conflicts.
