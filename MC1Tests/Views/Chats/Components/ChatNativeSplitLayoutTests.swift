@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing
import UIKit

/// Hosted split that measures `ChatTiledView` against the detail column.
/// Production `MainTabView` is not mounted; a window-wide collection fails these checks.
@Suite("Chat native split layout", .serialized)
@MainActor
struct ChatNativeSplitLayoutTests {
  private enum Layout {
    static let regularSize = CGSize(width: 1024, height: 768)
    /// Regular width and compact height, a wide phone in landscape.
    static let phoneLandscapeSize = CGSize(width: 932, height: 430)
    static let collectionWaitTimeout: TimeInterval = 6
    static let runLoopSlice: TimeInterval = 0.05
    static let settle: TimeInterval = 0.35
    static let mutationSettle: TimeInterval = 0.8
    static let geometrySlop: CGFloat = 8
    /// Sidebar must occupy this much when the list is shown, otherwise the
    /// window-width leak check cannot distinguish columns.
    static let sidebarOccupiedMinWidth: CGFloat = 80
    static let widthLeakAllowance: CGFloat = 24
    static let timelineCount = 40
    static let middleIndex = 20
    static let leadingAsymmetricInset: CGFloat = 48
    static let trailingAsymmetricInset: CGFloat = 16
    static let longTextRepeatCount = 24
  }

  private enum Identifier {
    static let sidebar = "gateA.sidebar"
    static let detail = "gateA.detail"
    static let composer = "gateA.composer"
  }

  // MARK: - Geometry with the list shown

