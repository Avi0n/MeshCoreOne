import Foundation
import SwiftData

public extension PersistenceStore {
  func fetchChannelForInfo(id: UUID) throws -> ChannelDTO? {
    guard let channel = try fetchChannel(id: id) else { return nil }
    guard channel.textRulesID == nil,
          try !isRadioErasePending(channel.radioID),
          try fetchDevice(radioID: channel.radioID) != nil else { return channel }
    try backfillTextRulesIdentities(radioID: channel.radioID)
    return try fetchChannel(id: id)
  }

  func textRulesConversations(kind: TextRulesPreference.Kind? = nil) throws -> [TextRulesPreference.Conversation] {
    var namesByRadio: [UUID: String] = [:]
    for device in try fetchDevices() where namesByRadio[device.radioID] == nil {
      guard try !isRadioErasePending(device.radioID) else { continue }
      namesByRadio[device.radioID] = device.nodeName
      try backfillTextRulesIdentities(radioID: device.radioID)
    }

    var conversations: [TextRulesPreference.Conversation] = []
    for (radioID, radioName) in namesByRadio {
      if kind != .directMessage && kind != .room {
        for channel in try fetchChannels(radioID: radioID) {
          let channelKind = TextRulesPreference.channelKind(isPublic: channel.isPublicChannel, name: channel.name)
          guard kind == nil || channelKind == kind, let localID = channel.textRulesID else { continue }
          conversations.append(.init(
            key: .init(radioID: radioID, conversationID: localID),
            kind: channelKind, name: channel.name, radioName: radioName, override: channel.textRulesOverride
          ))
        }
      }
      if kind == nil || kind == .directMessage {
        for contact in try fetchContacts(radioID: radioID) where contact.type == .chat {
          guard let localID = contact.textRulesID else { continue }
          conversations.append(.init(
            key: .init(radioID: radioID, conversationID: localID),
            kind: .directMessage, name: contact.displayName, radioName: radioName, override: contact.textRulesOverride
          ))
        }
      }
      if kind == nil || kind == .room {
        for room in try fetchRemoteNodeSessions(radioID: radioID) where room.isRoom {
          guard let localID = room.textRulesID else { continue }
          conversations.append(.init(
            key: .init(radioID: radioID, conversationID: localID),
            kind: .room, name: room.name, radioName: radioName, override: room.textRulesOverride
          ))
        }
      }
    }
    return conversations.sorted {
      if $0.name != $1.name { return $0.name.localizedStandardCompare($1.name) == .orderedAscending }
      return $0.radioName.localizedStandardCompare($1.radioName) == .orderedAscending
    }
  }

  func textRulesConversation(
    kind: TextRulesPreference.Kind,
    key: TextRulesPreference.Key
  ) throws -> TextRulesPreference.Conversation? {
    let radioID = key.radioID
    let conversationID = key.conversationID
    guard try !isRadioErasePending(radioID), let device = try fetchDevice(radioID: radioID) else { return nil }
    let name: String
    let choice: TextRulesPreference.Override
    switch kind {
    case .directMessage:
      let predicate = #Predicate<Contact> { $0.radioID == radioID && $0.textRulesID == conversationID }
      guard let contact = try modelContext.fetch(FetchDescriptor(predicate: predicate)).first else { return nil }
      name = contact.displayName
      choice = contact.textRulesOverride
    case .room:
      let predicate = #Predicate<RemoteNodeSession> { $0.radioID == radioID && $0.textRulesID == conversationID }
      guard let room = try modelContext.fetch(FetchDescriptor(predicate: predicate)).first else { return nil }
      name = room.name
      choice = room.textRulesOverride
    case .publicChannel, .hashtagChannel, .privateChannel:
      let predicate = #Predicate<Channel> { $0.radioID == radioID && $0.textRulesID == conversationID }
      guard let channel = try modelContext.fetch(FetchDescriptor(predicate: predicate)).first else { return nil }
      name = channel.name
      choice = channel.textRulesOverride
    }
    return .init(key: key, kind: kind, name: name, radioName: device.nodeName, override: choice)
  }

