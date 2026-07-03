import os
import SwiftUI
import UIKit

/// Logs the bottom safe-area inset across keyboard transitions so a residual
/// inset that fails to collapse after dismissal can be diagnosed.
private let chatKeyboardLogger = Logger(subsystem: "com.mc1", category: "ChatKeyboard")

/// Cell that pins its safe-area insets to zero. The edge-to-edge flipped table (iOS 26) spans
/// behind the nav/input bars and reserves their heights via `contentInset`, so a cell scrolling
/// under a bar overlaps the real safe area. Left alone, that inset propagates into the cell's
/// `UIHostingConfiguration`, which bakes it into the bubble's self-sized height; UITableView then
/// caches the inflated height, leaving a permanent gap after the cell scrolls back. Zeroing the
/// cell's safe area keeps every bubble measured at its true content height regardless of position.
private final class ChatHostingCell: UITableViewCell {
  override var safeAreaInsets: UIEdgeInsets {
    .zero
  }
}

/// UIKit table view controller with flipped orientation for chat-style scrolling
/// Newest messages appear at visual bottom, keyboard handling via native UIKit
@MainActor
final class ChatTableViewController<Item: Identifiable & Hashable & Sendable, CellContent: View>: UITableViewController, UIContextMenuInteractionDelegate, UIGestureRecognizerDelegate where Item.ID == UUID {
  // MARK: - Types

  private enum Section: Hashable {
    case main
  }

  private struct SnapshotApplyRequest {
    var snapshot: NSDiffableDataSourceSnapshot<Section, Item.ID>
    var animatingDifferences: Bool
    var completion: (() -> Void)?
    /// `true` when this request was issued by `reconfigureAllItems` (live theme switch).
    /// The pending-slot coalescer carries this flag — and a re-applied
    /// `reconfigureItems(allCurrentIDs)` — forward into any superseding request so the
    /// reconfigure intent isn't silently dropped when latest-wins overwrites pendingSnapshot.
    var reconfigureAll: Bool = false
  }

  // MARK: - Properties

  private var items: [Item] = []
  /// O(1) lookup for items by ID (replaces O(n) first(where:) in cell provider)
  private var itemsByID: [Item.ID: Item] = [:]
  /// O(1) index lookup for scroll-to-item (replaces O(n) firstIndex(where:))
  private var itemIndexByID: [Item.ID: Int] = [:]
  private var cellContentProvider: ((Item) -> CellContent)?
  var defaultTableBackgroundColor: UIColor?
  private var dataSource: UITableViewDiffableDataSource<Section, Item.ID>?
  /// Snapshot scheduled while a previous apply was still running. Latest
  /// wins: when a new request arrives mid-apply, it replaces this field
  /// and the intermediate snapshot is skipped. Diffable data source
  /// derives the visual result from the final snapshot alone, so
  /// dropping intermediates is safe.
  private var pendingSnapshot: SnapshotApplyRequest?
  /// Completions from snapshot requests whose snapshot was superseded
  /// before it landed. They still need to run because callers (notably
  /// the pagination prepend path) use them to restore the anchor row's
  /// viewport position after layout settles; the anchor row is part of
  /// the superseding snapshot too, so measuring against the post-apply
  /// layout is correct. Drained in order after the latest apply lands.
  private var pendingCompletions: [() -> Void] = []

  /// Bundled interaction/intent/apply/deferred axes for the scroll surface.
  private(set) var scrollState: ChatScrollState = .idle

  /// Tracks scroll position relative to bottom
  private(set) var isAtBottom: Bool = true

  /// Count of unread messages (messages added while scrolled up)
  private(set) var unreadCount: Int = 0

  /// ID of last message user has seen (for unread tracking)
  private var lastSeenItemID: Item.ID?

  /// Callback when scroll state changes
  var onScrollStateChanged: ((Bool, Int) -> Void)?

  /// Callback when user scrolls near the top (oldest messages). The closure receives a release
  /// callback the consumer must invoke when pagination work completes (success or short-circuit)
  /// so the request latch clears even when the view model's isLoadingOlder never visibly flips.
  var onNearTop: ((@escaping @MainActor () -> Void) -> Void)?

  /// Whether pagination is in progress (skip auto-scroll during pagination)
  var isLoadingOlderMessages = false

  /// Suppresses duplicate onNearTop fires while the view model's isLoadingOlder propagates back through SwiftUI
  private var isNearTopRequestInFlight = false

  /// Callback when a mention becomes visible. Returns whether the seen-state was
  /// persisted; a false result means the id must not stay marked, so it can re-fire.
  var onMentionBecameVisible: ((Item.ID) async -> Bool)?

  /// Callback when a row receives a secondary (right) click from a pointer. On Mac the click
  /// reaches the context-menu system (`UIContextMenuInteraction`); on iPad a trackpad or mouse
  /// secondary click arrives as an indirect-pointer tap, handled by a dedicated recognizer. Touch
  /// long-press is never routed here (it opens the sheet through the bubble's own gesture), so the
  /// two paths can't double-trigger. Surfaces that leave this nil install neither, so they don't
  /// suppress the native context menu in exchange for nothing.
  var onSecondaryClick: ((Item) -> Void)? {
    didSet {
      installMacSecondaryClickIfNeeded()
      installIPadSecondaryClickIfNeeded()
    }
  }

  /// Guards the one-time install of the Mac secondary-click interaction.
  private var hasInstalledMacSecondaryClick = false

  /// Guards the one-time install of the iPad secondary-click recognizer.
  private var hasInstalledIPadSecondaryClick = false

  /// Closure to check if an item contains an unseen self-mention
  var isUnseenMention: ((Item) -> Bool)?

  /// Item ID of the new messages divider (for visibility tracking)
  var dividerItemID: Item.ID?

  /// Callback when the divider row's visibility changes
  var onDividerVisibilityChanged: ((Bool) -> Void)?

  /// Last reported divider visibility (change detection to avoid redundant callbacks)
  private var lastDividerVisible: Bool?

  /// The full set of unseen self-mention ids, the source for the off-screen subset.
  var unseenMentionIDs: [Item.ID] = []

  /// Reports the unseen mentions not currently on screen, ordered oldest-to-newest as in
  /// `unseenMentionIDs`. Their count drives the scroll-to-mention button; the last (newest) is
  /// its scroll target.
  var onOffscreenMentionsChanged: (([Item.ID]) -> Void)?

  /// Last reported off-screen mentions (change detection to avoid redundant callbacks)
  private var lastReportedOffscreenMentionIDs: [Item.ID]?

  /// Tracks mention IDs that have already been reported as visible (prevents duplicate callbacks)
  private var markedMentionIDs: Set<Item.ID> = []

  private var pendingScrollTargetID: Item.ID?
  private var pendingScrollTask: Task<Void, Never>?
  private var checkVisibleMentionsTask: Task<Void, Never>?

  /// Latest-wins buffer for updateItems calls received mid-drag. Applying a
  /// snapshot mid-drag shifts contentOffset and fights the gesture; draining
  /// on drag-end lets the offset adjust on settled content.
  private var deferredItemsApply: (newItems: [Item], animated: Bool)?

