import Foundation
@testable import MC1Services
import Testing

struct NotificationStringProviderTests {
  @Test
  @MainActor
  func `Default fallback titles are English`() {
    let service = NotificationService()
    #expect(service.defaultDiscoveryTitle(for: .chat) == "New Contact Discovered")
    #expect(service.defaultDiscoveryTitle(for: .repeater) == "New Repeater Discovered")
    #expect(service.defaultDiscoveryTitle(for: .room) == "New Room Discovered")
  }
}
