@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing
import UIKit

@Suite("Adaptive navigation", .serialized)
@MainActor
struct AdaptiveNavigationTests {
  private enum Layout {
    static let regularSize = CGSize(width: 1024, height: 768)
    static let compactSize = CGSize(width: 390, height: 844)
    static let compactLandscapeSize = CGSize(width: 844, height: 390)
    static let waitTimeout: TimeInterval = 6
    static let runLoopSlice: TimeInterval = 0.05
    static let settle: TimeInterval = 0.35
    static let savedHistoryIndexPath = IndexPath(item: 2, section: 1)
    static let nicknameIndexPath = IndexPath(item: 0, section: 2)
    static let contactSectionCounts = [1, 6, 2, 5, 3, 3]
    static let nicknameSelectionLength = 6
    static let nicknameCaretOffset = 2
    static let nicknameIntentionalCaretOffset = 4
  }

  // MARK: - Presentation recipe

  @Test
  func `collapsed compact detail hides tabs`() {
    let visibility = SectionSplitPresentation.tabBarVisibility(
      sizeClass: .compact,
      columnVisibility: .all,
      tabBarPlacement: .bottomBar,
      preferredColumn: .detail,
      hasSelection: true
    )
    #expect(visibility == .hidden)
  }

  @Test
  func `compact sidebar leaves the tab bar automatic`() {
    let visibility = SectionSplitPresentation.tabBarVisibility(
      sizeClass: .compact,
      columnVisibility: .all,
      tabBarPlacement: .bottomBar,
      preferredColumn: .sidebar,
      hasSelection: false
    )
    #expect(visibility == .automatic)
  }

  @Test(arguments: [NavigationSplitViewVisibility.all, .detailOnly], [false, true])
  func `regular chat hides only bottom tabs when the selected detail is alone`(
    columnVisibility: NavigationSplitViewVisibility,
    hasSelection: Bool
  ) {
    for placement: TabBarPlacement? in [.bottomBar, .topBar, .sidebar, nil] {
      let visibility = SectionSplitPresentation.tabBarVisibility(
        sizeClass: .regular,
        columnVisibility: columnVisibility,
        tabBarPlacement: placement,
        preferredColumn: .sidebar,
        hasSelection: hasSelection
      )
      let hidesBottomTabs = placement == .bottomBar && columnVisibility == .detailOnly && hasSelection
      #expect(visibility == (hidesBottomTabs ? .hidden : .automatic))
    }
  }

  @Test
  func `compact sidebar preferred column clears the root`() {
    let action = SectionSplitPresentation.preferredColumnAction(
      preferredColumn: .sidebar,
      sizeClass: .compact,
      nestedPathIsEmpty: true
    )
    #expect(action == .clearRootSelection)
  }

  @Test
  func `nested path keeps the root when compact preferred column is sidebar`() {
    let action = SectionSplitPresentation.preferredColumnAction(
      preferredColumn: .sidebar,
      sizeClass: .compact,
      nestedPathIsEmpty: false
    )
    #expect(action == .none)
  }

  @Test
  func `regular preferred sidebar does not overlay the selected detail`() {
    let action = SectionSplitPresentation.preferredColumnAction(
      preferredColumn: .sidebar,
      sizeClass: .regular,
      nestedPathIsEmpty: true
    )
    #expect(action == .none)
  }

  @Test
  func `expanding compact to regular tiles list and detail`() {
    let presentation = SectionSplitPresentation.presentationForSizeClassChange(
      from: .compact,
      to: .regular,
      hasSelection: true
    )
    #expect(presentation.columnVisibility == .all)
    #expect(presentation.preferredColumn == .sidebar)
  }

  @Test
  func `collapsing regular to compact with a selection shows the detail`() {
    let presentation = SectionSplitPresentation.presentationForSizeClassChange(
      from: .regular,
      to: .compact,
      hasSelection: true
    )
    #expect(presentation.columnVisibility == nil)
    #expect(presentation.preferredColumn == .detail)
  }

  @Test
  func `collapsing regular to compact without a selection leaves the columns alone`() {
    let presentation = SectionSplitPresentation.presentationForSizeClassChange(
      from: .regular,
      to: .compact,
      hasSelection: false
    )
    #expect(presentation.columnVisibility == nil)
    #expect(presentation.preferredColumn == nil)
  }

  @Test
  func `compact selection prefers detail and a regular width leaves the column alone`() {
    #expect(SectionSplitPresentation.preferredColumn(sizeClass: .compact, hasSelection: true) == .detail)
    #expect(SectionSplitPresentation.preferredColumn(sizeClass: .compact, hasSelection: false) == .sidebar)
    #expect(SectionSplitPresentation.preferredColumn(sizeClass: .regular, hasSelection: true) == nil)
    #expect(SectionSplitPresentation.preferredColumn(sizeClass: .regular, hasSelection: false) == nil)
  }

  // MARK: - Radio-scoped auth