  /// Coalesces the four scroll-tracking callbacks
  /// (`updateIsAtBottom`, `checkVisibleMentions`, `checkDividerVisibility`,
  /// `checkNearTop`) to at most one invocation per display frame.
  /// `scrollViewDidScroll` only sets a flag; the display link's tick drains it.
  private var hasPendingScrollObservation = false
  private var scrollDisplayLink: CADisplayLink?
  private let scrollDisplayLinkProxy = ChatScrollDisplayLinkProxy()

  /// Target item ID for programmatic scroll, derived from the active scroll intent.
  private var scrollTargetItemID: Item.ID? {
    if case let .toTarget(id) = scrollState.intent { return id }
    return nil
  }

  /// True when an auto-scroll-to-bottom was suppressed because the user was interacting.
  /// Fired on drag end so messages arriving mid-drag aren't silently dropped.
  var deferredScrollToBottomPending: Bool {
    scrollState.deferredScroll != nil
  }

  /// Count of messages deferred while user is interacting; counted as unread if they release scrolled away.
  var deferredScrollMessageCount: Int {
    scrollState.deferredScroll?.targetMessageCount ?? 0
  }

  // MARK: - Lifecycle

  override func viewDidLoad() {
    super.viewDidLoad()

    // Flip the table view for chat-style bottom anchoring
    tableView.transform = CGAffineTransform(scaleX: 1, y: -1)

    // UIKit keyboard handling - bypasses SwiftUI bugs
    tableView.keyboardDismissMode = .onDrag

    // Visual setup
    tableView.separatorStyle = .none
    // estimatedRowHeight intentionally unset: UIHostingConfiguration
    // self-sizing produces an exact contentSize, so pagination prepends
    // don't shift contentOffset as estimates are replaced with measurements.

    // Flipped table (scaleX: 1, y: -1) inverts top/bottom, so automatic
    // content-inset adjustment applies safe-area padding to the wrong edges.
    // SwiftUI's .safeAreaInset already handles the input bar, so disable UIKit's.
    tableView.contentInsetAdjustmentBehavior = .never

    if #available(iOS 26.0, *) {
      // Clear and non-opaque allows Liquid Glass effects on nav/input bars
      tableView.backgroundColor = .clear
      tableView.isOpaque = false

      // Scroll edge effects don't work correctly with flipped table transform.
      // Hide both - the nav bar and input bar provide their own Liquid Glass blur.
      tableView.topEdgeEffect.isHidden = true
      tableView.bottomEdgeEffect.isHidden = true
    } else {
      tableView.backgroundColor = .systemBackground
    }
    tableView.allowsSelection = false

    // Register cell
    tableView.register(ChatHostingCell.self, forCellReuseIdentifier: "Cell")

    // Configure data source
    configureDataSource()

    // Manual keyboard observation (UIKit auto-adjustment doesn't work in SwiftUI embed)
    setupKeyboardObservers()

