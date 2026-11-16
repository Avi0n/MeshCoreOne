import Foundation
@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing
import UIKit

@Suite("Repeat row accessibility")
@MainActor
struct RepeatRowAccessibilityTests {
  private static let viewport = CGRect(x: 0, y: 0, width: 390, height: 844)
  private static let repeaterHash: UInt8 = 0x3F

  @Test
  func `a fallback match keeps its explanation separately accessible`() async throws {
    let radioID = UUID()
    let repeaters = [
      makeRepeater(radioID: radioID, secondByte: 0x01, name: "Ridge"),
      makeRepeater(radioID: radioID, secondByte: 0x02, name: "Valley")
    ]
    let resolution = RepeatRowView.resolution(
      repeaterHash: Data([Self.repeaterHash]),
      repeaters: repeaters,
      discoveredRepeaters: [],
      userLocation: nil
    )
    try #require(resolution.matchKind == .fallback)
    let repeatEntry = MessageRepeatDTO(
      messageID: UUID(),
      receivedAt: Date(),
      pathNodes: Data([Self.repeaterHash]),
      pathLength: encodePathLen(hashSize: 1, hopCount: 1),
      snr: 6.5,
      rssi: -92,
      rxLogEntryID: nil
    )
    let controller = UIHostingController(rootView: VStack {
      RepeatRowView(
        repeatEntry: repeatEntry,
        repeaters: repeaters,
        discoveredRepeaters: [],
        userLocation: nil
      )
      .padding()
      Spacer()
    })
    let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
    let previousKeyWindow = scene.keyWindow
    let window = UIWindow(windowScene: scene)
    window.frame = Self.viewport
    window.rootViewController = controller
    window.makeKeyAndVisible()
    defer {
      controller.dismiss(animated: false)
      window.isHidden = true
      window.rootViewController = nil
      window.windowScene = nil
      previousKeyWindow?.makeKey()
    }

    let rowLabel = L10n.Chats.Chats.Repeats.Row.accessibility(resolution.displayName)
    try await waitUntil("The fallback repeat row did not render") {
      window.layoutIfNeeded()
      return accessibleNodes(in: window).contains { $0.accessibilityLabel == rowLabel }
    }
    let row = try #require(accessibleNodes(in: window).first { $0.accessibilityLabel == rowLabel })
    let indicator = try #require(accessibleNodes(in: row).first {
      $0 !== row
        && $0.isAccessibilityElement
        && $0.accessibilityLabel == L10n.Chats.Chats.Path.Hop.possibleMatch
        && $0.accessibilityTraits.contains(.button)
    })
    try #require(indicator.accessibilityActivate())

    try await waitUntil("The possible-match explanation did not appear") {
      window.layoutIfNeeded()
      let labels = accessibleNodes(in: window).compactMap(\.accessibilityLabel)
      return labels.contains(L10n.Chats.Chats.Path.Hop.possibleMatchTitle)
        && labels.contains(L10n.Chats.Chats.Path.Hop.possibleMatchExplanation)
    }
  }

  private func makeRepeater(radioID: UUID, secondByte: UInt8, name: String) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: Data([Self.repeaterHash, secondByte] + Array(repeating: UInt8(0), count: ProtocolLimits.publicKeySize - 2)),
      name: name,
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      lastHeardTimestamp: nil,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
  }

  private func accessibleNodes(in root: NSObject) -> [NSObject] {
    var visited: Set<ObjectIdentifier> = []
    var result: [NSObject] = []
    func walk(_ object: NSObject) {
      guard visited.insert(ObjectIdentifier(object)).inserted else { return }
      if let view = object as? UIView, view.isHidden || view.accessibilityElementsHidden { return }
      let count = object.accessibilityElementCount()
      if object.isAccessibilityElement || object.accessibilityLabel != nil {
        result.append(object)
      }
      if count != NSNotFound, count > 0 {
        for index in 0..<count {
          if let child = object.accessibilityElement(at: index) as? NSObject { walk(child) }
        }
        return
      }
      if let elements = object.accessibilityElements, !elements.isEmpty {
        for case let child as NSObject in elements {
          walk(child)
        }
        return
      }
      guard !object.isAccessibilityElement, let view = object as? UIView else { return }
      view.subviews.filter { $0.alpha > 0 }.forEach(walk)
    }
    walk(root)
    return result
  }
}
