import UIKit
import SwiftUI

/// UIKit table view controller with flipped orientation for chat-style scrolling
/// Newest messages appear at visual bottom, keyboard handling via native UIKit
@MainActor
final class ChatTableViewController<Item: Identifiable & Hashable & Sendable, CellContent: View>: UITableViewController where Item.ID == UUID {

    // MARK: - Types

    private enum Section: Hashable {
        case main
    }

    private struct SnapshotApplyRequest {
        var snapshot: NSDiffableDataSourceSnapshot<Section, Item.ID>
        var animatingDifferences: Bool
        var completion: (() -> Void)?
    }

    // MARK: - Properties

    private var items: [Item] = []
    /// O(1) lookup for items by ID (replaces O(n) first(where:) in cell provider)
    private var itemsByID: [Item.ID: Item] = [:]
    /// O(1) index lookup for scroll-to-item (replaces O(n) firstIndex(where:))
    private var itemIndexByID: [Item.ID: Int] = [:]
    private var cellContentProvider: ((Item) -> CellContent)?
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
    /// callback the consumer must invoke when pagination work completes (success OR short-circuit)
    /// so the request latch clears even when the view model's isLoadingOlder never visibly flips.
    var onNearTop: ((@escaping @MainActor () -> Void) -> Void)?

    /// Whether pagination is in progress (skip auto-scroll during pagination)
    var isLoadingOlderMessages = false

    /// Suppresses duplicate onNearTop fires while the view model's isLoadingOlder propagates back through SwiftUI
    private var isNearTopRequestInFlight = false

    /// Callback when a mention becomes visible
    var onMentionBecameVisible: ((Item.ID) -> Void)?

    /// Closure to check if an item contains an unseen self-mention
    var isUnseenMention: ((Item) -> Bool)?

    /// Item ID of the new messages divider (for visibility tracking)
    var dividerItemID: Item.ID?

    /// Callback when the divider row's visibility changes
    var onDividerVisibilityChanged: ((Bool) -> Void)?