    // Coalesces scroll-tracking callbacks at display-frame cadence
    setupScrollDisplayLink()
  }

  /// Installs the Mac secondary-click interaction the first time a handler is set. Gated on Mac and
  /// on a handler existing, so iPad uses its own recognizer instead and a surface without a handler
  /// (a room with no actions sheet) leaves the native context menu untouched.
  private func installMacSecondaryClickIfNeeded() {
    guard ProcessInfo.processInfo.isiOSAppOnMac,
          !hasInstalledMacSecondaryClick, onSecondaryClick != nil, isViewLoaded else { return }
    hasInstalledMacSecondaryClick = true
    tableView.addInteraction(UIContextMenuInteraction(delegate: self))
  }

  /// Installs the iPad secondary-click recognizer the first time a handler is set. A trackpad or
  /// mouse secondary click arrives as an indirect-pointer event, not through the context-menu
  /// system, so the Mac `UIContextMenuInteraction` never fires for it. `buttonMaskRequired` plus
  /// the `shouldReceive` guard scope the recognizer to a sole secondary click, so a finger touch
  /// (including the bubble long-press) is never delivered here and the two paths can't
  /// double-trigger the sheet.
  private func installIPadSecondaryClickIfNeeded() {
    guard !ProcessInfo.processInfo.isiOSAppOnMac,
          !hasInstalledIPadSecondaryClick, onSecondaryClick != nil, isViewLoaded else { return }
    hasInstalledIPadSecondaryClick = true
    let recognizer = UITapGestureRecognizer(target: self, action: #selector(handleSecondaryClick(_:)))
    recognizer.buttonMaskRequired = .secondary
    recognizer.cancelsTouchesInView = false
    recognizer.delegate = self
    tableView.addGestureRecognizer(recognizer)
  }

  /// Resolves the model item under a point in the table view's coordinate space.
  private func itemForRow(at point: CGPoint) -> Item? {
    guard let indexPath = tableView.indexPathForRow(at: point),
          let itemID = dataSource?.itemIdentifier(for: indexPath) else { return nil }
    return itemsByID[itemID]
  }

  /// On the class, not an extension: a generic class can carry `@objc` conformance
  /// only in its primary declaration.
  func contextMenuInteraction(
    _ interaction: UIContextMenuInteraction,
    configurationForMenuAtLocation location: CGPoint
  ) -> UIContextMenuConfiguration? {
    guard let item = itemForRow(at: location) else { return nil }
    onSecondaryClick?(item)
    // No configuration suppresses the native menu, leaving the sheet the only actions surface.
    return nil
  }

  @objc private func handleSecondaryClick(_ recognizer: UITapGestureRecognizer) {
    guard let item = itemForRow(at: recognizer.location(in: tableView)) else { return }
    onSecondaryClick?(item)
  }

  /// Scope the iPad recognizer to a sole secondary-button click. A direct touch reports an empty
  /// button mask, so this rejects finger presses (and the bubble long-press), leaving an
  /// indirect-pointer secondary click the only event that reaches `handleSecondaryClick`. The mask
  /// is read from the event because the recognizer's own mask isn't updated with it yet here.
  func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive event: UIEvent) -> Bool {
    event.buttonMask == .secondary
  }

  func gestureRecognizer(
    _ gestureRecognizer: UIGestureRecognizer,
    shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
  ) -> Bool {
    true
  }

  /// Swift 6.3.2 EarlyPerfInliner crashes (infinite recursion in
  /// `isCallerAndCalleeLayoutConstraintsCompatible`) when optimizing this
  /// generic UITableViewController subclass's deinit under -O. Opting the
  /// deinit out of optimization sidesteps the crash without changing
  /// runtime behavior. Drop the attribute once a future Swift release
  /// fixes the underlying inliner bug.
  @_optimize(none)
  isolated deinit {
    NotificationCenter.default.removeObserver(self)
    scrollDisplayLink?.invalidate()
    checkVisibleMentionsTask?.cancel()
  }

  // MARK: - Keyboard Handling

  private func setupKeyboardObservers() {
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(keyboardWillShow(_:)),
      name: UIResponder.keyboardWillShowNotification,
      object: nil
    )
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(keyboardWillHide(_:)),
      name: UIResponder.keyboardWillHideNotification,
      object: nil
    )
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(keyboardWillChangeFrame(_:)),
      name: UIResponder.keyboardWillChangeFrameNotification,
      object: nil
    )
  }

  private func keyboardFrameEnd(_ notification: Notification) -> CGRect? {
    notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect
  }

  @objc private func keyboardWillHide(_ notification: Notification) {
    let frameEnd = keyboardFrameEnd(notification) ?? .null
    chatKeyboardLogger.debug(
      "keyboardWillHide frameEnd=\(frameEnd.debugDescription, privacy: .public) safeAreaBottom=\(self.view.safeAreaInsets.bottom, privacy: .public)"
    )
  }

  @objc private func keyboardWillChangeFrame(_ notification: Notification) {
    let frameEnd = keyboardFrameEnd(notification) ?? .null
    chatKeyboardLogger.debug(
      "keyboardWillChangeFrame frameEnd=\(frameEnd.debugDescription, privacy: .public) safeAreaBottom=\(self.view.safeAreaInsets.bottom, privacy: .public)"
    )
  }

  override func viewSafeAreaInsetsDidChange() {
    super.viewSafeAreaInsetsDidChange()
    chatKeyboardLogger.debug(
      "viewSafeAreaInsetsDidChange bottom=\(self.view.safeAreaInsets.bottom, privacy: .public)"
    )
  }

  // MARK: - Scroll Coalescing

  /// Creates the `CADisplayLink` that drains coalesced scroll observations.
  /// The link retains its target (the proxy), not the controller, so the
  /// `deinit` path stays clean. Starts paused; `scrollViewDidScroll` unpauses.
  private func setupScrollDisplayLink() {
    scrollDisplayLinkProxy.onTick = { [weak self] in
      self?.coalescedScrollTick()
    }
    let link = CADisplayLink(
      target: scrollDisplayLinkProxy,
      selector: #selector(ChatScrollDisplayLinkProxy.tick(_:))
    )
    link.add(to: .main, forMode: .common)
    link.isPaused = true
    scrollDisplayLink = link
  }

  /// Drains pending scroll observations once per display frame. If a callback
  /// re-arms the flag during processing, the link stays unpaused so the next
  /// frame picks it up; otherwise the link pauses to avoid waking the run loop.
  private func coalescedScrollTick() {
    let hadWork = hasPendingScrollObservation
    hasPendingScrollObservation = false
    if hadWork {
      updateIsAtBottom()
      let visible = visibleItems()
      checkVisibleMentions(visible: visible)
      reportOffscreenMentions(visible: visible)
      checkDividerVisibility()
      checkNearTop()
    }
    if !hasPendingScrollObservation {
      scrollDisplayLink?.isPaused = true
    }
  }

  #if DEBUG
    /// Drains pending scroll observations synchronously. Production code
    /// relies on the display link; this entry point lets unit tests verify
    /// scroll callbacks without waiting for a real frame tick.
    func flushScrollObservationsForTests() {
      coalescedScrollTick()
    }
  #endif

  #if DEBUG
    /// Exposes snapshot-derived scroll-row resolution so unit tests can assert that
    /// scroll targets are sourced from the applied snapshot (nil-safe for ids not
    /// yet applied) rather than the controller's leading items model.
    func resolvedScrollRowForTests(id: Item.ID) -> IndexPath? {
      snapshotRow(for: id)
    }
  #endif

  #if DEBUG
    /// Advances the items model (items/itemsByID/itemIndexByID) without applying a
    /// diffable snapshot, reproducing the model-ahead-of-snapshot state the apply-lag
    /// window produces — where a model-derived row can exceed the applied row count
    /// and abort scrollToRow. Tests use this to assert scroll-row resolution reads the
    /// applied snapshot (nil for ids not yet applied, in-bounds for applied ids)
    /// rather than the leading model. Mirrors the synchronous model mutation at the
    /// top of updateItems.
    func advanceItemsModelWithoutApplyingForTests(_ newItems: [Item]) {
      items = newItems
      itemsByID = Dictionary(uniqueKeysWithValues: newItems.map { ($0.id, $0) })
      itemIndexByID = Dictionary(uniqueKeysWithValues: newItems.enumerated().map { ($0.element.id, $0.offset) })
    }
  #endif

  #if DEBUG
    /// Identifiers reconfigured by every snapshot actually applied, accumulated across applies.
    /// Lets unit tests assert that a targeted reconfigure survives the pending-slot coalescer
    /// when its request is superseded mid-apply, reaching an applied snapshot.
    private(set) var appliedReconfiguredItemIDsForTests: [Item.ID] = []

    /// Forces the controller into the in-flight-apply state so subsequent `updateItems`
    /// calls park in the pending slot instead of applying immediately. Lets tests open
    /// the supersession window deterministically without a real animated apply in flight.
    func beginApplyingForTests() {
      scrollState.startApplying()
    }

    /// Drains the pending snapshot queue, applying the surviving request synchronously
    /// (no window means non-animated applies). Pairs with `beginApplyingForTests`.
    func drainPendingForTests() {
      drainSnapshotQueue()
    }
  #endif

  @objc private func keyboardWillShow(_ notification: Notification) {
    guard let userInfo = notification.userInfo,
          userInfo[UIResponder.keyboardFrameEndUserInfoKey] is CGRect else {
      return
    }

    chatKeyboardLogger.debug(
      "keyboardWillShow frameEnd=\((userInfo[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect ?? .null).debugDescription, privacy: .public) safeAreaBottom=\(self.view.safeAreaInsets.bottom, privacy: .public)"
    )

    let wasAtBottom = isAtBottom

    // SwiftUI handles frame changes for keyboard, so we don't add content inset.
    // Just scroll to bottom after layout settles if we were at bottom.
    if wasAtBottom {
      // Set intent now to prevent scroll delegate from reacting to contentOffset
      // oscillations during keyboard animation. Critical when content is shorter
      // than visible area - the bouncing would otherwise cause isAtBottom to flip.
      scrollState.startIntent(.toBottom)

      // Delay to let SwiftUI complete its layout pass
      Task { @MainActor [weak self] in
        try? await Task.sleep(for: ChatScrollConstants.layoutSettleDelay)
        self?.scrollToBottom(animated: true)
      }
    }
  }

  // MARK: - Configuration

  func configure(cellContent: @escaping (Item) -> CellContent) {
    cellContentProvider = cellContent
  }

  // MARK: - Data Source

  /// Row for an item in the *applied* diffable snapshot, or nil if the snapshot
  /// has not yet caught up with the controller's items model. updateItems mutates
  /// items/itemIndexByID synchronously while the snapshot apply can lag (queued
  /// behind an in-flight apply), so model-derived rows can exceed the table's
  /// applied row count and abort scrollToRow. All scroll-row lookups must go
  /// through here. Mirrors the snapshot-derived lookup in restorePrependAnchor.
  private func snapshotRow(for id: Item.ID) -> IndexPath? {
    dataSource?.indexPath(for: id)
  }

  private func configureDataSource() {
    dataSource = UITableViewDiffableDataSource<Section, Item.ID>(tableView: tableView) { [weak self] tableView, indexPath, itemID in
      guard let self,
            let item = itemsByID[itemID] else {
        return UITableViewCell()
      }

      let cell = tableView.dequeueReusableCell(withIdentifier: "Cell", for: indexPath)

      // Flip cell back to normal orientation (must be cell, not contentView,
      // because UIHostingConfiguration replaces contentView hierarchy)
      cell.transform = CGAffineTransform(scaleX: 1, y: -1)
      cell.backgroundColor = .clear
      cell.selectionStyle = .none
      // Allow long-press scale-up and shadow on message bubbles to extend
      // past the cell frame instead of being clipped at cell edges.
      cell.clipsToBounds = false
      cell.contentView.clipsToBounds = false

      // Embed SwiftUI content
      if let contentProvider = cellContentProvider {
        if #available(iOS 26.0, *) {
          cell.contentConfiguration = UIHostingConfiguration {
            contentProvider(item)
          }
          .margins(.all, 0)
          .minSize(width: 0, height: 0)
          .background(.clear)
        } else {
          cell.contentConfiguration = UIHostingConfiguration {
            contentProvider(item)
          }
          .margins(.all, 0)
          .minSize(width: 0, height: 0)
        }
      }

      return cell
    }
  }

  // MARK: - Update Items

  /// When true, updateItems will skip auto-scroll (caller will scroll explicitly)
  private var skipAutoScroll = false

  private func applySnapshot(
    _ snapshot: NSDiffableDataSourceSnapshot<Section, Item.ID>,
    animatingDifferences: Bool,
    completion: (() -> Void)? = nil,
    reconfigureAll: Bool = false
  ) {
    var request = SnapshotApplyRequest(
      snapshot: snapshot,
      animatingDifferences: animatingDifferences,
      completion: completion,
      reconfigureAll: reconfigureAll
    )

    if scrollState.isApplyingSnapshot {
      if let existing = pendingSnapshot {
        // Latest-wins for the snapshot itself, but preserve the superseded
        // request's completion so prepend anchor restores still fire after
        // the final apply lands.
        if let superseded = existing.completion {
          pendingCompletions.append(superseded)
        }
        // Carry the superseded snapshot's reconfigure intent forward into the
        // incoming snapshot so latest-wins doesn't silently drop a repaint. A
        // content-only reconfigure parked here would otherwise never reach an
        // applied snapshot, stranding its cell on stale content. Only items still
        // present in the incoming snapshot are carried; superseded-then-deleted
        // items are gone regardless.
        let carriedReconfigures = existing.snapshot.reconfiguredItemIdentifiers
          .filter { request.snapshot.itemIdentifiers.contains($0) }
        if !carriedReconfigures.isEmpty {
          request.snapshot.reconfigureItems(carriedReconfigures)
        }
        // A reconfigure-all (live theme switch) additionally repaints rows added by
        // the incoming snapshot, so extend the intent to its full identifier set.
        if existing.reconfigureAll {
          request.snapshot.reconfigureItems(request.snapshot.itemIdentifiers)
          request.reconfigureAll = true
        }
      }
      // The opposite ordering is intentionally not mirrored: an incoming reconfigure-all
      // replacing a pending content snapshot adopts the reconfigure's (already-applied)
      // identifier set, so the superseded snapshot's not-yet-applied items wait for the next
      // apply. That is safe because a theme change also drives buildItems() with the full
      // current set within a few frames, so the deferred rows reappear without a visible gap.
      pendingSnapshot = request
      return
    }

    applySnapshotRequest(request)
  }

  private func applySnapshotRequest(_ request: SnapshotApplyRequest) {
    guard let dataSource else {
      pendingSnapshot = nil
      pendingCompletions.removeAll()
      scrollState.endApplying()
      return
    }

    #if DEBUG
      appliedReconfiguredItemIDsForTests.append(contentsOf: request.snapshot.reconfiguredItemIdentifiers)
    #endif

    scrollState.startApplying()
    let shouldAnimate = request.animatingDifferences && view.window != nil

    if shouldAnimate {
      dataSource.apply(request.snapshot, animatingDifferences: true) { [weak self] in
        Task { @MainActor [weak self] in
          request.completion?()
          self?.drainSnapshotQueue()
        }
      }
    } else {
      dataSource.apply(request.snapshot, animatingDifferences: false)
      request.completion?()
      drainSnapshotQueue()
    }
  }

  private func drainSnapshotQueue() {
    scrollState.endApplying()
    // Loop in case a new pending snapshot is enqueued while draining;
    // each iteration applies the latest request and clears the field.
    while let next = pendingSnapshot {
      pendingSnapshot = nil
      applySnapshotRequest(next)
    }
    // Fire superseded completions only when truly idle. An animated apply
    // re-enters `scrollState.startApplying()` and finishes its drain in an
    // async callback; firing now would run the completions before the
    // final layout settles. The async callback will re-enter this method
    // and reach the idle branch then.
    if !scrollState.isApplyingSnapshot, !pendingCompletions.isEmpty {
      let completions = pendingCompletions
      pendingCompletions.removeAll()
      for completion in completions {
        completion()
      }
    }
  }

  private struct PrependAnchor {
    let itemID: Item.ID
    /// rect.minY - contentOffset.y at capture time (viewport-relative position)
    let distanceFromContentOffset: CGFloat
  }

  private func capturePrependAnchor(in oldItems: [Item]) -> PrependAnchor? {
    guard let visibleRows = tableView.indexPathsForVisibleRows, !visibleRows.isEmpty else {
      return nil
    }
    let midIndexPath = visibleRows[visibleRows.count / 2]
    let chronologicalIndex = oldItems.count - 1 - midIndexPath.row
    guard chronologicalIndex >= 0, chronologicalIndex < oldItems.count else { return nil }
    let rect = tableView.rectForRow(at: midIndexPath)
    return PrependAnchor(
      itemID: oldItems[chronologicalIndex].id,
      distanceFromContentOffset: rect.minY - tableView.contentOffset.y
    )
  }

  private func restorePrependAnchor(_ anchor: PrependAnchor) {
    // Read the row from the data source's current snapshot, not the controller's mutable items,
    // because a newer updateItems call may have overwritten items/itemIndexByID while the
    // queued prepend apply (whose completion fires here) was waiting to drain.
    guard let dataSource,
          let indexPath = dataSource.indexPath(for: anchor.itemID) else { return }
    tableView.layoutIfNeeded()
    let newRect = tableView.rectForRow(at: indexPath)
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    tableView.contentOffset.y = newRect.minY - anchor.distanceFromContentOffset
    CATransaction.commit()
  }

  func updateItems(_ newItems: [Item], animated: Bool = true) {
    if tableView.isDragging {
      deferredItemsApply = (newItems, animated)
      return
    }

    let previousCount = items.count
    let wasAtBottom = isAtBottom
    let oldItems = items
    items = newItems

    // Build O(1) lookup dictionaries
    itemsByID = Dictionary(uniqueKeysWithValues: newItems.map { ($0.id, $0) })
    itemIndexByID = Dictionary(uniqueKeysWithValues: newItems.enumerated().map { ($0.element.id, $0.offset) })

    // Detect prepend (pagination) vs append (new messages). A pure prepend changes the
    // first id but not the last; requiring an unchanged tail keeps a combined prepend+append
    // from being misclassified as prepend, which would skip the unread and auto-scroll branches.
    let hasNewItems = newItems.count > previousCount
    let wasPrepend = previousCount > 0 && hasNewItems
      && oldItems.first?.id != newItems.first?.id
      && oldItems.last?.id == newItems.last?.id

    // For prepends, capture a measured anchor row so we can restore the visible
    // content's screen position after the snapshot apply changes contentSize.
    let prependAnchor = wasPrepend ? capturePrependAnchor(in: oldItems) : nil

    // Apply snapshot with reversed order: newest-first for flipped table
    // Row 0 = newest message → appears at visual bottom after flip
    var snapshot = NSDiffableDataSourceSnapshot<Section, Item.ID>()
    snapshot.appendSections([.main])
    snapshot.appendItems(newItems.reversed().map(\.id))

    // Find items that changed content (same ID, different hash).
    // Without reconfiguring these, diffable data source won't update cells for items with same ID.
    let oldItemsByID = Dictionary(uniqueKeysWithValues: oldItems.map { ($0.id, $0) })
    let changedIDs = newItems.compactMap { newItem -> Item.ID? in
      guard let oldItem = oldItemsByID[newItem.id] else { return nil }
      return oldItem != newItem ? newItem.id : nil
    }

    // Two-phase apply to handle structural changes and content updates differently:
    // 1. Structural changes (new/deleted items) - animate for smooth UX, except prepends
    // 2. Content updates (status changes) - no animation to prevent flash
    let hasStructuralChanges = newItems.count != oldItems.count ||
      Set(newItems.map(\.id)) != Set(oldItems.map(\.id))

    // Skip the apply when nothing changed. Otherwise re-renders triggered by
    // non-content state (e.g. ChatRenderState.isLoadingOlder toggling) reach
    // applySnapshot without prepend-anchor protection and can shift
    // contentOffset, producing a visible jump while scrolling.
    if previousCount > 0, !hasStructuralChanges, changedIDs.isEmpty {
      return
    }

    // Prepends apply non-animated so anchor restoration in the apply completion runs
    // against the post-apply layout, not against a coalesced animation in flight
    let restoreClosure: (() -> Void)? = prependAnchor.map { anchor in
      { [weak self] in self?.restorePrependAnchor(anchor) }
    }

    if hasStructuralChanges {
      let animateStructural = animated && previousCount > 0 && !wasPrepend
      let structuralIsLastApply = changedIDs.isEmpty
      applySnapshot(
        snapshot,
        animatingDifferences: animateStructural,
        completion: structuralIsLastApply ? restoreClosure : nil
      )

      if !changedIDs.isEmpty {
        var reconfigureSnapshot = snapshot
        reconfigureSnapshot.reconfigureItems(changedIDs)
        applySnapshot(reconfigureSnapshot, animatingDifferences: false, completion: restoreClosure)
      }
    } else if !changedIDs.isEmpty {
      snapshot.reconfigureItems(changedIDs)
      applySnapshot(snapshot, animatingDifferences: false, completion: restoreClosure)
    } else {
      applySnapshot(snapshot, animatingDifferences: false, completion: restoreClosure)
    }

    // Handle unread tracking
    if !wasAtBottom, previousCount > 0, hasNewItems, !wasPrepend {
      // New messages arrived while scrolled up (not pagination)
      let newMessageCount = newItems.count - previousCount
      unreadCount += newMessageCount
      onScrollStateChanged?(isAtBottom, unreadCount)
    } else if wasAtBottom, hasNewItems, !skipAutoScroll, scrollState.intent != .toBottom, !wasPrepend {
      lastSeenItemID = newItems.last?.id
      if scrollState.isUserDriven {
        // Defer until drag ends — scrolling mid-drag fights the gesture and bounces
        let accumulatedCount = (scrollState.deferredScroll?.targetMessageCount ?? 0) + (newItems.count - previousCount)
        scrollState.scheduleDeferredScroll(
          DeferredScroll(targetMessageCount: accumulatedCount)
        )
      } else {
        scrollToBottom(animated: animated && previousCount > 0)
      }
    }

    // Re-evaluate visible mentions and divider after layout settles. Both initialize
    // assuming a scroll will correct them; without this an on-load viewport that already
    // contains the divider or a mention is never reconciled until the first scroll.
    scheduleVisibleMentionsRecheck()

    if let pendingID = pendingScrollTargetID {
      schedulePendingScroll(for: pendingID, delay: ChatScrollConstants.pendingScrollInitialDelay)
    }
  }

  // MARK: - Scroll Control

  /// Called before updateItems when user sends a message.
  /// Sets isAtBottom = true so updateItems won't increment unread.
  func prepareForUserSend() {
    isAtBottom = true
    unreadCount = 0
    _ = scrollState.consumeDeferredScroll()
    skipAutoScroll = true // Prevent updateItems from calling scrollToBottom (we'll do it explicitly)
  }

  /// contentOffset.y the flipped table rests at when showing the newest message. Zero unless a
  /// content inset is applied (iOS 26), where a scroll view's minimum offset is `-inset.top`.
  private var bottomRestingOffset: CGFloat {
    -tableView.adjustedContentInset.top
  }

  /// Reserves the bar heights as flipped content insets (iOS 26). `visualBottom` (input bar)
  /// maps to `contentInset.top`, `visualTop` (nav bar) to `contentInset.bottom`. A content-inset
  /// change never moves `contentOffset`, so we compensate: pin to the new resting baseline when
  /// at bottom, otherwise shift by the delta so visible content stays put.
  func applyContentInsets(visualBottom: CGFloat, visualTop: CGFloat) {
    let newInsets = UIEdgeInsets(top: visualBottom, left: 0, bottom: visualTop, right: 0)
    guard tableView.contentInset != newInsets else { return }

    let delta = visualBottom - tableView.contentInset.top

    CATransaction.begin()
    CATransaction.setDisableActions(true)
    tableView.contentInset = newInsets
    tableView.verticalScrollIndicatorInsets = newInsets
    if isAtBottom {
      tableView.contentOffset.y = -visualBottom
    } else {
      tableView.contentOffset.y += delta
    }
    CATransaction.commit()
  }

  func scrollToBottom(animated: Bool) {
    guard !items.isEmpty else { return }

    let alreadyAtBottom = tableView.contentOffset.y <= bottomRestingOffset + ChatScrollConstants.bottomDetectionEpsilon

    // Set state before scroll to prevent scroll delegate from overriding
    isAtBottom = true
    unreadCount = 0
    lastSeenItemID = items.last?.id

    // If already at bottom, just update state - no scroll needed.
    // In a flipped table view with short content, scrollToRow miscalculates
    // the target position and over-scrolls, pushing messages off screen.
    if alreadyAtBottom {
      scrollState.clearIntent()
      onScrollStateChanged?(isAtBottom, unreadCount)
      skipAutoScroll = false
      return
    }

    // Only update intent if not already toBottom (keyboardWillShow may have set it)
    if scrollState.intent != .toBottom, animated {
      scrollState.startIntent(.toBottom)
    }

    // In flipped table with reversed data: row 0 = newest message
    // Scroll row 0 to .top anchor (which is visual bottom in flipped table)
    tableView.scrollToRow(at: IndexPath(row: 0, section: 0), at: .top, animated: animated)

    if !animated {
      scrollState.clearIntent()
    }

    onScrollStateChanged?(isAtBottom, unreadCount)

    // Clear skipAutoScroll after explicit scroll (it was set by prepareForUserSend)
    skipAutoScroll = false
  }

  func scrollToItem(id: Item.ID, animated: Bool) {
    // Use O(1) dictionary lookup instead of O(n) firstIndex
    guard itemIndexByID[id] != nil else { return }

    // Set target intent so the computed scrollTargetItemID picks up the post-scroll reload target
    // and so checkNearTop blocks pagination during the scroll-to-target animation.
    scrollState.startIntent(.toTarget(id: id))
    pendingScrollTargetID = id

    pendingScrollTask?.cancel()
    pendingScrollTask = nil

    if animated {
      schedulePendingScroll(for: id, delay: ChatScrollConstants.scrollToTargetDelay)
    } else {
      pendingScrollTargetID = nil
      centerItem(id: id, animated: false)
      reloadTargetCell()
    }
  }

  func scrollToItemIfNotVisible(id: Item.ID, animated: Bool) {
    guard let itemIndex = itemIndexByID[id] else { return }
    let rowIndex = items.count - 1 - itemIndex
    let indexPath = IndexPath(row: rowIndex, section: 0)

    if let visibleRows = tableView.indexPathsForVisibleRows,
       visibleRows.contains(indexPath) {
      return
    }

    scrollToItem(id: id, animated: animated)
  }

  /// Reloads the scroll target cell to fix UIHostingConfiguration layout timing issues
  private func reloadTargetCell() {
    guard let targetID = scrollTargetItemID else { return }
    scrollState.clearIntent()

    // Force cell reconfiguration via snapshot reload
    var snapshot = dataSource?.snapshot() ?? NSDiffableDataSourceSnapshot<Section, Item.ID>()
    if snapshot.itemIdentifiers.contains(targetID) {
      snapshot.reloadItems([targetID])
      applySnapshot(snapshot, animatingDifferences: false)
    }
  }

  /// Reconfigures every current row in place so render-time, non-`MessageItem` styling
  /// (the themed bubble fill) repaints on a live theme switch. `reconfigureItems` preserves
  /// identity (no insert/delete, no scroll jump) and routes through the same serialized
  /// `applySnapshot` path as every other apply.
  func reconfigureAllItems() {
    guard let dataSource else { return }
    var snapshot = dataSource.snapshot()
    guard !snapshot.itemIdentifiers.isEmpty else { return }
    snapshot.reconfigureItems(snapshot.itemIdentifiers)
    applySnapshot(snapshot, animatingDifferences: false, reconfigureAll: true)
  }

  /// Returns true if the target was found in the applied snapshot and scrolled.
  /// Returns false when the snapshot has not yet caught up to the items model,
  /// letting the caller retry instead of silently dropping the scroll.
  @discardableResult
  private func centerItem(id: Item.ID, animated: Bool) -> Bool {
    guard let indexPath = snapshotRow(for: id) else { return false }
    tableView.layoutIfNeeded()
    // Intent is already .toTarget(id:) from scrollToItem; no need to set again here.
    tableView.scrollToRow(at: indexPath, at: .middle, animated: animated)
    return true
  }

  private func schedulePendingScroll(
    for id: Item.ID,
    delay: Duration,
    retriesRemaining: Int = ChatScrollConstants.pendingScrollMaxRetries
  ) {
    pendingScrollTask?.cancel()
    pendingScrollTask = Task { @MainActor [weak self] in
      try? await Task.sleep(for: delay)
      guard let self, !Task.isCancelled else { return }
      guard pendingScrollTargetID == id, !scrollState.isUserDriven else { return }
      pendingScrollTask = nil
      if centerItem(id: id, animated: true) {
        pendingScrollTargetID = nil
      } else if retriesRemaining > 0 {
        // The applied snapshot has not caught up to the items model yet.
        // Keep the target armed and retry once it has had a chance to drain.
        schedulePendingScroll(
          for: id,
          delay: ChatScrollConstants.pendingScrollRetryDelay,
          retriesRemaining: retriesRemaining - 1
        )
      } else {
        // No scrollToRow fired, so reloadTargetCell never clears the .toTarget
        // intent; clear it here or checkNearTop will block pagination.
        pendingScrollTargetID = nil
        scrollState.clearIntent()
      }
    }
  }

  // MARK: - Scroll Tracking

  override func scrollViewDidScroll(_ scrollView: UIScrollView) {
    // Arm the coalescer; the display link's next tick drains the callbacks.
    // Unpause only on the first hit of each burst — flipping `isPaused` is
    // cheap but unnecessary when already running.
    if !hasPendingScrollObservation {
      hasPendingScrollObservation = true
      scrollDisplayLink?.isPaused = false
    }
  }

  /// Re-checks mention and divider state after layout settles. The settle delay lets a snapshot
  /// apply or a freshly loaded `unseenMentionIDs` finish before row positions are read.
  func scheduleVisibleMentionsRecheck() {
    checkVisibleMentionsTask?.cancel()
    checkVisibleMentionsTask = Task { @MainActor [weak self] in
      try? await Task.sleep(for: ChatScrollConstants.layoutSettleDelay)
      guard let self, !Task.isCancelled else { return }
      let visible = visibleItems()
      checkVisibleMentions(visible: visible)
      reportOffscreenMentions(visible: visible)
      checkDividerVisibility()
    }
  }

  /// Items backing the currently visible rows, resolved through the applied snapshot so a model
  /// that is momentarily ahead of its snapshot can't map a visible row to the wrong item.
  private func visibleItems() -> [Item] {
    guard let visibleIndexPaths = tableView.indexPathsForVisibleRows else { return [] }
    return visibleIndexPaths.compactMap { indexPath in
      guard let id = dataSource?.itemIdentifier(for: indexPath) else { return nil }
      return itemsByID[id]
    }
  }

  /// Reports the unseen mentions not currently on screen, the subset that drives the
  /// scroll-to-mention button; a mention already in view is excluded.
  private func reportOffscreenMentions(visible: [Item]) {
    guard let onOffscreenMentionsChanged else { return }
    let offscreen: [Item.ID]
    if unseenMentionIDs.isEmpty {
      offscreen = []
    } else {
      let visibleIDs = Set(visible.map(\.id))
      offscreen = unseenMentionIDs.filter { !visibleIDs.contains($0) }
    }
    if offscreen != lastReportedOffscreenMentionIDs {
      lastReportedOffscreenMentionIDs = offscreen
      onOffscreenMentionsChanged(offscreen)
    }
  }

  private func checkVisibleMentions(visible: [Item]) {
    guard let isUnseenMention, let onMentionBecameVisible else { return }

    for item in visible {
      // Report each mention once per session. Mark optimistically to debounce in-flight
      // reports, then drop the mark if the async seen-persist fails, so a failed save does
      // not strand a still-unread mention that a later scroll could otherwise re-fire.
      if !markedMentionIDs.contains(item.id), isUnseenMention(item) {
        let id = item.id
        markedMentionIDs.insert(id)
        Task { @MainActor [weak self] in
          let persisted = await onMentionBecameVisible(id)
          if !persisted { self?.markedMentionIDs.remove(id) }
        }
      }
    }
  }

  private func checkDividerVisibility() {
    guard let dividerItemID,
          let indexPath = snapshotRow(for: dividerItemID),
          let onDividerVisibilityChanged else {
      // No divider configured or not yet in the applied snapshot — report
      // not visible if we previously reported visible.
      if lastDividerVisible == true {
        lastDividerVisible = false
        onDividerVisibilityChanged?(false)
      }
      return
    }

    let isVisible = tableView.indexPathsForVisibleRows?.contains(indexPath) ?? false

    if isVisible != lastDividerVisible {
      lastDividerVisible = isVisible
      onDividerVisibilityChanged(isVisible)
    }
  }

  override func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
    if !decelerate {
      scrollState.endDragging()
      finalizeScrollPosition()
      fireDeferredScrollIfNeeded()
    }
    drainDeferredItemsApply()
  }

  override func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
    scrollState.endDragging()
    finalizeScrollPosition()
    fireDeferredScrollIfNeeded()
    drainDeferredItemsApply()
  }

  private func drainDeferredItemsApply() {
    guard let deferred = deferredItemsApply else { return }
    deferredItemsApply = nil
    updateItems(deferred.newItems, animated: deferred.animated)
  }

  private func fireDeferredScrollIfNeeded() {
    guard let deferred = scrollState.consumeDeferredScroll() else { return }
    if isAtBottom {
      scrollToBottom(animated: true)
    } else {
      // User dragged away mid-message — the messages they didn't see become unread
      unreadCount += deferred.targetMessageCount
      onScrollStateChanged?(isAtBottom, unreadCount)
    }
  }

  override func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) {
    // Capture whether the completed animation was a scroll-to-bottom before mutating intent.
    let wasScrollingToBottom = scrollState.intent == .toBottom
    if wasScrollingToBottom {
      scrollState.clearIntent()
    }

    // Reload target cell after scroll completes to fix UIHostingConfiguration layout timing.
    // reloadTargetCell clears any .toTarget intent.
    reloadTargetCell()

    if wasScrollingToBottom {
      // We just finished a programmatic scroll-to-bottom
      // Use larger threshold since animation might not land exactly at 0
      let atBottom = scrollView.contentOffset.y <= bottomRestingOffset + ChatScrollConstants.bottomLandingEpsilon
      if atBottom {
        // Confirm we're at bottom - this is authoritative
        isAtBottom = true
        unreadCount = 0
        onScrollStateChanged?(isAtBottom, unreadCount)
        return
      }
    }

    // For user-initiated scrolls or if we didn't land at bottom, use normal check
    updateIsAtBottom()
  }

  private func updateIsAtBottom() {
    // Don't override isAtBottom during programmatic scroll-to-bottom animation
    // This prevents the scroll-to-bottom button from flickering when user sends a message
    if scrollState.intent == .toBottom {
      return
    }

    // In flipped table, visual bottom = contentOffset.y near the resting baseline
    // (0, or -contentInset.top when a bar inset is applied). Small threshold absorbs float error.
    let newIsAtBottom = tableView.contentOffset.y <= bottomRestingOffset + ChatScrollConstants.bottomDetectionEpsilon

    if newIsAtBottom != isAtBottom {
      isAtBottom = newIsAtBottom
      onScrollStateChanged?(isAtBottom, unreadCount)
    }
  }

  private func finalizeScrollPosition() {
    if isAtBottom {
      // User scrolled to bottom, clear unread
      unreadCount = 0
      lastSeenItemID = items.last?.id
      onScrollStateChanged?(isAtBottom, unreadCount)
    }
  }

  /// Check if user has scrolled near the top (oldest messages) and trigger callback
  private func checkNearTop() {
    if scrollState.intent != .none || isLoadingOlderMessages || isNearTopRequestInFlight {
      return
    }
    guard let visibleRows = tableView.indexPathsForVisibleRows,
          let highestRow = visibleRows.map(\.row).max() else { return }

    let totalRows = items.count
    let distanceFromTop = totalRows - highestRow

    // Trigger when within nearTopTriggerDistance messages of the oldest
    if distanceFromTop <= ChatScrollConstants.nearTopTriggerDistance {
      isNearTopRequestInFlight = true
      onNearTop? { @MainActor [weak self] in
        self?.isNearTopRequestInFlight = false
      }
    }
  }

  override func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
    scrollState.enterDragging()
    pendingScrollTargetID = nil
    pendingScrollTask?.cancel()
    pendingScrollTask = nil
  }
}

