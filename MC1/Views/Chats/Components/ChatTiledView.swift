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

  /// Bumped by callers to scroll to the visual bottom (e.g. on send).
  var scrollToBottomRequest: Int = 0

  /// Bumped by callers to jump to `scrollTargetID` (mention / reply / deeplink / divider).
  var scrollToTargetRequest: Int = 0
  var scrollTargetID: Item.ID?

  /// One-shot item the list opens scrolled to on the first non-empty snapshot;
  /// nil opens at the bottom. Drives the library's initial scroll target.
  var initialScrollTargetID: Item.ID?

  /// Invoked when the top is reached, to page in older messages.
  var onLoadOlder: (@MainActor @Sendable () async -> Void)?

  @State private var scrollPosition: TiledScrollPosition
  @State private var host = CellContentHost<Item, Content>()
  @State private var newestID: Item.ID?

  init(
    items: [Item],
    cellContent: @escaping (Item) -> Content,
    contentBackground: Color? = nil,
    appearanceIdentity: String = "",
    isAtBottom: Binding<Bool>,
    unreadCount: Binding<Int>,
    scrollToBottomRequest: Int = 0,
    scrollToTargetRequest: Int = 0,
    scrollTargetID: Item.ID? = nil,
    initialScrollTargetID: Item.ID? = nil,
    onLoadOlder: (@MainActor @Sendable () async -> Void)? = nil
  ) {
    self.items = items
    self.cellContent = cellContent
    self.contentBackground = contentBackground
    self.appearanceIdentity = appearanceIdentity
    _isAtBottom = isAtBottom
    _unreadCount = unreadCount
    self.scrollToBottomRequest = scrollToBottomRequest
    self.scrollToTargetRequest = scrollToTargetRequest
    self.scrollTargetID = scrollTargetID
    self.initialScrollTargetID = initialScrollTargetID
    self.onLoadOlder = onLoadOlder
    // Open at the bottom by default; with an initial target present, hold off
    // append-follow until the geometry callback re-derives it from the resting
    // position, so an append during open does not fight the target.
    _scrollPosition = State(initialValue: TiledScrollPosition(
      autoScrollsToBottomOnAppend: initialScrollTargetID == nil,
      scrollsToBottomOnReplace: true
    ))
  }

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
    .initialScrollTarget(id: initialScrollTargetID.map { AnyHashable($0) }, anchor: .top)
    .onTiledScrollGeometryChange { geometry in
      let atBottom = geometry.pointsFromBottom < ChatScrollConstants.bottomDetectionThreshold
      if atBottom != isAtBottom { isAtBottom = atBottom }
      if atBottom, unreadCount != 0 { unreadCount = 0 }
      // Only follow appends while near the bottom; otherwise new messages
      // accumulate as unread (counted in the onChange below).
      scrollPosition.autoScrollsToBottomOnAppend = atBottom
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
      defer { newestID = latest }
      guard !isAtBottom, let previous = newestID,
            let previousIndex = items.firstIndex(where: { $0.id == previous }) else { return }
      let appended = items.count - 1 - previousIndex
      if appended > 0 { unreadCount += appended }
    }
  }
}
