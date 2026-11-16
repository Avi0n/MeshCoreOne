@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing
import UIKit

@MainActor
@Suite("Message day divider accessibility", .serialized)
struct MessageDayDividerAccessibilityTests {
  private static let viewport = CGRect(x: 0, y: 0, width: 390, height: 800)
  private static let renderTimeout: TimeInterval = 2
  private static let runLoopSlice: TimeInterval = 0.01
  private static let narrowWidth: CGFloat = 320

  @Test
  func `full date stays within a narrow viewport at the largest accessibility text size`() throws {
    let date = try #require(Calendar.current.date(from: DateComponents(year: 2026, month: 9, day: 30)))
    let (window, controller) = mount(
      MessageDayDividerView(date: date)
        .padding(.horizontal, 12)
        .environment(\.dynamicTypeSize, .accessibility5),
      width: Self.narrowWidth
    )
    defer { window.isHidden = true }

    let heading = try #require(exposedElements(in: controller.view).first)
    let frame = window.convert(heading.accessibilityFrame, from: nil)
    #expect(frame.width > 0)
    #expect(frame.minX >= window.bounds.minX)
    #expect(frame.maxX <= window.bounds.maxX)
    #expect(heading.accessibilityLabel == date.formatted(.dateTime.day().month(.wide).year()))
    #expect(heading.accessibilityTraits.contains(.header))
  }

  @Test
  func `date is the only accessible element and has the heading trait`() throws {
    let date = MessageBubbleTestData.referenceDate
    let (window, controller) = mount(MessageDayDividerView(date: date))
    defer { window.isHidden = true }

    let elements = exposedElements(in: controller.view)
    let element = try #require(elements.first)
    #expect(elements.count == 1)
    #expect(element.accessibilityLabel == date.formatted(.dateTime.day().month(.wide).year()))
    #expect(element.accessibilityTraits.contains(.header))
  }

  @Test(arguments: [false, true])
  func `date stays separate from the message`(isChannel: Bool) throws {
    let message = isChannel
      ? MessageBubbleTestData.incomingChannel(text: "Meet at the bridge")
      : MessageBubbleTestData.outgoingDM(text: "Meet at the bridge")
    let bundle = MessageBubbleTestData.messageItem(message: message)
    let item = bundle.item.with(grouping: GroupingFlags(
      showTimestamp: false,
      showDirectionGap: false,
      showSenderName: isChannel,
      showNewMessagesDivider: false,
      showDayDivider: true
    ))
    let bubble = UnifiedMessageBubble(
      message: message,
      contactName: "Alice",
      deviceName: "Me",
      configuration: isChannel ? .channel(isPublic: false) : .directMessage,
      item: item,
      layout: FragmentLayout(content: item.content),
      imageResolver: bundle.imageResolver
    )
    let (window, controller) = mount(bubble)
    defer { window.isHidden = true }

    let elements = exposedElements(in: controller.view)
    #expect(elements.count == 2, "\(elements.map { String(reflecting: type(of: $0)) + ":" + ($0.accessibilityLabel ?? "nil") })")
    let heading = try #require(elements.first)
    #expect(heading.accessibilityTraits.contains(.header))
    #expect(heading.accessibilityLabel == item.envelope.date.formatted(.dateTime.day().month(.wide).year()))
    let messageElement = try #require(elements.last)
    #expect(messageElement !== heading)
    #expect(messageElement.accessibilityTraits.contains(.header) == false)
    #expect(messageElement.accessibilityLabel == bubble.accessibilityMessageLabel)
    #expect(accessibilityIdentifier(of: messageElement) == ChatScrollConstants.messageAccessibilityIdentifier(message.id))
  }

  @Test
  func `unavailable message keeps a message identifier`() throws {
    let message = MessageBubbleTestData.outgoingDM(text: "Unavailable message")
    let item = MessageBubbleTestData.messageItem(message: message).item
    let bubble = MessageBubbleView(
      item: item,
      contactName: "Alice",
      deviceName: "Me",
      configuration: .directMessage,
      resolver: BubbleResolver(message: { _ in nil }, image: { _ in nil }),
      actions: BubbleActions(
        onRetryMessage: { _ in }, onLongPress: { _ in }, onImageTap: { _ in },
        onRetryInlineImage: { _ in }, onRequestPreviewFetch: { _ in }, onManualPreviewFetch: { _ in },
        onMapPreviewTap: { _ in }, snapshotResolver: { _ in nil }, requestSnapshot: { _ in },
        retrySnapshot: { _ in }, onTranslationAction: { _ in }
      )
    )
    let (window, controller) = mount(bubble)
    defer { window.isHidden = true }
    let element = try #require(exposedElements(in: controller.view).first)
    #expect(element.accessibilityLabel == L10n.Chats.Chats.Message.unavailableAccessibility)
    #expect(accessibilityIdentifier(of: element) == ChatScrollConstants.messageAccessibilityIdentifier(item.id))
  }

  private func accessibilityIdentifier(of object: NSObject) -> String? {
    // Hosted elements answer the getter without declaring UIAccessibilityIdentification.
    let getter = #selector(getter: UIAccessibilityIdentification.accessibilityIdentifier)
    guard object.responds(to: getter) else { return nil }
    return object.perform(getter)?.takeUnretainedValue() as? String
  }

  private func mount(_ content: some View, width: CGFloat = viewport.width) -> (UIWindow, UIViewController) {
    let controller = UIHostingController(rootView: content.environment(\.accessibilityEnabled, true))
    let window: UIWindow
    if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
      window = UIWindow(windowScene: scene)
      window.frame = Self.viewport
    } else {
      window = UIWindow(frame: Self.viewport)
    }
    window.frame.size.width = width
    window.rootViewController = controller
    window.makeKeyAndVisible()
    window.layoutIfNeeded()
    let deadline = Date(timeIntervalSinceNow: Self.renderTimeout)
    while exposedElements(in: controller.view).isEmpty, Date() < deadline {
      RunLoop.main.run(until: Date(timeIntervalSinceNow: Self.runLoopSlice))
    }
    return (window, controller)
  }

  private func exposedElements(in root: NSObject) -> [NSObject] {
    var visited = Set<ObjectIdentifier>()
    var elements: [NSObject] = []
    func visit(_ object: NSObject) {
      guard visited.insert(ObjectIdentifier(object)).inserted,
            !object.accessibilityElementsHidden else { return }
      if let view = object as? UIView, view.isHidden { return }
      if object.isAccessibilityElement {
        elements.append(object)
        return
      }
      if let children = object.accessibilityElements {
        for case let child as NSObject in children {
          visit(child)
        }
      }
      if let children = object.automationElements {
        for case let child as NSObject in children {
          visit(child)
        }
      }
      let count = object.accessibilityElementCount()
      if count != NSNotFound, count > 0 {
        for index in 0..<count {
          if let child = object.accessibilityElement(at: index) as? NSObject { visit(child) }
        }
      }
      if let view = object as? UIView {
        for subview in view.subviews {
          visit(subview)
        }
      }
    }
    visit(root)
    return elements
  }
}