// MARK: - SwiftUI Wrapper

/// SwiftUI wrapper for ChatTableViewController
struct ChatTableView<Item: Identifiable & Hashable & Sendable, Content: View>: UIViewControllerRepresentable where Item.ID == UUID {
  let items: [Item]
  let cellContent: (Item) -> Content
  /// Themed canvas color for themes that paint a canvas (Ember → black, paid themes →
  /// asset-catalog tint); `nil` leaves the table's default system background untouched, so
  /// themes without surfaces are unchanged.
  var contentBackground: Color?
  /// Active theme id (`Theme.id`). Drives a one-shot reconfigure of all rows on a theme change
  /// so the render-time bubble fill (`\.appTheme.accentColor`, not part of `MessageItem`) repaints
  /// even when no baked text changed — e.g. switching between two themes that share white text.
  var themeID: String = Theme.default.id
  /// Appearance fingerprint (light/dark + contrast). Like `themeID`, a change reconfigures all rows
  /// once so identity colors that depend on the appearance repaint in place.
  var appearanceToken: String = ""
  @Binding var isAtBottom: Bool
  @Binding var unreadCount: Int
  @Binding var scrollToBottomRequest: Int
  @Binding var scrollToMentionRequest: Int
  var isUnseenMention: ((Item) -> Bool)?
  /// The current unseen self-mention ids. Feeds the controller's off-screen subset and
  /// triggers a recheck when the set changes; the per-row test goes through `isUnseenMention`.
  var unseenMentionIDs: [Item.ID] = []
  /// Unseen mentions currently off screen, reported up from the controller; drives the
  /// scroll-to-mention button's visibility, count, and scroll target.
  @Binding var offscreenMentionIDs: [Item.ID]
  var onMentionBecameVisible: ((Item.ID) async -> Bool)?
  var onSecondaryClick: ((Item) -> Void)?
  var mentionTargetID: Item.ID?
  @Binding var scrollToDividerRequest: Int
  var dividerItemID: Item.ID?
  @Binding var isDividerVisible: Bool
  var onNearTop: ((@escaping @MainActor () -> Void) -> Void)?
  var isLoadingOlderMessages: Bool = false
  /// Visual-top safe-area inset (nav bar) measured by the parent. Applied as the flipped
  /// table's content inset on iOS 26 so content scrolls edge-to-edge behind the bars; 0 on
  /// iOS 18, where the `.safeAreaInset` frame shrink still reserves the space.
  var topContentInset: CGFloat = 0
  /// Visual-bottom safe-area inset (input bar + home indicator). See `topContentInset`.
  var bottomContentInset: CGFloat = 0

