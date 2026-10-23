import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Nodes retained sidebar state", .timeLimit(.minutes(1)))
@MainActor
struct NodesSidebarStateTests {
  private enum ReloadRetirement: CaseIterable, Sendable {
    case taskCancellation
    case entryEnd
    case radioReplacement
  }

  @Test
  func `sidebar ignores outgoing renderer search focus and scroll updates`() {
    let state = ContactsSidebarState()
    defer { state.end() }
    let visibleID = UUID()
    state.searchText = "Alex"
    state.scrollPositionID = visibleID
    state.isSearchFocused = true

    state.prepareRenderer(revision: 1)
    state.updateSearchText("", rendererRevision: 0)
    state.updateSearchFocus(false, rendererRevision: 0)
    state.updateScrollPosition(UUID(), rendererRevision: 0)
    state.updateScrollPosition(nil, rendererRevision: 1)

    #expect(state.searchText == "Alex")
    #expect(state.scrollPositionID == visibleID)
    #expect(state.isSearchFocused)

    state.updateSearchFocus(false, rendererRevision: 1)
    #expect(!state.isSearchFocused)
  }

  @Test
  func `top clearing accepts only the current active renderer`() {
    let state = ContactsSidebarState()
    let visibleID = UUID()
    state.scrollPositionID = visibleID
    state.prepareRenderer(revision: 1)
    state.clearScrollPosition(rendererRevision: 0)
    #expect(state.scrollPositionID == visibleID)
    state.clearScrollPosition(rendererRevision: 1)
    #expect(state.scrollPositionID == nil)
    state.updateScrollPosition(visibleID, rendererRevision: 1)
    state.end()
    state.clearScrollPosition(rendererRevision: 1)
    #expect(state.scrollPositionID == visibleID)
  }

  @Test
  func `Discovery ignores outgoing renderer and ended entry updates`() {
    let state = DiscoveryState()
    defer { state.end() }
    let visibleID = UUID()
    state.searchText = "Alex"
    state.scrollPositionID = visibleID
    state.isSearchFocused = true

    state.prepareRenderer(revision: 1)
    state.updateSearchText("", rendererRevision: 0)
    state.updateSearchFocus(false, rendererRevision: 0)
    state.updateScrollPosition(UUID(), rendererRevision: 0)
    state.updateScrollPosition(nil, rendererRevision: 1)

    #expect(state.searchText == "Alex")
    #expect(state.scrollPositionID == visibleID)
    #expect(state.isSearchFocused)

    state.end()
    state.updateSearchText("", rendererRevision: 1)
    state.updateSearchFocus(false, rendererRevision: 1)
    state.updateScrollPosition(UUID(), rendererRevision: 1)
    #expect(state.isSearchFocused)
    #expect(state.searchText == "Alex")
    #expect(state.scrollPositionID == visibleID)
  }

  @Test
  func `Blocked Contacts rejects outgoing renderer and ended entry scroll updates`() {
    let state = BlockedContactsState()
    let visibleID = UUID()
    state.scrollPositionID = visibleID
    state.prepareRenderer(revision: 1)
    state.updateScrollPosition(UUID(), rendererRevision: 0)
    state.updateScrollPosition(nil, rendererRevision: 1)
    #expect(state.scrollPositionID == visibleID)

    state.end()
    state.updateScrollPosition(UUID(), rendererRevision: 1)
    #expect(state.scrollPositionID == visibleID)
  }

  @Test
  func `initial Favorites choice is applied once and preserves a user's later segment`() {
    let model = ContactsViewModel()
    model.contacts = [makeContact(isFavorite: true)]
    model.hasLoadedOnce = true
    let state = ContactsSidebarState(viewModel: model)

    state.firstLoadCompleted()
    #expect(state.selectedSegment == .favorites)

    state.selectedSegment = .contacts
    state.prepareRenderer(revision: 1)
    state.firstLoadCompleted()
    #expect(state.selectedSegment == .contacts)
  }

  @Test
  func `sidebar loads once per data identity and ignores reload requests after ending`() async throws {
    let fixture = try makeFixture()
    defer { fixture.cleanUp() }
    let store = fixture.appState.connectionManager.persistenceStore
    _ = try await store.saveContact(radioID: fixture.radioID, from: frame(name: "Alex", byte: 0x11))
    let state = ContactsSidebarState()
    defer { state.end() }
    state.configure(appState: fixture.appState)
    state.reloadIfNeeded(appState: fixture.appState)
    await state.waitForLoadForTesting()
    #expect(state.viewModel.contacts.map(\.name) == ["Alex"])

    _ = try await store.saveContact(radioID: fixture.radioID, from: frame(name: "Blair", byte: 0x22))
    state.prepareRenderer(revision: 1)
    state.configure(appState: fixture.appState)
    state.reloadIfNeeded(appState: fixture.appState)
    await state.waitForLoadForTesting()
    #expect(state.viewModel.contacts.map(\.name) == ["Alex"])

    fixture.appState.contactsVersion += 1
    state.reloadIfNeeded(appState: fixture.appState)
    await state.waitForLoadForTesting()
    #expect(Set(state.viewModel.contacts.map(\.name)) == ["Alex", "Blair"])

    state.end()
    _ = try await store.saveContact(radioID: fixture.radioID, from: frame(name: "Casey", byte: 0x33))
    fixture.appState.contactsVersion += 1
    state.configure(appState: fixture.appState)
    state.reloadIfNeeded(appState: fixture.appState)
    await state.waitForLoadForTesting()
    #expect(Set(state.viewModel.contacts.map(\.name)) == ["Alex", "Blair"])
  }

