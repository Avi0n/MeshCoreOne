import Foundation
@testable import MC1Services
import SwiftData
import Testing

@MainActor
struct RadioEraseDiskTests {
  private enum InjectedFailure: Error {
    case save
  }

  @Test
  func `pending target survives partial cleanup and reopens without A device row`() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let url = directory.appendingPathComponent("cleanup.store")
    let radio = DeviceDTO(from: Device(publicKey: Data(repeating: 1, count: 32), nodeName: "Target"))
    let other = DeviceDTO(from: Device(publicKey: Data(repeating: 2, count: 32), nodeName: "Other"))
    let lifetime = ReceiveScope.Lifetime()
    let scope: ReceiveScope

    do {
      let store = try openStore(url)
      try await store.saveDevice(radio)
      try await store.saveDevice(other)
      try await store.recordRecentlyBlockedMessage(makeDrop(radio))
      try await store.recordRecentlyBlockedMessage(makeDrop(other))
      scope = try #require(await store.captureReceiveScope(radioID: radio.radioID, link: lifetime))

      await store.setRadioEraseFaults(begin: { throw InjectedFailure.save }, complete: nil)
      await #expect(throws: InjectedFailure.save) {
        _ = try await store.beginRadioErase(radio)
      }
      #expect(scope.isCurrent)
      #expect(try await store.fetchPendingRadioErases().isEmpty)
      #expect(try await store.fetchDevice(id: radio.id) != nil)
      #expect(try await store.recentlyBlockedCount() == 2)

      await store.setRadioEraseFaults(begin: nil, complete: { throw InjectedFailure.save })
      _ = try await store.beginRadioErase(radio, isFactoryReset: true)
      #expect(!scope.isCurrent)
      #expect(try await store.captureReceiveScope(radioID: radio.radioID, link: lifetime) == nil)
      await #expect(throws: InjectedFailure.save) {
        try await store.completeRadioErase(radioID: radio.radioID)
      }
      try await store.deleteDevice(id: radio.id)
    }

    let reopened = try openStore(url)
    let target = try #require(await reopened.fetchPendingRadioErases().first)
    #expect(target.radioID == radio.radioID)
    #expect(target.radioName == radio.nodeName)
    #expect(target.isFactoryReset == true)
    #expect(try await reopened.captureReceiveScope(radioID: radio.radioID, link: .init()) == nil)
    try await reopened.completeRadioErase(radioID: target.radioID)
    #expect(try await reopened.fetchPendingRadioErases().isEmpty)
    #expect(try await reopened.recentlyBlockedMessages().map(\.identity.radioID) == [other.radioID])
    #expect(try await reopened.fetchDevice(id: other.id) != nil)
    #expect(!scope.isCurrent)
  }

  private func openStore(_ url: URL) throws -> PersistenceStore {
    let schema = PersistenceStore.schema
    let configuration = ModelConfiguration(schema: schema, url: url)
    return try PersistenceStore(modelContainer: ModelContainer(for: schema, configurations: configuration))
  }

  private func makeDrop(_ radio: DeviceDTO) throws -> RecentlyBlockedMessageDTO {
    let body = "Hidden body"
    var preference = TextRulesPreference()
    preference.append(.init(phrase: "hidden", lookIn: .message, match: .contains))
    return try RecentlyBlockedMessageDTO(
      identity: .init(
        radioID: radio.radioID, conversationID: nil, senderPrefix: nil,
        senderTimestamp: 1, textType: 0, wireText: Data(body.utf8)
      ),
      arrivedAt: Date(), body: body, sender: nil, conversationName: nil, radioName: radio.nodeName,
      match: #require(preference.firstMatch(body: body, sender: nil))
    )
  }
}