  func makeUIViewController(context: Context) -> ChatTableViewController<Item, Content> {
    let controller = ChatTableViewController<Item, Content>()
    controller.defaultTableBackgroundColor = controller.tableView.backgroundColor
    controller.configure { item in
      cellContent(item)
    }
    // Callback set up in updateUIViewController
    context.coordinator.lastScrollRequest = scrollToBottomRequest
    controller.isUnseenMention = isUnseenMention
    context.coordinator.lastMentionRequest = scrollToMentionRequest
    context.coordinator.lastDividerScrollRequest = scrollToDividerRequest
    return controller
  }

  func updateUIViewController(_ controller: ChatTableViewController<Item, Content>, context: Context) {
    // Update cell content provider each render cycle so reconfigured cells
    // get fresh closures (e.g., onRetry callback when message status changes)
    controller.configure { item in
      cellContent(item)
    }
    controller.tableView.backgroundColor = contentBackground.map(UIColor.init) ?? controller.defaultTableBackgroundColor

    // Breathing room between the newest message and the compose bar (flipped: visual bottom).
    let composeBarGap: CGFloat = 8

    if #available(iOS 26.0, *) {
      controller.applyContentInsets(visualBottom: bottomContentInset + composeBarGap, visualTop: topContentInset)
    } else {
      controller.applyContentInsets(visualBottom: composeBarGap, visualTop: 0)
    }