  @Test(arguments: ReloadRetirement.allCases)
  private func `retired suspended Discovery load cannot publish and a new load can`(
    _ retirement: ReloadRetirement
  ) async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let initialRadioID = UUID()
    var liveRadioID = initialRadioID
    _ = try await store.upsertDiscoveredNode(radioID: initialRadioID, from: frame(name: "Fetched", byte: 0x11))
    let model = DiscoveryViewModel()
    model.configure(dataStore: { store }, radioID: { liveRadioID })
    let retained = makeNode(radioID: initialRadioID, name: "Retained", type: .chat)
    model.discoveredNodes = [retained]
    model.updateVisibleNodes(searchText: "", segment: .all, sortOrder: .name, userLocation: nil)
    let gate = LoadGate()
    model.loadDiscoveredNodesBeforeFetchForTesting = { await gate.pause() }
    let loading = Task { await model.loadDiscoveredNodes() }
    await gate.waitForEntry()
    #expect(model.isLoading)

    switch retirement {
    case .taskCancellation:
      loading.cancel()
    case .entryEnd:
      model.cancelReloads()
    case .radioReplacement:
      liveRadioID = UUID()
    }
    model.loadDiscoveredNodesBeforeFetchForTesting = nil
    gate.resume()
    await loading.value

    #expect(model.discoveredNodes.map(\.id) == [retained.id])
    #expect(model.visibleNodes.map(\.id) == [retained.id])
    #expect(!model.hasLoadedOnce)
    #expect(!model.isLoading)
    #expect(model.errorMessage == nil)

    liveRadioID = initialRadioID
    await model.loadDiscoveredNodes()
    #expect(model.discoveredNodes.map(\.name) == ["Fetched"])
    #expect(model.visibleNodes.map(\.name) == ["Fetched"])
    #expect(model.hasLoadedOnce)
  }

  @Test
  func `ending a queued Discovery reload cancels debounce without reading its provider`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let radioID = UUID()
    let model = DiscoveryViewModel()
    var providerReads = 0
    model.configure(dataStore: {
      providerReads += 1
      return store
    }, radioID: { radioID })
    model.scheduleCoalescedReload()
    let cancelled = try #require(model.reloadTaskForTesting)
    model.cancelReloads()
    await cancelled.value
    #expect(providerReads == 0)
    #expect(!model.hasLoadedOnce)

    model.scheduleCoalescedReload()
    let current = try #require(model.reloadTaskForTesting)
    await current.value
    #expect(providerReads == 1)
    #expect(model.hasLoadedOnce)
  }

  private struct Fixture {
    let appState: AppState
    let radioID: UUID
    let defaults: UserDefaults
    let suiteName: String

    func cleanUp() {
      defaults.removePersistentDomain(forName: suiteName)
    }
  }

  private func makeFixture() throws -> Fixture {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let suiteName = "NodesSidebarStateTests.\(UUID())"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    let radioID = UUID()
    let appState = AppState(modelContainer: container, isPlaceholder: true, defaults: defaults)
    appState.connectionManager.persistConnection(deviceID: UUID(), radioID: radioID, deviceName: "Fixture")
    return Fixture(appState: appState, radioID: radioID, defaults: defaults, suiteName: suiteName)
  }

  private func frame(name: String, byte: UInt8) -> ContactFrame {
    ContactFrame(
      publicKey: Data(repeating: byte, count: ProtocolLimits.publicKeySize),
      type: .chat,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      name: name,
      lastAdvertTimestamp: 1_700_000_000,
      latitude: 0,
      longitude: 0,
      lastModified: 1_700_000_000
    )
  }

  private func makeContact(isFavorite: Bool) -> ContactDTO {
    ContactDTO(
      id: UUID(), radioID: UUID(), publicKey: Data(repeating: 0xAB, count: ProtocolLimits.publicKeySize),
      name: "Alex", typeRawValue: ContactType.chat.rawValue, flags: 0,
      outPathLength: 0, outPath: Data(), lastAdvertTimestamp: 0,
      latitude: 0, longitude: 0, lastModified: 0, lastHeardTimestamp: nil, nickname: nil,
      isBlocked: false, isMuted: false, isFavorite: isFavorite, lastMessageDate: nil, unreadCount: 0
    )
  }

  private func makeNode(radioID: UUID, name: String, type: ContactType) -> DiscoveredNodeDTO {
    DiscoveredNodeDTO(
      id: UUID(), radioID: radioID, publicKey: Data(repeating: 0xAB, count: ProtocolLimits.publicKeySize),
      name: name, typeRawValue: type.rawValue,
      lastHeard: Date(timeIntervalSince1970: 1_700_000_000), lastAdvertTimestamp: 1_700_000_000,
      latitude: 0, longitude: 0, outPathLength: 0, outPath: Data(),
      inboundHopCount: nil, inboundHopAdvertTimestamp: nil
    )
  }

  @MainActor
  private final class LoadGate {
    private var didEnter = false
    private var suspended: CheckedContinuation<Void, Never>?
    private var entryWaiter: CheckedContinuation<Void, Never>?

    func pause() async {
      didEnter = true
      entryWaiter?.resume()
      entryWaiter = nil
      await withCheckedContinuation { suspended = $0 }
    }

    func waitForEntry() async {
      guard !didEnter else { return }
      await withCheckedContinuation { entryWaiter = $0 }
    }

    func resume() {
      suspended?.resume()
      suspended = nil
    }
  }
}
