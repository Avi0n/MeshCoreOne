import Foundation
@testable import MC1Services
import Testing

extension BackupIntegrationTests {
  @Test
  func `metadata-only outgoing reaction import reports restored changes once`() async throws {
    let radioID = UUID()
    let destination = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let channel = ChannelDTO.testChannel(radioID: radioID)
    try await destination.saveChannel(channel)
    let target = MessageDTO.testChannelMessage(radioID: radioID, channelIndex: channel.index, text: "Target")
    let reaction = MessageDTO.testChannelMessage(radioID: radioID, channelIndex: channel.index, text: "Reaction")
    try await destination.saveMessage(target)
    try await destination.saveMessage(reaction)
    var backupReaction = reaction
    backupReaction.reactionTargetMessageID = target.id
    backupReaction.reactionSenderName = "Nate"

    let suiteName = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { UserDefaults().removePersistentDomain(forName: suiteName) }
    var preferences = BackupUserDefaults()
    preferences.hasCompletedOnboarding = true
    preferences.selectedThemeID = "marine"
    preferences.restore(to: defaults)
    let envelope = AppBackupEnvelope.test(
      devices: [.testDevice(id: radioID, radioID: radioID)],
      channels: [channel], messages: [target, backupReaction], userDefaults: preferences
    )
    let service = AppBackupService()

    let firstImportDefaults = try #require(UserDefaults(suiteName: suiteName))
    let firstResult = try await service.importBackup(
      envelope: envelope, into: destination, defaults: firstImportDefaults
    )

    #expect(firstResult.totalInserted == 0)
    #expect(firstResult.messagesSkipped == 2)
    #expect(firstResult.counts[.messages]?.merged == 1)
    #expect(firstResult.totalMerged == 1)
    #expect(firstResult.userDefaultsRestored == false)
    #expect(firstResult.hasRestoredChanges)
    let restored = try #require(try await destination.fetchMessage(id: reaction.id))
    #expect(restored.reactionTargetMessageID == target.id)
    #expect(restored.reactionSenderName == "Nate")

    let secondImportDefaults = try #require(UserDefaults(suiteName: suiteName))
    let secondResult = try await service.importBackup(
      envelope: envelope, into: destination, defaults: secondImportDefaults
    )

    #expect(secondResult.totalInserted == 0)
    #expect(secondResult.messagesSkipped == 2)
    #expect(secondResult.counts[.messages]?.merged == 0)
    #expect(secondResult.totalMerged == 0)
    #expect(secondResult.userDefaultsRestored == false)
    #expect(secondResult.hasRestoredChanges == false)
    let unchanged = try #require(try await destination.fetchMessage(id: reaction.id))
    #expect(unchanged.reactionTargetMessageID == target.id)
    #expect(unchanged.reactionSenderName == "Nate")
  }
}