    // Repaint visible bubbles whose render-time accent fill is not part of `MessageItem`:
    // a theme-id change reconfigures all rows in place once. The gate skips the first
    // pass (no previous id) so appearance does not trigger a needless reconfigure.
    let themeChanged = context.coordinator.lastThemeID.map { $0 != themeID } ?? false
    let appearanceChanged = context.coordinator.lastAppearanceToken.map { $0 != appearanceToken } ?? false
    if themeChanged || appearanceChanged {
      controller.reconfigureAllItems()
    }
    context.coordinator.lastThemeID = themeID
    context.coordinator.lastAppearanceToken = appearanceToken

    // Store current binding setters in coordinator (updated each render cycle)
    // This ensures deferred callbacks always use fresh bindings
    context.coordinator.setIsAtBottom = { [self] in isAtBottom = $0 }
    context.coordinator.setUnreadCount = { [self] in unreadCount = $0 }

    // Controller callback defers to next MainActor yield via coordinator.
    // SwiftUI blocks binding updates during updateUIViewController, so we must
    // defer the update to after the current update cycle completes.
    controller.onScrollStateChanged = { [weak coordinator = context.coordinator] atBottom, unread in
      Task { @MainActor in
        coordinator?.setIsAtBottom?(atBottom)
        coordinator?.setUnreadCount?(unread)
      }
    }