  @Test
  func `regular split proposes ChatTiledView the detail width, not the window`() async throws {
    let fixture = try await makeChatFixture()
    let host = try mountSelected(fixture)
    defer { host.window.isHidden = true }

    let found = try requireCollection(host, itemCount: fixture.model.chatViewModel.items.count)
    let snapshot = try geometrySnapshot(host: host, collectionView: found.collectionView)
    recordSnapshot("regular-list-shown", snapshot)

    #expect(snapshot.sidebarWidth >= Layout.sidebarOccupiedMinWidth, "\(snapshot.summary)")
    #expect(
      snapshot.collectionWidth + Layout.sidebarOccupiedMinWidth <= snapshot.windowWidth + Layout.geometrySlop,
      "collection \(snapshot.collectionWidth) is window-wide \(snapshot.windowWidth) while the list is shown. \(snapshot.summary)"
    )
    #expect(
      abs(snapshot.collectionWidth - snapshot.detailWidth) <= Layout.widthLeakAllowance
        || snapshot.collectionWidth < snapshot.detailWidth + Layout.widthLeakAllowance,
      "ChatTiledView leaked past the detail: \(snapshot.summary)"
    )
  }

  @Test
  func `long bubbles wrap using usable detail width while the list is shown`() async throws {
    let fixture = try await makeChatFixture(includeLongBubble: true)
    let host = try mountSelected(fixture)
    defer { host.window.isHidden = true }

    let found = try requireCollection(host, itemCount: fixture.model.chatViewModel.items.count)
    let longID = try #require(fixture.longMessageID)
    let cell = try screenFrame(of: longID, in: found, items: fixture.model.chatViewModel.items)
    let snapshot = try geometrySnapshot(host: host, collectionView: found.collectionView)
    recordSnapshot("long-bubble-list-shown", snapshot, extra: "longCell=\(cell)")

    #expect(cell.width <= snapshot.detailWidth + Layout.geometrySlop, "\(snapshot.summary)")
    #expect(
      cell.maxX <= snapshot.detailFrame.maxX + Layout.geometrySlop,
      "long bubble extends past the detail: cell=\(cell) detail=\(snapshot.detailFrame)"
    )
    #expect(
      cell.width < snapshot.windowWidth - Layout.sidebarOccupiedMinWidth,
      "long bubble wrapped at window width, not detail width: cell=\(cell) \(snapshot.summary)"
    )
  }

  @Test
  func `bubbles stay inside the detail when the list is hidden`() async throws {
    let fixture = try await makeChatFixture()
    let host = try mountSelected(fixture, columnVisibility: .detailOnly)
    defer { host.window.isHidden = true }

    let found = try requireCollection(host, itemCount: fixture.model.chatViewModel.items.count)
    let snapshot = try geometrySnapshot(host: host, collectionView: found.collectionView)
    recordSnapshot("regular-list-hidden", snapshot)

    let firstID = try #require(fixture.model.chatViewModel.items.first?.id)
    let cell = try screenFrame(of: firstID, in: found, items: fixture.model.chatViewModel.items)
    #expect(cell.minX >= snapshot.detailFrame.minX - Layout.geometrySlop)
    #expect(cell.maxX <= snapshot.detailFrame.maxX + Layout.geometrySlop)
    #expect(fixture.model.selectedRoute != nil, "hiding the list must not clear the route")
    #expect(fixture.model.tabBarVisibility == .automatic, "regular overlay must not hide tabs")
  }

  @Test
  func `phone landscape split keeps a long bubble inside the detail`() async throws {
    let fixture = try await makeChatFixture(includeLongBubble: true)
    let host = try mountSelected(
      fixture,
      size: Layout.phoneLandscapeSize,
      regularWidth: true,
      regularHeight: false
    )
    defer { host.window.isHidden = true }

    #expect(host.controller.view.traitCollection.horizontalSizeClass == .regular)
    #expect(host.controller.view.traitCollection.verticalSizeClass == .compact)

    let found = try requireCollection(host, itemCount: fixture.model.chatViewModel.items.count)
    let longID = try #require(fixture.longMessageID)
    let cell = try screenFrame(of: longID, in: found, items: fixture.model.chatViewModel.items)
    let snapshot = try geometrySnapshot(host: host, collectionView: found.collectionView)
    recordSnapshot("phone-landscape-list-shown", snapshot, extra: "longCell=\(cell)")

    #expect(snapshot.sidebarWidth >= Layout.sidebarOccupiedMinWidth, "\(snapshot.summary)")
    #expect(
      snapshot.collectionWidth + Layout.sidebarOccupiedMinWidth
        <= snapshot.windowWidth + Layout.geometrySlop,
      "collection \(snapshot.collectionWidth) is window-wide \(snapshot.windowWidth) while the list is shown. \(snapshot.summary)"
    )
    #expect(cell.width <= snapshot.detailWidth + Layout.geometrySlop, "\(snapshot.summary)")
    #expect(cell.minX >= snapshot.detailFrame.minX - Layout.geometrySlop)
    #expect(
      cell.maxX <= snapshot.detailFrame.maxX + Layout.geometrySlop,
      "long bubble extends past the detail: cell=\(cell) detail=\(snapshot.detailFrame)"
    )
  }

  @Test
  func `asymmetric leading and trailing insets keep bubbles in the detail`() async throws {
    let fixture = try await makeChatFixture(includeLongBubble: true)
    let host = try mountSelected(
      fixture,
      additionalSafeAreaInsets: UIEdgeInsets(
        top: 0,
        left: Layout.leadingAsymmetricInset,
        bottom: 0,
        right: Layout.trailingAsymmetricInset
      )
    )
    defer { host.window.isHidden = true }

    let found = try requireCollection(host, itemCount: fixture.model.chatViewModel.items.count)
    let snapshot = try geometrySnapshot(host: host, collectionView: found.collectionView)
    recordSnapshot("asymmetric-insets", snapshot)

    let longID = try #require(fixture.longMessageID)
    let cell = try screenFrame(of: longID, in: found, items: fixture.model.chatViewModel.items)
    #expect(cell.minX >= snapshot.detailFrame.minX - Layout.geometrySlop)
    #expect(cell.maxX <= snapshot.detailFrame.maxX + Layout.geometrySlop)
    #expect(host.controller.additionalSafeAreaInsets.left == Layout.leadingAsymmetricInset)
    #expect(host.controller.additionalSafeAreaInsets.right == Layout.trailingAsymmetricInset)
  }

  @Test
  func `composer sits in the detail bottom with production keyboard-lift chrome`() async throws {
    let fixture = try await makeChatFixture()
    let host = try mountSelected(fixture)
    defer { host.window.isHidden = true }

    let composer = try #require(
      waitForView(withIdentifier: Identifier.composer, in: host.window),
      "composer identifier missing. \(describeSplit(host, expectedItems: fixture.model.chatViewModel.items.count))"
    )
    let composerFrame = composer.convert(composer.bounds, to: host.window)
    let snapshot = try geometrySnapshot(host: host, collectionView: nil)
    recordSnapshot("composer", snapshot, extra: "composer=\(composerFrame)")

    #expect(composerFrame.maxX <= snapshot.detailFrame.maxX + Layout.geometrySlop)
    #expect(composerFrame.minX >= snapshot.detailFrame.minX - Layout.geometrySlop)
    #expect(
      composerFrame.maxY > snapshot.detailFrame.midY,
      "composer floated mid-screen: \(composerFrame) detail=\(snapshot.detailFrame)"
    )
  }

  // MARK: - Scroll continuity

  @Test
  func `middle message stays visible across resize, prepend, and live admit`() async throws {
    let fixture = try await makeChatFixture()
    let host = try mountSelected(fixture)
    defer { host.window.isHidden = true }

    let middle = try #require(fixture.model.chatViewModel.items[Layout.middleIndex].id)
    fixture.model.scrollToTargetID = middle
    fixture.model.scrollToTargetRequest += 1
    settle(host.window)

    var found = try requireCollection(host, itemCount: fixture.model.chatViewModel.items.count)
    try #require(isItemVisible(middle, in: found, items: fixture.model.chatViewModel.items))
    #expect(!fixture.model.isAtBottom)

    resize(host, to: CGSize(width: 834, height: 768), regularWidth: true)
    found = try requireCollection(host, itemCount: fixture.model.timelineItems.count)
    #expect(isItemVisible(middle, in: found, items: fixture.model.timelineItems))
    #expect(!fixture.model.isAtBottom)

    let live = makeMessage(
      id: UUID(),
      contactID: fixture.contact.id,
      radioID: fixture.contact.radioID,
      text: "live incoming",
      direction: .incoming,
      timestampOffset: 60
    )
    fixture.model.chatViewModel.appendMessageIfNew(live)
    let liveItem = try #require(fixture.model.chatViewModel.items.last)
    #expect(liveItem.id == live.id)
    fixture.model.timelineItems.append(liveItem)
    settle(host.window)
    spin(Layout.mutationSettle)
    found = try requireItem(live.id, host: host, items: fixture.model.timelineItems)
    #expect(isItemVisible(middle, in: found, items: fixture.model.timelineItems))
    #expect(!fixture.model.isAtBottom)
    #expect(fixture.model.unreadCount >= 1)

    let older = (0..<5).map { offset in
      makeMessage(
        id: UUID(),
        contactID: fixture.contact.id,
        radioID: fixture.contact.radioID,
        text: "older \(offset)",
        direction: .incoming,
        timestampOffset: -60 * (offset + 1)
      )
    }
    fixture.model.chatViewModel.timelineWriter?.prepend(older)
    fixture.model.chatViewModel.buildItems()
    await fixture.coordinator.buildItemsTask?.value
    let prependedItems = Array(fixture.model.chatViewModel.items.prefix(older.count))
    fixture.model.timelineItems.insert(contentsOf: prependedItems, at: 0)
    settle(host.window)
    spin(Layout.mutationSettle)

    let oldestPrepended = try #require(older.first?.id)
    found = try requireItem(oldestPrepended, host: host, items: fixture.model.timelineItems)
    let ids = fixture.model.timelineItems.map(\.id)
    #expect(Set(ids).count == ids.count)
    #expect(isItemVisible(middle, in: found, items: fixture.model.timelineItems))
    #expect(!fixture.model.isAtBottom)
  }

  @Test
  func `consumed unread anchor does not replay after resize`() async throws {
    let fixture = try await makeChatFixture()
    let middle = try #require(fixture.model.chatViewModel.items[Layout.middleIndex].id)
    fixture.model.firstSnapshotDecision = .present(target: middle)

    let host = try mountSelected(fixture)
    defer { host.window.isHidden = true }

    try #require(
      waitUntil { fixture.model.consumedAnchorCount >= 1 },
      "\(describeSplit(host, expectedItems: fixture.model.chatViewModel.items.count))"
    )
    #expect(fixture.model.consumedAnchorCount == 1)

    resize(host, to: CGSize(width: 900, height: 768), regularWidth: true)
    settle(host.window)
    #expect(fixture.model.consumedAnchorCount == 1)
    #expect(fixture.model.firstSnapshotDecision == .present(target: nil))
  }

  // MARK: - Fixtures

  private struct ChatFixture {
    let model: ChatNativeSplitHarnessModel
    let coordinator: ChatCoordinator
    let contact: ContactDTO
    let conversations: [ChatRoute]
    let longMessageID: UUID?
  }

  private func makeChatFixture(includeLongBubble: Bool = false) async throws -> ChatFixture {
    let radioID = UUID(uuidString: "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")!
    let contact = makeContact(name: "Alex", radioID: radioID)
    let other = makeContact(name: "Sam", radioID: radioID)
    let channel = ChannelDTO(
      id: UUID(),
      radioID: radioID,
      index: 1,
      name: "General",
      secret: Data(repeating: 0x11, count: ProtocolLimits.channelSecretSize),
      isEnabled: true,
      lastMessageDate: nil,
      unreadCount: 0
    )
    let room = RemoteNodeSessionDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: Data(repeating: 0xCC, count: ProtocolLimits.publicKeySize),
      name: "Ops",
      role: .roomServer,
      isConnected: true
    )
    let conversations: [ChatRoute] = [
      .direct(contact),
      .direct(other),
      .channel(channel),
      .room(room),
    ]

    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting(
      conversationID: .dm(radioID: radioID, contactID: contact.id)
    )
    viewModel.bindCoordinatorForTesting(coordinator)

    var longID: UUID?
    var messages: [MessageDTO] = (0..<Layout.timelineCount).map { index in
      let incoming = index.isMultiple(of: 2)
      return makeMessage(
        id: UUID(),
        contactID: contact.id,
        radioID: radioID,
        text: incoming ? "short incoming \(index)" : "short outgoing \(index)",
        direction: incoming ? .incoming : .outgoing,
        timestampOffset: index
      )
    }
    if includeLongBubble {
      let id = UUID()
      longID = id
      let longText = Array(repeating: "wrapping phrase", count: Layout.longTextRepeatCount)
        .joined(separator: " ")
      messages[Layout.middleIndex] = makeMessage(
        id: id,
        contactID: contact.id,
        radioID: radioID,
        text: longText,
        direction: .outgoing,
        timestampOffset: Layout.middleIndex
      )
    }

    coordinator.replaceAllForTesting(messages)
    viewModel.buildItems()
    await coordinator.buildItemsTask?.value
    coordinator.markLoadedForTesting()

    let model = ChatNativeSplitHarnessModel(
      conversations: conversations,
      chatViewModel: viewModel,
      contact: contact
    )
    model.timelineItems = viewModel.items
    return ChatFixture(
      model: model,
      coordinator: coordinator,
      contact: contact,
      conversations: conversations,
      longMessageID: longID
    )
  }

  private func makeContact(name: String, radioID: UUID = UUID()) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: Data(repeating: 0xAB, count: ProtocolLimits.publicKeySize),
      name: name,
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      lastHeardTimestamp: nil,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
  }

  private func makeMessage(
    id: UUID,
    contactID: UUID,
    radioID: UUID,
    text: String,
    direction: MessageDirection,
    timestampOffset: Int
  ) -> MessageDTO {
    let timestamp = UInt32(1_700_000_000 + timestampOffset * 60)
    return MessageDTO(
      id: id,
      radioID: radioID,
      contactID: contactID,
      channelIndex: nil,
      text: text,
      timestamp: timestamp,
      createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)),
      direction: direction,
      status: .sent,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      senderKeyPrefix: direction == .incoming ? Data([0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45]) : nil,
      senderNodeName: direction == .incoming ? "Alex" : nil,
      isRead: true,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
  }

  // MARK: - Hosting

  private struct Host {
    let window: UIWindow
    let controller: UIHostingController<ChatNativeSplitHarness>
  }

  private func mountSelected(
    _ fixture: ChatFixture,
    size: CGSize = Layout.regularSize,
    regularWidth: Bool = true,
    regularHeight: Bool = true,
    additionalSafeAreaInsets: UIEdgeInsets = .zero,
    columnVisibility: NavigationSplitViewVisibility = .all
  ) throws -> Host {
    try #require(fixture.model.chatViewModel.items.count == Layout.timelineCount)
    fixture.model.horizontalSizeClass = regularWidth ? .regular : .compact
    fixture.model.select(fixture.conversations[0])
    fixture.model.columnVisibility = columnVisibility
    return try mount(
      model: fixture.model,
      size: size,
      regularWidth: regularWidth,
      regularHeight: regularHeight,
      additionalSafeAreaInsets: additionalSafeAreaInsets
    )
  }

  private func mount(
    model: ChatNativeSplitHarnessModel,
    size: CGSize,
    regularWidth: Bool,
    regularHeight: Bool = true,
    additionalSafeAreaInsets: UIEdgeInsets = .zero
  ) throws -> Host {
    let controller = UIHostingController(rootView: ChatNativeSplitHarness(model: model))
    let window = makeWindow(size: size)
    window.rootViewController = controller
    controller.additionalSafeAreaInsets = additionalSafeAreaInsets
    applySizeClass(controller, regularWidth: regularWidth, regularHeight: regularHeight)
    window.frame = CGRect(origin: .zero, size: size)
    controller.view.frame = window.bounds
    window.makeKeyAndVisible()
    window.layoutIfNeeded()
    settle(window)
    return Host(window: window, controller: controller)
  }

  private func makeWindow(size: CGSize) -> UIWindow {
    let frame = CGRect(origin: .zero, size: size)
    let window: UIWindow
    if let scene = UIApplication.shared.connectedScenes
      .compactMap({ $0 as? UIWindowScene })
      .first {
      window = UIWindow(windowScene: scene)
      window.frame = frame
    } else {
      window = UIWindow(frame: frame)
    }
    return window
  }

  private func applySizeClass(
    _ controller: UIViewController,
    regularWidth: Bool,
    regularHeight: Bool = true
  ) {
    controller.traitOverrides.horizontalSizeClass = regularWidth ? .regular : .compact
    controller.traitOverrides.verticalSizeClass = regularHeight ? .regular : .compact
  }

  private func resize(
    _ host: Host,
    to size: CGSize,
    regularWidth: Bool,
    regularHeight: Bool = true
  ) {
    host.window.frame = CGRect(origin: .zero, size: size)
    applySizeClass(host.controller, regularWidth: regularWidth, regularHeight: regularHeight)
    host.window.layoutIfNeeded()
    settle(host.window)
  }

  private func settle(_ window: UIWindow) {
    window.layoutIfNeeded()
    spin(Layout.settle)
  }

  private func spin(_ duration: TimeInterval) {
    RunLoop.main.run(until: Date(timeIntervalSinceNow: duration))
  }

  // MARK: - Geometry

  private struct GeometrySnapshot {
    var windowWidth: CGFloat
    var windowBounds: CGRect
    var detailFrame: CGRect
    var sidebarFrame: CGRect
    var collectionFrame: CGRect
    var leadingSafeArea: CGFloat
    var trailingSafeArea: CGFloat
    var firstVisibleMessageID: UUID?
    var consumedAnchorCount: Int

    var detailWidth: CGFloat {
      detailFrame.width
    }

    var sidebarWidth: CGFloat {
      sidebarFrame.width
    }

    var collectionWidth: CGFloat {
      collectionFrame.width
    }

    var summary: String {
      """
      window=\(windowBounds) sidebar=\(sidebarFrame) detail=\(detailFrame) \
      collection=\(collectionFrame) safeArea.leading=\(leadingSafeArea) \
      trailing=\(trailingSafeArea) firstVisible=\(String(describing: firstVisibleMessageID)) \
      consumed=\(consumedAnchorCount)
      """
    }
  }

  private func geometrySnapshot(
    host: Host,
    collectionView: UICollectionView?
  ) throws -> GeometrySnapshot {
    let model = host.controller.rootView.model
    let window = host.window
    let collectionFrame: CGRect = if let collectionView {
      collectionView.convert(collectionView.bounds, to: window)
    } else {
      .zero
    }
    return GeometrySnapshot(
      windowWidth: window.bounds.width,
      windowBounds: window.bounds,
      detailFrame: resolvedFrame(
        identifier: Identifier.detail,
        reported: model.detailBounds,
        in: window
      ),
      sidebarFrame: resolvedFrame(
        identifier: Identifier.sidebar,
        reported: model.sidebarBounds,
        in: window
      ),
      collectionFrame: collectionFrame,
      leadingSafeArea: host.controller.view.safeAreaInsets.left,
      trailingSafeArea: host.controller.view.safeAreaInsets.right,
      firstVisibleMessageID: firstVisibleMessageID(in: collectionView, items: model.chatViewModel.items),
      consumedAnchorCount: model.consumedAnchorCount
    )
  }

  private func resolvedFrame(identifier: String, reported: CGRect, in window: UIWindow) -> CGRect {
    if reported.width > 1, reported.height > 1 { return reported }
    if let view = viewWithIdentifier(identifier, in: window) {
      let frame = view.convert(view.bounds, to: window)
      if frame.width > 1 { return frame }
    }
    return reported
  }

  private func recordSnapshot(_ name: String, _ snapshot: GeometrySnapshot, extra: String = "") {
    let line = extra.isEmpty ? snapshot.summary : "\(snapshot.summary) \(extra)"
    print("GateA \(name): \(line)")
  }

  private func requireCollection(
    _ host: Host,
    itemCount: Int
  ) throws -> (collectionView: UICollectionView, messagesSection: Int) {
    try #require(
      waitForCollectionView(in: host.window, itemCount: itemCount),
      "\(describeSplit(host, expectedItems: itemCount))"
    )
  }

  private func requireItem(
    _ id: UUID,
    host: Host,
    items: [MessageItem]
  ) throws -> (collectionView: UICollectionView, messagesSection: Int) {
    try #require(
      waitForItem(id, in: host.window, items: items),
      "missing \(id) in \(describeSplit(host, expectedItems: items.count))"
    )
  }

  private func waitForItem(
    _ id: UUID,
    in window: UIWindow,
    items: [MessageItem],
    timeout: TimeInterval = Layout.collectionWaitTimeout
  ) -> (collectionView: UICollectionView, messagesSection: Int)? {
    let deadline = Date(timeIntervalSinceNow: timeout)
    while Date() < deadline {
      RunLoop.main.run(until: Date(timeIntervalSinceNow: Layout.runLoopSlice))
      window.layoutIfNeeded()
      guard let itemIndex = items.firstIndex(where: { $0.id == id }) else { continue }
      for collectionView in collectionViews(in: window) {
        for section in 0..<collectionView.numberOfSections
          where collectionView.numberOfItems(inSection: section) > itemIndex {
          if collectionView.layoutAttributesForItem(
            at: IndexPath(item: itemIndex, section: section)
          ) != nil {
            return (collectionView, section)
          }
        }
      }
    }
    return nil
  }

  private func describeSplit(_ host: Host, expectedItems: Int) -> String {
    let model = host.controller.rootView.model
    return """
    items=\(model.chatViewModel.items.count) expected=\(expectedItems) \
    selected=\(String(describing: model.selectedRoute?.conversationID)) \
    preferred=\(String(describing: model.preferredCompactColumn)) \
    visibility=\(String(describing: model.columnVisibility)) \
    sizeClass=\(String(describing: model.horizontalSizeClass)) \
    sidebar=\(model.sidebarBounds) detail=\(model.detailBounds) \
    window=\(host.window.bounds) \
    collections=[\(describeCollectionViews(in: host.window))] \
    navs=[\(describeNavControllers(from: host.controller))] \
    bars=[\(describeNavigationBars(in: host.window))] \
    controls=[\(describeControls(in: host.window))]
    """
  }

  private func describeNavControllers(from root: UIViewController?) -> String {
    let navs = navigationControllers(from: root)
    if navs.isEmpty { return "none" }
    return navs.map { nav in
      let titles = nav.viewControllers.map { $0.navigationItem.title ?? String(describing: type(of: $0)) }
      return "count=\(nav.viewControllers.count) titles=\(titles) back=\(nav.navigationBar.backItem?.title ?? "nil") top=\(nav.topViewController?.navigationItem.title ?? "nil")"
    }.joined(separator: "; ")
  }

  private func describeCollectionViews(in view: UIView) -> String {
    var parts: [String] = []
    func walk(_ view: UIView) {
      if let collectionView = view as? UICollectionView {
        let items = (0..<collectionView.numberOfSections).map {
          collectionView.numberOfItems(inSection: $0)
        }
        parts.append(
          "frame=\(collectionView.frame) bounds=\(collectionView.bounds) content=\(collectionView.contentSize) items=\(items)"
        )
      }
      view.subviews.forEach(walk)
    }
    walk(view)
    return parts.isEmpty ? "none" : parts.joined(separator: "; ")
  }

  private func describeNavigationBars(in view: UIView) -> String {
    var parts: [String] = []
    func walk(_ view: UIView) {
      if let bar = view as? UINavigationBar {
        parts.append(
          "title=\(bar.topItem?.title ?? "nil") back=\(bar.backItem?.title ?? "nil") buttons=\(bar.items?.count ?? 0)"
        )
      }
      view.subviews.forEach(walk)
    }
    walk(view)
    return parts.isEmpty ? "none" : parts.joined(separator: "; ")
  }

  private func describeControls(in view: UIView) -> String {
    var parts: [String] = []
    func walk(_ view: UIView) {
      if let control = view as? UIControl {
        parts.append(
          "\(type(of: control)) label=\(control.accessibilityLabel ?? "") id=\(control.accessibilityIdentifier ?? "")"
        )
      }
      view.subviews.forEach(walk)
    }
    walk(view)
    return parts.isEmpty ? "none" : parts.joined(separator: "; ")
  }

  private func waitForCollectionView(
    in window: UIWindow,
    itemCount: Int,
    timeout: TimeInterval = Layout.collectionWaitTimeout
  ) -> (collectionView: UICollectionView, messagesSection: Int)? {
    let deadline = Date(timeIntervalSinceNow: timeout)
    while Date() < deadline {
      RunLoop.main.run(until: Date(timeIntervalSinceNow: Layout.runLoopSlice))
      window.layoutIfNeeded()
      guard let collectionView = findCollectionView(in: window) else { continue }
      for section in 0..<collectionView.numberOfSections
        where collectionView.numberOfItems(inSection: section) == itemCount {
        if collectionView.bounds.width > 0 {
          return (collectionView, section)
        }
      }
    }
    return nil
  }

  private func collectionViews(in view: UIView) -> [UICollectionView] {
    var result: [UICollectionView] = []
    func walk(_ view: UIView) {
      if let collectionView = view as? UICollectionView {
        result.append(collectionView)
      }
      view.subviews.forEach(walk)
    }
    walk(view)
    return result
  }

  private func findCollectionView(in view: UIView) -> UICollectionView? {
    collectionViews(in: view).max { lhs, rhs in
      let left = (0..<lhs.numberOfSections).map { lhs.numberOfItems(inSection: $0) }.max() ?? 0
      let right = (0..<rhs.numberOfSections).map { rhs.numberOfItems(inSection: $0) }.max() ?? 0
      return left < right
    }
  }

  private func viewWithIdentifier(_ identifier: String, in view: UIView) -> UIView? {
    if view.accessibilityIdentifier == identifier { return view }
    for subview in view.subviews {
      if let found = viewWithIdentifier(identifier, in: subview) { return found }
    }
    return nil
  }

  private func waitForView(
    withIdentifier identifier: String,
    in window: UIWindow,
    timeout: TimeInterval = Layout.collectionWaitTimeout
  ) -> UIView? {
    let deadline = Date(timeIntervalSinceNow: timeout)
    while Date() < deadline {
      window.layoutIfNeeded()
      if let view = viewWithIdentifier(identifier, in: window) { return view }
      RunLoop.main.run(until: Date(timeIntervalSinceNow: Layout.runLoopSlice))
    }
    return nil
  }

  private func waitUntil(
    timeout: TimeInterval = Layout.collectionWaitTimeout,
    _ condition: () -> Bool
  ) -> Bool {
    let deadline = Date(timeIntervalSinceNow: timeout)
    while Date() < deadline {
      if condition() { return true }
      RunLoop.main.run(until: Date(timeIntervalSinceNow: Layout.runLoopSlice))
    }
    return condition()
  }

  private func screenFrame(
    of id: UUID,
    in found: (collectionView: UICollectionView, messagesSection: Int),
    items: [MessageItem]
  ) throws -> CGRect {
    let itemIndex = try #require(items.firstIndex(where: { $0.id == id }))
    let attributes = try #require(found.collectionView.layoutAttributesForItem(
      at: IndexPath(item: itemIndex, section: found.messagesSection)
    ))
    return found.collectionView.convert(attributes.frame, to: nil)
  }

  private func isItemVisible(
    _ id: UUID,
    in found: (collectionView: UICollectionView, messagesSection: Int),
    items: [MessageItem]
  ) -> Bool {
    guard let itemIndex = items.firstIndex(where: { $0.id == id }),
          let attributes = found.collectionView.layoutAttributesForItem(
            at: IndexPath(item: itemIndex, section: found.messagesSection)
          )
    else { return false }
    return found.collectionView.bounds.intersects(attributes.frame)
  }

  private func firstVisibleMessageID(
    in collectionView: UICollectionView?,
    items: [MessageItem]
  ) -> UUID? {
    guard let collectionView else { return nil }
    for section in 0..<collectionView.numberOfSections {
      for item in 0..<collectionView.numberOfItems(inSection: section) {
        let path = IndexPath(item: item, section: section)
        guard let attributes = collectionView.layoutAttributesForItem(at: path),
              collectionView.bounds.intersects(attributes.frame),
              items.indices.contains(item)
        else { continue }
        return items[item].id
      }
    }
    return nil
  }

  private func navigationControllers(from root: UIViewController?) -> [UINavigationController] {
    guard let root else { return [] }
    var result: [UINavigationController] = []
    var seen = Set<ObjectIdentifier>()
    func walk(_ viewController: UIViewController) {
      let id = ObjectIdentifier(viewController)
      guard seen.insert(id).inserted else { return }
      if let nav = viewController as? UINavigationController {
        result.append(nav)
        nav.viewControllers.forEach(walk)
      }
      viewController.children.forEach(walk)
    }
    walk(root)
    return result
  }
}