  @Test
  func `room auth is kept only for the connected radio that opened it`() {
    let radioID = UUID()
    #expect(
      ChatsRadioScopedSheets.shouldKeepRoomAuth(
        sessionRadioID: radioID,
        currentRadioID: radioID,
        hasConnectedDevice: true
      )
    )
    #expect(
      !ChatsRadioScopedSheets.shouldKeepRoomAuth(
        sessionRadioID: radioID,
        currentRadioID: radioID,
        hasConnectedDevice: false
      )
    )
    #expect(
      !ChatsRadioScopedSheets.shouldKeepRoomAuth(
        sessionRadioID: radioID,
        currentRadioID: UUID(),
        hasConnectedDevice: true
      )
    )
  }

  // MARK: - Scroll request delivery

  @Test
  func `scroll target is preserved until the conversation can honor it`() async {
    let appState = AppState()
    let contact = makeContact()
    let messageID = UUID()
    let timeline = ChatTimeline(role: .interactive)
    timeline.bind(
      ChatCoordinator.makeForTesting(),
      dataStore: { nil }, senderTables: { .empty }, postApply: nil
    )
    timeline.admit(MessageDTO(from: Message(
      id: messageID, radioID: contact.radioID, contactID: contact.id, text: "Target"
    )))
    appState.navigation.navigateToChat(with: contact, scrollToMessageID: messageID)

    let skipped = await ChatScrollRequestDelivery.takeIfHonorable(
      from: appState.navigation,
      kind: .direct,
      conversationID: contact.id,
      canHonor: false,
      timeline: timeline,
      loadOlder: { Issue.record("An unready conversation must not page") }
    )
    #expect(skipped == nil)
    #expect(appState.navigation.pendingScrollToMessageID == messageID)

    let taken = await ChatScrollRequestDelivery.takeIfHonorable(
      from: appState.navigation,
      kind: .direct,
      conversationID: contact.id,
      canHonor: true,
      timeline: timeline,
      loadOlder: { Issue.record("An already loaded target must not page") }
    )
    #expect(taken == messageID)
    #expect(appState.navigation.pendingScrollTarget == nil)
  }

  // MARK: - Hosted ChatsView

  @Test(arguments: [ChatRoute.Kind.direct, .channel, .room])
  func `hosted chats split back from collapsed root clears the route`(
    _ kind: ChatRoute.Kind
  ) throws {
    let appState = AppState()
    let route = makeRoute(kind: kind)
    appState.navigation.chatsSelectedRoute = route
    appState.navigation.selectedTab = AppTab.chats.rawValue

    let host = try mount(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    try #require(
      waitUntil { appState.navigation.chatsSelectedRoute == route },
      "\(describe(host, appState: appState))"
    )

    let didBack = performNativeBack(in: host.window)
    #expect(didBack, "\(describe(host, appState: appState))")
    try #require(
      waitUntil { appState.navigation.chatsSelectedRoute == nil },
      "\(describe(host, appState: appState))"
    )
  }

  @Test(arguments: [ChatRoute.Kind.direct, .channel, .room])
  func `hosted chats split keeps the route across compact and regular`(
    _ kind: ChatRoute.Kind
  ) throws {
    let appState = AppState()
    let route = makeRoute(kind: kind)
    appState.navigation.chatsSelectedRoute = route
    appState.navigation.selectedTab = AppTab.chats.rawValue

    let host = try mount(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    resize(host, to: Layout.regularSize, regularWidth: true)
    #expect(appState.navigation.chatsSelectedRoute == route)

    resize(host, to: Layout.compactSize, regularWidth: false)
    #expect(appState.navigation.chatsSelectedRoute == route)

    let didBack = performNativeBack(in: host.window)
    #expect(didBack, "\(describe(host, appState: appState))")
    try #require(
      waitUntil { appState.navigation.chatsSelectedRoute == nil },
      "\(describe(host, appState: appState))"
    )

    resize(host, to: Layout.regularSize, regularWidth: true)
    #expect(appState.navigation.chatsSelectedRoute == nil)
  }

  @Test
  func `programmatic chat navigation from another tab selects the route`() throws {
    let appState = AppState()
    appState.navigation.selectedTab = AppTab.nodes.rawValue
    let contact = makeContact()

    let host = try mount(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    appState.navigation.navigateToChat(with: contact)
    settle(host.window)

    #expect(appState.navigation.selectedTab == AppTab.chats.rawValue)
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
    try #require(
      waitUntil { appState.navigation.chatsSelectedRoute == .direct(contact) },
      "\(describe(host, appState: appState))"
    )
  }

  @Test
  func `explicit root navigation bumps generation without changing identity`() {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.navigateToChat(with: contact)
    let generation = appState.navigation.chatsRootNavigationGeneration
    appState.navigation.navigateToChat(with: contact)
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
    #expect(appState.navigation.chatsRootNavigationGeneration == generation + 1)
  }

  // MARK: - Hosted Nodes

  @Test
  func `production contact editor and live sheet survive navigation container replacement`() async throws {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.navigateToContactDetail(contact)
    let state = ContactDetailState(contact: contact)
    state.nickname = "Unsent nickname"
    state.isEditingNickname = true
    state.nicknameFocused = true
    state.scrollSection = .info
    let routes = NodesNavigationState(presentation: .compact)
    let host = try mount(
      ContactContinuityGateHost(appState: appState, state: state, routes: routes),
      size: Layout.compactSize, regularWidth: false
    )
    defer { dismount(host); state.end() }

    try #require(await waitFor { nicknameField(in: host.window)?.isFirstResponder == true })
    let selectionEnd = state.nickname.index(state.nickname.startIndex, offsetBy: Layout.nicknameSelectionLength)
    state.nicknameSelection = TextSelection(range: state.nickname.startIndex..<selectionEnd)
    try #require(await waitFor { nicknameSelectionIsRetained(in: host.window) })
    resize(host, to: Layout.regularSize, regularWidth: true)
    try #require(await waitFor { nicknameField(in: host.window)?.isFirstResponder == true },
                 "\(describeEditor(host, state: state))")
    #expect(nicknameField(in: host.window)?.text == "Unsent nickname")
    try #require(await waitFor { nicknameSelectionIsRetained(in: host.window) }, "\(describeEditor(host, state: state))")
    resize(host, to: Layout.compactSize, regularWidth: false)
    try #require(await waitFor { nicknameField(in: host.window)?.isFirstResponder == true })
    try #require(await waitFor { nicknameSelectionIsRetained(in: host.window) }, "\(describeEditor(host, state: state))")
    #expect(state.isEditingNickname)
    #expect(state.nickname == "Unsent nickname")

    let field = try #require(nicknameField(in: host.window))
    let caret = try #require(field.position(from: field.beginningOfDocument, offset: Layout.nicknameCaretOffset))
    field.selectedTextRange = field.textRange(from: caret, to: caret)
    field.delegate?.textFieldDidChangeSelection?(field)
    let caretIndex = state.nickname.index(state.nickname.startIndex, offsetBy: Layout.nicknameCaretOffset)
    try #require(await waitFor {
      if case let .selection(range) = state.nicknameSelection?.indices {
        return range == caretIndex..<caretIndex
      }
      return false
    })

    state.nicknameFocused = false
    state.showQRShareSheet = true
    try #require(await waitFor { host.controller.presentedViewController != nil })
    let sheet = try #require(host.controller.presentedViewController)
    resize(host, to: Layout.regularSize, regularWidth: true)
    #expect(host.controller.presentedViewController === sheet)
    #expect(state.showQRShareSheet)
    resize(host, to: Layout.compactSize, regularWidth: false)
    #expect(host.controller.presentedViewController === sheet)
    #expect(state.showQRShareSheet)

    state.showQRShareSheet = false
    try #require(await waitFor { host.controller.presentedViewController == nil })
    let unfocusedField = try #require(nicknameField(in: host.window))
    #expect(!unfocusedField.isFirstResponder)
    let intendedCaret = try #require(unfocusedField.position(
      from: unfocusedField.beginningOfDocument, offset: Layout.nicknameIntentionalCaretOffset
    ))
    unfocusedField.selectedTextRange = unfocusedField.textRange(from: intendedCaret, to: intendedCaret)
    unfocusedField.delegate?.textFieldDidChangeSelection?(unfocusedField)
    try #require(unfocusedField.becomeFirstResponder())
    try #require(await waitFor { state.nicknameFocused })
    let nativeSelection = try #require(unfocusedField.selectedTextRange)
    let nativeStart = unfocusedField.offset(from: unfocusedField.beginningOfDocument, to: nativeSelection.start)
    let nativeEnd = unfocusedField.offset(from: unfocusedField.beginningOfDocument, to: nativeSelection.end)
    #expect(nativeStart != Layout.nicknameCaretOffset)
    let nativeRange = String.Index(utf16Offset: nativeStart, in: state.nickname)..<String.Index(
      utf16Offset: nativeEnd, in: state.nickname
    )
    try #require(await waitFor {
      if case let .selection(range) = state.nicknameSelection?.indices { return range == nativeRange }
      return false
    })
    unfocusedField.selectedTextRange = unfocusedField.textRange(from: intendedCaret, to: intendedCaret)
    unfocusedField.delegate?.textFieldDidChangeSelection?(unfocusedField)
    let intendedIndex = state.nickname.index(state.nickname.startIndex, offsetBy: Layout.nicknameIntentionalCaretOffset)
    try #require(await waitFor {
      if case let .selection(range) = state.nicknameSelection?.indices {
        return range == intendedIndex..<intendedIndex
      }
      return false
    }, "\(describeEditor(host, state: state))")
  }

  @Test
  func `naturally scrolled contact info survives resizing without focus`() async throws {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.navigateToContactDetail(contact)
    let state = ContactDetailState(contact: contact)
    state.nickname = "Unsent nickname"
    let routes = NodesNavigationState(presentation: .compact)
    let host = try mount(
      ContactContinuityGateHost(appState: appState, state: state, routes: routes),
      size: Layout.compactSize, regularWidth: false
    )
    defer { dismount(host); state.end() }

    let list = try #require(descendantViews(in: host.window, of: UICollectionView.self).first)
    #expect((0..<list.numberOfSections).map { list.numberOfItems(inSection: $0) } == Layout.contactSectionCounts)
    list.scrollToItem(at: Layout.nicknameIndexPath, at: .top, animated: false)
    settle(host.window)
    try #require(await waitFor { state.scrollSection == .info }, "\(describeEditor(host, state: state))")
    state.isEditingNickname = true
    try #require(await waitFor { nicknameField(in: host.window) != nil })
    #expect(!state.nicknameFocused)
    #expect(firstVisibleContactRow(in: host.window) == Layout.nicknameIndexPath)

    resize(host, to: Layout.regularSize, regularWidth: true)
    try #require(await waitFor { firstVisibleContactRow(in: host.window) == Layout.nicknameIndexPath },
                 "\(describeEditor(host, state: state))")
    #expect(state.scrollSection == .info)
    #expect(state.isEditingNickname)
    #expect(state.nickname == "Unsent nickname")
    resize(host, to: Layout.compactSize, regularWidth: false)
    try #require(await waitFor { firstVisibleContactRow(in: host.window) == Layout.nicknameIndexPath },
                 "\(describeEditor(host, state: state))")
    #expect(state.scrollSection == .info)
  }

  @Test
  func `saved contact actions restore without focus`() async throws {
    let appState = AppState()
    let contact = makeContact()
    let state = ContactDetailState(contact: contact)
    state.scrollSection = .actions
    let routes = NodesNavigationState(presentation: .compact)
    let host = try mount(
      ContactContinuityGateHost(appState: appState, state: state, routes: routes),
      size: Layout.compactSize, regularWidth: false
    )
    defer { dismount(host); state.end() }

    try #require(await waitFor {
      guard let navigation = navigationControllers(from: host.controller)
        .first(where: { viewIsVisible($0.view, in: host.window) }),
        let source = navigation.topViewController?.viewIfLoaded else { return false }
      return navigation.viewControllers.count == 1
        && navigation.transitionCoordinator == nil
        && viewIsVisible(source, in: host.window)
    })
    try #require(state.rendererID == nil)
    try #require(appState.navigation.selectedContact == nil)
    appState.navigation.navigateToContactDetail(contact)

    let actionsRow = IndexPath(item: 0, section: Layout.savedHistoryIndexPath.section)
    try #require(await waitFor { firstVisibleContactRow(in: host.window) == actionsRow },
                 "\(describeContactScroll(host, state: state))")
    #expect(state.scrollSection == .actions)
    resize(host, to: Layout.regularSize, regularWidth: true)
    try #require(await waitFor { firstVisibleContactRow(in: host.window) == actionsRow },
                 "\(describeContactScroll(host, state: state))")
    #expect(state.scrollSection == .actions)
    resize(host, to: Layout.compactSize, regularWidth: false)
    try #require(await waitFor { firstVisibleContactRow(in: host.window) == actionsRow },
                 "\(describeContactScroll(host, state: state))")
    #expect(state.scrollSection == .actions)
  }

  private func firstVisibleContactRow(in window: UIWindow) -> IndexPath? {
    guard let list = descendantViews(in: window, of: UICollectionView.self)
      .first(where: { viewIsVisible($0, in: window) }) else { return nil }
    let viewport = list.bounds.inset(by: list.adjustedContentInset)
    return list.indexPathsForVisibleItems.filter { index in
      index.item == 0 && list.layoutAttributesForItem(at: index)?.frame.intersects(viewport) == true
    }.min()
  }

  private func describeContactScroll(_ host: Host, state: ContactDetailState) -> String {
    let lists = descendantViews(in: host.window, of: UICollectionView.self).enumerated().map { index, list in
      let rows = list.indexPathsForVisibleItems.sorted().map { path in
        "\(path)=\(String(describing: list.layoutAttributesForItem(at: path)?.frame))"
      }
      return "list\(index) visible=\(viewIsVisible(list, in: host.window)) bounds=\(list.bounds) "
        + "offset=\(list.contentOffset) inset=\(list.adjustedContentInset) content=\(list.contentSize) rows=\(rows)"
    }
    return describeEditor(host, state: state)
      + " first=\(String(describing: firstVisibleContactRow(in: host.window))) lists=\(lists)"
  }

  private func nicknameField(in window: UIWindow) -> UITextField? {
    descendantViews(in: window, of: UITextField.self).first {
      $0.text == "Unsent nickname" && viewIsVisible($0, in: window)
    }
  }

  private func nicknameSelectionIsRetained(in window: UIWindow) -> Bool {
    guard let field = nicknameField(in: window), let selected = field.selectedTextRange else { return false }
    return field.offset(from: field.beginningOfDocument, to: selected.start) == 0
      && field.offset(from: selected.start, to: selected.end) == Layout.nicknameSelectionLength
  }

  private func describeEditor(_ host: Host, state: ContactDetailState) -> String {
    let fields = descendantViews(in: host.window, of: UITextField.self).map { field in
      let range = field.selectedTextRange.map { range in
        [field.offset(from: field.beginningOfDocument, to: range.start), field.offset(from: range.start, to: range.end)]
      }
      return (field.text, field.isFirstResponder, field.frame, range)
    }
    return "focus=\(state.nicknameFocused) editor=\(state.isEditingNickname) "
      + "renderer=\(String(describing: state.rendererID)) section=\(String(describing: state.scrollSection)) "
      + "fields=\(fields) selection=\(String(describing: state.nicknameSelection?.indices))"
  }

  @Test
  func `compact nodes keeps tabs visible on discovery`() throws {
    let appState = AppState()
    appState.navigation.selectedTab = AppTab.nodes.rawValue
    let host = try mountTabs(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    try #require(waitUntil { visibleTabItemCount(in: host.window) == 5 })
    appState.navigation.navigateToDiscovery()
    settle(host.window)
    #expect(visibleTabItemCount(in: host.window) == 5, "\(describe(host, appState: appState))")
  }

  @Test
  func `compact height and same class resizing preserve history and native tabs`() async throws {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.selectedTab = AppTab.nodes.rawValue
    let host = try mountTabs(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }
    appState.navigation.navigateToContactDetail(contact)
    try #require(await waitFor { contactDetailIsVisible(in: host, contact: contact) }, "\(describe(host, appState: appState))")
    try #require(activateSavedHistory(in: host.window))
    try #require(await waitFor { visibleHistoryNavigationController(in: host.window) != nil })

    for compactHeight in [true, false] {
      resize(
        host, to: compactHeight ? Layout.compactLandscapeSize : Layout.compactSize,
        regularWidth: false, compactHeight: compactHeight
      )
      try #require(await waitFor { visibleHistoryNavigationController(in: host.window) != nil })
      #expect(visibleTabItemCount(in: host.window) == 5)
      #expect(appState.navigation.selectedContact?.id == contact.id)
    }
    try #require(performNativeBack(in: host.window))
    #expect(appState.navigation.selectedContact?.id == contact.id)
    try #require(await waitFor { visibleHistoryNavigationController(in: host.window) == nil },
                 "\(describe(host, appState: appState))")
    try #require(await waitFor { contactDetailIsVisible(in: host, contact: contact) },
                 "\(describe(host, appState: appState))")
  }

  @Test
  func `compact nodes saved history back retains contact before returning to list`() throws {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.selectedTab = AppTab.nodes.rawValue
    let host = try mountTabs(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    appState.navigation.navigateToContactDetail(contact)
    settle(host.window)
    #expect(visibleTabItemCount(in: host.window) == 5)
    try #require(
      activateSavedHistory(in: host.window),
      "\(describe(host, appState: appState))"
    )
    try #require(waitUntil { visibleHistoryNavigationController(in: host.window) != nil })
    #expect(visibleTabItemCount(in: host.window) == 5)
    let initialHistoryNavigation = try #require(visibleHistoryNavigationController(in: host.window))
    #expect(!initialHistoryNavigation.isNavigationBarHidden)
    #expect(!initialHistoryNavigation.navigationBar.prefersLargeTitles
      || initialHistoryNavigation.topViewController?.navigationItem.largeTitleDisplayMode == .never)
    #expect(visibleHistoryNavigationController(in: host.window)?
      .viewControllers.dropLast().last?.navigationItem.title == contact.displayName)

    appState.navigation.selectedTab = AppTab.chats.rawValue
    settle(host.window)
    appState.navigation.selectedTab = AppTab.nodes.rawValue
    settle(host.window)
    try #require(visibleHistoryNavigationController(in: host.window) != nil)

    resize(host, to: Layout.regularSize, regularWidth: true)
    try #require(visibleHistoryNavigationController(in: host.window) != nil)
    resize(host, to: Layout.compactSize, regularWidth: false)
    try #require(visibleHistoryNavigationController(in: host.window) != nil)

    let historyNavigation = try #require(visibleHistoryNavigationController(in: host.window))
    #expect(!historyNavigation.isNavigationBarHidden)
    #expect(!historyNavigation.navigationBar.prefersLargeTitles
      || historyNavigation.topViewController?.navigationItem.largeTitleDisplayMode == .never)
    historyNavigation.popViewController(animated: false)
    settle(host.window)
    #expect(appState.navigation.selectedContact?.id == contact.id, "\(describe(host, appState: appState))")
    #expect(visibleHistoryNavigationController(in: host.window) == nil)
    #expect(contactDetailIsVisible(in: host, contact: contact))
    #expect(visibleTabItemCount(in: host.window) == 5)

    let beforeBack = describe(host, appState: appState)
    try #require(performNativeBack(in: host.window))
    try #require(waitUntil { appState.navigation.selectedContact == nil },
                 "Before: \(beforeBack) After: \(describe(host, appState: appState))")
    #expect(visibleTabItemCount(in: host.window) == 5)
  }

  @Test(arguments: [false, true])
  func `compact nodes replaces or invalidates a contact with history open`(invalidate: Bool) throws {
    let appState = AppState()
    appState.navigation.selectedTab = AppTab.nodes.rawValue
    let host = try mountTabs(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    appState.navigation.navigateToContactDetail(makeContact())
    settle(host.window)
    try #require(activateSavedHistory(in: host.window))
    try #require(waitUntil { visibleHistoryNavigationController(in: host.window) != nil })

    let replacement = makeContact(name: "Sam")
    if invalidate {
      appState.navigation.clearPerRadioSelection()
    } else {
      appState.navigation.navigateToContactDetail(replacement)
    }
    settle(host.window)
    #expect(visibleHistoryNavigationController(in: host.window) == nil)
    #expect(appState.navigation.selectedContact?.id == (invalidate ? nil : replacement.id))
    #expect(visibleTabItemCount(in: host.window) == 5)
    if !invalidate { #expect(contactDetailIsVisible(in: host, contact: replacement)) }
  }

  private func visibleHistoryNavigationController(in window: UIWindow) -> UINavigationController? {
    navigationControllers(from: window.rootViewController).first { navigation in
      navigation.topViewController?.navigationItem.title == L10n.RemoteNodes.RemoteNodes.History.overviewTitle
        && viewIsVisible(navigation.view, in: window)
    }
  }

  private func activateSavedHistory(in window: UIWindow) -> Bool {
    // This unblocked chat fixture lists Send Message, Telemetry, then Saved History.
    guard let navigation = navigationControllers(from: window.rootViewController)
      .first(where: { viewIsVisible($0.view, in: window) }),
      let root = navigation.topViewController?.view,
      let list = descendantViews(in: root, of: UICollectionView.self).first,
      list.numberOfSections > Layout.savedHistoryIndexPath.section,
      list.numberOfItems(inSection: Layout.savedHistoryIndexPath.section) > Layout.savedHistoryIndexPath.item,
      let delegate = list.delegate,
      delegate.responds(to: #selector(UICollectionViewDelegate.collectionView(_:performPrimaryActionForItemAt:)))
    else { return false }
    list.scrollToItem(at: Layout.savedHistoryIndexPath, at: .centeredVertically, animated: false)
    window.layoutIfNeeded()
    list.selectItem(at: Layout.savedHistoryIndexPath, animated: false, scrollPosition: [])
    delegate.collectionView?(list, didSelectItemAt: Layout.savedHistoryIndexPath)
    if delegate.collectionView?(list, canPerformPrimaryActionForItemAt: Layout.savedHistoryIndexPath) == true {
      delegate.collectionView?(list, performPrimaryActionForItemAt: Layout.savedHistoryIndexPath)
    }
    return true
  }

  private func contactDetailIsVisible(in host: Host, contact: ContactDTO) -> Bool {
    guard host.renderedContact.id == contact.id,
          let navigation = navigationControllers(from: host.window.rootViewController)
          .first(where: { viewIsVisible($0.view, in: host.window) }),
          let root = navigation.topViewController?.view,
          let list = descendantViews(in: root, of: UICollectionView.self).first else { return false }
    return viewIsVisible(list, in: host.window)
  }

  private func descendantViews<T: UIView>(in view: UIView, of type: T.Type) -> [T] {
    ((view as? T).map { [$0] } ?? [])
      + view.subviews.flatMap { descendantViews(in: $0, of: type) }
  }

  private func visibleTabItemCount(in window: UIWindow) -> Int {
    tabBars(from: window).filter { viewIsVisible($0, in: window) }
      .compactMap(\.items).map(\.count).max() ?? 0
  }

  private func viewIsVisible(_ view: UIView, in window: UIWindow) -> Bool {
    guard view.window === window, !view.bounds.isEmpty,
          view.convert(view.bounds, to: window).intersects(window.bounds) else { return false }
    var ancestor: UIView? = view
    while let current = ancestor {
      if current.isHidden || current.alpha == 0 { return false }
      ancestor = current.superview
    }
    return true
  }

  @Test
  func `hosted nodes split back from collapsed contact clears the selection`() throws {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.selectedContact = contact
    appState.navigation.selectedTab = AppTab.nodes.rawValue

    let host = try mountNodes(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    try #require(
      waitUntil { appState.navigation.selectedContact?.id == contact.id },
      "\(describe(host, appState: appState))"
    )

    let didBack = performNativeBack(in: host.window)
    #expect(didBack, "\(describe(host, appState: appState))")
    try #require(
      waitUntil { appState.navigation.selectedContact == nil },
      "\(describe(host, appState: appState))"
    )
  }

  @Test
  func `hosted nodes split back from collapsed discovery clears discovery`() throws {
    let appState = AppState()
    appState.navigation.nodesShowingDiscovery = true
    appState.navigation.selectedTab = AppTab.nodes.rawValue

    let host = try mountNodes(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    try #require(
      waitUntil { appState.navigation.nodesShowingDiscovery },
      "\(describe(host, appState: appState))"
    )

    let didBack = performNativeBack(in: host.window)
    #expect(didBack, "\(describe(host, appState: appState))")
    try #require(
      waitUntil { !appState.navigation.nodesShowingDiscovery },
      "\(describe(host, appState: appState))"
    )
  }

  @Test
  func `hosted nodes split keeps the contact across compact and regular`() throws {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.selectedContact = contact
    appState.navigation.selectedTab = AppTab.nodes.rawValue

    let host = try mountNodes(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    resize(host, to: Layout.regularSize, regularWidth: true)
    #expect(appState.navigation.selectedContact?.id == contact.id)

    resize(host, to: Layout.compactSize, regularWidth: false)
    #expect(appState.navigation.selectedContact?.id == contact.id)

    let didBack = performNativeBack(in: host.window)
    #expect(didBack, "\(describe(host, appState: appState))")
    try #require(
      waitUntil { appState.navigation.selectedContact == nil },
      "\(describe(host, appState: appState))"
    )

    resize(host, to: Layout.regularSize, regularWidth: true)
    #expect(appState.navigation.selectedContact == nil)
  }

  @Test
  func `confirmed list removal of the selected contact clears the root`() throws {
    let appState = AppState()
    let contact = makeContact()
    let other = makeContact(name: "Sam")
    appState.navigation.selectedContact = contact
    let viewModel = ContactsViewModel()
    viewModel.contacts = [contact, other]

    let host = try mountNodesContent(appState: appState, viewModel: viewModel)
    defer { dismount(host) }

    viewModel.pendingRemovalIDs.insert(contact.id)
    try #require(
      waitUntil { appState.navigation.selectedContact == nil },
      "\(describe(host, appState: appState))"
    )
  }

  @Test
  func `hiding a contact with a filter does not clear the selection`() throws {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.selectedContact = contact
    let viewModel = ContactsViewModel()
    viewModel.contacts = [contact]

    let host = try mountNodesContent(appState: appState, viewModel: viewModel)
    defer { dismount(host) }

    viewModel.contacts = []
    settle(host.window)
    #expect(appState.navigation.selectedContact?.id == contact.id)
  }

  @Test
  func `programmatic contact navigation from another tab selects the contact`() throws {
    let appState = AppState()
    appState.navigation.selectedTab = AppTab.chats.rawValue
    let contact = makeContact()

    let host = try mountNodes(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    appState.navigation.navigateToContactDetail(contact)
    settle(host.window)

    #expect(appState.navigation.selectedTab == AppTab.nodes.rawValue)
    #expect(appState.navigation.selectedContact?.id == contact.id)
    #expect(appState.navigation.nodesShowingDiscovery == false)
  }

  @Test
  func `full radio invalidation while nodes is inactive does not restore the contact`() throws {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.selectedContact = contact
    appState.navigation.selectedTab = AppTab.nodes.rawValue

    let host = try mountNodes(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    appState.navigation.selectedTab = AppTab.chats.rawValue
    settle(host.window)
    appState.navigation.clearPerRadioSelection()
    appState.navigation.selectedTab = AppTab.nodes.rawValue
    settle(host.window)

    #expect(appState.navigation.selectedContact == nil)
    #expect(appState.navigation.nodesShowingDiscovery == false)
  }

  // MARK: - Hosted Settings

  @Test
  func `hosted settings split back from collapsed page clears the selection`() throws {
    let appState = AppState()
    appState.navigation.selectedSetting = .language
    appState.navigation.selectedTab = AppTab.settings.rawValue

    let host = try mountSettings(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    try #require(
      waitUntil { appState.navigation.selectedSetting == .language },
      "\(describe(host, appState: appState))"
    )

    let didBack = performNativeBack(in: host.window)
    #expect(didBack, "\(describe(host, appState: appState))")
    try #require(
      waitUntil { appState.navigation.selectedSetting == nil },
      "\(describe(host, appState: appState))"
    )
  }

  @Test
  func `hosted settings split keeps the page across compact and regular`() throws {
    let appState = AppState()
    appState.navigation.selectedSetting = .notifications
    appState.navigation.selectedTab = AppTab.settings.rawValue

    let host = try mountSettings(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    resize(host, to: Layout.regularSize, regularWidth: true)
    #expect(appState.navigation.selectedSetting == .notifications)

    resize(host, to: Layout.compactSize, regularWidth: false)
    #expect(appState.navigation.selectedSetting == .notifications)

    let didBack = performNativeBack(in: host.window)
    #expect(didBack, "\(describe(host, appState: appState))")
    try #require(
      waitUntil { appState.navigation.selectedSetting == nil },
      "\(describe(host, appState: appState))"
    )

    resize(host, to: Layout.regularSize, regularWidth: true)
    #expect(appState.navigation.selectedSetting == nil)
  }

  @Test
  func `hosted settings nested back keeps the selected page`() throws {
    let appState = AppState()
    appState.navigation.selectedSetting = .chats
    appState.navigation.selectedTab = AppTab.settings.rawValue

    let host = try mountSettings(appState: appState, size: Layout.regularSize, regularWidth: true)
    defer { dismount(host) }

    try #require(
      waitUntil { appState.navigation.selectedSetting == .chats },
      "\(describe(host, appState: appState))"
    )
    try #require(
      pushNestedPage(in: host.window),
      "\(describe(host, appState: appState))"
    )

    let didBack = performNativeBack(in: host.window)
    #expect(didBack, "\(describe(host, appState: appState))")
    try #require(
      waitUntil { appState.navigation.selectedSetting == .chats },
      "\(describe(host, appState: appState))"
    )
  }

  @Test
  func `programmatic settings navigation replaces an open page`() throws {
    let appState = AppState()
    appState.navigation.selectedSetting = .chats
    appState.navigation.selectedTab = AppTab.settings.rawValue

    let host = try mountSettings(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    appState.navigation.navigateToSetting(.language)
    settle(host.window)

    #expect(appState.navigation.selectedTab == AppTab.settings.rawValue)
    #expect(appState.navigation.selectedSetting == .language)
  }

  // MARK: - Root tab host

  @Test
  func `app tabs keep chats nodes map tools settings order`() {
    #expect(AppTab.chats.rawValue == 0)
    #expect(AppTab.nodes.rawValue == 1)
    #expect(AppTab.map.rawValue == 2)
    #expect(AppTab.tools.rawValue == 3)
    #expect(AppTab.settings.rawValue == 4)
  }

  @Test(arguments: [false, true])
  func `hosted tab root shows five native tabs`(regularWidth: Bool) throws {
    let appState = AppState()
    appState.navigation.selectedTab = AppTab.chats.rawValue
    let size = regularWidth ? Layout.regularSize : Layout.compactSize
    let host = try mountTabs(appState: appState, size: size, regularWidth: regularWidth)
    defer { dismount(host) }

    try #require(
      waitUntil { tabItemCount(in: host.window) == 5 },
      "\(describe(host, appState: appState)) tabs=\(tabItemCount(in: host.window))"
    )
  }

  @Test
  func `hosted tab root retains a chat route across tab switches`() throws {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.navigateToChat(with: contact)

    let host = try mountTabs(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    appState.navigation.selectedTab = AppTab.nodes.rawValue
    settle(host.window)
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
    #expect(appState.navigation.selectedTab == AppTab.nodes.rawValue)

    appState.navigation.selectedTab = AppTab.chats.rawValue
    settle(host.window)
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
  }

  @Test
  func `hosted tab root disconnect clears radio tool and device settings`() throws {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.chatsSelectedRoute = .direct(contact)
    appState.navigation.selectedContact = contact
    appState.navigation.selectedTool = .tracePath
    appState.navigation.selectedSetting = .radio
    appState.connectionManager.setTestState(connectedDevice: makeDevice())

    let host = try mountTabs(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    try #require(
      waitUntil { appState.connectedDevice != nil },
      "\(describe(host, appState: appState))"
    )

    appState.connectionManager.setTestState(connectedDevice: .some(nil))
    try #require(
      waitUntil {
        appState.navigation.selectedTool == nil
          && appState.navigation.selectedSetting == nil
      },
      "\(describe(host, appState: appState))"
    )
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
    #expect(appState.navigation.selectedContact?.id == contact.id)
  }

  @Test
  func `hosted tab root keeps routes when the connected radio changes`() throws {
    let appState = AppState()
    appState.navigation.selectedTool = .tracePath
    appState.navigation.selectedSetting = .radio
    appState.connectionManager.setTestState(connectedDevice: makeDevice())

    let host = try mountTabs(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    appState.connectionManager.setTestState(connectedDevice: makeDevice())
    settle(host.window)

    #expect(appState.navigation.selectedTool == .tracePath)
    #expect(appState.navigation.selectedSetting == .radio)
  }

  @Test
  func `hosted tools stack follows selectedTool and survives a tab switch`() throws {
    // Every tool shares this path binding. Rx Log stands in so the suite does not host a map.
    let tool = ToolSelection.rxLog
    let appState = AppState()
    appState.navigation.selectedTab = AppTab.tools.rawValue
    appState.navigation.selectedTool = tool

    let host = try mountTools(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    try #require(
      waitUntil { toolWorkspaceIsVisible(tool, in: host.window) },
      "\(describe(host, appState: appState)) tool=\(tool.title)"
    )

    appState.navigation.selectedTab = AppTab.chats.rawValue
    settle(host.window)
    #expect(appState.navigation.selectedTool == tool)

    appState.navigation.selectedTab = AppTab.tools.rawValue
    settle(host.window)
    try #require(
      waitUntil { toolWorkspaceIsVisible(tool, in: host.window) },
      "\(describe(host, appState: appState)) tool=\(tool.title)"
    )

    let didBack = performNativeBack(in: host.window)
    #expect(didBack, "\(describe(host, appState: appState))")
    try #require(
      waitUntil { appState.navigation.selectedTool == nil },
      "\(describe(host, appState: appState))"
    )
  }

  // MARK: - Hosting

  @MainActor
  private final class RenderedContactIdentity {
    var id: UUID?
  }

  private struct Host {
    let renderedContact: RenderedContactIdentity
    let window: UIWindow
    let controller: UIViewController
  }

  private struct ChatsAdaptiveHost: View {
    let appState: AppState

    var body: some View {
      @Bindable var navigation = appState.navigation
      TabView(selection: $navigation.selectedTab) {
        Tab("Chats", systemImage: "message.fill", value: AppTab.chats.rawValue) {
          ChatsView()
        }
        Tab("Nodes", systemImage: "flipphone", value: AppTab.nodes.rawValue) {
          Text("Nodes")
        }
      }
      .environment(\.appState, appState)
    }
  }

  private struct NodesAdaptiveHost: View {
    let appState: AppState

    var body: some View {
      @Bindable var navigation = appState.navigation
      TabView(selection: $navigation.selectedTab) {
        Tab("Chats", systemImage: "message.fill", value: AppTab.chats.rawValue) {
          ChatsView()
        }
        Tab("Nodes", systemImage: "flipphone", value: AppTab.nodes.rawValue) {
          ContactsListView()
        }
      }
      .environment(\.appState, appState)
    }
  }

  private struct SettingsAdaptiveHost: View {
    let appState: AppState

    var body: some View {
      @Bindable var navigation = appState.navigation
      TabView(selection: $navigation.selectedTab) {
        Tab("Chats", systemImage: "message.fill", value: AppTab.chats.rawValue) {
          ChatsView()
        }
        Tab("Settings", systemImage: "gear", value: AppTab.settings.rawValue) {
          SettingsView()
        }
      }
      .environment(\.appState, appState)
    }
  }

  private struct NodesContentHost: View {
    let appState: AppState
    let viewModel: ContactsViewModel

    var body: some View {
      NavigationStack {
        ContactsContentColumn(viewModel: viewModel)
      }
      .environment(\.appState, appState)
    }
  }

  private struct MainTabHost: View {
    let appState: AppState

    var body: some View {
      MainTabView()
        .environment(\.appState, appState)
    }
  }

  private struct ContactContinuityGateHost: View {
    let appState: AppState
    let state: ContactDetailState
    let routes: NodesNavigationState
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var columnVisibility = NavigationSplitViewVisibility.all

    var body: some View {
      containers
        .modifier(ContactDetailPresenter(state: state))
        .environment(\.appState, appState)
        .onChange(of: sizeClass, initial: true) { _, value in
          routes.changePresentation(to: value == .regular ? .regular : .compact) { revision in
            state.prepareRenderer(revision: revision)
          }
        }
        .onAppear {
          state.configure(appState: appState, onClose: {})
          state.setActive(true, appState: appState)
        }
    }

    @ViewBuilder
    private var containers: some View {
      if routes.presentation == .compact {
        NavigationStack(path: routes.compactBinding(in: appState.navigation)) {
          Text("Nodes")
            .navigationDestination(for: NodesRoute.self) { _ in
              ContactDetailContent(state: state, rendererRevision: routes.layoutRevision).id(routes.layoutRevision)
            }
        }
      } else {
        NavigationSplitView(columnVisibility: $columnVisibility) {
          Text("Nodes")
        } detail: {
          NavigationStack {
            ContactDetailContent(state: state, rendererRevision: routes.layoutRevision).id(routes.layoutRevision)
          }
        }
        .navigationSplitViewStyle(.balanced)
      }
    }
  }

  private struct ToolsAdaptiveHost: View {
    let appState: AppState

    var body: some View {
      @Bindable var navigation = appState.navigation
      TabView(selection: $navigation.selectedTab) {
        Tab("Chats", systemImage: "message.fill", value: AppTab.chats.rawValue) {
          Text("Chats")
        }
        Tab("Tools", systemImage: "wrench.and.screwdriver", value: AppTab.tools.rawValue) {
          ToolsView()
        }
      }
      .environment(\.appState, appState)
    }
  }

  private func mount(
    appState: AppState,
    size: CGSize,
    regularWidth: Bool
  ) throws -> Host {
    try mount(ChatsAdaptiveHost(appState: appState), size: size, regularWidth: regularWidth)
  }

  private func mountNodes(
    appState: AppState,
    size: CGSize,
    regularWidth: Bool
  ) throws -> Host {
    try mount(NodesAdaptiveHost(appState: appState), size: size, regularWidth: regularWidth)
  }

  private func mountSettings(
    appState: AppState,
    size: CGSize,
    regularWidth: Bool
  ) throws -> Host {
    try mount(SettingsAdaptiveHost(appState: appState), size: size, regularWidth: regularWidth)
  }

  private func mountNodesContent(
    appState: AppState,
    viewModel: ContactsViewModel
  ) throws -> Host {
    try mount(
      NodesContentHost(appState: appState, viewModel: viewModel),
      size: Layout.compactSize,
      regularWidth: false
    )
  }

  private func mountTabs(
    appState: AppState,
    size: CGSize,
    regularWidth: Bool
  ) throws -> Host {
    try mount(MainTabHost(appState: appState), size: size, regularWidth: regularWidth)
  }

  private func mountTools(
    appState: AppState,
    size: CGSize,
    regularWidth: Bool
  ) throws -> Host {
    try mount(
      ToolsAdaptiveHost(appState: appState),
      size: size,
      regularWidth: regularWidth,
      attachesToScene: false
    )
  }

  private func mount(
    _ rootView: some View,
    size: CGSize,
    regularWidth: Bool,
    attachesToScene: Bool = true
  ) throws -> Host {
    let renderedContact = RenderedContactIdentity()
    let controller = UIHostingController(rootView: rootView.onPreferenceChange(ContactDetailIdentityPreference.self) {
      renderedContact.id = $0
    })
    let window = attachesToScene
      ? makeWindow(size: size)
      : UIWindow(frame: CGRect(origin: .zero, size: size))
    window.rootViewController = controller
    applySizeClass(controller, regularWidth: regularWidth)
    window.frame = CGRect(origin: .zero, size: size)
    controller.view.frame = window.bounds
    if attachesToScene {
      window.makeKeyAndVisible()
    } else {
      window.isHidden = false
    }
    window.layoutIfNeeded()
    settle(window)
    return Host(renderedContact: renderedContact, window: window, controller: controller)
  }

  private func makeWindow(size: CGSize) -> UIWindow {
    let frame = CGRect(origin: .zero, size: size)
    if let scene = UIApplication.shared.connectedScenes
      .compactMap({ $0 as? UIWindowScene })
      .first {
      let window = UIWindow(windowScene: scene)
      window.frame = frame
      return window
    }
    return UIWindow(frame: frame)
  }

  private func applySizeClass(_ controller: UIViewController, regularWidth: Bool, compactHeight: Bool = false) {
    controller.traitOverrides.horizontalSizeClass = regularWidth ? .regular : .compact
    controller.traitOverrides.verticalSizeClass = compactHeight ? .compact : .regular
  }

  private func resize(_ host: Host, to size: CGSize, regularWidth: Bool, compactHeight: Bool = false) {
    host.window.frame = CGRect(origin: .zero, size: size)
    applySizeClass(host.controller, regularWidth: regularWidth, compactHeight: compactHeight)
    host.window.layoutIfNeeded()
    settle(host.window)
  }

  private func dismount(_ host: Host) {
    host.window.rootViewController = nil
    host.window.isHidden = true
    host.window.windowScene = nil
    spin(Layout.runLoopSlice)
  }

  private func settle(_ window: UIWindow) {
    window.layoutIfNeeded()
    spin(Layout.settle)
  }

  private func spin(_ duration: TimeInterval) {
    RunLoop.main.run(until: Date(timeIntervalSinceNow: duration))
  }

  private func waitUntil(
    timeout: TimeInterval = Layout.waitTimeout,
    _ condition: () -> Bool
  ) -> Bool {
    let deadline = Date(timeIntervalSinceNow: timeout)
    while Date() < deadline {
      if condition() { return true }
      RunLoop.main.run(until: Date(timeIntervalSinceNow: Layout.runLoopSlice))
    }
    return condition()
  }

  private func waitFor(_ condition: () -> Bool) async -> Bool {
    let deadline = Date(timeIntervalSinceNow: Layout.waitTimeout)
    while Date() < deadline, !Task.isCancelled {
      if condition() { return true }
      try? await Task.sleep(for: .seconds(Layout.runLoopSlice))
    }
    return condition()
  }

  private func pushNestedPage(in window: UIWindow) -> Bool {
    let navs = navigationControllers(from: window.rootViewController)
    // Regular split: the detail column sits further trailing than the list.
    let nav = navs.max { lhs, rhs in
      lhs.view.convert(lhs.view.bounds, to: window).minX
        < rhs.view.convert(rhs.view.bounds, to: window).minX
    }
    guard let nav else { return false }
    let extra = UIViewController()
    extra.navigationItem.title = "Nested"
    nav.pushViewController(extra, animated: false)
    window.layoutIfNeeded()
    return nav.viewControllers.count > 1
  }

  private func performNativeBack(in window: UIWindow) -> Bool {
    let navs = navigationControllers(from: window.rootViewController)
      .filter { $0.viewIfLoaded.map { viewIsVisible($0, in: window) } == true }
    if let nav = navs.filter({ $0.viewControllers.count > 1 })
      .max(by: { $0.viewControllers.count < $1.viewControllers.count }),
      nav.popViewController(animated: false) != nil {
      window.layoutIfNeeded()
      return true
    }
    if let nav = navs.first(where: { $0.navigationBar.backItem != nil }),
       nav.popViewController(animated: false) != nil {
      window.layoutIfNeeded()
      return true
    }
    // iOS 18 compact NavigationSplitView often has no extra stack item; Back
    // is the split showing its primary column.
    if let split = splitViewControllers(from: window.rootViewController)
      .last(where: { $0.viewIfLoaded.map { viewIsVisible($0, in: window) } == true }) {
      split.show(.primary)
      window.layoutIfNeeded()
      return true
    }
    return false
  }

  private func navigationControllers(from root: UIViewController?) -> [UINavigationController] {
    viewControllers(from: root, as: UINavigationController.self)
  }

  private func splitViewControllers(from root: UIViewController?) -> [UISplitViewController] {
    viewControllers(from: root, as: UISplitViewController.self)
  }

  private func viewControllers<T: UIViewController>(from root: UIViewController?, as type: T.Type) -> [T] {
    guard let root else { return [] }
    var result: [T] = []
    var seen = Set<ObjectIdentifier>()
    func walk(_ viewController: UIViewController) {
      let id = ObjectIdentifier(viewController)
      guard seen.insert(id).inserted else { return }
      if let typed = viewController as? T {
        result.append(typed)
      }
      if let nav = viewController as? UINavigationController {
        nav.viewControllers.forEach(walk)
      }
      viewController.children.forEach(walk)
    }
    walk(root)
    return result
  }

  private func describe(_ host: Host, appState: AppState) -> String {
    let navs = navigationControllers(from: host.controller)
    let titles = navs.map { nav in
      nav.viewControllers.map { $0.navigationItem.title ?? String(describing: type(of: $0)) }
    }
    return """
    route=\(String(describing: appState.navigation.chatsSelectedRoute?.kind)) \
    contact=\(String(describing: appState.navigation.selectedContact?.id)) \
    discovery=\(appState.navigation.nodesShowingDiscovery) \
    setting=\(String(describing: appState.navigation.selectedSetting)) \
    tab=\(appState.navigation.selectedTab) \
    navs=\(titles) renderedContact=\(String(describing: host.renderedContact.id)) window=\(host.window.bounds)
    """
  }

  private func makeRoute(kind: ChatRoute.Kind) -> ChatRoute {
    switch kind {
    case .direct:
      .direct(makeContact())
    case .channel:
      .channel(makeChannel())
    case .room:
      .room(makeRoom())
    }
  }

  private func makeContact(name: String = "Alex") -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
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

  private func makeChannel() -> ChannelDTO {
    ChannelDTO(
      id: UUID(),
      radioID: UUID(),
      index: 1,
      name: "General",
      secret: Data(repeating: 0x11, count: ProtocolLimits.channelSecretSize),
      isEnabled: true,
      lastMessageDate: nil,
      unreadCount: 0
    )
  }

  private func makeRoom() -> RemoteNodeSessionDTO {
    RemoteNodeSessionDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0xCC, count: ProtocolLimits.publicKeySize),
      name: "Ops",
      role: .roomServer,
      isConnected: true
    )
  }

  private func makeDevice() -> DeviceDTO {
    DeviceDTO(
      id: UUID(),
      publicKey: Data(repeating: 0xBB, count: ProtocolLimits.publicKeySize),
      nodeName: "Relay",
      firmwareVersion: 1,
      firmwareVersionString: "1.12.0",
      manufacturerName: "Test",
      buildDate: "2025-01-01",
      maxContacts: 100,
      maxChannels: 8,
      frequency: 915_000,
      bandwidth: 250_000,
      spreadingFactor: 10,
      codingRate: 5,
      txPower: 20,
      maxTxPower: 20,
      latitude: 0,
      longitude: 0,
      blePin: 0,
      manualAddContacts: false,
      multiAcks: 2,
      telemetryModeBase: 2,
      telemetryModeLoc: 0,
      telemetryModeEnv: 0,
      advertLocationPolicy: 0,
      lastConnected: Date(),
      lastContactSync: 0,
      isActive: true,
      ocvPreset: nil,
      customOCVArrayString: nil
    )
  }

  private func tabItemCount(in window: UIWindow) -> Int {
    let barCounts = tabBars(from: window).compactMap(\.items).map(\.count)
    if let count = barCounts.max(), count > 0 {
      return count
    }
    let titles = [
      L10n.Localizable.Tabs.chats,
      L10n.Localizable.Tabs.nodes,
      L10n.Localizable.Tabs.map,
      L10n.Localizable.Tabs.tools,
      L10n.Localizable.Tabs.settings,
    ]
    return titles.filter { containsText($0, in: window) }.count
  }

  private func tabBars(from window: UIWindow) -> [UITabBar] {
    var bars: [UITabBar] = []
    func walk(_ view: UIView) {
      if let bar = view as? UITabBar {
        bars.append(bar)
      }
      view.subviews.forEach(walk)
    }
    walk(window)
    return bars
  }

  /// Trace Path's bar shows the List/Map picker, so visibility uses `Contacts.Trace.title`.
  /// Line of Sight has no navigation title; its layout probe is the workspace signal.
  private func toolWorkspaceIsVisible(_ tool: ToolSelection, in window: UIWindow) -> Bool {
    switch tool {
    case .lineOfSight:
      containsIdentifier(LineOfSightLayoutMode.mapWithSheet.accessibilityIdentifier, in: window)
        || containsIdentifier(LineOfSightLayoutMode.paired.accessibilityIdentifier, in: window)
    default:
      navigationStackContains(workspaceNavigationTitle(for: tool), in: window)
    }
  }

  private func workspaceNavigationTitle(for tool: ToolSelection) -> String {
    switch tool {
    case .tracePath: L10n.Contacts.Contacts.Trace.title
    default: tool.title
    }
  }

  private func containsIdentifier(_ identifier: String, in view: UIView) -> Bool {
    if view.accessibilityIdentifier == identifier { return true }
    return view.subviews.contains { containsIdentifier(identifier, in: $0) }
  }

  private func navigationStackContains(_ title: String, in window: UIWindow) -> Bool {
    navigationControllers(from: window.rootViewController).contains { nav in
      nav.viewControllers.contains { $0.navigationItem.title == title }
    }
  }

  private func containsText(_ text: String, in view: UIView) -> Bool {
    if view.accessibilityLabel == text { return true }
    if let label = view as? UILabel, label.text == text { return true }
    if let button = view as? UIButton, button.currentTitle == text { return true }
    if let item = (view as? UITabBar)?.items?.contains(where: { $0.title == text }) {
      if item { return true }
    }
    return view.subviews.contains { containsText(text, in: $0) }
  }
}

