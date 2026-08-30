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

  @Binding var isAtBottom: Bool
  @Binding var unreadCount: Int

  /// Bumped by callers to pin the visual bottom. Honored only while `isAtBottom`
  /// is already true; `ScrollToBottomButton` calls `scrollPosition.scrollTo` itself.
  var scrollToBottomRequest: Int = 0

  /// Returns whether an appended row raises the unread badge while scrolled up.
  var countsTowardUnread: (Item) -> Bool = { _ in true }

  /// Bumped by callers to jump to `scrollTargetID` (mention / reply / deeplink / divider).
  var scrollToTargetRequest: Int = 0
  var scrollTargetID: Item.ID?

  /// One-shot item the list opens scrolled to on the first non-empty snapshot;
  /// nil opens at the bottom. Drives the library's initial scroll target.
  var initialScrollTargetID: Item.ID?

  /// Invoked when the top is reached, to page in older messages.
  var onLoadOlder: (@MainActor @Sendable () async -> Void)?

  /// Invoked once, on the first post-positioning geometry report, when an
  /// `initialScrollTargetID` was in effect — the point at which the library has
  /// consumed the one-shot target. Lets the owner retire a divider target so a
  /// later `.id` rebuild does not re-jump to it.
  var onInitialTargetConsumed: (() -> Void)?

  @Environment(\.appTheme) private var appTheme
  @Environment(\.colorScheme) private var colorScheme
  @Environment(\.colorSchemeContrast) private var colorSchemeContrast
  @Environment(\.dynamicTypeSize) private var dynamicTypeSize

  @State private var scrollPosition: TiledScrollPosition
  @State private var host = CellContentHost<Item, Content>()
  @State private var newestID: Item.ID?
  @State private var hasConsumedInitialGeometry = false

  init(
    items: [Item],
    cellContent: @escaping (Item) -> Content,
    contentBackground: Color? = nil,
    isAtBottom: Binding<Bool>,
    unreadCount: Binding<Int>,
    scrollToBottomRequest: Int = 0,
    countsTowardUnread: @escaping (Item) -> Bool = { _ in true },
    scrollToTargetRequest: Int = 0,
    scrollTargetID: Item.ID? = nil,
    initialScrollTargetID: Item.ID? = nil,
    onLoadOlder: (@MainActor @Sendable () async -> Void)? = nil,
    onInitialTargetConsumed: (() -> Void)? = nil
  ) {
    self.items = items
    self.cellContent = cellContent
    self.contentBackground = contentBackground
    _isAtBottom = isAtBottom
    _unreadCount = unreadCount
    self.scrollToBottomRequest = scrollToBottomRequest
    self.countsTowardUnread = countsTowardUnread
    self.scrollToTargetRequest = scrollToTargetRequest
    self.scrollTargetID = scrollTargetID
    self.initialScrollTargetID = initialScrollTargetID
    self.onLoadOlder = onLoadOlder
    self.onInitialTargetConsumed = onInitialTargetConsumed
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
      // accumulate as unread (counted in the onChange below). The first report
      // after a target open reflects the resting position, not a user scroll, so
      // it consumes the one-shot target instead of arming follow.
      if hasConsumedInitialGeometry || initialScrollTargetID == nil {
        scrollPosition.autoScrollsToBottomOnAppend = atBottom
      } else {
        onInitialTargetConsumed?()
      }
      hasConsumedInitialGeometry = true
    }
    .onDragIntoBottomSafeArea {
      UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
    .background(contentBackground ?? .clear)
    .id(appearanceIdentity)
    .overlay(alignment: .bottomTrailing) {
      ScrollToBottomButton(
        isVisible: !isAtBottom,
        unreadCount: unreadCount,
        onTap: { scrollPosition.scrollTo(edge: .bottom) }
      )
      .padding(.trailing, 16)
      .padding(.bottom, 8)
    }
    .onChange(of: scrollToBottomRequest) {
      guard isAtBottom else { return }
      scrollPosition.scrollTo(edge: .bottom, animated: false)
    }
    .onChange(of: scrollToTargetRequest) {
      guard let id = scrollTargetID else { return }
      scrollPosition.scrollTo(id: id)
    }
    .onChange(of: items.last?.id, initial: true) { _, latest in
      defer { newestID = latest }
      guard !isAtBottom, let previous = newestID,
            let previousIndex = items.firstIndex(where: { $0.id == previous }) else { return }
      let incoming = items.suffix(from: previousIndex + 1).filter(countsTowardUnread).count
      if incoming > 0 { unreadCount += incoming }
    }
  }

  /// Fingerprint of theme + appearance. A change fully rebuilds the list (via `.id`) so the
  /// baked bubble colors repaint — the library does not reconfigure cells when only the
  /// environment changes.
  private var appearanceIdentity: String {
    let appearance = AppearanceToken.make(
      colorScheme: colorScheme,
      contrast: colorSchemeContrast,
      dynamicTypeSize: dynamicTypeSize
    )
    return "\(appTheme.id)|\(appearance)"
  }
}