// MARK: - Chat harness

@Observable
@MainActor
private final class ChatNativeSplitHarnessModel {
  var selectedTab: Int = AppTab.chats.rawValue
  var selectedRoute: ChatRoute?
  var columnVisibility: NavigationSplitViewVisibility = .all
  var preferredCompactColumn: NavigationSplitViewColumn = .sidebar
  var horizontalSizeClass: UserInterfaceSizeClass = .compact
  var windowBounds: CGRect = .zero
  var detailBounds: CGRect = .zero
  var sidebarBounds: CGRect = .zero
  var isAtBottom = true
  var unreadCount = 0
  var scrollToBottomRequest = 0
  var scrollToTargetRequest = 0
  var scrollToTargetID: UUID?
  var composingText = ""
  var inputFocusRequest = 0
  var consumedAnchorCount = 0
  var displayEpoch = 0
  var timelineItems: [MessageItem] = []
  var firstSnapshotDecision: ChatInitialScrollPolicy.FirstSnapshotDecision = .present(target: nil)
  var selectedMessageForActions: MessageDTO?
  var imageViewerData: ImageViewerData?

  let conversations: [ChatRoute]
  let chatViewModel: ChatViewModel
  let contact: ContactDTO
  let appState = AppState()
  let recentEmojisStore = RecentEmojisStore()

