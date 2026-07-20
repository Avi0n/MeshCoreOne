import Foundation
import MC1Services

/// Conversation keying for reaction indexing: channels match by
/// (channel index, sender name) and persist `channelIndex`; DMs match by
/// contact and persist `contactID`.
enum ReactionIndexScope {
  case channel(ChannelDTO, localNodeName: String?)
  case direct(ContactDTO)
}
