import Foundation
@testable import MC1Services

extension ImportResult {
  var devicesInserted: Int {
    counts[.devices]?.inserted ?? 0
  }

  var devicesSkipped: Int {
    counts[.devices]?.skipped ?? 0
  }

  var contactsInserted: Int {
    counts[.contacts]?.inserted ?? 0
  }

  var contactsSkipped: Int {
    counts[.contacts]?.skipped ?? 0
  }

  var contactsMerged: Int {
    counts[.contacts]?.merged ?? 0
  }

  var channelsInserted: Int {
    counts[.channels]?.inserted ?? 0
  }

  var channelsSkipped: Int {
    counts[.channels]?.skipped ?? 0
  }

  var channelsMerged: Int {
    counts[.channels]?.merged ?? 0
  }

  var channelsDropped: Int {
    counts[.channels]?.dropped ?? 0
  }

  var messagesInserted: Int {
    counts[.messages]?.inserted ?? 0
  }

  var messagesSkipped: Int {
    counts[.messages]?.skipped ?? 0
  }

  var messageRepeatsInserted: Int {
    counts[.messageRepeats]?.inserted ?? 0
  }

  var messageRepeatsSkipped: Int {
    counts[.messageRepeats]?.skipped ?? 0
  }

  var reactionsInserted: Int {
    counts[.reactions]?.inserted ?? 0
  }

  var reactionsSkipped: Int {
    counts[.reactions]?.skipped ?? 0
  }

  var roomMessagesInserted: Int {
    counts[.roomMessages]?.inserted ?? 0
  }

  var roomMessagesSkipped: Int {
    counts[.roomMessages]?.skipped ?? 0
  }

  var remoteNodeSessionsInserted: Int {
    counts[.remoteNodeSessions]?.inserted ?? 0
  }

  var remoteNodeSessionsSkipped: Int {
    counts[.remoteNodeSessions]?.skipped ?? 0
  }

  var remoteNodeSessionsMerged: Int {
    counts[.remoteNodeSessions]?.merged ?? 0
  }

  var savedTracePathsInserted: Int {
    counts[.savedTracePaths]?.inserted ?? 0
  }

  var savedTracePathsSkipped: Int {
    counts[.savedTracePaths]?.skipped ?? 0
  }

  var savedTracePathsMerged: Int {
    counts[.savedTracePaths]?.merged ?? 0
  }

  var blockedChannelSendersInserted: Int {
    counts[.blockedChannelSenders]?.inserted ?? 0
  }

  var blockedChannelSendersSkipped: Int {
    counts[.blockedChannelSenders]?.skipped ?? 0
  }

  var nodeStatusSnapshotsInserted: Int {
    counts[.nodeStatusSnapshots]?.inserted ?? 0
  }

  var nodeStatusSnapshotsSkipped: Int {
    counts[.nodeStatusSnapshots]?.skipped ?? 0
  }

  var discoveredNodesInserted: Int {
    counts[.discoveredNodes]?.inserted ?? 0
  }

  var discoveredNodesSkipped: Int {
    counts[.discoveredNodes]?.skipped ?? 0
  }

  var discoveredNodesDropped: Int {
    counts[.discoveredNodes]?.dropped ?? 0
  }
}