@Suite("Compact chat composer inset", .serialized)
@MainActor
struct CompactChatComposerInsetTests {
  @Test
  func `opening a compact chat does not drop the composer after it appears`() throws {
    let appState = AppState()
    appState.navigation.selectedTab = AppTab.chats.rawValue
    let host = mountCompactHost(HostRoot(appState: appState))
    defer { dismountCompactHost(host) }

    let contact = ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0xAB, count: ProtocolLimits.publicKeySize),
      name: "Alex",
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

    UIView.setAnimationsEnabled(true)
    let start = Date()
    appState.navigation.setChatsRoute(.direct(contact))

    var samples: [(time: TimeInterval, gap: CGFloat)] = []
    while Date().timeIntervalSince(start) < 1.6 {
      if let gap = visibleComposerGap(in: host.window) {
        samples.append((Date().timeIntervalSince(start), gap))
      }
      RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.05))
    }
    if let gap = visibleComposerGap(in: host.window) {
      samples.append((Date().timeIntervalSince(start), gap))
    }

    let trace = samples
      .map { String(format: "t=%.2f gap=%.1f", $0.time, $0.gap) }
      .joined(separator: "\n")
    let first = try #require(samples.first, "composer never appeared\n\(trace)")
    let last = try #require(samples.last, "composer never appeared\n\(trace)")
    // Settled gap is about 42 (34pt home indicator plus 8pt of bar padding).
    // A bar still above the tab bar is about 91.
    #expect(first.time < 0.5, "chat waited \(first.time)s to draw\n\(trace)")
    #expect(
      first.gap < 60 && last.gap < 60,
      "composer gap \(first.gap) -> \(last.gap) still includes the tab bar\n\(trace)"
    )
    #expect(
      abs(first.gap - last.gap) < 16,
      "composer gap moved from \(first.gap) to \(last.gap)\n\(trace)"
    )
  }

  private func visibleComposerGap(in window: UIWindow) -> CGFloat? {
    window.layoutIfNeeded()
    guard let composer = composerTextView(in: window) else { return nil }
    let frame = composer.convert(composer.bounds, to: window)
    let onScreen = frame.intersects(window.bounds)
      && frame.midX >= 0
      && frame.midX <= window.bounds.width
      && frame.maxY > window.bounds.height * 0.45
    guard onScreen else { return nil }
    return window.bounds.maxY - frame.maxY
  }

  private func composerTextView(in view: UIView) -> UIView? {
    if view is ChatComposerUITextView { return view }
    for subview in view.subviews {
      if let found = composerTextView(in: subview) { return found }
    }
    return nil
  }

  private struct HostRoot: View {
    let appState: AppState

    var body: some View {
      @Bindable var navigation = appState.navigation
      TabView(selection: $navigation.selectedTab) {
        Tab("Chats", systemImage: "message.fill", value: AppTab.chats.rawValue) {
          ChatsView()
        }
        Tab("Nodes", systemImage: "flipphone", value: AppTab.nodes.rawValue) {
          Text("Nodes")
        }
      }
      .environment(\.appState, appState)
    }
  }
}

