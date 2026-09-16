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

    await viewModel.prefetchPreviews(
      message: fixture.message,
      arrivals: fixture.arrivals,
      selectedID: nil,
      connectedDevice: nil,
      userLocation: fixture.userLocation,
      isDark: false,
      isOffline: false,
      containerWidth: 390
    )
    let key = MessagePathPreviewSnapshot.key(
      arrivalID: fixture.arrivals[0].id,
      isDark: false,
      isOffline: false,
      containerWidth: 390
    )
    #expect(viewModel.previewImage(for: key) != nil)
    #expect(fake.callCount == 1)

    await viewModel.prefetchPreviews(
      message: fixture.message,
      arrivals: fixture.arrivals,
      selectedID: nil,
      connectedDevice: nil,
      userLocation: fixture.userLocation,
      isDark: false,
      isOffline: false,
      containerWidth: 390
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

    await viewModel.prefetchPreviews(
      message: fixture.message,
      arrivals: fixture.arrivals,
      selectedID: nil,
      connectedDevice: nil,
      userLocation: fixture.userLocation,
      isDark: false,
      isOffline: false,
      containerWidth: 390
    )
    let key = MessagePathPreviewSnapshot.key(
      arrivalID: fixture.arrivals[0].id,
      isDark: false,
      isOffline: false,
      containerWidth: 390
    )
    #expect(viewModel.previewFailed(for: key))
    #expect(viewModel.previewImage(for: key) == nil)
    #expect(fake.callCount == 1)

    fake.image = Self.pixel()
    await viewModel.retryPreview(
      arrivalID: fixture.arrivals[0].id,
      message: fixture.message,
      arrivals: fixture.arrivals,
      connectedDevice: nil,
      userLocation: fixture.userLocation,
      isDark: false,
      isOffline: false,
      containerWidth: 390
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

    await viewModel.prefetchPreviews(
      message: fixture.message,
      arrivals: fixture.arrivals,
      selectedID: nil,
      connectedDevice: nil,
      userLocation: fixture.userLocation,
      isDark: false,
      isOffline: false,
      containerWidth: 390
    )
    let key = MessagePathPreviewSnapshot.key(
      arrivalID: fixture.arrivals[0].id,
      isDark: false,
      isOffline: false,
      containerWidth: 390
    )
    #expect(viewModel.previewImage(for: key) != nil)
    let callsAfterStore = fake.callCount

    viewModel.isLoading = true
    await viewModel.prefetchPreviews(
      message: fixture.message,
      arrivals: fixture.arrivals,
      selectedID: nil,
      connectedDevice: nil,
      userLocation: fixture.userLocation,
      isDark: false,
      isOffline: false,
      containerWidth: 390
    )
    #expect(fake.callCount == callsAfterStore)
    #expect(viewModel.previewImage(for: key) != nil)
  }

  private struct LocatedFixture {
    let message: MessageDTO
    let arrivals: [MessagePathArrival]
    let userLocation: CLLocation
  }

  private static func locatedFixture() -> LocatedFixture {
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
      userLocation: CLLocation(latitude: 37.5, longitude: -122.3)
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
