import Foundation
@testable import MC1Services
import Testing

@Suite("MECP notifications")
struct MECPNotificationTests {
  private let canonical = "MECP/0/M01 M02 T04 3pax 48.8566,2.3522 @1430 @en"

  @Test
  func `channel arrival body prefixes the sender after the space-joined reading`() {
    let reading = MECPCatalog.displayText(canonical, bundle: .module, preferredLocalizations: ["en"])
    let body = NotificationService.arrivalBody(
      messageText: canonical,
      senderName: "Ada",
      bundle: .module
    )
    #expect(body == "Ada: \(reading)")
    #expect(body.hasPrefix("Ada: MAYDAY"))
    #expect(body != "Ada: \(canonical)")
    #expect(!body.contains("Ada: MECP"))
  }

  @Test
  func `reaction quote truncates the space-joined reading`() {
    let reading = MECPCatalog.displayText(canonical, bundle: .module, preferredLocalizations: ["en"])
    let quote = NotificationActionHandler.reactionQuote(for: canonical, bundle: .module)
    #expect(reading.count > NotificationActionHandler.reactionPreviewMaxLength)
    #expect(quote == NotificationActionHandler.reactionPreview(for: reading))
    #expect(quote.hasPrefix("MAYDAY"))
    #expect(quote != NotificationActionHandler.reactionPreview(for: canonical))
  }
}