    // Update mention detection closures
    controller.isUnseenMention = isUnseenMention
    controller.unseenMentionIDs = unseenMentionIDs
    controller.onMentionBecameVisible = onMentionBecameVisible
    controller.onSecondaryClick = onSecondaryClick

    context.coordinator.setOffscreenMentionIDs = { [self] in offscreenMentionIDs = $0 }
    controller.onOffscreenMentionsChanged = { [weak coordinator = context.coordinator] ids in
      // Defer: SwiftUI forbids binding writes during updateUIViewController.
      Task { @MainActor in
        coordinator?.setOffscreenMentionIDs?(ids)
      }
    }

    // unseenMentionIDs can load after the first render, with no item change to trigger the
    // recheck inside updateItems. Re-run it here so the off-screen set reflects the new ids.
    if context.coordinator.lastUnseenMentionIDs != unseenMentionIDs {
      context.coordinator.lastUnseenMentionIDs = unseenMentionIDs
      controller.scheduleVisibleMentionsRecheck()
    }

    // Update divider visibility tracking
    controller.dividerItemID = dividerItemID
    context.coordinator.setIsDividerVisible = { [self] in isDividerVisible = $0 }
    controller.onDividerVisibilityChanged = { [weak coordinator = context.coordinator] visible in
      Task { @MainActor in
        coordinator?.setIsDividerVisible?(visible)
      }
    }