@Suite("Compact composer keyboard gap", .serialized)
@MainActor
struct CompactComposerKeyboardGapTests {
  private struct Probe: View {
    var body: some View {
      Color.clear
        .chatBottomChrome(canvas: Color(.systemBackground)) {
          Field().frame(height: 38)
        }
        .environment(\.scenePhase, .active)
    }
  }

  private struct Field: UIViewRepresentable {
    func makeUIView(context: Context) -> UITextView {
      let view = UITextView()
      view.accessibilityIdentifier = "keyboard-gap-field"
      view.backgroundColor = .gray
      view.textContainerInset = .zero
      return view
    }

    func updateUIView(_ uiView: UITextView, context: Context) {}
  }

  @Test
  func `a keyboard notification without layout movement leaves the composer at rest`() {
    let host = mountCompactHost(Probe())
    defer { dismountCompactHost(host) }

    let resting = gap(in: host.window)
    let keyboardHeight: CGFloat = 336
    postKeyboard(height: keyboardHeight, in: host.window)
    RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.4))
    host.window.layoutIfNeeded()
    let lifted = gap(in: host.window)
    let safe = host.controller.view.safeAreaInsets.bottom

    // This field has no bar padding, so the resting gap is the home indicator.
    #expect(
      abs((resting ?? -1) - 34) < 16,
      "resting gap \(resting ?? -1), safe \(safe)"
    )
    #expect(
      abs((lifted ?? -1) - (resting ?? -1)) < 1,
      "notification changed resting gap from \(resting ?? -1) to \(lifted ?? -1)"
    )
  }

  private func postKeyboard(height: CGFloat, in window: UIWindow) {
    let local = CGRect(
      x: 0,
      y: window.bounds.height - height,
      width: window.bounds.width,
      height: height
    )
    let screen = window.convert(local, to: nil)
    NotificationCenter.default.post(
      name: UIResponder.keyboardWillChangeFrameNotification,
      object: nil,
      userInfo: [
        UIResponder.keyboardFrameEndUserInfoKey: NSValue(cgRect: screen),
        UIResponder.keyboardAnimationDurationUserInfoKey: 0.0,
      ]
    )
  }

  private func gap(in window: UIWindow) -> CGFloat? {
    guard let field = findField(in: window) else { return nil }
    let frame = field.convert(field.bounds, to: window)
    return window.bounds.maxY - frame.maxY
  }

  private func findField(in view: UIView) -> UIView? {
    if view.accessibilityIdentifier == "keyboard-gap-field" { return view }
    for subview in view.subviews {
      if let found = findField(in: subview) { return found }
    }
    return nil
  }
}

@MainActor
private func mountCompactHost(_ root: some View) -> (window: UIWindow, controller: UIViewController) {
  let controller = UIHostingController(rootView: root)
  let size = CGSize(width: 390, height: 844)
  let window = if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
    UIWindow(windowScene: scene)
  } else {
    UIWindow(frame: CGRect(origin: .zero, size: size))
  }
  window.rootViewController = controller
  controller.traitOverrides.horizontalSizeClass = .compact
  controller.traitOverrides.verticalSizeClass = .regular
  window.frame = CGRect(origin: .zero, size: size)
  controller.view.frame = window.bounds
  window.makeKeyAndVisible()
  window.layoutIfNeeded()
  RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.35))
  return (window, controller)
}

@MainActor
private func dismountCompactHost(_ host: (window: UIWindow, controller: UIViewController)) {
  host.window.rootViewController = nil
  host.window.isHidden = true
  host.window.windowScene = nil
}