  init(conversations: [ChatRoute], chatViewModel: ChatViewModel, contact: ContactDTO) {
    self.conversations = conversations
    self.chatViewModel = chatViewModel
    self.contact = contact
  }

  func select(_ route: ChatRoute) {
    selectedRoute = route
    if horizontalSizeClass == .compact {
      preferredCompactColumn = .detail
    }
    applyPresentationRecipe()
  }

  var tabBarVisibility: Visibility {
    ChatsSplitPresentation.tabBarVisibility(
      sizeClass: horizontalSizeClass,
      preferredColumn: preferredCompactColumn,
      hasSelection: selectedRoute != nil
    )
  }

  func applyPresentationRecipe() {}

  func handlePreferredColumnChange() {
    switch ChatsSplitPresentation.preferredColumnAction(
      preferredColumn: preferredCompactColumn,
      sizeClass: horizontalSizeClass,
      nestedPathIsEmpty: true
    ) {
    case .clearRootSelection:
      selectedRoute = nil
    case .none:
      break
    }
    applyPresentationRecipe()
  }
}

private struct ChatNativeSplitHarness: View {
  @Bindable var model: ChatNativeSplitHarnessModel
  @Environment(\.horizontalSizeClass) private var sizeClass

  var body: some View {
    TabView(selection: $model.selectedTab) {
      Tab("Chats", systemImage: "message.fill", value: AppTab.chats.rawValue) {
        chatsSplit
      }
      Tab("Nodes", systemImage: "flipphone", value: AppTab.nodes.rawValue) {
        Text("Nodes")
      }
    }
    .toolbarVisibility(model.tabBarVisibility, for: .tabBar)
    .environment(\.appState, model.appState)
    .themedChrome(.default)
    .onAppear { syncTraits() }
    .onChange(of: sizeClass) { _, _ in syncTraits() }
  }