    /// Last reported divider visibility (change detection to avoid redundant callbacks)
    private var lastDividerVisible: Bool?

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
        if case .toTarget(let id) = scrollState.intent { return id }
        return nil
    }

    /// True when an auto-scroll-to-bottom was suppressed because the user was interacting.
    /// Fired on drag end so messages arriving mid-drag aren't silently dropped.
    var deferredScrollToBottomPending: Bool { scrollState.deferredScroll != nil }

    /// Count of messages deferred while user is interacting; counted as unread if they release scrolled away.
    var deferredScrollMessageCount: Int { scrollState.deferredScroll?.targetMessageCount ?? 0 }

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
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "Cell")

        // Configure data source
        configureDataSource()

        // Manual keyboard observation (UIKit auto-adjustment doesn't work in SwiftUI embed)
        setupKeyboardObservers()

        // Coalesces scroll-tracking callbacks at display-frame cadence
        setupScrollDisplayLink()
    }

    // Swift 6.3.2 EarlyPerfInliner crashes (infinite recursion in
    // `isCallerAndCalleeLayoutConstraintsCompatible`) when optimizing this
    // generic UITableViewController subclass's deinit under -O. Opting the
    // deinit out of optimization sidesteps the crash without changing
    // runtime behavior. Drop the attribute once a future Swift release
    // fixes the underlying inliner bug.
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
            checkVisibleMentions()
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

    @objc private func keyboardWillShow(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let _ = userInfo[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else {
            return
        }

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
                try? await Task.sleep(for: .milliseconds(100))
                self?.scrollToBottom(animated: true)
            }
        }
    }

    // MARK: - Configuration

    func configure(cellContent: @escaping (Item) -> CellContent) {
        self.cellContentProvider = cellContent
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
                  let item = self.itemsByID[itemID] else {
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
            if let contentProvider = self.cellContentProvider {
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
        completion: (() -> Void)? = nil
    ) {
        let request = SnapshotApplyRequest(
            snapshot: snapshot,
            animatingDifferences: animatingDifferences,
            completion: completion
        )

        if scrollState.isApplyingSnapshot {
            // Latest-wins for the snapshot itself, but preserve the superseded
            // request's completion so prepend anchor restores still fire after
            // the final apply lands.
            if let superseded = pendingSnapshot?.completion {
                pendingCompletions.append(superseded)
            }
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
        if !scrollState.isApplyingSnapshot && !pendingCompletions.isEmpty {
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

        // Detect prepend (pagination) vs append (new messages): prepend changes first item ID
        let hasNewItems = newItems.count > previousCount
        let wasPrepend = previousCount > 0 && hasNewItems && oldItems.first?.id != newItems.first?.id

        // For prepends, capture a measured anchor row so we can restore the visible
        // content's screen position after the snapshot apply changes contentSize.
        let prependAnchor = wasPrepend ? capturePrependAnchor(in: oldItems) : nil

        // Apply snapshot with REVERSED order: newest-first for flipped table
        // Row 0 = newest message → appears at visual bottom after flip
        var snapshot = NSDiffableDataSourceSnapshot<Section, Item.ID>()
        snapshot.appendSections([.main])
        snapshot.appendItems(newItems.reversed().map(\.id))

        // Find items that changed content (same ID, different hash).
        // Without reloading these, diffable data source won't update cells for items with same ID.
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
        if previousCount > 0 && !hasStructuralChanges && changedIDs.isEmpty {
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
                var reloadSnapshot = snapshot
                reloadSnapshot.reloadItems(changedIDs)
                applySnapshot(reloadSnapshot, animatingDifferences: false, completion: restoreClosure)
            }
        } else if !changedIDs.isEmpty {
            snapshot.reloadItems(changedIDs)
            applySnapshot(snapshot, animatingDifferences: false, completion: restoreClosure)
        } else {
            applySnapshot(snapshot, animatingDifferences: false, completion: restoreClosure)
        }

        // Handle unread tracking
        if !wasAtBottom && previousCount > 0 && hasNewItems && !wasPrepend {
            // New messages arrived while scrolled up (not pagination)
            let newMessageCount = newItems.count - previousCount
            unreadCount += newMessageCount
            onScrollStateChanged?(isAtBottom, unreadCount)
        } else if wasAtBottom && hasNewItems && !skipAutoScroll && scrollState.intent != .toBottom && !wasPrepend {
            lastSeenItemID = newItems.last?.id
            if scrollState.isUserDriven {
                // Defer until drag ends — scrolling mid-drag fights the gesture and bounces
                let accumulatedCount = (scrollState.deferredScroll?.targetMessageCount ?? 0) + (newItems.count - previousCount)
                scrollState.scheduleDeferredScroll(
                    DeferredScroll(targetMessageCount: accumulatedCount, createdAt: Date())
                )
            } else {
                scrollToBottom(animated: animated && previousCount > 0)
            }
        }

        // Check for visible mentions after layout settles (handles mentions visible on load)
        checkVisibleMentionsTask?.cancel()
        checkVisibleMentionsTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(100))
            guard !Task.isCancelled else { return }
            self?.checkVisibleMentions()
        }

        if let pendingID = pendingScrollTargetID {
            schedulePendingScroll(for: pendingID, delay: .milliseconds(120))
        }
    }

    // MARK: - Scroll Control

    /// Called before updateItems when user sends a message.
    /// Sets isAtBottom = true so updateItems won't increment unread.
    func prepareForUserSend() {
        isAtBottom = true
        unreadCount = 0
        _ = scrollState.consumeDeferredScroll()
        skipAutoScroll = true  // Prevent updateItems from calling scrollToBottom (we'll do it explicitly)
    }

    func scrollToBottom(animated: Bool) {
        guard !items.isEmpty else { return }

        let alreadyAtBottom = tableView.contentOffset.y <= 1

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
        if scrollState.intent != .toBottom && animated {
            scrollState.startIntent(.toBottom)
        }

        // In flipped table with reversed data: row 0 = newest message
        // Scroll row 0 to .top anchor (which is visual BOTTOM in flipped table)
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
            schedulePendingScroll(for: id, delay: .milliseconds(180))
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
            guard self.pendingScrollTargetID == id, !self.scrollState.isUserDriven else { return }
            self.pendingScrollTask = nil
            if self.centerItem(id: id, animated: true) {
                self.pendingScrollTargetID = nil
            } else if retriesRemaining > 0 {
                // The applied snapshot has not caught up to the items model yet.
                // Keep the target armed and retry once it has had a chance to drain.
                self.schedulePendingScroll(
                    for: id,
                    delay: ChatScrollConstants.pendingScrollRetryDelay,
                    retriesRemaining: retriesRemaining - 1
                )
            } else {
                // No scrollToRow fired, so reloadTargetCell never clears the .toTarget
                // intent; clear it here or checkNearTop will block pagination.
                self.pendingScrollTargetID = nil
                self.scrollState.clearIntent()
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

    private func checkVisibleMentions() {
        guard let visibleIndexPaths = tableView.indexPathsForVisibleRows,
              let isUnseenMention,
              let onMentionBecameVisible else { return }

        for indexPath in visibleIndexPaths {
            guard indexPath.row < items.count else { continue }
            // Items are reversed in table: row 0 = newest (items.last)
            let reversedIndex = items.count - 1 - indexPath.row
            guard reversedIndex >= 0 else { continue }
            let item = items[reversedIndex]
            // Only report each mention once per session
            if !markedMentionIDs.contains(item.id) && isUnseenMention(item) {
                markedMentionIDs.insert(item.id)
                onMentionBecameVisible(item.id)
            }
        }
    }

    /// Resets the debouncing state (call when conversation changes)
    func resetMarkedMentions() {
        markedMentionIDs.removeAll()
    }

    private func checkDividerVisibility() {
        guard let dividerItemID,
              let indexPath = snapshotRow(for: dividerItemID),
              let onDividerVisibilityChanged else {
            // No divider configured or not yet in the applied snapshot — report
            // not visible if we previously reported visible.
            if lastDividerVisible == true {
                lastDividerVisible = false
                self.onDividerVisibilityChanged?(false)
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
            let atBottom = scrollView.contentOffset.y <= 10
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
        // This prevents the FAB from flickering when user sends a message
        if scrollState.intent == .toBottom {
            return
        }

        // In flipped table, visual bottom = contentOffset.y near 0
        // Use small threshold to handle float imprecision
        let newIsAtBottom = tableView.contentOffset.y <= 1

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

        // Trigger when within 10 messages of the oldest
        if distanceFromTop <= 10 {
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
    @Binding var isAtBottom: Bool
    @Binding var unreadCount: Int
    @Binding var scrollToBottomRequest: Int
    @Binding var scrollToMentionRequest: Int
    var isUnseenMention: ((Item) -> Bool)?
    var onMentionBecameVisible: ((Item.ID) -> Void)?
    var mentionTargetID: Item.ID?
    @Binding var scrollToDividerRequest: Int
    var dividerItemID: Item.ID?
    @Binding var isDividerVisible: Bool
    var onNearTop: ((@escaping @MainActor () -> Void) -> Void)?
    var isLoadingOlderMessages: Bool = false

    func makeUIViewController(context: Context) -> ChatTableViewController<Item, Content> {
        let controller = ChatTableViewController<Item, Content>()
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
        controller.onMentionBecameVisible = onMentionBecameVisible

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
                mentionTargetID: mentionTargetID.map { AnyHashable($0) },
                newestItemID: newestItemID.map { AnyHashable($0) }
            )
        }

        // Check for scroll-to-divider request (new messages divider)
        let shouldScrollToDivider = scrollToDividerRequest != context.coordinator.lastDividerScrollRequest
        if shouldScrollToDivider {
            context.coordinator.lastDividerScrollRequest = scrollToDividerRequest
        }

        // Check for scroll-to-bottom request BEFORE updating items
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
        var setIsAtBottom: ((Bool) -> Void)?
        var setUnreadCount: ((Int) -> Void)?
        var setIsDividerVisible: ((Bool) -> Void)?
    }
}
