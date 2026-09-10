import UIKit
import UniformTypeIdentifiers

/// Placeholder is empty String so the share sheet classifies this as text and offers Copy.
/// A meshcore:// String is otherwise treated as a URL, and Copy is http(s)-only.
final class ContactURIActivityItem: NSObject, UIActivityItemSource {
  let uri: String
  let subject: String

  init(uri: String, subject: String) {
    self.uri = uri
    self.subject = subject
  }

  func activityViewControllerPlaceholderItem(_ activityViewController: UIActivityViewController) -> Any {
    ""
  }

  func activityViewController(
    _ activityViewController: UIActivityViewController,
    itemForActivityType activityType: UIActivity.ActivityType?
  ) -> Any? {
    uri
  }

  func activityViewController(
    _ activityViewController: UIActivityViewController,
    subjectForActivityType activityType: UIActivity.ActivityType?
  ) -> String {
    subject
  }

  func activityViewController(
    _ activityViewController: UIActivityViewController,
    dataTypeIdentifierForActivityType activityType: UIActivity.ActivityType?
  ) -> String {
    UTType.utf8PlainText.identifier
  }
}
