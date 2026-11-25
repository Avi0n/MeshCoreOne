import CoreLocation
@testable import MC1
@testable import MC1Services
import Testing
import UIKit

@Suite("MessagePathPreviewSnapshot")
struct MessagePathPreviewSnapshotTests {
  @Test
  func `width buckets ignore one-point jitter`() {
    #expect(MessagePathPreviewSnapshot.bucketedWidth(357) == MessagePathPreviewSnapshot.bucketedWidth(358))
    #expect(MessagePathPreviewSnapshot.bucketedWidth(357) != MessagePathPreviewSnapshot.bucketedWidth(340))
  }
}

@Suite("MessagePathViewModel preview prefetch")
@MainActor
struct MessagePathViewModelPreviewTests {
  @Test
  func `prefetch stores an image and skips a second render of the same key`() async {
    let fake = FakePathPreviewRenderer()
    fake.image = Self.pixel()
    let viewModel = MessagePathViewModel(renderPreview: fake.asRenderer)
    viewModel.isLoading = false
    let fixture = Self.locatedFixture()
    viewModel.contacts = [fixture.hop]
    viewModel.repeaters = [fixture.hop]

    await viewModel.prefetchPreviews(
      sender: MessagePathSender(message: fixture.message),
      arrivals: fixture.arrivals,
      skipping: nil,
      connectedDevice: nil,
      userLocation: fixture.userLocation,
      isDark: false,
      isOffline: false,
      previewWidth: 358
    )
    let key = MessagePathPreviewSnapshot.key(
      arrivalID: fixture.arrivals[0].id,
      isDark: false,
      isOffline: false,
      previewWidth: 358
    )
    #expect(viewModel.previewImage(for: key) != nil)
    #expect(fake.callCount == 1)

    await viewModel.prefetchPreviews(
      sender: MessagePathSender(message: fixture.message),
      arrivals: fixture.arrivals,
      skipping: nil,
      connectedDevice: nil,
      userLocation: fixture.userLocation,
      isDark: false,
      isOffline: false,
      previewWidth: 358
    )
    #expect(fake.callCount == 1)
  }

  @Test
  func `nil render marks failed and retry calls the renderer again`() async {
    let fake = FakePathPreviewRenderer()
    fake.image = nil
    let viewModel = MessagePathViewModel(renderPreview: fake.asRenderer)
    viewModel.isLoading = false
    let fixture = Self.locatedFixture()
    viewModel.contacts = [fixture.hop]
    viewModel.repeaters = [fixture.hop]

    await viewModel.prefetchPreviews(
      sender: MessagePathSender(message: fixture.message),
      arrivals: fixture.arrivals,
      skipping: nil,
      connectedDevice: nil,
      userLocation: fixture.userLocation,
      isDark: false,
      isOffline: false,
      previewWidth: 358
    )
    let key = MessagePathPreviewSnapshot.key(
      arrivalID: fixture.arrivals[0].id,
      isDark: false,
      isOffline: false,
      previewWidth: 358
    )
    #expect(viewModel.previewFailed(for: key))
    #expect(viewModel.previewImage(for: key) == nil)
    #expect(fake.callCount == 1)

    fake.image = Self.pixel()
    await viewModel.retryPreview(
      arrivalID: fixture.arrivals[0].id,
      sender: MessagePathSender(message: fixture.message),
      arrivals: fixture.arrivals,
      connectedDevice: nil,
      userLocation: fixture.userLocation,
      isDark: false,
      isOffline: false,
      previewWidth: 358
    )
    #expect(fake.callCount == 2)
    #expect(viewModel.previewImage(for: key) != nil)
    #expect(viewModel.previewFailed(for: key) == false)
  }

  @Test
  func `prefetch while loading does not render or drop a stored image`() async {
    let fake = FakePathPreviewRenderer()
    fake.image = Self.pixel()
    let viewModel = MessagePathViewModel(renderPreview: fake.asRenderer)
    viewModel.isLoading = false
    let fixture = Self.locatedFixture()
    viewModel.contacts = [fixture.hop]
    viewModel.repeaters = [fixture.hop]

    await viewModel.prefetchPreviews(
      sender: MessagePathSender(message: fixture.message),
      arrivals: fixture.arrivals,
      skipping: nil,
      connectedDevice: nil,
      userLocation: fixture.userLocation,
      isDark: false,
      isOffline: false,
      previewWidth: 358
    )
    let key = MessagePathPreviewSnapshot.key(
      arrivalID: fixture.arrivals[0].id,
      isDark: false,
      isOffline: false,
      previewWidth: 358
    )
    #expect(viewModel.previewImage(for: key) != nil)
    let callsAfterStore = fake.callCount

    viewModel.isLoading = true
    await viewModel.prefetchPreviews(
      sender: MessagePathSender(message: fixture.message),
      arrivals: fixture.arrivals,
      skipping: nil,
      connectedDevice: nil,
      userLocation: fixture.userLocation,
      isDark: false,
      isOffline: false,
      previewWidth: 358
    )
    #expect(fake.callCount == callsAfterStore)
    #expect(viewModel.previewImage(for: key) != nil)
  }