  private var chatsSplit: some View {
    NavigationSplitView(
      columnVisibility: $model.columnVisibility,
      preferredCompactColumn: $model.preferredCompactColumn
    ) {
      sidebar
    } detail: {
      NavigationStack {
        detailRoot
      }
      .background {
        GateAIdentifierView(identifier: "gateA.detail")
      }
      .onGeometryChange(for: CGRect.self) { proxy in
        proxy.frame(in: .global)
      } action: { frame in
        model.detailBounds = frame
      }
    }
    .navigationSplitViewStyle(.balanced)
    .onChange(of: model.preferredCompactColumn) { _, _ in
      model.handlePreferredColumnChange()
    }
    .onChange(of: model.selectedRoute) { _, _ in
      model.applyPresentationRecipe()
    }
  }

  private var sidebar: some View {
    ScrollView {
      LazyVStack(alignment: .leading, spacing: 0) {
        ForEach(model.conversations, id: \.conversationID) { route in
          Button {
            model.select(route)
          } label: {
            Text(title(for: route))
              .frame(maxWidth: .infinity, alignment: .leading)
              .padding(.horizontal, 16)
              .padding(.vertical, 12)
              .contentShape(.rect)
          }
          .buttonStyle(.plain)
          .selectedRowHighlight(isSelected: model.selectedRoute == route)
          .accessibilityAddTraits(model.selectedRoute == route ? .isSelected : [])
          .accessibilityIdentifier("gateA.row.\(route.conversationID.uuidString)")
        }
      }
    }
    .navigationTitle("Chats")
    .background {
      GateAIdentifierView(identifier: "gateA.sidebar")
    }
    .onGeometryChange(for: CGRect.self) { proxy in
      proxy.frame(in: .global)
    } action: { frame in
      model.sidebarBounds = frame
    }
  }

