# WP-503 native execution matrix

**Partial native execution; no complete row or device acceptance.** This matrix remains the
full assertion/observation contract, not a passing result. The real App runner discovered
24 audit tests on SDK31/37 at candidate86993ee65: 6 passed, 18 failed, no errors/skips.
It covered all 38 rich-span/pixel frames, room distinctions, mixed-direction direct retry,
appearance and noise-floor states. See [exact evidence](../evidence/WP-503/README.md).
The remaining states/flows/windows below are NOT RUN. Do not reuse historical screenshots
or promote a partial automated check to observed TalkBack/whole-WP acceptance.

## Shared acceptance rules

For all enabled interactive controls, measure effective hit regions at least **48 x 48 dp**,
including custom rows, menus, links and map affordances. Record the failing node, state and
dimensions. A visual icon's size or a source modifier is not a hit-region measurement.
Check adjacent expanded hit regions do not make another action unreachable.

Use actual system **2.0 font scale** and record the effective native configuration. Check
text layout overflow, action visibility/reachability, dialog/sheet scrolling, IME occlusion
and focus after resize. Include long localized labels, CJK, RTL layout and mixed-direction
message bodies. A manually supplied Density in a preview is supplemental, not system-font
or TalkBack evidence.

Measure normal readable text at **4.5:1**, source high-contrast identity families at
**7:1**, and meaningful non-text boundaries/indicators at **3:1** against the actual
composited surface. Preserve existing stronger assertions. Distinguish disabled decoration
from actionable/readable content; do not silently exempt a failure. Check all exported
surface tiers, selected states, status/error colors, rich spans and mention backgrounds.

Automated semantics must check labels, roles, selected/checked/heading state, custom
actions and progress. Separate focusable child actions must remain reachable. Never
replace the whole chat with one changing accessibility announcement. Actual TalkBack
order, focus entry/return and spoken repetition remain device observations.

## Theme and window coverage

Cover Default/System, Ember, Fern, Marine, Olive, Lavender, Sakura, Solarized, Nord and
Catppuccin. Ember is forced dark. The other nine themes have light/dark schemes; combine
each effective scheme with standard/high contrast: **38 effective frames**. Do not count
an impossible light Ember frame as acceptance, or remove System because the source used
historical purchase metadata to select some contrast cases. All themes are unlocked.

Use compact and expanded **current window sizes**, plus an in-place compact/expanded
resize preserving selected detail, draft and scroll state. Run every contrast frame;
pairwise selection may reduce unrelated locale/font/window combinations but must not
reduce those contrast pairs or the required 200%-font flow states.

| Flow / states | Automated native assertions required | Actual device/TalkBack observation required |
| --- | --- | --- |
| Shell: Chats, Nodes, Map, Tools, Settings; unread zero/overflow; compact/rail/list-detail | Tab role/selection/label, unread value once, heading, effective targets; resize retains stacks/detail/draft; reduced-motion transitions and native Back | Logical tab-to-content traversal, selected tab announcement, focus restoration on Back/resize, hidden badge not announced twice |
| Onboarding: scan/pairing, BLE/LAN permission denial, WiFi failure, setup sheets | Meaningful status/recovery labels, non-color failure text, 48dp actions; 200% labels and sheet controls remain reachable; denial does not disable cached navigation | Permission-dialog return focus, retry/cancel focus, keyboard Next/Done/Back and sheet dismissal |
| Direct/channel chat: cached/offline/empty, unread/day grouping, pending/delivered/failed retry, pagination | Per-message label/status, headings, named link actions, actual retry/send callbacks; all emitted spans versus painted surfaces; composer targets and 200% CJK/RTL/IME layout | Individual-message order, no whole-chat announcement on arrival, link/retry access, composer focus and menu/sheet return |
| Rooms: cluster-start/follow-up, sending/sent/failed, guest/read-only/disconnected, authentication | Author on each incoming row, outgoing status once, failed-only retry, open-link actions, labelled remember switch; sheet/body at 200% | Sender context when entering mid-cluster, authentication focus and switch purpose/state, non-gesture retry/link action |
| Nodes: list/search/filter/sort, discovery, detail, edit/share/delete | Filter selection, row/action labels, destructive confirmation, status text; effective row/menu targets; long/CJK names at 200% | Detail/Back focus, non-swipe action access, dialog cancellation and selection order |
| Map: populated/filter/list/selected marker, empty/offline/download/error | Non-gesture marker-list selection with coordinates, filter selection, progress/error text; control rows/list/callout at 200% | Map-to-list traversal, marker access without dragging, callout and offline panel entry/return focus |
| Tools: CLI, Rx log, noise-floor empty/running/stopped/error; LoS clear/blocked/relay | Labels and enabled/selected state, data-bound graph summaries/numeric alternatives, keyboard/non-gesture controls; semantic status versus color; 200% long metrics | Access to meaningful readings/clearance, no unreadable chart-only state, result/terminal focus and keyboard operation |
| Remote nodes: authentication, status/telemetry/history, unavailable sensor/location, map | Labelled settings controls; units/time/value/status text; data-bound history/graph alternatives; 200% columns and dialogs | Auth/status/map focus; each sensor and change understandable without color |
| Settings: appearance/effective schemes, backup valid/invalid/restore results, language/permissions/licenses | Theme/scheme selection, all unlocked options, error/result counts, headings and control labels; 200% cards/forms/dialogs | Selected theme announcement, language/permission return focus, restore confirmation/result focus |

