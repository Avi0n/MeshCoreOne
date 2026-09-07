@testable import MC1
import Testing
import UIKit

@Suite("ContactURIActivityItem")
@MainActor
struct ContactURIActivityItemTests {
  private static let uri = "meshcore://contact/add?name=Alice&public_key=AB&type=1"
  private static let subject = "MeshCore One Contact"

  @Test
  func `placeholder is a String not a URL`() {
    let item = ContactURIActivityItem(uri: Self.uri, subject: Self.subject)
    let controller = UIActivityViewController(activityItems: [], applicationActivities: nil)
    let placeholder = item.activityViewControllerPlaceholderItem(controller)
    #expect(placeholder is String)
    #expect(placeholder is URL == false)
  }

  @Test
  func `copy activity returns the uri string`() {
    let item = ContactURIActivityItem(uri: Self.uri, subject: Self.subject)
    let controller = UIActivityViewController(activityItems: [], applicationActivities: nil)
    let value = item.activityViewController(controller, itemForActivityType: .copyToPasteboard) as? String
    #expect(value == Self.uri)
  }
}