  @ViewBuilder
  private var detailRoot: some View {
    if model.selectedRoute != nil {
      conversation
        .navigationTitle(title(for: model.selectedRoute))
    } else {
      ContentUnavailableView("Select a conversation", systemImage: "message")
    }
  }

  private var conversation: some View {
    _ = model.displayEpoch
    return ChatTiledView(
      items: model.timelineItems,
      cellContent: { item in
        cellFactory.makeContent(for: item)
          .overlay {
            GateAIdentifierView(identifier: "gateA.message.\(item.id.uuidString)")
              .allowsHitTesting(false)
          }
      },
      contentBackground: Theme.default.surfaces?.canvas,
      isAtBottom: $model.isAtBottom,
      unreadCount: $model.unreadCount,
      scrollToBottomRequest: model.scrollToBottomRequest,
      countsTowardUnread: { !$0.envelope.isOutgoing },
      scrollToTargetRequest: model.scrollToTargetRequest,
      scrollTargetID: model.scrollToTargetID,
      initialScrollTargetID: initialScrollTargetID,
      onInitialTargetConsumed: {
        model.consumedAnchorCount += 1
        model.firstSnapshotDecision = .present(target: nil)
      }
    )
    .safeAreaInset(edge: .bottom, spacing: 0) {
      ChatConversationInputBar(
        conversationType: .dm(model.contact),
        composingText: $model.composingText,
        focusRequest: $model.inputFocusRequest,
        nodeNameByteCount: 0,
        onSend: { _ in },
        onWillSend: { model.scrollToBottomRequest += 1 },
        onFocus: { model.scrollToBottomRequest += 1 }
      )
      .chatKeyboardLiftPadding()
      .background {
        GateAIdentifierView(identifier: "gateA.composer")
      }
    }
    .chatKeyboardOwnedLift()
  }

