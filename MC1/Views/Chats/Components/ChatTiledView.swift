import MessagingUI
import SwiftUI
import UIKit

/// Chat scroll container backed by `MessagingUI.TiledView`.
///
/// Replaces the bespoke flipped `UITableView`: the library provides stable
/// prepend (no scroll jump when paging older messages) and auto-scroll-to-bottom
/// on append. Consumers keep passing the same items array and cell-content
/// closure they used with the old table.
struct ChatTiledView<Item: Identifiable & Hashable & Sendable, Content: View>: View where Item.ID == UUID {
  let items: [Item]
  let cellContent: (Item) -> Content

  /// Themed canvas color; `nil` leaves the background transparent so the
  /// surrounding surface shows through.
  var contentBackground: Color?

  /// Fingerprint of theme + appearance. A change fully rebuilds the list so the
  /// baked bubble colors repaint — the library does not reconfigure cells when
  /// only the environment changes.
  var appearanceIdentity: String = ""

  @Binding var isAtBottom: Bool
  @Binding var unreadCount: Int

  /// Counter the owner bumps only for live single-message appends. When set, a
  /// tail change whose value matches the last seen one is a bulk catch-up
  /// reload (e.g. reopening a chat that received messages while off-screen) and
  /// is scrolled without animation. `nil` (rooms) treats every append as live.
  var liveAppendGeneration: Int?

  /// Bumped by callers to scroll to the visual bottom (e.g. on send).
  var scrollToBottomRequest: Int = 0

  /// Bumped by callers to jump to `scrollTargetID` (mention / reply / deeplink / divider).
  var scrollToTargetRequest: Int = 0
  var scrollTargetID: Item.ID?

  /// Invoked when the top is reached, to page in older messages.
  var onLoadOlder: (@MainActor @Sendable () async -> Void)?

  /// Auto-scroll-on-append is driven manually (see `onChange(of: items.last?.id)`)
  /// so a live append can spring-scroll while a catch-up reload jumps silently;
  /// the library's built-in append scroll is always animated, so it stays off.
  @State private var scrollPosition = TiledScrollPosition(
    autoScrollsToBottomOnAppend: false,
    scrollsToBottomOnReplace: true
  )
  @State private var host = CellContentHost<Item, Content>()
  @State private var newestID: Item.ID?
  @State private var lastSeenLiveAppendGeneration: Int?

  /// A catch-up reload appended content below the fold; jump to the true bottom
  /// once the appended cells have laid out (their height is only known then).
  @State private var pendingBottomPin = false

  /// The tiled layout reports a stale, oversized `pointsFromBottom` before it
  /// settles the initial bottom-anchored position, which would flash the button
  /// on load. Ignore "not at bottom" until we've seen the settled bottom once.
  @State private var hasSettledAtBottom = false

  var body: some View {
    host.content = cellContent

    return TiledView(items: items, scrollPosition: $scrollPosition) { item in
      ChatTiledCell(item: item, host: host)
    }
    .prependLoader(onLoadOlder.map { load in
      .loader(perform: load) {
        ProgressView().padding(.vertical, 8)
      }
    })
    .onTiledScrollGeometryChange { geometry in
      let atBottom = geometry.pointsFromBottom < ChatScrollConstants.bottomDetectionThreshold
      // Resolve a queued catch-up jump first: the reload's cells have now laid
      // out, so the true bottom offset is known. Handled before the settle
      // guard so an off-bottom reload cannot get stuck waiting to settle.
      if pendingBottomPin {
        guard atBottom else {
          scrollPosition.scrollTo(edge: .bottom, animated: false)
          return
        }
        pendingBottomPin = false
        hasSettledAtBottom = true
      }
      if !hasSettledAtBottom {
        guard atBottom else { return }
        hasSettledAtBottom = true
      }
      if atBottom != isAtBottom { isAtBottom = atBottom }
      if atBottom, unreadCount != 0 { unreadCount = 0 }
    }
    .onDragIntoBottomSafeArea {
      UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
    .background(contentBackground ?? .clear)
    .id(appearanceIdentity)
    .onChange(of: scrollToBottomRequest) { scrollPosition.scrollTo(edge: .bottom) }
    .onChange(of: scrollToTargetRequest) {
      guard let id = scrollTargetID else { return }
      scrollPosition.scrollTo(id: id)
    }
    .onChange(of: items.last?.id, initial: true) { _, latest in
      // A live append (owner bumped the generation) rides down with a spring;
      // an unchanged generation means a bulk catch-up reload, which is pinned to
      // the bottom without animation. Rooms pass no generation, so every append
      // is treated as live, preserving their spring-scroll behavior.
      let wasLiveAppend = liveAppendGeneration.map { $0 != lastSeenLiveAppendGeneration } ?? true
      defer {
        newestID = latest
        lastSeenLiveAppendGeneration = liveAppendGeneration
      }
      guard let previous = newestID,
            let previousIndex = items.firstIndex(where: { $0.id == previous }) else { return }
      let appended = items.count - 1 - previousIndex
      guard appended > 0 else { return }

      if isAtBottom {
        if wasLiveAppend {
          scrollPosition.scrollTo(edge: .bottom, animated: true)
        } else {
          pendingBottomPin = true
        }
      } else if wasLiveAppend {
        unreadCount += appended
      }
    }
  }
}
