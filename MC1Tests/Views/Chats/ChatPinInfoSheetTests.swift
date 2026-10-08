import Foundation
@testable import MC1
@testable import MC1Services
import SwiftData
import SwiftUI
import Testing
import UIKit
import Vision

@Suite("Chat pin info sheets", .serialized)
@MainActor
struct ChatPinInfoSheetTests {
  private static let viewport = CGRect(x: 0, y: 0, width: 390, height: 844)

  @Test
  func `turning Pin on for a missing channel reverts the switch and hides Try Again`() async throws {
    let store = try makeStore()
    let viewModel = configuredViewModel(dataStore: store)
    let channel = makeChannel()
    let host = try mount(viewModel: viewModel) {
      ChannelInfoSheet(channel: channel, onClearMessages: {}, onDelete: {})
    }
    defer { hide(host) }

    try await turnPinOn(in: host.window)
    let message = PersistenceStoreError.channelNotFound.userFacingMessage
    let text = try await revertedSheetText(in: host.window)
    #expect(text.contains(message), "saw \(text)")
    #expect(!text.contains(L10n.Localizable.Common.tryAgain))
    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `turning Pin on for a missing room shows the session error in the sheet`() async throws {
    let store = try makeStore()
    let viewModel = configuredViewModel(dataStore: store)
    let session = makeSession()
    let host = try mount(viewModel: viewModel) {
      RoomInfoSheet(session: session)
    }
    defer { hide(host) }

    try await turnPinOn(in: host.window)
    let message = PersistenceStoreError.remoteNodeSessionNotFound.userFacingMessage
    let text = try await revertedSheetText(in: host.window)
    #expect(text.contains(message), "saw \(text)")
    #expect(viewModel.errorMessage == nil)
  }

  private func makeStore() throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  private func makeChannel() -> ChannelDTO {
    ChannelDTO(
      id: UUID(),
      radioID: UUID(),
      index: 1,
      name: "General",
      secret: Data(repeating: 0, count: 16),
      isEnabled: true,
      lastMessageDate: nil,
      unreadCount: 0,
      isPinned: false
    )
  }

  private func makeSession() -> RemoteNodeSessionDTO {
    RemoteNodeSessionDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0xCC, count: 32),
      name: "Ops",
      role: .roomServer,
      isConnected: false,
      isPinned: false
    )
  }

  private func configuredViewModel(dataStore: PersistenceStore?) -> ChatViewModel {
    let viewModel = ChatViewModel()
    viewModel.configure(
      dependencies: ChatViewModel.Dependencies(
        dataStore: { dataStore },
        messageService: { nil },
        notificationService: { nil },
        channelService: { nil },
        roomServerService: { nil },
        contactService: { nil },
        syncCoordinator: { nil },
        connectionState: { .disconnected },
        connectedDevice: { nil },
        currentRadioID: { nil },
        session: { nil },
        reactionService: { nil },
        chatSendQueueService: { nil },
        inlineImageDimensionsStore: { nil },
        prefetchDataStore: { nil }
      ),
      onNavigateToMap: nil,
      linkPreviewCache: nil,
      chatCoordinatorRegistry: nil,
      conversation: nil
    )
    return viewModel
  }

  private struct Host {
    let window: UIWindow
    let appState: AppState
  }

  private func mount(viewModel: ChatViewModel, sheet: () -> some View) throws -> Host {
    let configuration = ModelConfiguration(isStoredInMemoryOnly: true)
    let container = try ModelContainer(for: PersistenceStore.schema, configurations: [configuration])
    let appState = AppState(modelContainer: container, isPlaceholder: true)
    let rootView = sheet()
      .environment(\.appState, appState)
      .environment(\.chatViewModel, viewModel)
    let controller = UIHostingController(rootView: rootView)
    let window = UIWindow(frame: Self.viewport)
    window.rootViewController = controller
    window.makeKeyAndVisible()
    window.layoutIfNeeded()
    return Host(window: window, appState: appState)
  }

  private func hide(_ host: Host) {
    host.window.isHidden = true
    host.window.rootViewController = nil
    host.appState.shutdown()
  }

  /// List text on iOS 27 is drawn in a SwiftUI cell, not a UILabel.
  private func revertedSheetText(in window: UIWindow) async throws -> String {
    try await waitUntil(timeout: .seconds(2), "Pin switch did not revert") {
      window.layoutIfNeeded()
      return findSwitches(in: window).first?.isOn == false
    }
    let renderer = UIGraphicsImageRenderer(bounds: window.bounds)
    let image = renderer.image { window.layer.render(in: $0.cgContext) }
    guard let cgImage = image.cgImage else { return "" }
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.usesLanguageCorrection = false
    try VNImageRequestHandler(cgImage: cgImage).perform([request])
    return request.results?
      .compactMap { $0.topCandidates(1).first?.string }
      .joined(separator: "\n") ?? ""
  }

  private func turnPinOn(in window: UIWindow) async throws {
    try await waitUntil("Pin switch never appeared") {
      window.layoutIfNeeded()
      return findSwitches(in: window).count == 1
    }
    let pinSwitch = try #require(findSwitches(in: window).first)
    #expect(!pinSwitch.isOn)
    pinSwitch.setOn(true, animated: false)
    pinSwitch.sendActions(for: .valueChanged)
  }

  private func findSwitches(in view: UIView) -> [UISwitch] {
    if let toggle = view as? UISwitch { return [toggle] }
    return view.subviews.flatMap { findSwitches(in: $0) }
  }
}