  @Test
  func `selected render completes while a prefetch that skips it runs`() async throws {
    let gate = SuspendingPathPreviewRenderer(image: Self.pixel())
    let viewModel = MessagePathViewModel(renderPreview: gate.asRenderer)
    viewModel.isLoading = false
    let fixture = Self.locatedFixture()
    viewModel.contacts = [fixture.hop]
    viewModel.repeaters = [fixture.hop]
    let selected = fixture.arrivals[0]
    let heardLater = MessagePathArrival(
      id: UUID(),
      pathNodes: selected.pathNodes,
      pathLength: selected.pathLength,
      snr: nil,
      rssi: nil,
      receivedAt: selected.receivedAt.addingTimeInterval(1),
      isFirst: false
    )
    let sender = MessagePathSender(message: fixture.message)

    let selectedRender = Task {
      await viewModel.renderSelectedPreview(
        arrivalID: selected.id,
        sender: sender,
        arrivals: [selected],
        connectedDevice: nil,
        userLocation: fixture.userLocation,
        isDark: false,
        isOffline: false,
        previewWidth: 358
      )
    }
    try await waitUntil("selected render started") { gate.callCount == 1 }

    let prefetch = Task {
      await viewModel.prefetchPreviews(
        sender: sender,
        arrivals: [selected, heardLater],
        skipping: selected.id,
        connectedDevice: nil,
        userLocation: fixture.userLocation,
        isDark: false,
        isOffline: false,
        previewWidth: 358
      )
    }
    try await waitUntil("prefetch render started") { gate.callCount == 2 }
    gate.resumeAll()
    await selectedRender.value
    await prefetch.value

    let selectedKey = MessagePathPreviewSnapshot.key(
      arrivalID: selected.id,
      isDark: false,
      isOffline: false,
      previewWidth: 358
    )
    #expect(viewModel.previewImage(for: selectedKey) != nil)
    #expect(gate.callCount == 2)
  }

  @Test
  func `cancelled render neither stores nor marks the key failed`() async throws {
    let gate = SuspendingPathPreviewRenderer(image: Self.pixel())
    let viewModel = MessagePathViewModel(renderPreview: gate.asRenderer)
    viewModel.isLoading = false
    let fixture = Self.locatedFixture()
    viewModel.contacts = [fixture.hop]
    viewModel.repeaters = [fixture.hop]
    let arrival = fixture.arrivals[0]

    let render = Task {
      await viewModel.renderSelectedPreview(
        arrivalID: arrival.id,
        sender: MessagePathSender(message: fixture.message),
        arrivals: fixture.arrivals,
        connectedDevice: nil,
        userLocation: fixture.userLocation,
        isDark: false,
        isOffline: false,
        previewWidth: 358
      )
    }
    try await waitUntil("render started") { gate.callCount == 1 }
    render.cancel()
    gate.resumeAll()
    await render.value

    let key = MessagePathPreviewSnapshot.key(
      arrivalID: arrival.id,
      isDark: false,
      isOffline: false,
      previewWidth: 358
    )
    #expect(viewModel.previewImage(for: key) == nil)
    #expect(viewModel.previewFailed(for: key) == false)
  }

  private struct LocatedFixture {
    let message: MessageDTO
    let arrivals: [MessagePathArrival]
    let userLocation: CLLocation
    let hop: ContactDTO
  }

  private static func locatedFixture() -> LocatedFixture {
    let hop = ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data([0xAA] + Array(repeating: UInt8(0), count: 31)),
      name: "HopA",
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 37.11,
      longitude: -122.1,
      lastModified: 0,
      lastHeardTimestamp: nil,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
    let message = MessageDTO(
      id: UUID(),
      radioID: UUID(),
      contactID: nil,
      channelIndex: 0,
      text: "flood",
      timestamp: 1,
      createdAt: Date(),
      direction: .incoming,
      status: .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: 1,
      snr: 8.5,
      pathNodes: Data([0xAA]),
      senderKeyPrefix: nil,
      senderNodeName: "Alice",
      isRead: true,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
    let arrivals = MessagePathArrivals.assemble(message: message, repeats: [])
    return LocatedFixture(
      message: message,
      arrivals: arrivals,
      userLocation: CLLocation(latitude: 37.5, longitude: -122.3),
      hop: hop
    )
  }

  private static func pixel() -> UIImage {
    UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1)).image { ctx in
      UIColor.red.setFill()
      ctx.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
    }
  }
}

@MainActor
private final class FakePathPreviewRenderer {
  var image: UIImage?
  private(set) var callCount = 0

  var asRenderer: MessagePathViewModel.PathPreviewRenderer {
    { [weak self] _, _, _, _, _, _ in
      guard let self else { return nil }
      self.callCount += 1
      return self.image
    }
  }
}

/// Holds each render until `resumeAll()`; a cancelled render returns nil like the real renderer.
@MainActor
private final class SuspendingPathPreviewRenderer {
  private let image: UIImage
  private var waiting: [CheckedContinuation<Void, Never>] = []
  private(set) var callCount = 0

  init(image: UIImage) {
    self.image = image
  }

  var asRenderer: MessagePathViewModel.PathPreviewRenderer {
    { [weak self] _, _, _, _, _, _ in
      guard let self else { return nil }
      callCount += 1
      await withCheckedContinuation { waiting.append($0) }
      return Task.isCancelled ? nil : image
    }
  }

  func resumeAll() {
    let pending = waiting
    waiting.removeAll()
    pending.forEach { $0.resume() }
  }
}