  func setTextRulesOverride(
    _ choice: TextRulesPreference.Override,
    kind: TextRulesPreference.Kind,
    key: TextRulesPreference.Key
  ) throws {
    let radioID = key.radioID
    let conversationID = key.conversationID
    guard try !isRadioErasePending(radioID), try fetchDevice(radioID: radioID) != nil else {
      throw PersistenceStoreError.deviceNotFound
    }
    try commitPendingRxLogEntries()
    do {
      switch kind {
      case .directMessage:
        let predicate = #Predicate<Contact> { $0.radioID == radioID && $0.textRulesID == conversationID }
        guard let contact = try modelContext.fetch(FetchDescriptor(predicate: predicate)).first else {
          throw PersistenceStoreError.contactNotFound
        }
        contact.textRulesOverrideRawValue = choice.rawValue
      case .room:
        let predicate = #Predicate<RemoteNodeSession> { $0.radioID == radioID && $0.textRulesID == conversationID }
        guard let room = try modelContext.fetch(FetchDescriptor(predicate: predicate)).first else {
          throw PersistenceStoreError.remoteNodeSessionNotFound
        }
        room.textRulesOverrideRawValue = choice.rawValue
      case .publicChannel, .hashtagChannel, .privateChannel:
        let predicate = #Predicate<Channel> { $0.radioID == radioID && $0.textRulesID == conversationID }
        guard let channel = try modelContext.fetch(FetchDescriptor(predicate: predicate)).first else {
          throw PersistenceStoreError.channelNotFound
        }
        channel.textRulesOverrideRawValue = choice.rawValue
      }
      try modelContext.save()
    } catch {
      modelContext.rollback()
      throw error
    }
  }
}

extension PersistenceStore {
  func backfillTextRulesIdentities(radioID: UUID) throws {
    let channels = try modelContext.fetch(FetchDescriptor<Channel>(
      predicate: #Predicate { $0.radioID == radioID && $0.textRulesID == nil }
    ))
    let contacts = try modelContext.fetch(FetchDescriptor<Contact>(
      predicate: #Predicate { $0.radioID == radioID && $0.textRulesID == nil }
    ))
    let sessions = try modelContext.fetch(FetchDescriptor<RemoteNodeSession>(
      predicate: #Predicate { $0.radioID == radioID && $0.textRulesID == nil }
    ))
    guard !channels.isEmpty || !contacts.isEmpty || !sessions.isEmpty else { return }
    try commitPendingRxLogEntries()
    do {
      for channel in channels {
        channel.textRulesID = UUID()
      }
      for contact in contacts {
        contact.textRulesID = UUID()
      }
      for session in sessions {
        session.textRulesID = UUID()
      }
      try modelContext.save()
    } catch {
      modelContext.rollback()
      throw error
    }
  }

  func retireTextRulesIdentities(radioID: UUID) throws {
    for channel in try modelContext.fetch(FetchDescriptor<Channel>(predicate: #Predicate { $0.radioID == radioID })) {
      channel.textRulesID = nil
      channel.textRulesOverrideRawValue = TextRulesPreference.Override.inherit.rawValue
    }
    for contact in try modelContext.fetch(FetchDescriptor<Contact>(predicate: #Predicate { $0.radioID == radioID })) {
      contact.textRulesID = nil
      contact.textRulesOverrideRawValue = TextRulesPreference.Override.inherit.rawValue
    }
    for room in try modelContext.fetch(FetchDescriptor<RemoteNodeSession>(predicate: #Predicate { $0.radioID == radioID })) {
      room.textRulesID = nil
      room.textRulesOverrideRawValue = TextRulesPreference.Override.inherit.rawValue
    }
  }
}