  private var initialScrollTargetID: UUID? {
    if case let .present(target) = model.firstSnapshotDecision { return target }
    return nil
  }

  private var cellFactory: ChatCellContentFactory {
    ChatCellContentFactory(
      contactName: model.contact.displayName,
      deviceName: "Device",
      configuration: .directMessage,
      theme: .default,
      openURL: OpenURLAction { _ in .discarded },
      resolver: BubbleResolver(viewModel: model.chatViewModel),
      actions: BubbleActions(
        onRetryMessage: { _ in },
        onLongPress: { _ in },
        onImageTap: { _ in },
        onRetryInlineImage: { _ in },
        onRequestPreviewFetch: { _ in },
        onManualPreviewFetch: { _ in },
        onMapPreviewTap: { _ in },
        snapshotResolver: { _ in nil },
        requestSnapshot: { _ in },
        retrySnapshot: { _ in },
        onTranslationAction: { _ in }
      )
    )
  }

  private func title(for route: ChatRoute?) -> String {
    switch route {
    case let .direct(contact):
      contact.displayName
    case let .channel(channel):
      channel.displayName
    case let .room(session):
      session.name
    case .none:
      "Chats"
    }
  }

  private func syncTraits() {
    let old = model.horizontalSizeClass
    let new = sizeClass ?? .compact
    model.horizontalSizeClass = new
    let presentation = ChatsSplitPresentation.presentationForSizeClassChange(
      from: old,
      to: new,
      hasSelection: model.selectedRoute != nil
    )
    if let visibility = presentation.columnVisibility {
      model.columnVisibility = visibility
    }
    if let column = presentation.preferredColumn {
      model.preferredCompactColumn = column
    }
    model.applyPresentationRecipe()
  }
}

// MARK: - Hosted identifier

/// UIKit identifier probe. Liquid Glass often does not copy SwiftUI
/// `.accessibilityIdentifier` onto `UIView.accessibilityIdentifier`.
private struct GateAIdentifierView: UIViewRepresentable {
  var identifier: String

  func makeUIView(context: Context) -> UIView {
    let view = UIView()
    view.isUserInteractionEnabled = false
    view.backgroundColor = .clear
    view.accessibilityIdentifier = identifier
    return view
  }

  func updateUIView(_ uiView: UIView, context: Context) {
    uiView.accessibilityIdentifier = identifier
  }
}