Use deterministic fake-service data, clocks, local media/map inputs and meaningful native
screens. WP-502 owns its existing screenshot/flow surfaces. Reuse its admitted evidence
where exact candidate/state bindings match; coordinate new states rather than changing its
goldens here. No approved WP-503 feature screenshot baselines or tolerances were found.
`golden-policy.json` governs existing independent protocol/backup vectors and is not approval
to manufacture screenshot expectations.

## Frozen accessibility case families

These are **23 inspected source declarations**, not 23 executed Android tests or new WP-501
parity bindings. The four files are manifest cross-references for WP-503.

| Frozen source | Declarations / parameter requirements | Native audit relationship |
| --- | --- | --- |
| `MC1Tests/Theme/ThemeContrastTests.swift` | 6; source outgoing/hashtag 34 frames each, identity 15,466 name/frame parameters against both surfaces, category 102, distinct hues 9, glyph 3,800 | Existing `ThemeContrastTest` preserves these families and adds Material-role coverage. Execute it, then separately test actual feature-span and painted-surface use. |
| `MC1Tests/Views/Chats/Components/UnifiedMessageBubbleA11yLabelTests.swift` | 4; sender/body/footer fragments; outgoing status; fallback-match disclosure; stable repeated label | Check the real `ChatTimeline` merged row and reachable link/retry children, not just a pure label builder. Source footer/disclosure parity remains WP-501 ownership. |
| `MC1Tests/Views/Chats/Components/RoomMessageBubbleA11yLabelTests.swift` | 6; follow-up author; cluster-start author once; outgoing status once; failed retry callback; sent no retry; named HTTPS action | A503-01/02/03 identify missing production presentation. Test the repaired mounted room consumer, retaining all six distinctions. |
| `MC1Tests/Views/Chats/Linkify/MessageLinkAccessibilityTests.swift` | 7; document-order link kinds; contact/channel names; preview first; deduplication; cap; empty/nil | Reuse `MessageLinkAccessibility` and verify its custom actions on mounted direct/channel and repaired room nodes, including real dispatcher behavior. |

Existing `ThemeComposeTest`, `SharedUiComposeTest`, `SharedColorComposeTest` and
`NavigationComposeTest` contain useful native theme/shared/shell assertions. Their
previews and Robolectric API31 tests are not complete feature flows, actual TalkBack
observations or API37 device evidence. Record real discovered/run/passed/failed/error/skipped
counts from the admitted runner; missing, malformed or zero-test output must fail.

## Device evidence record

No physical/device record exists for this attempt. When available, retain only necessary
non-reproducible evidence: exact APK commit, API/device class, TalkBack version, font/locale/
window/animation settings, tested state/action, expected/observed result, sanitized screenshot
or recording location, observer and date. Do not retain identifiers, message contents, keys
or a duplicate reproducible CI result bundle. Automated semantics must be labeled separately
from this record.
