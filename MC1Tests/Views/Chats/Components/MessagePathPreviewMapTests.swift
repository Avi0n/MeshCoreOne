import Foundation
@testable import MC1
import SwiftUI
import Testing
import UIKit

@Suite("MessagePathPreviewMap")
struct MessagePathPreviewMapTests {
  @Test
  @MainActor
  func `overlay exposes distance and incomplete without hops`() {
    let labels = hostedLabels()
    let distance = Measurement(value: 12400, unit: UnitLength.meters)
      .formatted(.measurement(width: .abbreviated, usage: .road))
    #expect(labels.contains { $0.contains(distance) || $0 == distance })
    #expect(labels.contains(L10n.Chats.Chats.Path.Distance.incomplete))
    #expect(labels.contains(L10n.Chats.Chats.Path.Accessibility.viewOnMap))
    #expect(!labels.contains(L10n.Contacts.Contacts.Trace.Map.hops(0)))
  }
}

@MainActor
private func hostedLabels() -> [String] {
  let image = UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1)).image { _ in }
  let content = MessagePathPreviewMap(
    image: image,
    didFail: false,
    totalPathDistance: 12400,
    isDistanceIncomplete: true,
    onExpand: {},
    onRetry: {}
  )
  .frame(width: 390, height: MessagePathPreviewMap.previewHeight)

  let host = UIHostingController(rootView: content)
  let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
  window.rootViewController = host
  window.isHidden = false
  window.layoutIfNeeded()
  return accessibilityLabels(in: host.view)
}

@MainActor
private func accessibilityLabels(in view: UIView) -> [String] {
  var labels: [String] = []
  func appendLabel(_ object: NSObject) {
    if let label = object.accessibilityLabel, !label.isEmpty {
      labels.append(label)
    }
    if let text = (object as? UILabel)?.text, !text.isEmpty {
      labels.append(text)
    }
  }
  if view.accessibilityElementsHidden { return labels }
  if view.isAccessibilityElement {
    appendLabel(view)
  }
  if let elements = view.accessibilityElements {
    for element in elements {
      guard let object = element as? NSObject else { continue }
      appendLabel(object)
    }
  }
  let count = view.accessibilityElementCount()
  if count != NSNotFound {
    for index in 0..<count {
      if let object = view.accessibilityElement(at: index) as? NSObject {
        appendLabel(object)
      }
    }
  }
  for subview in view.subviews {
    labels.append(contentsOf: accessibilityLabels(in: subview))
  }
  return labels
}