    // Update pagination state
    controller.onNearTop = onNearTop
    controller.isLoadingOlderMessages = isLoadingOlderMessages

    // Check for scroll-to-mention request
    let shouldScrollToMention = scrollToMentionRequest != context.coordinator.lastMentionRequest
    var shouldScrollMentionToBottom = false
    var mentionScrollTargetID: Item.ID?

    if shouldScrollToMention {
      context.coordinator.lastMentionRequest = scrollToMentionRequest
      mentionScrollTargetID = mentionTargetID

      let newestItemID = items.last?.id
      shouldScrollMentionToBottom = ChatScrollToMentionPolicy.shouldScrollToBottom(
        mentionTargetID: mentionTargetID,
        newestItemID: newestItemID
      )
    }

    // Check for scroll-to-divider request (new messages divider)
    let shouldScrollToDivider = scrollToDividerRequest != context.coordinator.lastDividerScrollRequest
    if shouldScrollToDivider {
      context.coordinator.lastDividerScrollRequest = scrollToDividerRequest
    }

    // Check for scroll-to-bottom request before updating items
    // This ensures user sends don't trigger unread badge
    let shouldForceScroll = scrollToBottomRequest != context.coordinator.lastScrollRequest

    if shouldForceScroll {
      context.coordinator.lastScrollRequest = scrollToBottomRequest
      // Mark as at bottom so updateItems won't increment unread
      controller.prepareForUserSend()
    }

    controller.updateItems(items)

    // Perform the scroll after items are updated
    if shouldForceScroll {
      controller.scrollToBottom(animated: true)
    } else if shouldScrollToMention {
      if shouldScrollMentionToBottom {
        controller.scrollToBottom(animated: true)
      } else if let targetID = mentionScrollTargetID {
        controller.scrollToItem(id: targetID, animated: true)
      }
    } else if shouldScrollToDivider, let targetID = dividerItemID {
      controller.scrollToItem(id: targetID, animated: true)
    }
  }

  func makeCoordinator() -> Coordinator {
    Coordinator()
  }

  @MainActor
  class Coordinator {
    var lastScrollRequest: Int = 0
    var lastMentionRequest: Int = 0
    var lastDividerScrollRequest: Int = 0
    var lastThemeID: String?
    var lastAppearanceToken: String?
    var setIsAtBottom: ((Bool) -> Void)?
    var setUnreadCount: ((Int) -> Void)?
    var setIsDividerVisible: ((Bool) -> Void)?
    var setOffscreenMentionIDs: (([Item.ID]) -> Void)?
    var lastUnseenMentionIDs: [Item.ID] = []
  }
}
