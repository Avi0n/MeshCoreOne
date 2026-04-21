// swiftlint:disable all
// Generated using SwiftGen — https://github.com/SwiftGen/SwiftGen

import Foundation

// swiftlint:disable superfluous_disable_command file_length implicit_return prefer_self_in_static_references

// MARK: - Strings

// swiftlint:disable explicit_type_interface function_parameter_count identifier_name line_length
// swiftlint:disable nesting type_body_length type_name vertical_whitespace_opening_braces
public enum L10n {
  public enum Chats {
    public enum Chats {
      /// Location: ChatsView.swift - Navigation title for main chat list
      public static let title = L10n.tr("Chats", "chats.title", fallback: "Chats")
      public enum Accessibility {
        /// Location: ChatsView.swift - VoiceOver announcement when viewing cached data offline
        public static let offlineAnnouncement = L10n.tr("Chats", "chats.accessibility.offlineAnnouncement", fallback: "Viewing cached data. Connect to device for updates.")
      }
      public enum Alert {
        public enum LeaveRoom {
          /// Location: ChatsView.swift - Button to confirm leaving a room
          public static let confirm = L10n.tr("Chats", "chats.alert.leaveRoom.confirm", fallback: "Leave")
          /// Location: ChatsView.swift - Alert message explaining what leaving a room does
          public static let message = L10n.tr("Chats", "chats.alert.leaveRoom.message", fallback: "This will remove the room from your chat list, delete all room messages, and remove the associated contact.")
          /// Location: ChatsView.swift - Alert title for leaving a room
          public static let title = L10n.tr("Chats", "chats.alert.leaveRoom.title", fallback: "Leave Room")
        }
        public enum UnableToSend {
          /// Location: ChatConversationView.swift - Alert message when message send fails
          public static let message = L10n.tr("Chats", "chats.alert.unableToSend.message", fallback: "Please ensure your device is connected and try again.")
          /// Location: ChatConversationView.swift - Alert title when message send fails
          public static let title = L10n.tr("Chats", "chats.alert.unableToSend.title", fallback: "Unable to Send")
        }
      }
      public enum BlockSender {
        /// Location: BlockSenderSheet.swift - Purpose: Destructive block button
        public static let blockAnyway = L10n.tr("Chats", "chats.blockSender.blockAnyway", fallback: "Block")
        /// Location: BlockSenderSheet.swift - Purpose: Cancel button
        public static let cancel = L10n.tr("Chats", "chats.blockSender.cancel", fallback: "Cancel")
        /// Location: BlockSenderSheet.swift - Purpose: Explanation of name-based blocking limitation
        public static let limitation = L10n.tr("Chats", "chats.blockSender.limitation", fallback: "Channel messages don't include sender identity. This block matches by name only — the sender could change their name to bypass it.")
        /// Location: BlockSenderSheet.swift - Purpose: Section header when matching contacts found
        public static let matchingContacts = L10n.tr("Chats", "chats.blockSender.matchingContacts", fallback: "The following contacts share this name. Select any you also want to block:")
        /// Location: BlockSenderSheet.swift - Purpose: Sheet title with sender name
        public static func title(_ p1: Any) -> String {
          return L10n.tr("Chats", "chats.blockSender.title", String(describing: p1), fallback: "Block \"%@\"")
        }
      }
      public enum Channel {
        /// Location: ChatConversationView.swift - Fallback channel name format - %d is channel index
        public static func defaultName(_ p1: Int) -> String {
          return L10n.tr("Chats", "chats.channel.defaultName", p1, fallback: "Channel %d")
        }
        /// Location: ChatConversationView.swift - Header subtitle for private channels
        public static let typePrivate = L10n.tr("Chats", "chats.channel.typePrivate", fallback: "Private Channel")
        /// Location: ChatConversationView.swift - Header subtitle for public channels
        public static let typePublic = L10n.tr("Chats", "chats.channel.typePublic", fallback: "Public Channel")
        public enum EmptyState {
          /// Location: ChatConversationView.swift - Empty state message
          public static let noMessages = L10n.tr("Chats", "chats.channel.emptyState.noMessages", fallback: "No messages yet")
          /// Location: ChatConversationView.swift - Empty state description for private channel
          public static let privateDescription = L10n.tr("Chats", "chats.channel.emptyState.privateDescription", fallback: "This is a private channel")
          /// Location: ChatConversationView.swift - Empty state description for public channel
          public static let publicDescription = L10n.tr("Chats", "chats.channel.emptyState.publicDescription", fallback: "This is a public broadcast channel")
        }
      }
      public enum ChannelInfo {
        /// Location: ChannelInfoSheet.swift - Clear messages button
        public static let clearMessagesButton = L10n.tr("Chats", "chats.channelInfo.clearMessagesButton", fallback: "Clear Messages")
        /// Location: ChannelInfoSheet.swift - Button to copy secret key
        public static let copy = L10n.tr("Chats", "chats.channelInfo.copy", fallback: "Copy")
        /// Location: ChannelInfoSheet.swift - Delete channel button
        public static let deleteButton = L10n.tr("Chats", "chats.channelInfo.deleteButton", fallback: "Delete Channel")
        /// Location: ChannelInfoSheet.swift - Footer explaining delete action
        public static let deleteFooter = L10n.tr("Chats", "chats.channelInfo.deleteFooter", fallback: "Deleting removes this channel from your device. You can rejoin later if you have the secret key.")
        /// Location: ChannelInfoSheet.swift - Label for last message date
        public static let lastMessage = L10n.tr("Chats", "chats.channelInfo.lastMessage", fallback: "Last Message")
        /// Location: ChannelInfoSheet.swift - Section header for manual sharing
        public static let manualSharing = L10n.tr("Chats", "chats.channelInfo.manualSharing", fallback: "Manual Sharing")
        /// Location: ChannelInfoSheet.swift - Footer explaining manual sharing
        public static let manualSharingFooter = L10n.tr("Chats", "chats.channelInfo.manualSharingFooter", fallback: "Share the channel name and this secret key for others to join manually.")
        /// Location: ChannelInfoSheet.swift - Purpose: Region row label
        public static let region = L10n.tr("Chats", "chats.channelInfo.region", fallback: "Region")
        /// Location: ChannelInfoSheet.swift - QR code instruction text
        public static let scanToJoin = L10n.tr("Chats", "chats.channelInfo.scanToJoin", fallback: "Scan to join this channel")
        /// Location: ChannelInfoSheet.swift - Label for secret key
        public static let secretKey = L10n.tr("Chats", "chats.channelInfo.secretKey", fallback: "Secret Key")
        /// Location: ChannelInfoSheet.swift - Section header for QR sharing
        public static let shareChannel = L10n.tr("Chats", "chats.channelInfo.shareChannel", fallback: "Share Channel")
        /// Location: ChannelInfoSheet.swift - Label for channel slot
        public static let slot = L10n.tr("Chats", "chats.channelInfo.slot", fallback: "Slot")
        /// Location: ChannelInfoSheet.swift - Navigation title
        public static let title = L10n.tr("Chats", "chats.channelInfo.title", fallback: "Channel Info")
        public enum ChannelType {
          /// Location: ChannelInfoSheet.swift - Channel type label for hashtag channel
          public static let hashtag = L10n.tr("Chats", "chats.channelInfo.channelType.hashtag", fallback: "Hashtag Channel")
          /// Location: ChannelInfoSheet.swift - Channel type label for private channel
          public static let `private` = L10n.tr("Chats", "chats.channelInfo.channelType.private", fallback: "Private Channel")
          /// Location: ChannelInfoSheet.swift - Channel type label for public channel
          public static let `public` = L10n.tr("Chats", "chats.channelInfo.channelType.public", fallback: "Public Channel")
        }
        public enum ClearMessagesConfirm {
          /// Location: ChannelInfoSheet.swift - Clear messages confirmation dialog message
          public static let message = L10n.tr("Chats", "chats.channelInfo.clearMessagesConfirm.message", fallback: "All messages in this channel will be permanently deleted. The channel will remain active.")
          /// Location: ChannelInfoSheet.swift - Clear messages confirmation dialog title
          public static let title = L10n.tr("Chats", "chats.channelInfo.clearMessagesConfirm.title", fallback: "Clear Messages?")
        }
        public enum DeleteConfirm {
          /// Location: ChannelInfoSheet.swift - Confirmation dialog message
          public static let message = L10n.tr("Chats", "chats.channelInfo.deleteConfirm.message", fallback: "This will remove the channel from your device and delete all local messages. This action cannot be undone.")
          /// Location: ChannelInfoSheet.swift - Confirmation dialog title
          public static let title = L10n.tr("Chats", "chats.channelInfo.deleteConfirm.title", fallback: "Delete Channel")
        }
        public enum DeleteFailed {
          /// Location: ChatsView.swift - Alert title when channel deletion fails
          public static let title = L10n.tr("Chats", "chats.channelInfo.deleteFailed.title", fallback: "Channel Deletion Failed")
        }
        public enum Region {
          /// Location: RegionManagementView.swift - Purpose: Add manually button
          public static let addManually = L10n.tr("Chats", "chats.channelInfo.region.addManually", fallback: "Add Manually")
          /// Location: AddRegionView.swift - Purpose: Text field placeholder
          public static let addRegionPlaceholder = L10n.tr("Chats", "chats.channelInfo.region.addRegionPlaceholder", fallback: "Region name")
          /// Location: AddRegionView.swift - Purpose: Navigation title
          public static let addRegionTitle = L10n.tr("Chats", "chats.channelInfo.region.addRegionTitle", fallback: "Add Region")
          /// Location: RegionDiscoveryResultsView.swift - Purpose: Add selected regions button
          public static let addSelected = L10n.tr("Chats", "chats.channelInfo.region.addSelected", fallback: "Add")
          /// Location: ChannelInfoSheet.swift - Purpose: Region value when no scope set
          public static let allRegions = L10n.tr("Chats", "chats.channelInfo.region.allRegions", fallback: "All Regions")
          /// Location: ChannelInfoSheet.swift - Purpose: Discover button
          public static let discover = L10n.tr("Chats", "chats.channelInfo.region.discover", fallback: "Discover Nearby Regions")
          /// Location: ChannelInfoSheet.swift - Purpose: Discover button loading state
          public static let discovering = L10n.tr("Chats", "chats.channelInfo.region.discovering", fallback: "Discovering…")
          /// Location: AddRegionView.swift - Purpose: Duplicate error
          public static let duplicate = L10n.tr("Chats", "chats.channelInfo.region.duplicate", fallback: "This region is already in your list.")
          /// Location: ChannelInfoSheet.swift - Purpose: Couldn't load nearby repeater list
          public static let errLoadingRepeaters = L10n.tr("Chats", "chats.channelInfo.region.errLoadingRepeaters", fallback: "Couldn't load nearby repeaters")
          /// Location: ChannelInfoSheet.swift - Purpose: Some queries failed because radio contact list is full
          public static let errRadioContactsFull = L10n.tr("Chats", "chats.channelInfo.region.errRadioContactsFull", fallback: "Radio contact list is full — some repeaters could not be queried")
          /// Location: ChannelInfoSheet.swift - Purpose: Explanation shown when no regions exist
          public static let explanation = L10n.tr("Chats", "chats.channelInfo.region.explanation", fallback: "Limit messages to a geographic area")
          /// Location: AddRegionView.swift - Purpose: Validation error
          public static let invalidName = L10n.tr("Chats", "chats.channelInfo.region.invalidName", fallback: "Region names can only contain letters, numbers, and hyphens.")
          /// Location: RegionManagementView.swift - Purpose: Navigation title
          public static let manage = L10n.tr("Chats", "chats.channelInfo.region.manage", fallback: "Regions")
          /// Location: RegionManagementView.swift - Purpose: Manage regions link
          public static let manageRegions = L10n.tr("Chats", "chats.channelInfo.region.manageRegions", fallback: "Manage Regions")
          /// Location: RegionDiscoveryResultsView.swift - Purpose: No new regions found
          public static let noNewRegions = L10n.tr("Chats", "chats.channelInfo.region.noNewRegions", fallback: "No new regions found")
          /// Location: RegionManagementView.swift - Purpose: Empty state title
          public static let noRegions = L10n.tr("Chats", "chats.channelInfo.region.noRegions", fallback: "No regions added")
          /// Location: RegionManagementView.swift - Purpose: Empty state description
          public static let noRegionsDescription = L10n.tr("Chats", "chats.channelInfo.region.noRegionsDescription", fallback: "Discover regions from nearby repeaters or add them manually.")
          /// Location: RegionDiscoveryResultsView.swift - Purpose: No repeaters responded
          public static let noRepeatersResponded = L10n.tr("Chats", "chats.channelInfo.region.noRepeatersResponded", fallback: "No repeaters responded")
          /// Location: ChannelInfoSheet.swift - Purpose: Region value when no regions configured
          public static let notConfigured = L10n.tr("Chats", "chats.channelInfo.region.notConfigured", fallback: "Not configured")
          /// Location: ChannelInfoSheet.swift - Purpose: Private region label
          public static let `private` = L10n.tr("Chats", "chats.channelInfo.region.private", fallback: "Private")
          /// Location: ChatConversationType.swift - Purpose: Accessibility label for scoped subtitle
          public static func scopedAccessibility(_ p1: Any, _ p2: Any) -> String {
            return L10n.tr("Chats", "chats.channelInfo.region.scopedAccessibility", String(describing: p1), String(describing: p2), fallback: "%@, scoped to %@")
          }
        }
      }
      public enum ChannelOptions {
        /// Location: ChannelOptionsSheet.swift - Loading indicator text
        public static let loading = L10n.tr("Chats", "chats.channelOptions.loading", fallback: "Loading channels...")
        /// Location: ChannelOptionsSheet.swift - Navigation title
        public static let title = L10n.tr("Chats", "chats.channelOptions.title", fallback: "New Channel")
        public enum CreatePrivate {
          /// Location: ChannelOptionsSheet.swift - Create private channel option description
          public static let description = L10n.tr("Chats", "chats.channelOptions.createPrivate.description", fallback: "Generate a secret key and QR code to share")
          /// Location: ChannelOptionsSheet.swift - Create private channel option title
          public static let title = L10n.tr("Chats", "chats.channelOptions.createPrivate.title", fallback: "Create a Private Channel")
        }
        public enum Footer {
          /// Location: ChannelOptionsSheet.swift - Footer when public channel already exists
          public static let hasPublic = L10n.tr("Chats", "chats.channelOptions.footer.hasPublic", fallback: "The public channel is already configured on slot 0.")
          /// Location: ChannelOptionsSheet.swift - Footer when all slots are in use
          public static let noSlots = L10n.tr("Chats", "chats.channelOptions.footer.noSlots", fallback: "All channel slots are in use. Delete an existing channel to add a new one.")
        }
        public enum JoinHashtag {
          /// Location: ChannelOptionsSheet.swift - Join hashtag channel option description
          public static let description = L10n.tr("Chats", "chats.channelOptions.joinHashtag.description", fallback: "Public channel anyone can join by name")
          /// Location: ChannelOptionsSheet.swift - Join hashtag channel option title
          public static let title = L10n.tr("Chats", "chats.channelOptions.joinHashtag.title", fallback: "Join a Hashtag Channel")
        }
        public enum JoinPrivate {
          /// Location: ChannelOptionsSheet.swift - Join private channel option description
          public static let description = L10n.tr("Chats", "chats.channelOptions.joinPrivate.description", fallback: "Enter channel name and secret key")
          /// Location: ChannelOptionsSheet.swift - Join private channel option title
          public static let title = L10n.tr("Chats", "chats.channelOptions.joinPrivate.title", fallback: "Join a Private Channel")
        }
        public enum JoinPublic {
          /// Location: ChannelOptionsSheet.swift - Join public channel option description
          public static let description = L10n.tr("Chats", "chats.channelOptions.joinPublic.description", fallback: "The default public channel")
          /// Location: ChannelOptionsSheet.swift - Join public channel option title
          public static let title = L10n.tr("Chats", "chats.channelOptions.joinPublic.title", fallback: "Join the Public Channel")
        }
        public enum ScanQR {
          /// Location: ChannelOptionsSheet.swift - Scan QR code option description
          public static let description = L10n.tr("Chats", "chats.channelOptions.scanQR.description", fallback: "Join a channel by scanning its QR code")
          /// Location: ChannelOptionsSheet.swift - Scan QR code option title
          public static let title = L10n.tr("Chats", "chats.channelOptions.scanQR.title", fallback: "Scan a QR Code")
        }
        public enum Section {
          /// Location: ChannelOptionsSheet.swift - Section header for private channels
          public static let `private` = L10n.tr("Chats", "chats.channelOptions.section.private", fallback: "Private Channels")
          /// Location: ChannelOptionsSheet.swift - Section header for public channels
          public static let `public` = L10n.tr("Chats", "chats.channelOptions.section.public", fallback: "Public Channels")
        }
      }
      public enum Common {
        /// Location: Various - Cancel button (use L10n.Localizable.Common.cancel)
        public static let cancel = L10n.tr("Chats", "chats.common.cancel", fallback: "Cancel")
        /// Location: Various - Done button (use L10n.Localizable.Common.done)
        public static let done = L10n.tr("Chats", "chats.common.done", fallback: "Done")
        /// Location: Various - OK button (use L10n.Localizable.Common.ok)
        public static let ok = L10n.tr("Chats", "chats.common.ok", fallback: "OK")
      }
      public enum Compose {
        /// Location: ChatsView.swift - Button to create or join a channel
        public static let newChannel = L10n.tr("Chats", "chats.compose.newChannel", fallback: "New Channel")
        /// Location: ChatsView.swift - Button to start a new direct chat
        public static let newChat = L10n.tr("Chats", "chats.compose.newChat", fallback: "New Chat")
        /// Location: ChatsView.swift - Menu label for new message options
        public static let newMessage = L10n.tr("Chats", "chats.compose.newMessage", fallback: "New Message")
      }
      public enum ConnectionStatus {
        /// Location: ChatConversationView.swift - Connection status format for direct path - %d is hop count
        public static func direct(_ p1: Int) -> String {
          return L10n.tr("Chats", "chats.connectionStatus.direct", p1, fallback: "Direct • %d hops")
        }
        /// Location: ChatConversationView.swift - Connection status for flood routed contacts
        public static let floodRouting = L10n.tr("Chats", "chats.connectionStatus.floodRouting", fallback: "Flood routing")
      }
      public enum ContactInfo {
        /// Location: ChatConversationView.swift - Label showing contact has location
        public static let hasLocation = L10n.tr("Chats", "chats.contactInfo.hasLocation", fallback: "Has location")
      }
      public enum ContactMatch {
        /// Location: ContactMatchRow.swift - Purpose: Public key label
        public static func key(_ p1: Any) -> String {
          return L10n.tr("Chats", "chats.contactMatch.key", String(describing: p1), fallback: "Key: %@")
        }
        public enum Accessibility {
          /// Location: ContactMatchRow.swift - Purpose: Accessibility value when contact is not selected
          public static let notSelected = L10n.tr("Chats", "chats.contactMatch.accessibility.notSelected", fallback: "Not selected")
          /// Location: ContactMatchRow.swift - Purpose: Accessibility value when contact is selected
          public static let selected = L10n.tr("Chats", "chats.contactMatch.accessibility.selected", fallback: "Selected")
        }
      }
      public enum CreatePrivate {
        /// Location: CreatePrivateChannelView.swift - Text field placeholder for channel name
        public static let channelName = L10n.tr("Chats", "chats.createPrivate.channelName", fallback: "Channel Name")
        /// Location: CreatePrivateChannelView.swift - Button to create channel
        public static let createButton = L10n.tr("Chats", "chats.createPrivate.createButton", fallback: "Create Channel")
        /// Location: CreatePrivateChannelView.swift - Footer explaining generated secret
        public static let secretFooter = L10n.tr("Chats", "chats.createPrivate.secretFooter", fallback: "A random secret key has been generated. You'll be able to share it via QR code after creating the channel.")
        /// Location: CreatePrivateChannelView.swift - Footer explaining manual sharing
        public static let shareManuallyFooter = L10n.tr("Chats", "chats.createPrivate.shareManuallyFooter", fallback: "Share the channel name and this secret key with others. They'll need both to join.")
        /// Location: CreatePrivateChannelView.swift - Title when creating channel
        public static let titleCreate = L10n.tr("Chats", "chats.createPrivate.titleCreate", fallback: "Create Private Channel")
        /// Location: CreatePrivateChannelView.swift - Title when sharing created channel
        public static let titleShare = L10n.tr("Chats", "chats.createPrivate.titleShare", fallback: "Share Private Channel")
        public enum Section {
          /// Location: CreatePrivateChannelView.swift - Section header for channel details
          public static let details = L10n.tr("Chats", "chats.createPrivate.section.details", fallback: "Channel Details")
          /// Location: CreatePrivateChannelView.swift - Section header for generated secret
          public static let secret = L10n.tr("Chats", "chats.createPrivate.section.secret", fallback: "Generated Secret")
          /// Location: CreatePrivateChannelView.swift - Section header for manual sharing
          public static let shareManually = L10n.tr("Chats", "chats.createPrivate.section.shareManually", fallback: "Share Manually")
        }
      }
      public enum Divider {
        /// Location: NewMessagesDividerView.swift - Label for new messages divider
        public static let newMessages = L10n.tr("Chats", "chats.divider.newMessages", fallback: "New Messages")
        /// Location: NewMessagesDividerView.swift - VoiceOver label for new messages divider
        public static let newMessagesAccessibility = L10n.tr("Chats", "chats.divider.newMessagesAccessibility", fallback: "New messages divider")
      }
      public enum EmptyState {
        /// Location: ChatsView.swift - Split view placeholder when no conversation selected
        public static let selectConversation = L10n.tr("Chats", "chats.emptyState.selectConversation", fallback: "Select a conversation")
        /// Location: ChatConversationView.swift - Empty state text prompting user to start chatting
        public static let startConversation = L10n.tr("Chats", "chats.emptyState.startConversation", fallback: "Start a conversation")
        public enum NoChannels {
          /// Location: ChatsView.swift - Description when no channels
          public static let description = L10n.tr("Chats", "chats.emptyState.noChannels.description", fallback: "Join or create a channel")
          /// Location: ChatsView.swift - Title when no channels
          public static let title = L10n.tr("Chats", "chats.emptyState.noChannels.title", fallback: "No Channels")
        }
        public enum NoConversations {
          /// Location: ChatsView.swift - Description when no conversations exist
          public static let description = L10n.tr("Chats", "chats.emptyState.noConversations.description", fallback: "Start a conversation from Contacts")
          /// Location: ChatsView.swift - Title when no conversations exist
          public static let title = L10n.tr("Chats", "chats.emptyState.noConversations.title", fallback: "No Conversations")
        }
        public enum NoDirectMessages {
          /// Location: ChatsView.swift - Description when no direct messages
          public static let description = L10n.tr("Chats", "chats.emptyState.noDirectMessages.description", fallback: "Start a chat from Contacts")
          /// Location: ChatsView.swift - Title when no direct messages
          public static let title = L10n.tr("Chats", "chats.emptyState.noDirectMessages.title", fallback: "No Direct Messages")
        }
        public enum NoUnread {
          /// Location: ChatsView.swift - Description when no unread messages
          public static let description = L10n.tr("Chats", "chats.emptyState.noUnread.description", fallback: "You're all caught up")
          /// Location: ChatsView.swift - Title when no unread messages
          public static let title = L10n.tr("Chats", "chats.emptyState.noUnread.title", fallback: "No Unread Messages")
        }
      }
      public enum Error {
        /// Location: ChannelInfoSheet.swift - Error when device not connected
        public static let noDeviceConnected = L10n.tr("Chats", "chats.error.noDeviceConnected", fallback: "No device connected")
        /// Location: ChannelInfoSheet.swift - Error when services unavailable
        public static let servicesUnavailable = L10n.tr("Chats", "chats.error.servicesUnavailable", fallback: "Services not available")
      }
      public enum Errors {
        /// Location: ChatConversationView.swift - Error when loading older messages fails
        public static let loadOlderMessagesFailed = L10n.tr("Chats", "chats.errors.loadOlderMessagesFailed", fallback: "Failed to load older messages")
      }
      public enum Filter {
        /// Location: ChatsView.swift - Filter option for all conversations
        public static let all = L10n.tr("Chats", "chats.filter.all", fallback: "All")
        /// Location: ChatsView.swift - Filter option for channels
        public static let channels = L10n.tr("Chats", "chats.filter.channels", fallback: "Channels")
        /// Location: ChatsView.swift - Button to clear active filter
        public static let clear = L10n.tr("Chats", "chats.filter.clear", fallback: "Clear Filter")
        /// Location: ChatsView.swift - Filter option for direct messages
        public static let directMessages = L10n.tr("Chats", "chats.filter.directMessages", fallback: "DMs")
        /// Location: ChatsView.swift - Filter menu title
        public static let title = L10n.tr("Chats", "chats.filter.title", fallback: "Filter")
        /// Location: ChatsView.swift - Filter option for unread conversations
        public static let unread = L10n.tr("Chats", "chats.filter.unread", fallback: "Unread")
      }
      public enum ImageViewer {
        /// Location: FullScreenImageViewer.swift - Close button label
        public static let close = L10n.tr("Chats", "chats.imageViewer.close", fallback: "Close")
        /// Location: FullScreenImageViewer.swift - Share button label
        public static let share = L10n.tr("Chats", "chats.imageViewer.share", fallback: "Image")
      }
      public enum InlineImage {
        /// Location: InlineImageView.swift - Accessibility label for animated image
        public static let animatedAccessibility = L10n.tr("Chats", "chats.inlineImage.animatedAccessibility", fallback: "Animated image")
        /// Location: InlineImageView.swift - Accessibility label for static image
        public static let imageAccessibility = L10n.tr("Chats", "chats.inlineImage.imageAccessibility", fallback: "Image")
        /// Location: UnifiedMessageBubble.swift - Accessibility hint for retry button
        public static let retryHint = L10n.tr("Chats", "chats.inlineImage.retryHint", fallback: "Double tap to retry loading image")
        /// Location: InlineImageView.swift - Accessibility hint for tap to view full screen
        public static let tapHint = L10n.tr("Chats", "chats.inlineImage.tapHint", fallback: "Double tap to view full screen")
        /// Location: UnifiedMessageBubble.swift - Tap to retry loading failed image
        public static let tapToRetry = L10n.tr("Chats", "chats.inlineImage.tapToRetry", fallback: "Tap to retry")
      }
      public enum Input {
        /// Location: ChatInputBar.swift - Accessibility hint for text input
        public static let accessibilityHint = L10n.tr("Chats", "chats.input.accessibilityHint", fallback: "Type your message here")
        /// Location: ChatInputBar.swift - Accessibility label for text input
        public static let accessibilityLabel = L10n.tr("Chats", "chats.input.accessibilityLabel", fallback: "Message input")
        /// Location: ChatInputBar.swift - Accessibility label for character count - %d is current, %d is max
        public static func characterCount(_ p1: Int, _ p2: Int) -> String {
          return L10n.tr("Chats", "chats.input.characterCount", p1, p2, fallback: "%d of %d characters")
        }
        /// Location: ChatInputBar.swift - Accessibility label for encrypted indicator
        public static let encrypted = L10n.tr("Chats", "chats.input.encrypted", fallback: "Encrypted")
        /// Location: ChatInputBar.swift - Accessibility label for not encrypted indicator
        public static let notEncrypted = L10n.tr("Chats", "chats.input.notEncrypted", fallback: "Not encrypted")
        /// Location: ChatInputBar.swift - Accessibility hint when over character limit - %d is characters to remove
        public static func removeCharacters(_ p1: Int) -> String {
          return L10n.tr("Chats", "chats.input.removeCharacters", p1, fallback: "Remove %d characters to send")
        }
        /// Location: ChatInputBar.swift - Accessibility hint when not connected
        public static let requiresConnection = L10n.tr("Chats", "chats.input.requiresConnection", fallback: "Requires radio connection")
        /// Location: ChatInputBar.swift - Accessibility label for send button
        public static let sendMessage = L10n.tr("Chats", "chats.input.sendMessage", fallback: "Send message")
        /// Location: ChatInputBar.swift - Accessibility hint when ready to send
        public static let tapToSend = L10n.tr("Chats", "chats.input.tapToSend", fallback: "Tap to send your message")
        /// Location: ChatInputBar.swift - Accessibility label when message too long
        public static let tooLong = L10n.tr("Chats", "chats.input.tooLong", fallback: "Message too long")
        /// Location: ChatInputBar.swift - Accessibility hint when message is empty
        public static let typeFirst = L10n.tr("Chats", "chats.input.typeFirst", fallback: "Type a message first")
        public enum Placeholder {
          /// Location: ChatConversationView.swift - Input bar placeholder for direct messages
          public static let directMessage = L10n.tr("Chats", "chats.input.placeholder.directMessage", fallback: "Direct Message")
        }
      }
      public enum JoinFromMessage {
        /// Location: JoinHashtagFromMessageView.swift - Description of hashtag channels
        public static let description = L10n.tr("Chats", "chats.joinFromMessage.description", fallback: "Hashtag channels are public. Anyone can join by entering the same name.")
        /// Location: JoinHashtagFromMessageView.swift - Button to join channel - %@ is channel name
        public static func joinButton(_ p1: Any) -> String {
          return L10n.tr("Chats", "chats.joinFromMessage.joinButton", String(describing: p1), fallback: "Join %@")
        }
        /// Location: JoinHashtagFromMessageView.swift - Loading text
        public static let loading = L10n.tr("Chats", "chats.joinFromMessage.loading", fallback: "Loading...")
        /// Location: JoinHashtagFromMessageView.swift - Navigation title
        public static let title = L10n.tr("Chats", "chats.joinFromMessage.title", fallback: "Join Channel")
        public enum Error {
          /// Location: JoinHashtagFromMessageView.swift - Error for invalid channel name
          public static let invalidName = L10n.tr("Chats", "chats.joinFromMessage.error.invalidName", fallback: "Invalid channel name format.")
          /// Location: JoinHashtagFromMessageView.swift - Error when channel created but couldn't be loaded
          public static let loadFailed = L10n.tr("Chats", "chats.joinFromMessage.error.loadFailed", fallback: "Channel created but could not be loaded.")
          /// Location: JoinHashtagFromMessageView.swift - Error for no available slots
          public static let noSlots = L10n.tr("Chats", "chats.joinFromMessage.error.noSlots", fallback: "No available slots.")
        }
        public enum NoDevice {
          /// Location: JoinHashtagFromMessageView.swift - No device connected description - %@ is channel name
          public static func description(_ p1: Any) -> String {
            return L10n.tr("Chats", "chats.joinFromMessage.noDevice.description", String(describing: p1), fallback: "Connect a device to join %@.")
          }
          /// Location: JoinHashtagFromMessageView.swift - No device connected title
          public static let title = L10n.tr("Chats", "chats.joinFromMessage.noDevice.title", fallback: "No Device Connected")
        }
        public enum NoSlots {
          /// Location: JoinHashtagFromMessageView.swift - No slots available description - %@ is channel name
          public static func description(_ p1: Any) -> String {
            return L10n.tr("Chats", "chats.joinFromMessage.noSlots.description", String(describing: p1), fallback: "All channel slots are full. Remove an existing channel to join %@.")
          }
          /// Location: JoinHashtagFromMessageView.swift - No slots available title
          public static let title = L10n.tr("Chats", "chats.joinFromMessage.noSlots.title", fallback: "No Slots Available")
        }
      }
      public enum JoinHashtag {
        /// Location: JoinHashtagChannelView.swift - Footer label when channel already joined
        public static let alreadyJoined = L10n.tr("Chats", "chats.joinHashtag.alreadyJoined", fallback: "Already joined")
        /// Location: JoinHashtagChannelView.swift - Accessibility label for already joined
        public static let alreadyJoinedAccessibility = L10n.tr("Chats", "chats.joinHashtag.alreadyJoinedAccessibility", fallback: "Channel already joined")
        /// Location: JoinHashtagChannelView.swift - Description about encryption
        public static let encryptionDescription = L10n.tr("Chats", "chats.joinHashtag.encryptionDescription", fallback: "The channel name is used to generate the encryption key. Anyone with the same name can read messages.")
        /// Location: JoinHashtagChannelView.swift - Accessibility hint for existing channel
        public static let existingHint = L10n.tr("Chats", "chats.joinHashtag.existingHint", fallback: "Opens the channel you've already joined")
        /// Location: JoinHashtagChannelView.swift - Footer explaining hashtag channels
        public static let footer = L10n.tr("Chats", "chats.joinHashtag.footer", fallback: "Hashtag channels are public. Anyone can join by entering the same name. Only lowercase letters, numbers, and hyphens are allowed.")
        /// Location: JoinHashtagChannelView.swift - Button format for existing channel - %@ is channel name
        public static func goToButton(_ p1: Any) -> String {
          return L10n.tr("Chats", "chats.joinHashtag.goToButton", String(describing: p1), fallback: "Go to #%@")
        }
        /// Location: JoinHashtagChannelView.swift - Button format for new channel - %@ is channel name
        public static func joinButton(_ p1: Any) -> String {
          return L10n.tr("Chats", "chats.joinHashtag.joinButton", String(describing: p1), fallback: "Join #%@")
        }
        /// Location: JoinHashtagChannelView.swift - Accessibility hint for new channel
        public static let newHint = L10n.tr("Chats", "chats.joinHashtag.newHint", fallback: "Creates and joins this hashtag channel")
        /// Location: JoinHashtagChannelView.swift - Text field placeholder
        public static let placeholder = L10n.tr("Chats", "chats.joinHashtag.placeholder", fallback: "channel-name")
        /// Location: JoinHashtagChannelView.swift - Navigation title
        public static let title = L10n.tr("Chats", "chats.joinHashtag.title", fallback: "Join Hashtag Channel")
        public enum Section {
          /// Location: JoinHashtagChannelView.swift - Section header
          public static let header = L10n.tr("Chats", "chats.joinHashtag.section.header", fallback: "Hashtag Channel")
        }
      }
      public enum JoinPrivate {
        /// Location: JoinPrivateChannelView.swift - Footer explaining how to join
        public static let footer = L10n.tr("Chats", "chats.joinPrivate.footer", fallback: "Enter the channel name and secret key shared by the channel creator.")
        /// Location: JoinPrivateChannelView.swift - Button to join channel
        public static let joinButton = L10n.tr("Chats", "chats.joinPrivate.joinButton", fallback: "Join Channel")
        /// Location: JoinPrivateChannelView.swift - Text field placeholder for secret key
        public static let secretKeyPlaceholder = L10n.tr("Chats", "chats.joinPrivate.secretKeyPlaceholder", fallback: "Secret Key (32 hex characters)")
        /// Location: JoinPrivateChannelView.swift - Navigation title
        public static let title = L10n.tr("Chats", "chats.joinPrivate.title", fallback: "Join Private Channel")
        public enum Error {
          /// Location: JoinPrivateChannelView.swift - Error for invalid secret key format
          public static let invalidFormat = L10n.tr("Chats", "chats.joinPrivate.error.invalidFormat", fallback: "Invalid secret key format")
          /// Location: JoinPrivateChannelView.swift - Validation error for invalid secret
          public static let invalidSecret = L10n.tr("Chats", "chats.joinPrivate.error.invalidSecret", fallback: "Secret key must be exactly 32 hexadecimal characters (0-9, A-F)")
        }
      }
      public enum JoinPublic {
        /// Location: JoinPublicChannelView.swift - Button to add public channel
        public static let addButton = L10n.tr("Chats", "chats.joinPublic.addButton", fallback: "Add Public Channel")
        /// Location: JoinPublicChannelView.swift - Channel name displayed
        public static let channelName = L10n.tr("Chats", "chats.joinPublic.channelName", fallback: "Public Channel")
        /// Location: JoinPublicChannelView.swift - Description of public channel
        public static let description = L10n.tr("Chats", "chats.joinPublic.description", fallback: "The public channel is an open broadcast channel on slot 0. All devices on the mesh network can send and receive messages on this channel.")
        /// Location: JoinPublicChannelView.swift - Navigation title
        public static let title = L10n.tr("Chats", "chats.joinPublic.title", fallback: "Join Public Channel")
      }
      public enum LinkPreview {
        public enum Accessibility {
          /// Location: LinkPreviewCard.swift - Accessibility hint for link preview
          public static let hint = L10n.tr("Chats", "chats.linkPreview.accessibility.hint", fallback: "Opens in browser")
          /// Location: LinkPreviewCard.swift - Accessibility label for link preview - %1$@ is title, %2$@ is domain
          public static func label(_ p1: Any, _ p2: Any) -> String {
            return L10n.tr("Chats", "chats.linkPreview.accessibility.label", String(describing: p1), String(describing: p2), fallback: "%1$@, from %2$@, link")
          }
        }
      }
      public enum MalwareWarning {
        /// Location: MalwareWarningCard.swift - Accessibility label - %@ is domain
        public static func accessibility(_ p1: Any) -> String {
          return L10n.tr("Chats", "chats.malwareWarning.accessibility", String(describing: p1), fallback: "Warning: link to %@ flagged as suspicious")
        }
        /// Location: MalwareWarningCard.swift - Warning title for flagged domains
        public static let title = L10n.tr("Chats", "chats.malwareWarning.title", fallback: "Link flagged as suspicious")
      }
      public enum Mention {
        public enum Accessibility {
          /// Location: MentionSuggestionRow.swift - Accessibility hint for channel sender mention
          public static let hintChannel = L10n.tr("Chats", "chats.mention.accessibility.hintChannel", fallback: "Channel sender. Double tap to mention")
          /// Location: MentionSuggestionRow.swift - Accessibility hint for saved contact mention
          public static let hintContact = L10n.tr("Chats", "chats.mention.accessibility.hintContact", fallback: "Saved contact. Double tap to mention")
          /// Location: MentionSuggestionRow.swift - Accessibility label for mention row - %@ is contact name
          public static func label(_ p1: Any) -> String {
            return L10n.tr("Chats", "chats.mention.accessibility.label", String(describing: p1), fallback: "Mention %@")
          }
        }
      }
      public enum Message {
        /// Location: ChatConversationView.swift - Placeholder when message data is unavailable
        public static let unavailable = L10n.tr("Chats", "chats.message.unavailable", fallback: "Message unavailable")
        /// Location: ChatConversationView.swift - Accessibility label for unavailable message
        public static let unavailableAccessibility = L10n.tr("Chats", "chats.message.unavailableAccessibility", fallback: "Message could not be loaded")
        public enum Action {
          /// Location: MessageActionsSheet.swift - Purpose: Block sender action
          public static let blockSender = L10n.tr("Chats", "chats.message.action.blockSender", fallback: "Block Sender")
          /// Location: UnifiedMessageBubble.swift - Context menu action to copy
          public static let copy = L10n.tr("Chats", "chats.message.action.copy", fallback: "Copy")
          /// Location: UnifiedMessageBubble.swift - Context menu action to delete
          public static let delete = L10n.tr("Chats", "chats.message.action.delete", fallback: "Delete")
          /// Location: UnifiedMessageBubble.swift - Context menu submenu label
          public static let details = L10n.tr("Chats", "chats.message.action.details", fallback: "Details")
          /// Location: MessageActionsSheet.swift - Context menu action to mention
          public static let mention = L10n.tr("Chats", "chats.message.action.mention", fallback: "Mention")
          /// Location: UnifiedMessageBubble.swift - Context menu action to view repeat details
          public static let repeatDetails = L10n.tr("Chats", "chats.message.action.repeatDetails", fallback: "Repeat Details")
          /// Location: UnifiedMessageBubble.swift - Context menu action to reply
          public static let reply = L10n.tr("Chats", "chats.message.action.reply", fallback: "Reply")
          /// Location: UnifiedMessageBubble.swift - Context menu action to send again
          public static let sendAgain = L10n.tr("Chats", "chats.message.action.sendAgain", fallback: "Send Again")
          /// Location: MessageActionsSheet.swift - Purpose: Action to start a DM with the channel sender
          public static let sendDM = L10n.tr("Chats", "chats.message.action.sendDM", fallback: "Send DM")
          /// Location: UnifiedMessageBubble.swift - Context menu action to view path
          public static let viewPath = L10n.tr("Chats", "chats.message.action.viewPath", fallback: "View Path")
        }
        public enum HopCount {
          /// Location: UnifiedMessageBubble.swift - Accessibility label for hop count display - %d is count
          public static func accessibilityLabel(_ p1: Int) -> String {
            return L10n.tr("Chats", "chats.message.hopCount.accessibilityLabel", p1, fallback: "Hop count: %d")
          }
        }
        public enum Hops {
          /// Location: UnifiedMessageBubble.swift - Hop count direct
          public static let direct = L10n.tr("Chats", "chats.message.hops.direct", fallback: "Direct")
        }
        public enum Info {
          /// Location: UnifiedMessageBubble.swift - Indicator that timestamp was adjusted
          public static let adjusted = L10n.tr("Chats", "chats.message.info.adjusted", fallback: "(adjusted)")
          /// Location: UnifiedMessageBubble.swift - Accessibility label for adjusted timestamp
          public static let adjustedAccessibility = L10n.tr("Chats", "chats.message.info.adjustedAccessibility", fallback: "Sent time adjusted due to sender clock error")
          /// Location: UnifiedMessageBubble.swift - Accessibility hint for adjusted timestamp
          public static let adjustedHint = L10n.tr("Chats", "chats.message.info.adjustedHint", fallback: "Sender's clock was incorrect")
          /// Location: UnifiedMessageBubble.swift - Context menu text showing heard repeats - %d is count, second %@ is "repeat" or "repeats"
          public static func heardRepeats(_ p1: Int, _ p2: Any) -> String {
            return L10n.tr("Chats", "chats.message.info.heardRepeats", p1, String(describing: p2), fallback: "Heard: %d %@")
          }
          /// Location: UnifiedMessageBubble.swift - Context menu text showing hop count - %@ is count or "Direct"
          public static func hops(_ p1: Any) -> String {
            return L10n.tr("Chats", "chats.message.info.hops", String(describing: p1), fallback: "Hops: %@")
          }
          /// Location: UnifiedMessageBubble.swift - Context menu text showing received time - %@ is formatted date
          public static func received(_ p1: Any) -> String {
            return L10n.tr("Chats", "chats.message.info.received", String(describing: p1), fallback: "Received: %@")
          }
          /// Location: UnifiedMessageBubble.swift - Context menu text showing round trip time - %d is milliseconds
          public static func roundTrip(_ p1: Int) -> String {
            return L10n.tr("Chats", "chats.message.info.roundTrip", p1, fallback: "Round trip: %dms")
          }
          /// Location: UnifiedMessageBubble.swift - Context menu text showing sent time - %@ is formatted date
          public static func sent(_ p1: Any) -> String {
            return L10n.tr("Chats", "chats.message.info.sent", String(describing: p1), fallback: "Sent: %@")
          }
          /// Location: UnifiedMessageBubble.swift - Context menu text showing SNR - %@ is formatted value
          public static func snr(_ p1: Any) -> String {
            return L10n.tr("Chats", "chats.message.info.snr", String(describing: p1), fallback: "SNR: %@")
          }
        }
        public enum Path {
          /// Location: UnifiedMessageBubble.swift - Accessibility label for routing path - %@ is the path
          public static func accessibilityLabel(_ p1: Any) -> String {
            return L10n.tr("Chats", "chats.message.path.accessibilityLabel", String(describing: p1), fallback: "Routing path: %@")
          }
          /// Location: UnifiedMessageBubble.swift - Path footer for direct messages (no hops)
          public static let direct = L10n.tr("Chats", "chats.message.path.direct", fallback: "Direct")
          /// Flood
          public static let flood = L10n.tr("Chats", "chats.message.path.flood", fallback: "Flood")
          /// Location: UnifiedMessageBubble.swift - Fallback path showing hop count - %d is number
          public static func hops(_ p1: Int) -> String {
            return L10n.tr("Chats", "chats.message.path.hops", p1, fallback: "%d hops")
          }
          /// Location: MessagePathFormatter.swift - Fallback when path nodes unavailable
          public static let unavailable = L10n.tr("Chats", "chats.message.path.unavailable", fallback: "Unavailable")
        }
        public enum Repeat {
          /// Location: UnifiedMessageBubble.swift - Plural form of repeats
          public static let plural = L10n.tr("Chats", "chats.message.repeat.plural", fallback: "repeats")
          /// Location: UnifiedMessageBubble.swift - Singular form of repeat
          public static let singular = L10n.tr("Chats", "chats.message.repeat.singular", fallback: "repeat")
        }
        public enum Sender {
          /// Location: UnifiedMessageBubble.swift - Fallback sender name
          public static let unknown = L10n.tr("Chats", "chats.message.sender.unknown", fallback: "Unknown")
        }
        public enum Status {
          /// Location: UnifiedMessageBubble.swift - Message status delivered
          public static let delivered = L10n.tr("Chats", "chats.message.status.delivered", fallback: "Delivered")
          /// Location: UnifiedMessageBubble.swift - Message status failed
          public static let failed = L10n.tr("Chats", "chats.message.status.failed", fallback: "Failed")
          /// Location: UnifiedMessageBubble.swift - Status row retry button
          public static let retry = L10n.tr("Chats", "chats.message.status.retry", fallback: "Retry")
          /// Location: UnifiedMessageBubble.swift - Message status retrying
          public static let retrying = L10n.tr("Chats", "chats.message.status.retrying", fallback: "Retrying...")
          /// Location: UnifiedMessageBubble.swift - Message status retrying with attempt count - %d is current attempt, %d is max attempts
          public static func retryingAttempt(_ p1: Int, _ p2: Int) -> String {
            return L10n.tr("Chats", "chats.message.status.retryingAttempt", p1, p2, fallback: "Retrying %d/%d")
          }
          /// Location: UnifiedMessageBubble.swift - Message status sending
          public static let sending = L10n.tr("Chats", "chats.message.status.sending", fallback: "Sending...")
          /// Location: UnifiedMessageBubble.swift - Message status sent
          public static let sent = L10n.tr("Chats", "chats.message.status.sent", fallback: "Sent")
          /// Location: UnifiedMessageBubble.swift - Message status sent multiple times - %d is count
          public static func sentMultiple(_ p1: Int) -> String {
            return L10n.tr("Chats", "chats.message.status.sentMultiple", p1, fallback: "Sent %d times")
          }
        }
      }
      public enum MessageActions {
        /// Location: MessageActionsSheet.swift - Sheet title
        public static let title = L10n.tr("Chats", "chats.messageActions.title", fallback: "Message")
      }
      public enum NewChat {
        /// Location: NewChatView.swift - Navigation title
        public static let title = L10n.tr("Chats", "chats.newChat.title", fallback: "New Chat")
        public enum ContactType {
          /// Location: NewChatView.swift - Contact type label for direct contacts
          public static let direct = L10n.tr("Chats", "chats.newChat.contactType.direct", fallback: "Direct")
          /// Location: NewChatView.swift - Contact type label for repeaters
          public static let repeater = L10n.tr("Chats", "chats.newChat.contactType.repeater", fallback: "Repeater")
          /// Location: NewChatView.swift - Contact type label for rooms
          public static let room = L10n.tr("Chats", "chats.newChat.contactType.room", fallback: "Room")
        }
        public enum EmptyState {
          /// Location: NewChatView.swift - Empty state description
          public static let description = L10n.tr("Chats", "chats.newChat.emptyState.description", fallback: "Contacts will appear when discovered")
          /// Location: NewChatView.swift - Empty state title
          public static let title = L10n.tr("Chats", "chats.newChat.emptyState.title", fallback: "No Contacts")
        }
        public enum Search {
          /// Location: NewChatView.swift - Search placeholder
          public static let placeholder = L10n.tr("Chats", "chats.newChat.search.placeholder", fallback: "Search contacts")
        }
      }
      public enum NotificationLevel {
        /// Location: NotificationLevelPicker.swift - Accessibility hint for notification level picker
        public static let hint = L10n.tr("Chats", "chats.notificationLevel.hint", fallback: "Choose when to receive notifications")
        /// Location: NotificationLevelPicker.swift - Accessibility label for notification level picker
        public static let label = L10n.tr("Chats", "chats.notificationLevel.label", fallback: "Notification level")
      }
      public enum Path {
        /// Location: MessagePathSheet.swift - Accessibility label for copy button
        public static let copyAccessibility = L10n.tr("Chats", "chats.path.copyAccessibility", fallback: "Copy path to clipboard")
        /// Location: MessagePathSheet.swift - Button to copy path
        public static let copyButton = L10n.tr("Chats", "chats.path.copyButton", fallback: "Copy Path")
        /// Location: MessagePathSheet.swift - Accessibility hint for copy button
        public static let copyHint = L10n.tr("Chats", "chats.path.copyHint", fallback: "Copies node IDs as hexadecimal values")
        /// Location: MessagePathSheet.swift - Navigation title
        public static let title = L10n.tr("Chats", "chats.path.title", fallback: "Message Path")
        public enum Hop {
          /// Location: PathHopRowView.swift - Accessibility value format for non-last hops - %@ is hex ID
          public static func nodeId(_ p1: Any) -> String {
            return L10n.tr("Chats", "chats.path.hop.nodeId", String(describing: p1), fallback: "Node ID: %@")
          }
          /// Location: PathHopRowView.swift - Label for intermediate hops - %d is hop number
          public static func number(_ p1: Int) -> String {
            return L10n.tr("Chats", "chats.path.hop.number", p1, fallback: "Hop %d")
          }
          /// Location: PathHopRowView.swift - Label for sender (first hop)
          public static let sender = L10n.tr("Chats", "chats.path.hop.sender", fallback: "Sender")
          /// Location: PathHopRowView.swift - Accessibility value format for last hop - %@ is quality, %@ is SNR
          public static func signalQuality(_ p1: Any, _ p2: Any) -> String {
            return L10n.tr("Chats", "chats.path.hop.signalQuality", String(describing: p1), String(describing: p2), fallback: "Signal quality: %@, SNR %@ dB")
          }
          /// Location: PathHopRowView.swift - Unknown signal quality
          public static let signalUnknown = L10n.tr("Chats", "chats.path.hop.signalUnknown", fallback: "Unknown")
          /// Location: PathHopRowView.swift - Unknown node name
          public static let unknown = L10n.tr("Chats", "chats.path.hop.unknown", fallback: "<unknown>")
        }
        public enum Receiver {
          /// Location: PathHopRowView.swift - Label for receiver (your device)
          public static let label = L10n.tr("Chats", "chats.path.receiver.label", fallback: "Receiver")
          /// Location: MessagePathSheet.swift - Fallback name when device name unavailable
          public static let you = L10n.tr("Chats", "chats.path.receiver.you", fallback: "You")
        }
        public enum Section {
          /// Location: MessagePathSheet.swift - Section header for path
          public static let header = L10n.tr("Chats", "chats.path.section.header", fallback: "Path")
        }
        public enum Unavailable {
          /// Location: MessagePathSheet.swift - Empty state description
          public static let description = L10n.tr("Chats", "chats.path.unavailable.description", fallback: "Path data is not available for this message")
          /// Location: MessagePathSheet.swift - Empty state title
          public static let title = L10n.tr("Chats", "chats.path.unavailable.title", fallback: "Path Unavailable")
        }
      }
      public enum Preview {
        /// Location: TapToLoadPreview.swift - Loading state text
        public static let loading = L10n.tr("Chats", "chats.preview.loading", fallback: "Loading preview...")
        /// Location: TapToLoadPreview.swift - Loading accessibility label format - %@ is host
        public static func loadingAccessibility(_ p1: Any) -> String {
          return L10n.tr("Chats", "chats.preview.loadingAccessibility", String(describing: p1), fallback: "Loading preview for %@")
        }
        /// Location: TapToLoadPreview.swift - Loading accessibility hint
        public static let loadingHint = L10n.tr("Chats", "chats.preview.loadingHint", fallback: "Please wait")
        /// Location: TapToLoadPreview.swift - Idle accessibility label format - %@ is host
        public static func tapAccessibility(_ p1: Any) -> String {
          return L10n.tr("Chats", "chats.preview.tapAccessibility", String(describing: p1), fallback: "Load preview for %@")
        }
        /// Location: TapToLoadPreview.swift - Idle accessibility hint
        public static let tapHint = L10n.tr("Chats", "chats.preview.tapHint", fallback: "Fetches title and image from the website")
        /// Location: TapToLoadPreview.swift - Idle state text
        public static let tapToLoad = L10n.tr("Chats", "chats.preview.tapToLoad", fallback: "Tap to load preview")
      }
      public enum Repeats {
        /// Location: RepeatDetailsSheet.swift - Navigation title
        public static let title = L10n.tr("Chats", "chats.repeats.title", fallback: "Repeat Details")
        /// Location: RepeatRowView.swift - Unknown repeater name
        public static let unknownRepeater = L10n.tr("Chats", "chats.repeats.unknownRepeater", fallback: "<unknown repeater>")
        public enum EmptyState {
          /// Location: RepeatDetailsSheet.swift - Empty state description
          public static let description = L10n.tr("Chats", "chats.repeats.emptyState.description", fallback: "Repeats will appear here as your message propagates through the mesh")
          /// Location: RepeatDetailsSheet.swift - Empty state title
          public static let title = L10n.tr("Chats", "chats.repeats.emptyState.title", fallback: "No repeats yet")
        }
        public enum Hop {
          /// Location: RepeatRowView.swift - Plural hops label - %d is count
          public static func plural(_ p1: Int) -> String {
            return L10n.tr("Chats", "chats.repeats.hop.plural", p1, fallback: "%d Hops")
          }
          /// Location: RepeatRowView.swift - Singular hop label
          public static let singular = L10n.tr("Chats", "chats.repeats.hop.singular", fallback: "1 Hop")
        }
        public enum Row {
          /// Location: RepeatRowView.swift - Accessibility label format - %@ is repeater name
          public static func accessibility(_ p1: Any) -> String {
            return L10n.tr("Chats", "chats.repeats.row.accessibility", String(describing: p1), fallback: "Repeat from %@")
          }
          /// Location: RepeatRowView.swift - Accessibility value format - %@ is quality, %@ is SNR, %@ is RSSI
          public static func accessibilityValue(_ p1: Any, _ p2: Any, _ p3: Any) -> String {
            return L10n.tr("Chats", "chats.repeats.row.accessibilityValue", String(describing: p1), String(describing: p2), String(describing: p3), fallback: "%@ signal, SNR %@, RSSI %@")
          }
        }
      }
      public enum Room {
        /// Location: RoomConversationRow.swift - Status when room is connected
        public static let connected = L10n.tr("Chats", "chats.room.connected", fallback: "Connected")
        /// Location: RoomConversationRow.swift - Prompt to reconnect to room
        public static let tapToReconnect = L10n.tr("Chats", "chats.room.tapToReconnect", fallback: "Tap to reconnect")
      }
      public enum RoomAuth {
        public enum NotFound {
          /// Location: RoomAuthenticationSheet.swift - Error description when room not found
          public static let description = L10n.tr("Chats", "chats.roomAuth.notFound.description", fallback: "Could not find the room contact")
          /// Location: RoomAuthenticationSheet.swift - Error title when room not found
          public static let title = L10n.tr("Chats", "chats.roomAuth.notFound.title", fallback: "Room Not Found")
        }
      }
      public enum Row {
        /// Location: ConversationRow.swift, ChannelConversationRow.swift, RoomConversationRow.swift - Accessibility label for favorite indicator
        public static let favorite = L10n.tr("Chats", "chats.row.favorite", fallback: "Favorite")
        /// Location: MutedIndicator.swift - Accessibility label for mentions-only indicator
        public static let mentionsOnly = L10n.tr("Chats", "chats.row.mentionsOnly", fallback: "Mentions only")
        /// Location: MutedIndicator.swift - Accessibility label for muted indicator
        public static let muted = L10n.tr("Chats", "chats.row.muted", fallback: "Muted")
        /// Location: ConversationRow.swift, ChannelConversationRow.swift - Default text when no messages exist
        public static let noMessages = L10n.tr("Chats", "chats.row.noMessages", fallback: "No messages yet")
      }
      public enum ScanQR {
        /// Location: ScanChannelQRView.swift - Instruction to point camera
        public static let instruction = L10n.tr("Chats", "chats.scanQR.instruction", fallback: "Point your camera at a channel QR code")
        /// Location: ScanChannelQRView.swift - Button to open settings
        public static let openSettings = L10n.tr("Chats", "chats.scanQR.openSettings", fallback: "Open Settings")
        /// Location: ScanChannelQRView.swift - Button to scan again
        public static let scanAgain = L10n.tr("Chats", "chats.scanQR.scanAgain", fallback: "Scan Again")
        /// Location: ScanChannelQRView.swift - Navigation title
        public static let title = L10n.tr("Chats", "chats.scanQR.title", fallback: "Scan QR Code")
        public enum Error {
          /// Location: ScanChannelQRView.swift - Error for invalid channel data
          public static let invalidData = L10n.tr("Chats", "chats.scanQR.error.invalidData", fallback: "Invalid channel data in QR code")
          /// Location: ScanChannelQRView.swift - Error for invalid QR format
          public static let invalidFormat = L10n.tr("Chats", "chats.scanQR.error.invalidFormat", fallback: "Invalid QR code format")
        }
        public enum NotAvailable {
          /// Location: ScanChannelQRView.swift - Error description when scanner not available
          public static let description = L10n.tr("Chats", "chats.scanQR.notAvailable.description", fallback: "QR scanning is not supported on this device")
          /// Location: ScanChannelQRView.swift - Error when scanner not available
          public static let title = L10n.tr("Chats", "chats.scanQR.notAvailable.title", fallback: "Scanner Not Available")
        }
        public enum PermissionDenied {
          /// Location: ScanChannelQRView.swift - Camera permission denied message
          public static let message = L10n.tr("Chats", "chats.scanQR.permissionDenied.message", fallback: "Please enable camera access in Settings to scan QR codes.")
          /// Location: ScanChannelQRView.swift - Camera permission denied title
          public static let title = L10n.tr("Chats", "chats.scanQR.permissionDenied.title", fallback: "Camera Access Required")
        }
      }
      public enum ScrollButton {
        public enum Badge {
          /// Location: ScrollToMentionButton.swift, ScrollToBottomButton.swift - Badge text for 99+ unread
          public static let overflow = L10n.tr("Chats", "chats.scrollButton.badge.overflow", fallback: "99+")
        }
        public enum ScrollToBottom {
          /// Location: ScrollToBottomButton.swift - Accessibility label for scroll to bottom button
          public static let accessibilityLabel = L10n.tr("Chats", "chats.scrollButton.scrollToBottom.accessibilityLabel", fallback: "Scroll to latest message")
        }
        public enum ScrollToDivider {
          /// Location: ScrollToDividerButton.swift - Accessibility hint for scroll to divider button
          public static let accessibilityHint = L10n.tr("Chats", "chats.scrollButton.scrollToDivider.accessibilityHint", fallback: "Double-tap to scroll to the new messages divider")
          /// Location: ScrollToDividerButton.swift - Accessibility label for scroll to divider button
          public static let accessibilityLabel = L10n.tr("Chats", "chats.scrollButton.scrollToDivider.accessibilityLabel", fallback: "Scroll to new messages")
        }
        public enum ScrollToMention {
          /// Location: ScrollToMentionButton.swift - Accessibility hint for scroll to mention button
          public static let accessibilityHint = L10n.tr("Chats", "chats.scrollButton.scrollToMention.accessibilityHint", fallback: "Double-tap to navigate to the message")
          /// Location: ScrollToMentionButton.swift - Accessibility label for scroll to mention button
          public static let accessibilityLabel = L10n.tr("Chats", "chats.scrollButton.scrollToMention.accessibilityLabel", fallback: "Scroll to your oldest unread mention")
          /// Location: ScrollToMentionButton.swift - Accessibility value for unread mention count - %d is the number of unread mentions
          public static func accessibilityValue(_ p1: Int) -> String {
            return L10n.tr("Chats", "chats.scrollButton.scrollToMention.accessibilityValue", p1, fallback: "%d unread mentions")
          }
        }
      }
      public enum Search {
        /// Location: ChatsView.swift - Search placeholder
        public static let placeholder = L10n.tr("Chats", "chats.search.placeholder", fallback: "Search conversations")
      }
      public enum Section {
        /// Location: ConversationListContent.swift - Section accessibility label for other conversations
        public static let conversations = L10n.tr("Chats", "chats.section.conversations", fallback: "Conversations")
        /// Location: ConversationListContent.swift - Section accessibility label for favorites
        public static let favorites = L10n.tr("Chats", "chats.section.favorites", fallback: "Favorites")
      }
      public enum SendDM {
        /// Location: SendDMSheet.swift - Purpose: Cancel button
        public static let cancel = L10n.tr("Chats", "chats.sendDM.cancel", fallback: "Cancel")
        /// Location: SendDMSheet.swift - Purpose: Name-based matching limitation warning
        public static let limitation = L10n.tr("Chats", "chats.sendDM.limitation", fallback: "Channel messages don't include sender identity. This match is by name only — the contact could be someone else with the same name.")
        /// Location: SendDMSheet.swift - Purpose: Section header listing matching contacts
        public static let matchingContacts = L10n.tr("Chats", "chats.sendDM.matchingContacts", fallback: "Select a contact to message:")
        /// Location: SendDMSheet.swift - Purpose: Empty-state message when no contact matches
        public static func noMatches(_ p1: Any) -> String {
          return L10n.tr("Chats", "chats.sendDM.noMatches", String(describing: p1), fallback: "No contact named \"%@\" was found.")
        }
        /// Location: SendDMSheet.swift - Purpose: Sheet title with sender name
        public static func title(_ p1: Any) -> String {
          return L10n.tr("Chats", "chats.sendDM.title", String(describing: p1), fallback: "Send DM to \"%@\"")
        }
      }
      public enum Signal {
        /// Location: UnifiedMessageBubble.swift - SNR quality excellent
        public static let excellent = L10n.tr("Chats", "chats.signal.excellent", fallback: "Excellent")
        /// Location: UnifiedMessageBubble.swift - SNR quality fair
        public static let fair = L10n.tr("Chats", "chats.signal.fair", fallback: "Fair")
        /// Location: UnifiedMessageBubble.swift - SNR quality good
        public static let good = L10n.tr("Chats", "chats.signal.good", fallback: "Good")
        /// Location: UnifiedMessageBubble.swift - SNR quality poor
        public static let poor = L10n.tr("Chats", "chats.signal.poor", fallback: "Poor")
        /// Location: UnifiedMessageBubble.swift - SNR quality very poor
        public static let veryPoor = L10n.tr("Chats", "chats.signal.veryPoor", fallback: "Very Poor")
      }
      public enum Suggestions {
        /// Location: MentionSuggestionView.swift - Accessibility label for mention suggestions popup
        public static let accessibilityLabel = L10n.tr("Chats", "chats.suggestions.accessibilityLabel", fallback: "Mention suggestions")
      }
      public enum SwipeAction {
        /// Location: ConversationSwipeActionsModifier.swift - Swipe action to delete
        public static let delete = L10n.tr("Chats", "chats.swipeAction.delete", fallback: "Delete")
        /// Location: ConversationSwipeActionsModifier.swift - Swipe action to add to favorites
        public static let favorite = L10n.tr("Chats", "chats.swipeAction.favorite", fallback: "Favorite")
        /// Location: ConversationSwipeActionsModifier.swift - Swipe action to mute
        public static let mute = L10n.tr("Chats", "chats.swipeAction.mute", fallback: "Mute")
        /// Location: ConversationSwipeActionsModifier.swift - Swipe action to remove from favorites
        public static let unfavorite = L10n.tr("Chats", "chats.swipeAction.unfavorite", fallback: "Unfavorite")
        /// Location: ConversationSwipeActionsModifier.swift - Swipe action to unmute
        public static let unmute = L10n.tr("Chats", "chats.swipeAction.unmute", fallback: "Unmute")
      }
      public enum Timestamp {
        /// Location: RelativeTimestampText.swift - Timestamp for messages under 1 minute old
        public static let now = L10n.tr("Chats", "chats.timestamp.now", fallback: "Now")
        /// Location: MessageTimestampView.swift - Prefix for yesterday's date
        public static let yesterday = L10n.tr("Chats", "chats.timestamp.yesterday", fallback: "Yesterday")
      }
      public enum Tip {
        public enum DeviceMenu {
          /// Location: DeviceMenuTip.swift - Purpose: Tip message explaining device menu features
          public static let message = L10n.tr("Chats", "chats.tip.deviceMenu.message", fallback: "Manage your connection, send advertisements, and check battery. Bluetooth devices stay connected even if the app is closed — tap Disconnect when you're ready to turn off the link.")
          /// Location: DeviceMenuTip.swift - Purpose: Tip title introducing device menu
          public static let title = L10n.tr("Chats", "chats.tip.deviceMenu.title", fallback: "Your Device Menu")
        }
      }
    }
    public enum Reactions {
      /// Location: ReactionBadgesView.swift - Accessibility label for reaction badge - %@ is emoji, %d is count
      public static func badge(_ p1: Any, _ p2: Int) -> String {
        return L10n.tr("Chats", "reactions.badge", String(describing: p1), p2, fallback: "%@ %d")
      }
      /// Location: ReactionBadgesView.swift - Accessibility hint for reaction badge
      public static let badgeHint = L10n.tr("Chats", "reactions.badge_hint", fallback: "Double tap to add your reaction, long press for details")
      /// Location: MessageContextOverlay.swift - Copy action label
      public static let copy = L10n.tr("Chats", "reactions.copy", fallback: "Copy")
      /// Location: MessageContextOverlay.swift - Delete action label
      public static let delete = L10n.tr("Chats", "reactions.delete", fallback: "Delete")
      /// Location: ReactionDetailsSheet.swift - Navigation title
      public static let detailsTitle = L10n.tr("Chats", "reactions.details_title", fallback: "Reactions")
      /// Location: ReactionBadgesView.swift - Accessibility label for overflow badge - %d is count
      public static func moreBadge(_ p1: Int) -> String {
        return L10n.tr("Chats", "reactions.more_badge", p1, fallback: "%d more reaction types")
      }
      /// Location: ReactionBadgesView.swift - Accessibility hint for overflow badge
      public static let moreBadgeHint = L10n.tr("Chats", "reactions.more_badge_hint", fallback: "Double tap to see all reactions")
      /// Location: EmojiPickerRow.swift - Label for more emojis button
      public static let moreEmojis = L10n.tr("Chats", "reactions.more_emojis", fallback: "More emojis")
      /// Location: MessageContextOverlay.swift - Reply action label
      public static let reply = L10n.tr("Chats", "reactions.reply", fallback: "Reply")
      /// Location: ChatViewModel.swift - Error message when reaction fails to send
      public static let sendFailed = L10n.tr("Chats", "reactions.send_failed", fallback: "Could not send reaction")
      /// Location: ReactionDetailsSheet.swift - Navigation title
      public static let title = L10n.tr("Chats", "reactions.title", fallback: "Reactions")
      /// Location: ReactionBadgesView.swift - VoiceOver accessibility action to view reaction details
      public static let viewDetails = L10n.tr("Chats", "reactions.view_details", fallback: "View reaction details")
      public enum Emoji {
        /// Location: EmojiPickerSheet.swift - Search placeholder
        public static let searchPlaceholder = L10n.tr("Chats", "reactions.emoji.searchPlaceholder", fallback: "Search emojis")
        public enum Category {
          /// Location: EmojiProvider.swift - Activity category
          public static let activity = L10n.tr("Chats", "reactions.emoji.category.activity", fallback: "Activity")
          /// Location: EmojiProvider.swift - Flags category
          public static let flags = L10n.tr("Chats", "reactions.emoji.category.flags", fallback: "Flags")
          /// Location: EmojiProvider.swift - Foods category
          public static let foods = L10n.tr("Chats", "reactions.emoji.category.foods", fallback: "Food & Drink")
          /// Location: EmojiProvider.swift - Frequently used category
          public static let frequent = L10n.tr("Chats", "reactions.emoji.category.frequent", fallback: "Frequently Used")
          /// Location: EmojiProvider.swift - Nature category
          public static let nature = L10n.tr("Chats", "reactions.emoji.category.nature", fallback: "Animals & Nature")
          /// Location: EmojiProvider.swift - Objects category
          public static let objects = L10n.tr("Chats", "reactions.emoji.category.objects", fallback: "Objects")
          /// Location: EmojiProvider.swift - People category
          public static let people = L10n.tr("Chats", "reactions.emoji.category.people", fallback: "Smileys & People")
          /// Location: EmojiProvider.swift - Places category
          public static let places = L10n.tr("Chats", "reactions.emoji.category.places", fallback: "Travel & Places")
          /// Location: EmojiProvider.swift - Symbols category
          public static let symbols = L10n.tr("Chats", "reactions.emoji.category.symbols", fallback: "Symbols")
        }
      }
      public enum EmptyState {
        /// Location: ReactionDetailsSheet.swift - Empty state description
        public static let description = L10n.tr("Chats", "reactions.emptyState.description", fallback: "No one has reacted to this message yet")
        /// Location: ReactionDetailsSheet.swift - Empty state title
        public static let title = L10n.tr("Chats", "reactions.emptyState.title", fallback: "No Reactions")
      }
    }
  }
  public enum Contacts {
    public enum Contacts {
      public enum Add {
        /// Location: AddContactSheet.swift - Purpose: Add button
        public static let add = L10n.tr("Contacts", "contacts.add.add", fallback: "Add")
        /// Location: AddContactSheet.swift - Purpose: Character count status
        public static func characterCount(_ p1: Int, _ p2: Int) -> String {
          return L10n.tr("Contacts", "contacts.add.characterCount", p1, p2, fallback: "%d/%d characters")
        }
        /// Location: AddContactSheet.swift - Purpose: Contact name placeholder
        public static let contactName = L10n.tr("Contacts", "contacts.add.contactName", fallback: "Contact Name")
        /// Location: AddContactSheet.swift - Purpose: Public key placeholder
        public static func hexPlaceholder(_ p1: Int) -> String {
          return L10n.tr("Contacts", "contacts.add.hexPlaceholder", p1, fallback: "%d hex characters")
        }
        /// Location: AddContactSheet.swift - Purpose: Name section header
        public static let name = L10n.tr("Contacts", "contacts.add.name", fallback: "Name")
        /// Add Node
        public static let nodeTitle = L10n.tr("Contacts", "contacts.add.nodeTitle", fallback: "Add Node")
        /// Location: AddContactSheet.swift - Purpose: Paste URL button label
        public static let pasteURL = L10n.tr("Contacts", "contacts.add.pasteURL", fallback: "Paste Contact URL")
        /// Location: AddContactSheet.swift - Purpose: Paste URL section footer
        public static let pasteURLFooter = L10n.tr("Contacts", "contacts.add.pasteURLFooter", fallback: "Paste a meshcore:// contact link to auto-fill the fields above")
        /// Location: AddContactSheet.swift - Purpose: Public key section header
        public static let publicKey = L10n.tr("Contacts", "contacts.add.publicKey", fallback: "Public Key")
        /// Location: AddContactSheet.swift - Purpose: Public key footer
        public static func publicKeyFooter(_ p1: Int) -> String {
          return L10n.tr("Contacts", "contacts.add.publicKeyFooter", p1, fallback: "Enter the %d-character hexadecimal public key of the contact")
        }
        /// Location: AddContactSheet.swift - Purpose: Scan QR button label
        public static let scanQR = L10n.tr("Contacts", "contacts.add.scanQR", fallback: "Scan QR Code")
        /// Location: AddContactSheet.swift - Purpose: Navigation title
        public static let title = L10n.tr("Contacts", "contacts.add.title", fallback: "Add Contact")
        /// Location: AddContactSheet.swift - Purpose: Type section header
        public static let type = L10n.tr("Contacts", "contacts.add.type", fallback: "Type")
        /// Location: AddContactSheet.swift - Purpose: Valid key status
        public static let valid = L10n.tr("Contacts", "contacts.add.valid", fallback: "Valid")
        public enum Error {
          /// Location: AddContactSheet.swift - Purpose: Invalid public key format error
          public static let invalidFormat = L10n.tr("Contacts", "contacts.add.error.invalidFormat", fallback: "Invalid public key format")
          /// Location: AddContactSheet.swift - Purpose: Invalid public key size error
          public static func invalidSize(_ p1: Int, _ p2: Int) -> String {
            return L10n.tr("Contacts", "contacts.add.error.invalidSize", p1, p2, fallback: "Public key must be %d bytes (%d hex characters)")
          }
          /// Location: AddContactSheet.swift - Purpose: Invalid paste URL error
          public static let invalidURL = L10n.tr("Contacts", "contacts.add.error.invalidURL", fallback: "Clipboard does not contain a valid contact URL")
          /// Location: AddContactSheet.swift, DiscoveryView.swift - Purpose: Node list full error with max count
          public static func nodeListFull(_ p1: Int) -> String {
            return L10n.tr("Contacts", "contacts.add.error.nodeListFull", p1, fallback: "Node list is full (max %d nodes)")
          }
          /// Location: AddContactSheet.swift, DiscoveryView.swift - Purpose: Node list full error without max count
          public static let nodeListFullSimple = L10n.tr("Contacts", "contacts.add.error.nodeListFullSimple", fallback: "Node list is full")
          /// Location: AddContactSheet.swift - Purpose: Not connected error
          public static let notConnected = L10n.tr("Contacts", "contacts.add.error.notConnected", fallback: "Not connected to device")
        }
      }
      public enum Blocked {
        /// Location: BlockedContactsView.swift - Purpose: Loading progress
        public static let loading = L10n.tr("Contacts", "contacts.blocked.loading", fallback: "Loading...")
        /// Location: BlockedContactsView.swift - Purpose: Navigation title
        public static let title = L10n.tr("Contacts", "contacts.blocked.title", fallback: "Blocked Contacts")
        public enum Empty {
          /// Location: BlockedContactsView.swift - Purpose: Empty state description
          public static let description = L10n.tr("Contacts", "contacts.blocked.empty.description", fallback: "Contacts you block will appear here.")
          /// Location: BlockedContactsView.swift - Purpose: Empty state title
          public static let title = L10n.tr("Contacts", "contacts.blocked.empty.title", fallback: "No Blocked Contacts")
        }
      }
      public enum CodeInput {
        public enum Error {
          /// Location: TracePathViewModel.swift - Purpose: Already in path error
          public static func alreadyInPath(_ p1: Any) -> String {
            return L10n.tr("Contacts", "contacts.codeInput.error.alreadyInPath", String(describing: p1), fallback: "%@ already in path")
          }
          /// Location: TracePathViewModel.swift - Purpose: Invalid format error
          public static func invalidFormat(_ p1: Any) -> String {
            return L10n.tr("Contacts", "contacts.codeInput.error.invalidFormat", String(describing: p1), fallback: "Invalid format: %@")
          }
          /// Location: TracePathViewModel.swift - Purpose: Not found error
          public static func notFound(_ p1: Any) -> String {
            return L10n.tr("Contacts", "contacts.codeInput.error.notFound", String(describing: p1), fallback: "%@ not found")
          }
        }
      }
      public enum Common {
        /// Location: Multiple files - Purpose: Generic Cancel button
        public static let cancel = L10n.tr("Contacts", "contacts.common.cancel", fallback: "Cancel")
        /// Location: Multiple files - Purpose: Generic Delete button
        public static let delete = L10n.tr("Contacts", "contacts.common.delete", fallback: "Delete")
        /// Location: Multiple files - Purpose: Generic Done button
        public static let done = L10n.tr("Contacts", "contacts.common.done", fallback: "Done")
        /// Location: Multiple files - Purpose: Generic Edit button
        public static let edit = L10n.tr("Contacts", "contacts.common.edit", fallback: "Edit")
        /// Location: Multiple files - Purpose: Generic Error alert title
        public static let error = L10n.tr("Contacts", "contacts.common.error", fallback: "Error")
        /// Location: Multiple files - Purpose: Fallback error message
        public static let errorOccurred = L10n.tr("Contacts", "contacts.common.errorOccurred", fallback: "An error occurred")
        /// Location: Multiple files - Purpose: Generic OK button
        public static let ok = L10n.tr("Contacts", "contacts.common.ok", fallback: "OK")
        /// Location: Multiple files - Purpose: Generic Save button
        public static let save = L10n.tr("Contacts", "contacts.common.save", fallback: "Save")
      }
      public enum Detail {
        /// Location: ContactDetailView.swift - Purpose: Add to favorites button
        public static let addToFavorites = L10n.tr("Contacts", "contacts.detail.addToFavorites", fallback: "Add to Favorites")
        /// Location: ContactDetailView.swift - Purpose: Block contact button
        public static let blockContact = L10n.tr("Contacts", "contacts.detail.blockContact", fallback: "Block Contact")
        /// Location: ContactDetailView.swift - Purpose: Blocked status indicator
        public static let blocked = L10n.tr("Contacts", "contacts.detail.blocked", fallback: "Blocked")
        /// Location: ContactDetailView.swift - Purpose: Coordinates label
        public static let coordinates = L10n.tr("Contacts", "contacts.detail.coordinates", fallback: "Coordinates")
        /// Location: ContactDetailView.swift - Purpose: Danger zone section header
        public static let dangerZone = L10n.tr("Contacts", "contacts.detail.dangerZone", fallback: "Danger Zone")
        /// Location: ContactDetailView.swift - Purpose: Delete button with type
        public static func deleteType(_ p1: Any) -> String {
          return L10n.tr("Contacts", "contacts.detail.deleteType", String(describing: p1), fallback: "Delete %@")
        }
        /// Location: ContactDetailView.swift - Purpose: Path discovery in progress
        public static let discoveringPath = L10n.tr("Contacts", "contacts.detail.discoveringPath", fallback: "Discovering path...")
        /// Location: ContactDetailView.swift - Purpose: Discover path button
        public static let discoverPath = L10n.tr("Contacts", "contacts.detail.discoverPath", fallback: "Discover Path")
        /// Location: ContactDetailView.swift - Purpose: Edit path button
        public static let editPath = L10n.tr("Contacts", "contacts.detail.editPath", fallback: "Edit Path")
        /// Location: ContactDetailView.swift - Purpose: Favorite status indicator
        public static let favorite = L10n.tr("Contacts", "contacts.detail.favorite", fallback: "Favorite")
        /// Location: ContactDetailView.swift - Purpose: Footer for flood routing
        public static let floodFooter = L10n.tr("Contacts", "contacts.detail.floodFooter", fallback: "Messages are broadcast to all nodes. Discover Path to find an optimal route.")
        /// Location: ContactDetailView.swift - Purpose: Has location status indicator
        public static let hasLocation = L10n.tr("Contacts", "contacts.detail.hasLocation", fallback: "Has Location")
        /// Location: ContactDetailView.swift - Purpose: Hops away label
        public static let hopsAway = L10n.tr("Contacts", "contacts.detail.hopsAway", fallback: "Hops Away")
        /// Location: ContactDetailView.swift - Purpose: Info section header
        public static let info = L10n.tr("Contacts", "contacts.detail.info", fallback: "Info")
        /// Location: ContactDetailView.swift - Purpose: Join room button
        public static let joinRoom = L10n.tr("Contacts", "contacts.detail.joinRoom", fallback: "Join Room")
        /// Location: ContactDetailView.swift - Purpose: Last advert label
        public static let lastAdvert = L10n.tr("Contacts", "contacts.detail.lastAdvert", fallback: "Last Advert")
        /// Location: ContactDetailView.swift - Purpose: Location section header
        public static let location = L10n.tr("Contacts", "contacts.detail.location", fallback: "Location")
        /// Location: ContactDetailView.swift - Purpose: Management button
        public static let management = L10n.tr("Contacts", "contacts.detail.management", fallback: "Management")
        /// Location: ContactDetailView.swift - Purpose: Name label
        public static let name = L10n.tr("Contacts", "contacts.detail.name", fallback: "Name")
        /// Location: ContactDetailView.swift - Purpose: Network path section header
        public static let networkPath = L10n.tr("Contacts", "contacts.detail.networkPath", fallback: "Network Path")
        /// Location: ContactDetailView.swift - Purpose: Nickname label
        public static let nickname = L10n.tr("Contacts", "contacts.detail.nickname", fallback: "Nickname")
        /// Location: ContactDetailView.swift - Purpose: No nickname placeholder
        public static let nicknameNone = L10n.tr("Contacts", "contacts.detail.nicknameNone", fallback: "None")
        /// Location: ContactDetailView.swift - Purpose: Open in Maps button
        public static let openInMaps = L10n.tr("Contacts", "contacts.detail.openInMaps", fallback: "Open in Maps")
        /// Location: ContactDetailView.swift - Purpose: Footer for path routing
        public static let pathFooter = L10n.tr("Contacts", "contacts.detail.pathFooter", fallback: "Messages route through the path shown. Reset Path to use flood routing instead.")
        /// Location: ContactDetailView.swift - Purpose: Generalized ping button for non-repeater nodes
        public static let ping = L10n.tr("Contacts", "contacts.detail.ping", fallback: "Zero-Hop Ping")
        /// Location: ContactDetailView.swift - Purpose: Ping failure VoiceOver announcement
        public static let pingFailureAnnouncement = L10n.tr("Contacts", "contacts.detail.pingFailureAnnouncement", fallback: "Ping failed")
        /// Location: ContactDetailView.swift - Purpose: Ping failure accessibility label
        public static func pingFailureLabel(_ p1: Any) -> String {
          return L10n.tr("Contacts", "contacts.detail.pingFailureLabel", String(describing: p1), fallback: "Ping failed: %@")
        }
        /// Location: ContactDetailView.swift - Purpose: Ping no response message
        public static let pingNoResponse = L10n.tr("Contacts", "contacts.detail.pingNoResponse", fallback: "No response")
        /// Location: ContactDetailView.swift - Purpose: Ping success VoiceOver announcement
        public static func pingSuccessAnnouncement(_ p1: Int) -> String {
          return L10n.tr("Contacts", "contacts.detail.pingSuccessAnnouncement", p1, fallback: "Ping successful, %d milliseconds")
        }
        /// Location: ContactDetailView.swift - Purpose: Ping success accessibility label
        public static func pingSuccessLabel(_ p1: Int, _ p2: Int, _ p3: Int) -> String {
          return L10n.tr("Contacts", "contacts.detail.pingSuccessLabel", p1, p2, p3, fallback: "Ping successful, %1$d milliseconds, %2$d decibels there, %3$d back")
        }
        /// Location: ContactDetailView.swift - Purpose: Public key label
        public static let publicKey = L10n.tr("Contacts", "contacts.detail.publicKey", fallback: "Public Key")
        /// Location: ContactDetailView.swift - Purpose: Remove from favorites button
        public static let removeFromFavorites = L10n.tr("Contacts", "contacts.detail.removeFromFavorites", fallback: "Remove from Favorites")
        /// Location: ContactDetailView.swift - Purpose: Reset path button
        public static let resetPath = L10n.tr("Contacts", "contacts.detail.resetPath", fallback: "Reset Path")
        /// Location: ContactDetailView.swift - Purpose: Route label
        public static let route = L10n.tr("Contacts", "contacts.detail.route", fallback: "Route")
        /// Location: ContactDetailView.swift - Purpose: Accessibility label for direct route
        public static let routeDirect = L10n.tr("Contacts", "contacts.detail.routeDirect", fallback: "Route: Direct")
        /// Location: ContactDetailView.swift - Purpose: Accessibility label for flood route
        public static let routeFlood = L10n.tr("Contacts", "contacts.detail.routeFlood", fallback: "Route: Flood")
        /// Location: ContactDetailView.swift - Purpose: Accessibility label prefix for route
        public static func routePrefix(_ p1: Any) -> String {
          return L10n.tr("Contacts", "contacts.detail.routePrefix", String(describing: p1), fallback: "Route: %@")
        }
        /// Location: ContactDetailView.swift - Purpose: Saved History button for offline telemetry
        public static let savedHistory = L10n.tr("Contacts", "contacts.detail.savedHistory", fallback: "Telemetry History")
        /// Location: ContactDetailView.swift - Purpose: Discovery countdown
        public static func secondsRemaining(_ p1: Int) -> String {
          return L10n.tr("Contacts", "contacts.detail.secondsRemaining", p1, fallback: "Up to %d seconds remaining")
        }
        /// Location: ContactDetailView.swift - Purpose: Send message button
        public static let sendMessage = L10n.tr("Contacts", "contacts.detail.sendMessage", fallback: "Send Message")
        /// Location: ContactDetailView.swift - Purpose: Share contact button
        public static let shareContact = L10n.tr("Contacts", "contacts.detail.shareContact", fallback: "Share Contact")
        /// Location: ContactDetailView.swift - Purpose: Share contact error when advert is missing or stale
        public static let shareContactUnavailable = L10n.tr("Contacts", "contacts.detail.shareContactUnavailable", fallback: "Unable to share node. The node's advertisement may be missing or too old.")
        /// Location: ContactDetailView.swift - Purpose: Share via advert button
        public static let shareViaAdvert = L10n.tr("Contacts", "contacts.detail.shareViaAdvert", fallback: "Share Contact via Advert")
        /// Location: ContactDetailView.swift - Purpose: Technical section header
        public static let technical = L10n.tr("Contacts", "contacts.detail.technical", fallback: "Technical")
        /// Location: ContactDetailView.swift - Purpose: Telemetry button
        public static let telemetry = L10n.tr("Contacts", "contacts.detail.telemetry", fallback: "Telemetry")
        /// Location: ContactDetailView.swift - Purpose: Telemetry access sheet title
        public static let telemetryAccess = L10n.tr("Contacts", "contacts.detail.telemetryAccess", fallback: "Telemetry Access")
        /// Location: ContactDetailView.swift - Purpose: Type label
        public static let type = L10n.tr("Contacts", "contacts.detail.type", fallback: "Type")
        /// Location: ContactDetailView.swift - Purpose: Unblock contact button
        public static let unblockContact = L10n.tr("Contacts", "contacts.detail.unblockContact", fallback: "Unblock Contact")
        /// Location: ContactDetailView.swift - Purpose: Unread messages label
        public static let unreadMessages = L10n.tr("Contacts", "contacts.detail.unreadMessages", fallback: "Unread Messages")
        public enum Alert {
          /// Location: ContactDetailView.swift - Purpose: Path discovery alert title
          public static let pathDiscovery = L10n.tr("Contacts", "contacts.detail.alert.pathDiscovery", fallback: "Path Discovery")
          /// Location: ContactDetailView.swift - Purpose: Path error alert title
          public static let pathError = L10n.tr("Contacts", "contacts.detail.alert.pathError", fallback: "Path Error")
          public enum Block {
            /// Location: ContactDetailView.swift - Purpose: Block contact alert message
            public static func message(_ p1: Any) -> String {
              return L10n.tr("Contacts", "contacts.detail.alert.block.message", String(describing: p1), fallback: "You won't receive messages from %@. Their conversations will be hidden from your Chats list and new channel messages will be discarded. Unblocking will allow new messages, but discarded messages cannot be recovered.")
            }
            /// Location: ContactDetailView.swift - Purpose: Block contact alert title
            public static let title = L10n.tr("Contacts", "contacts.detail.alert.block.title", fallback: "Block Contact")
          }
          public enum Delete {
            /// Location: ContactDetailView.swift - Purpose: Delete contact alert message
            public static func message(_ p1: Any) -> String {
              return L10n.tr("Contacts", "contacts.detail.alert.delete.message", String(describing: p1), fallback: "This will remove %@ and delete all associated data. This action cannot be undone.")
            }
            /// Location: ContactDetailView.swift - Purpose: Delete contact alert title
            public static func title(_ p1: Any) -> String {
              return L10n.tr("Contacts", "contacts.detail.alert.delete.title", String(describing: p1), fallback: "Delete %@")
            }
          }
        }
      }
      public enum Discovery {
        /// Location: DiscoveryView.swift - Purpose: Add button
        public static let add = L10n.tr("Contacts", "contacts.discovery.add", fallback: "Add")
        /// Location: DiscoveryView.swift - Purpose: Button label when node is already added
        public static let added = L10n.tr("Contacts", "contacts.discovery.added", fallback: "Added")
        /// Location: DiscoveryView.swift - Purpose: Accessibility label for added button
        public static let addedAccessibility = L10n.tr("Contacts", "contacts.discovery.addedAccessibility", fallback: "Already added to contacts")
        /// Location: DiscoveryView.swift - Purpose: Clear all menu item
        public static let clear = L10n.tr("Contacts", "contacts.discovery.clear", fallback: "Clear All")
        /// Location: DiscoveryView.swift - Purpose: VoiceOver announcement after clearing
        public static let clearedAllNodes = L10n.tr("Contacts", "contacts.discovery.clearedAllNodes", fallback: "All discovered nodes cleared")
        /// Location: DiscoveryView.swift - Purpose: More menu accessibility label
        public static let menu = L10n.tr("Contacts", "contacts.discovery.menu", fallback: "More")
        /// Location: DiscoveryView.swift - Purpose: Swipe action to remove discovered node
        public static let remove = L10n.tr("Contacts", "contacts.discovery.remove", fallback: "Remove")
        /// Location: DiscoveryView.swift - Purpose: VoiceOver announcement when searching
        public static let searchingAllTypes = L10n.tr("Contacts", "contacts.discovery.searchingAllTypes", fallback: "Searching all types")
        /// Location: DiscoveryView.swift - Purpose: Search prompt
        public static let searchPrompt = L10n.tr("Contacts", "contacts.discovery.searchPrompt", fallback: "Search discovered")
        /// Location: DiscoveryView.swift - Purpose: Sort menu accessibility label
        public static let sortMenu = L10n.tr("Contacts", "contacts.discovery.sortMenu", fallback: "Sort options")
        /// Location: DiscoveryView.swift - Purpose: Sort menu accessibility hint
        public static let sortMenuHint = L10n.tr("Contacts", "contacts.discovery.sortMenuHint", fallback: "Choose how to sort discovered nodes")
        /// Location: DiscoveryView.swift - Purpose: Navigation title
        public static let title = L10n.tr("Contacts", "contacts.discovery.title", fallback: "Discover")
        public enum Clear {
          /// Location: DiscoveryView.swift - Purpose: Clear confirmation button
          public static let confirm = L10n.tr("Contacts", "contacts.discovery.clear.confirm", fallback: "Clear")
          /// Location: DiscoveryView.swift - Purpose: Clear confirmation message
          public static let message = L10n.tr("Contacts", "contacts.discovery.clear.message", fallback: "Discovered nodes will be removed from this list but can be rediscovered on the mesh network.")
          /// Location: DiscoveryView.swift - Purpose: Clear confirmation title
          public static let title = L10n.tr("Contacts", "contacts.discovery.clear.title", fallback: "Clear all discovered nodes?")
        }
        public enum Empty {
          /// Location: DiscoveryView.swift - Purpose: Empty state description
          public static let description = L10n.tr("Contacts", "contacts.discovery.empty.description", fallback: "Nodes will appear here as their advertisements are discovered.")
          /// Location: DiscoveryView.swift - Purpose: Empty state title
          public static let title = L10n.tr("Contacts", "contacts.discovery.empty.title", fallback: "No Discovered Nodes")
          public enum Search {
            /// Location: DiscoveryView.swift - Purpose: Search empty state description
            public static func description(_ p1: Any) -> String {
              return L10n.tr("Contacts", "contacts.discovery.empty.search.description", String(describing: p1), fallback: "No discovered nodes match '%@'")
            }
            /// Location: DiscoveryView.swift - Purpose: Search empty state title
            public static let title = L10n.tr("Contacts", "contacts.discovery.empty.search.title", fallback: "No Results")
          }
        }
        public enum Error {
          /// Location: DiscoveryView.swift - Purpose: Services not available error
          public static let servicesUnavailable = L10n.tr("Contacts", "contacts.discovery.error.servicesUnavailable", fallback: "Services not available")
        }
        public enum Segment {
          /// Location: DiscoveryView.swift - Purpose: Segment filter: All
          public static let all = L10n.tr("Contacts", "contacts.discovery.segment.all", fallback: "All")
          /// Location: DiscoveryView.swift - Purpose: Segment filter: Contacts
          public static let contacts = L10n.tr("Contacts", "contacts.discovery.segment.contacts", fallback: "Contacts")
          /// Location: DiscoveryView.swift - Purpose: Segment filter: Network
          public static let network = L10n.tr("Contacts", "contacts.discovery.segment.network", fallback: "Network")
        }
      }
      public enum List {
        /// Location: ContactsListView.swift - Purpose: Menu item to add contact
        public static let addContact = L10n.tr("Contacts", "contacts.list.addContact", fallback: "Add Contact")
        /// Location: ContactsListView.swift - Purpose: Menu item for blocked contacts
        public static let blockedContacts = L10n.tr("Contacts", "contacts.list.blockedContacts", fallback: "Blocked Contacts")
        /// Location: ContactsListView.swift - Purpose: Refresh alert title
        public static let cannotRefresh = L10n.tr("Contacts", "contacts.list.cannotRefresh", fallback: "Cannot Refresh")
        /// Location: ContactsListView.swift - Purpose: Refresh alert message
        public static let connectToSync = L10n.tr("Contacts", "contacts.list.connectToSync", fallback: "Connect to your device to sync contacts.")
        /// Location: ContactsListView.swift - Purpose: Menu item for discovery
        public static let discover = L10n.tr("Contacts", "contacts.list.discover", fallback: "Discover")
        /// Location: ContactsListView.swift - Purpose: Location alert message
        public static let distanceRequiresLocation = L10n.tr("Contacts", "contacts.list.distanceRequiresLocation", fallback: "Distance sorting requires location access.")
        /// Location: ContactsListView.swift - Purpose: Location alert title
        public static let locationUnavailable = L10n.tr("Contacts", "contacts.list.locationUnavailable", fallback: "Location Unavailable")
        /// Location: ContactsListView.swift - Purpose: VoiceOver offline announcement
        public static let offlineAnnouncement = L10n.tr("Contacts", "contacts.list.offlineAnnouncement", fallback: "Viewing cached contacts. Connect to device for updates.")
        /// Location: ContactsListView.swift - Purpose: Location settings button
        public static let openSettings = L10n.tr("Contacts", "contacts.list.openSettings", fallback: "Open Settings")
        /// Location: ContactsListView.swift - Purpose: Options menu label
        public static let options = L10n.tr("Contacts", "contacts.list.options", fallback: "Options")
        /// Location: ContactsListView.swift - Purpose: Search prompt
        public static let searchPrompt = L10n.tr("Contacts", "contacts.list.searchPrompt", fallback: "Search nodes")
        /// Location: ContactsListView.swift - Purpose: Search prompt with count
        public static func searchPromptWithCount(_ p1: Int) -> String {
          return L10n.tr("Contacts", "contacts.list.searchPromptWithCount", p1, fallback: "Search nodes (%d)")
        }
        /// Location: ContactsListView.swift - Purpose: Empty state for split view
        public static let selectNode = L10n.tr("Contacts", "contacts.list.selectNode", fallback: "Select a node")
        /// Location: ContactsListView.swift - Purpose: Menu item to share own contact
        public static let shareMyContact = L10n.tr("Contacts", "contacts.list.shareMyContact", fallback: "Share My Contact")
        /// Location: ContactsListView.swift - Purpose: Sort menu label
        public static let sort = L10n.tr("Contacts", "contacts.list.sort", fallback: "Sort")
        /// Location: ContactsListView.swift - Purpose: Menu item to sync nodes
        public static let syncNodes = L10n.tr("Contacts", "contacts.list.syncNodes", fallback: "Sync Nodes")
        /// Location: ContactsListView.swift - Purpose: Navigation title
        public static let title = L10n.tr("Contacts", "contacts.list.title", fallback: "Nodes")
        public enum Empty {
          public enum Contacts {
            /// Location: ContactsListView.swift - Purpose: No contacts empty description
            public static let description = L10n.tr("Contacts", "contacts.list.empty.contacts.description", fallback: "Contacts appear when discovered on the mesh network. If auto-add contacts is off, check Discovery in the top right menu.")
            /// Location: ContactsListView.swift - Purpose: No contacts empty title
            public static let title = L10n.tr("Contacts", "contacts.list.empty.contacts.title", fallback: "No Contacts")
          }
          public enum Favorites {
            /// Location: ContactsListView.swift - Purpose: No favorites empty description
            public static let description = L10n.tr("Contacts", "contacts.list.empty.favorites.description", fallback: "Swipe right on any node to add it to your favorites.")
            /// Location: ContactsListView.swift - Purpose: No favorites empty title
            public static let title = L10n.tr("Contacts", "contacts.list.empty.favorites.title", fallback: "No Favorites Yet")
          }
          public enum Network {
            /// Location: ContactsListView.swift - Purpose: No network nodes empty description
            public static let description = L10n.tr("Contacts", "contacts.list.empty.network.description", fallback: "Repeaters and room servers will appear when discovered on the mesh.")
            /// Location: ContactsListView.swift - Purpose: No network nodes empty title
            public static let title = L10n.tr("Contacts", "contacts.list.empty.network.title", fallback: "No Network Nodes")
          }
          public enum Search {
            /// Location: ContactsListView.swift - Purpose: No search results description
            public static func description(_ p1: Any) -> String {
              return L10n.tr("Contacts", "contacts.list.empty.search.description", String(describing: p1), fallback: "No nodes match '%@'")
            }
            /// Location: ContactsListView.swift - Purpose: No search results title
            public static let title = L10n.tr("Contacts", "contacts.list.empty.search.title", fallback: "No Results")
          }
        }
      }
      public enum NodeKind {
        /// Location: Multiple files - Purpose: Chat contact type label
        public static let chat = L10n.tr("Contacts", "contacts.nodeKind.chat", fallback: "Chat")
        /// Location: Multiple files - Purpose: Chat contact full label
        public static let chatContact = L10n.tr("Contacts", "contacts.nodeKind.chatContact", fallback: "Chat Contact")
        /// Location: ContactsListView.swift - Purpose: Contact label in search results
        public static let contact = L10n.tr("Contacts", "contacts.nodeKind.contact", fallback: "Contact")
        /// Location: TracePathListView.swift, PathEditingSheet.swift - Purpose: Badge label for discovered nodes
        public static let discovered = L10n.tr("Contacts", "contacts.nodeKind.discovered", fallback: "Discovered")
        /// Location: Multiple files - Purpose: Repeater contact type label
        public static let repeater = L10n.tr("Contacts", "contacts.nodeKind.repeater", fallback: "Repeater")
        /// Location: Multiple files - Purpose: Room contact type label
        public static let room = L10n.tr("Contacts", "contacts.nodeKind.room", fallback: "Room")
      }
      public enum PathDetail {
        /// Location: SavedPathDetailView.swift - Purpose: Average stat label
        public static let avg = L10n.tr("Contacts", "contacts.pathDetail.avg", fallback: "Avg")
        /// Location: SavedPathDetailView.swift - Purpose: Best stat label
        public static let best = L10n.tr("Contacts", "contacts.pathDetail.best", fallback: "Best")
        /// Location: SavedPathDetailView.swift - Purpose: Date label
        public static let date = L10n.tr("Contacts", "contacts.pathDetail.date", fallback: "Date")
        /// Location: SavedPathDetailView.swift - Purpose: Failed status
        public static let failed = L10n.tr("Contacts", "contacts.pathDetail.failed", fallback: "Failed")
        /// Location: SavedPathDetailView.swift - Purpose: History section header
        public static let history = L10n.tr("Contacts", "contacts.pathDetail.history", fallback: "History")
        /// Location: SavedPathDetailView.swift - Purpose: Hop label
        public static func hop(_ p1: Int) -> String {
          return L10n.tr("Contacts", "contacts.pathDetail.hop", p1, fallback: "Hop %d")
        }
        /// Location: SavedPathDetailView.swift - Purpose: Overview section header
        public static let overview = L10n.tr("Contacts", "contacts.pathDetail.overview", fallback: "Overview")
        /// Location: SavedPathDetailView.swift - Purpose: Path section header
        public static let path = L10n.tr("Contacts", "contacts.pathDetail.path", fallback: "Path")
        /// Location: SavedPathDetailView.swift - Purpose: Performance section header
        public static let performance = L10n.tr("Contacts", "contacts.pathDetail.performance", fallback: "Performance")
        /// Location: SavedPathDetailView.swift - Purpose: Per-hop SNR section header
        public static let perHopSNR = L10n.tr("Contacts", "contacts.pathDetail.perHopSNR", fallback: "Per-Hop SNR")
        /// Location: SavedPathDetailView.swift - Purpose: Round trip label
        public static let roundTrip = L10n.tr("Contacts", "contacts.pathDetail.roundTrip", fallback: "Round Trip")
        /// Location: SavedPathDetailView.swift - Purpose: Chart Y axis label
        public static let roundTripMs = L10n.tr("Contacts", "contacts.pathDetail.roundTripMs", fallback: "Round Trip (ms)")
        /// Location: SavedPathDetailView.swift - Purpose: Run details navigation title
        public static let runDetails = L10n.tr("Contacts", "contacts.pathDetail.runDetails", fallback: "Run Details")
        /// Location: SavedPathDetailView.swift - Purpose: Status label
        public static let status = L10n.tr("Contacts", "contacts.pathDetail.status", fallback: "Status")
        /// Location: SavedPathDetailView.swift - Purpose: Success stat label
        public static let success = L10n.tr("Contacts", "contacts.pathDetail.success", fallback: "Success")
      }
      public enum PathDiscovery {
        /// Location: PathManagementViewModel.swift - Purpose: Cached path suffix
        public static let cachedSuffix = L10n.tr("Contacts", "contacts.pathDiscovery.cachedSuffix", fallback: ". Using cached info from advertisement. Node may have telemetry disabled.")
        /// Location: PathManagementViewModel.swift - Purpose: Direct path result
        public static let direct = L10n.tr("Contacts", "contacts.pathDiscovery.direct", fallback: "Direct")
        /// Location: PathManagementViewModel.swift - Purpose: Failed prefix
        public static func failed(_ p1: Any) -> String {
          return L10n.tr("Contacts", "contacts.pathDiscovery.failed", String(describing: p1), fallback: "Failed: %@")
        }
        /// Location: PathManagementViewModel.swift - Purpose: No response message
        public static let noResponse = L10n.tr("Contacts", "contacts.pathDiscovery.noResponse", fallback: "Remote node did not respond. Nodes must have telemetry requests enabled to respond to path discovery.")
        public enum Hops {
          /// Location: PathManagementViewModel.swift - Purpose: Hop count result plural
          public static func plural(_ p1: Int) -> String {
            return L10n.tr("Contacts", "contacts.pathDiscovery.hops.plural", p1, fallback: "%d hops")
          }
          /// Location: PathManagementViewModel.swift - Purpose: Hop count result singular
          public static let singular = L10n.tr("Contacts", "contacts.pathDiscovery.hops.singular", fallback: "1 hop")
        }
      }
      public enum PathEdit {
        /// Location: PathEditingSheet.swift - Purpose: Add Hop CTA (primary button when path has >=1 hop)
        public static let addHop = L10n.tr("Contacts", "contacts.pathEdit.addHop", fallback: "Add Hop")
        /// Location: AddHopPickerView.swift - Purpose: Picker row accessibility label, %1$@ is name, %2$d is target hop number
        public static func addToPathAsHop(_ p1: Any, _ p2: Int) -> String {
          return L10n.tr("Contacts", "contacts.pathEdit.addToPathAsHop", String(describing: p1), p2, fallback: "Add %1$@ to path as hop %2$d")
        }
        /// Location: PathEditingSheet.swift - Purpose: Current path section header
        public static let currentPath = L10n.tr("Contacts", "contacts.pathEdit.currentPath", fallback: "Current Path")
        /// Location: PathEditingSheet.swift - Purpose: Description with contact name
        public static func description(_ p1: Any) -> String {
          return L10n.tr("Contacts", "contacts.pathEdit.description", String(describing: p1), fallback: "Customize the route messages take to reach %@.")
        }
        /// Location: AddHopSegmentPicker.swift - Purpose: VoiceOver label for the section filter picker
        public static let filterPickerLabel = L10n.tr("Contacts", "contacts.pathEdit.filterPickerLabel", fallback: "Filter hops")
        /// Location: PathEditingSheet.swift - Purpose: VoiceOver hint for a hop row describing swipe + drag actions
        public static let hopHint = L10n.tr("Contacts", "contacts.pathEdit.hopHint", fallback: "Swipe to delete. Drag to reorder.")
        /// Location: PathEditingSheet.swift - Purpose: Hop accessibility with hex
        public static func hopWithHex(_ p1: Int, _ p2: Int, _ p3: Any) -> String {
          return L10n.tr("Contacts", "contacts.pathEdit.hopWithHex", p1, p2, String(describing: p3), fallback: "Hop %d of %d: repeater %@")
        }
        /// Location: PathEditingSheet.swift - Purpose: Hop accessibility with name
        public static func hopWithName(_ p1: Int, _ p2: Int, _ p3: Any) -> String {
          return L10n.tr("Contacts", "contacts.pathEdit.hopWithName", p1, p2, String(describing: p3), fallback: "Hop %d of %d: %@")
        }
        /// Location: AddHopPickerView.swift - Purpose: Position banner when appending, %d is target hop number
        public static func positionAppend(_ p1: Int) -> String {
          return L10n.tr("Contacts", "contacts.pathEdit.positionAppend", p1, fallback: "Adding as hop %d")
        }
        /// Location: PathEditingSheet.swift - Purpose: Section footer hint
        public static let reorderHint = L10n.tr("Contacts", "contacts.pathEdit.reorderHint", fallback: "Drag to reorder · Swipe left to remove")
        /// Location: AddHopPickerView.swift - Purpose: Search field placeholder
        public static let searchPrompt = L10n.tr("Contacts", "contacts.pathEdit.searchPrompt", fallback: "Search by name or hex")
        /// Location: PathEditingSheet.swift - Purpose: Navigation title
        public static let title = L10n.tr("Contacts", "contacts.pathEdit.title", fallback: "Edit Path")
        /// Location: PathEditingSheet.swift - Purpose: Use-direct-routing empty-state button
        public static let useDirectRouting = L10n.tr("Contacts", "contacts.pathEdit.useDirectRouting", fallback: "Use Direct Routing")
        /// Location: PathEditingSheet.swift - Purpose: Use-flood-routing empty-state button
        public static let useFloodRouting = L10n.tr("Contacts", "contacts.pathEdit.useFloodRouting", fallback: "Use Flood Routing")
        public enum DirectRouting {
          public enum Confirm {
            /// Location: PathEditingSheet.swift - Purpose: Direct-routing confirmation confirm label
            public static let confirm = L10n.tr("Contacts", "contacts.pathEdit.directRouting.confirm.confirm", fallback: "Save as Direct")
            /// Location: PathEditingSheet.swift - Purpose: Direct-routing confirmation alert message, %@ is contact name
            public static func message(_ p1: Any, _ p2: Any) -> String {
              return L10n.tr("Contacts", "contacts.pathEdit.directRouting.confirm.message", String(describing: p1), String(describing: p2), fallback: "Messages to %@ will be sent directly without relaying through any repeater. Use this only if %@ is a 1-hop neighbor.")
            }
            /// Location: PathEditingSheet.swift - Purpose: Direct-routing confirmation alert title
            public static let title = L10n.tr("Contacts", "contacts.pathEdit.directRouting.confirm.title", fallback: "Save as direct routing?")
          }
        }
        public enum Empty {
          /// Location: PathEditingSheet.swift - Purpose: Empty-state description, %@ is contact name
          public static func description(_ p1: Any) -> String {
            return L10n.tr("Contacts", "contacts.pathEdit.empty.description", String(describing: p1), fallback: "Add repeaters to control how messages reach %@, or use flood routing if no path is known.")
          }
          /// Location: PathEditingSheet.swift - Purpose: Empty-state title
          public static let title = L10n.tr("Contacts", "contacts.pathEdit.empty.title", fallback: "No hops yet")
        }
        public enum Filter {
          /// Location: AddHopSegmentPicker.swift - Purpose: All filter
          public static let all = L10n.tr("Contacts", "contacts.pathEdit.filter.all", fallback: "All")
          /// Location: AddHopSegmentPicker.swift - Purpose: Discovered filter
          public static let discovered = L10n.tr("Contacts", "contacts.pathEdit.filter.discovered", fallback: "Discovered")
          /// Location: AddHopSegmentPicker.swift - Purpose: Favorites filter
          public static let favorites = L10n.tr("Contacts", "contacts.pathEdit.filter.favorites", fallback: "Favorites")
          /// Location: AddHopSegmentPicker.swift - Purpose: Recent filter
          public static let recent = L10n.tr("Contacts", "contacts.pathEdit.filter.recent", fallback: "Recent")
        }
        public enum FloodRouting {
          public enum Confirm {
            /// Location: PathEditingSheet.swift - Purpose: Flood-routing confirmation confirm label
            public static let confirm = L10n.tr("Contacts", "contacts.pathEdit.floodRouting.confirm.confirm", fallback: "Use Flood Routing")
            /// Location: PathEditingSheet.swift - Purpose: Flood-routing confirmation alert message, %@ is contact name
            public static func message(_ p1: Any) -> String {
              return L10n.tr("Contacts", "contacts.pathEdit.floodRouting.confirm.message", String(describing: p1), fallback: "Messages to %@ will be sent to every nearby node until a path is found. Use this when no repeater route is known.")
            }
            /// Location: PathEditingSheet.swift - Purpose: Flood-routing confirmation alert title
            public static let title = L10n.tr("Contacts", "contacts.pathEdit.floodRouting.confirm.title", fallback: "Use flood routing?")
          }
        }
        public enum MaxHops {
          /// Location: AddHopPickerView.swift - Purpose: Max-hops reached description, %d is the cap
          public static func description(_ p1: Int) -> String {
            return L10n.tr("Contacts", "contacts.pathEdit.maxHops.description", p1, fallback: "This path has reached the maximum of %d hops for the current hash mode. Remove a hop to add another.")
          }
          /// Location: PathEditingSheet.swift - Purpose: Add Hop CTA footer explaining the hop cap, %d is the cap
          public static func footer(_ p1: Int) -> String {
            return L10n.tr("Contacts", "contacts.pathEdit.maxHops.footer", p1, fallback: "Maximum %d hops reached. Remove a hop to add another.")
          }
          /// Location: PathEditingSheet.swift + AddHopPickerView.swift - Purpose: Max-hops reached title
          public static let reached = L10n.tr("Contacts", "contacts.pathEdit.maxHops.reached", fallback: "Max hops reached")
        }
        public enum NoRepeaters {
          /// Location: PathEditingSheet.swift - Purpose: No repeaters empty description
          public static let description = L10n.tr("Contacts", "contacts.pathEdit.noRepeaters.description", fallback: "Repeaters appear here once they're discovered in your mesh network.")
          /// Location: PathEditingSheet.swift - Purpose: No repeaters empty title
          public static let title = L10n.tr("Contacts", "contacts.pathEdit.noRepeaters.title", fallback: "No Repeaters Available")
        }
        public enum Search {
          public enum NoMatches {
            /// Location: AddHopPickerView.swift - Purpose: No-match search description
            public static let description = L10n.tr("Contacts", "contacts.pathEdit.search.noMatches.description", fallback: "Try a name or hex prefix like 'a3'.")
            /// Location: AddHopPickerView.swift - Purpose: No-match description when a filter other than All is selected and a matching room exists
            public static let descriptionWithRoomsHint = L10n.tr("Contacts", "contacts.pathEdit.search.noMatches.descriptionWithRoomsHint", fallback: "Try a name or hex prefix like 'a3'. Rooms only appear under the All filter.")
          }
        }
        public enum Sections {
          /// Location: AddHopPickerView.swift - Purpose: Contacts section header
          public static let contacts = L10n.tr("Contacts", "contacts.pathEdit.sections.contacts", fallback: "Contacts")
          /// Location: AddHopPickerView.swift - Purpose: Discovered section header
          public static let discovered = L10n.tr("Contacts", "contacts.pathEdit.sections.discovered", fallback: "Discovered")
          /// Location: AddHopPickerView.swift - Purpose: Favorites section header
          public static let favorites = L10n.tr("Contacts", "contacts.pathEdit.sections.favorites", fallback: "Favorites")
          /// Location: AddHopPickerView.swift - Purpose: Recent section header
          public static let recent = L10n.tr("Contacts", "contacts.pathEdit.sections.recent", fallback: "Recent")
          /// Location: AddHopPickerView.swift - Purpose: Rooms section header
          public static let rooms = L10n.tr("Contacts", "contacts.pathEdit.sections.rooms", fallback: "Rooms")
        }
      }
      public enum PathManagement {
        public enum Error {
          /// Location: PathManagementViewModel.swift - Purpose: Shown when a stored path's hop can't be resized to the device's current hash mode
          public static let hopResizeRequired = L10n.tr("Contacts", "contacts.pathManagement.error.hopResizeRequired", fallback: "Hash mode changed — remove and re-add hops to save.")
          /// Location: PathManagementViewModel.swift - Purpose: Reset path error prefix
          public static func resetFailed(_ p1: Any) -> String {
            return L10n.tr("Contacts", "contacts.pathManagement.error.resetFailed", String(describing: p1), fallback: "Reset path failed: %@")
          }
          /// Location: PathManagementViewModel.swift - Purpose: Save path error prefix
          public static func saveFailed(_ p1: Any) -> String {
            return L10n.tr("Contacts", "contacts.pathManagement.error.saveFailed", String(describing: p1), fallback: "Save path failed: %@")
          }
          /// Location: PathManagementViewModel.swift - Purpose: Set path error prefix
          public static func setFailed(_ p1: Any) -> String {
            return L10n.tr("Contacts", "contacts.pathManagement.error.setFailed", String(describing: p1), fallback: "Set path failed: %@")
          }
          /// Location: PathManagementViewModel.swift - Purpose: Shown when the existing path has more hops than the current hash mode supports
          public static func tooManyHops(_ p1: Int) -> String {
            return L10n.tr("Contacts", "contacts.pathManagement.error.tooManyHops", p1, fallback: "Too many hops. Current hash mode supports at most %d.")
          }
        }
      }
      public enum PathName {
        /// Location: TracePathViewModel.swift - Purpose: Path name with multiple endpoints (abbreviated)
        public static func multipleEndpoints(_ p1: Any, _ p2: Any) -> String {
          return L10n.tr("Contacts", "contacts.pathName.multipleEndpoints", String(describing: p1), String(describing: p2), fallback: "%@ → ... → %@")
        }
        /// Location: TracePathViewModel.swift - Purpose: Default path name prefix for hash-only paths
        public static func `prefix`(_ p1: Any) -> String {
          return L10n.tr("Contacts", "contacts.pathName.prefix", String(describing: p1), fallback: "Path %@")
        }
        /// Location: TracePathViewModel.swift - Purpose: Path name with two endpoints
        public static func twoEndpoints(_ p1: Any, _ p2: Any) -> String {
          return L10n.tr("Contacts", "contacts.pathName.twoEndpoints", String(describing: p1), String(describing: p2), fallback: "%@ → %@")
        }
      }
      public enum Qr {
        /// Location: ContactQRShareSheet.swift - Purpose: Copy button when copied
        public static let copied = L10n.tr("Contacts", "contacts.qr.copied", fallback: "Copied!")
        /// Location: ContactQRShareSheet.swift - Purpose: Copy button when not copied
        public static let copy = L10n.tr("Contacts", "contacts.qr.copy", fallback: "Copy")
        /// Location: ContactQRShareSheet.swift - Purpose: Share button
        public static let share = L10n.tr("Contacts", "contacts.qr.share", fallback: "Share")
        /// Location: ContactQRShareSheet.swift - Purpose: Share subject
        public static let shareSubject = L10n.tr("Contacts", "contacts.qr.shareSubject", fallback: "MeshCore One Contact")
        /// Location: ContactQRShareSheet.swift - Purpose: Navigation title
        public static let title = L10n.tr("Contacts", "contacts.qr.title", fallback: "Share Contact")
      }
      public enum Results {
        /// Location: TraceResultsSheet.swift - Purpose: Average round trip label
        public static let avgRoundTrip = L10n.tr("Contacts", "contacts.results.avgRoundTrip", fallback: "Avg Round Trip")
        /// Location: TraceResultsSheet.swift - Purpose: Average RTT accessibility
        public static func avgRTTLabel(_ p1: Int, _ p2: Int, _ p3: Int) -> String {
          return L10n.tr("Contacts", "contacts.results.avgRTTLabel", p1, p2, p3, fallback: "Average round trip: %d milliseconds, range %d to %d")
        }
        /// Location: TraceResultsSheet.swift - Purpose: Batch complete accessibility
        public static func batchCompleteLabel(_ p1: Int, _ p2: Int) -> String {
          return L10n.tr("Contacts", "contacts.results.batchCompleteLabel", p1, p2, fallback: "Batch complete: %d of %d traces successful")
        }
        /// Location: TraceResultsSheet.swift - Purpose: Batch progress
        public static func batchProgress(_ p1: Int, _ p2: Int) -> String {
          return L10n.tr("Contacts", "contacts.results.batchProgress", p1, p2, fallback: "Running Trace %d of %d...")
        }
        /// Location: TraceResultsSheet.swift - Purpose: Batch progress accessibility
        public static func batchProgressLabel(_ p1: Int, _ p2: Int) -> String {
          return L10n.tr("Contacts", "contacts.results.batchProgressLabel", p1, p2, fallback: "Batch progress: trace %d of %d")
        }
        /// Location: TraceResultsSheet.swift - Purpose: Batch success count
        public static func batchSuccess(_ p1: Int, _ p2: Int) -> String {
          return L10n.tr("Contacts", "contacts.results.batchSuccess", p1, p2, fallback: "%d of %d successful")
        }
        /// Location: TraceResultsSheet.swift - Purpose: Comparison text
        public static func comparison(_ p1: Int, _ p2: Any) -> String {
          return L10n.tr("Contacts", "contacts.results.comparison", p1, String(describing: p2), fallback: "vs. %d ms on %@")
        }
        /// Location: TraceResultsSheet.swift - Purpose: Dismiss button
        public static let dismiss = L10n.tr("Contacts", "contacts.results.dismiss", fallback: "Dismiss")
        /// Location: TraceResultsSheet.swift - Purpose: Distance error message
        public static let distanceError = L10n.tr("Contacts", "contacts.results.distanceError", fallback: "Distance cannot be calculated. All repeaters have coordinates but an error occurred.")
        /// Location: TraceResultsSheet.swift - Purpose: Distance info button accessibility
        public static let distanceInfo = L10n.tr("Contacts", "contacts.results.distanceInfo", fallback: "Distance info")
        /// Location: TraceResultsSheet.swift - Purpose: Distance info hint
        public static let distanceInfoHint = L10n.tr("Contacts", "contacts.results.distanceInfoHint", fallback: "Double tap for details about missing locations")
        /// Location: TraceResultsSheet.swift - Purpose: Distance info navigation title (unavailable)
        public static let distanceInfoTitle = L10n.tr("Contacts", "contacts.results.distanceInfoTitle", fallback: "Distance Unavailable")
        /// Location: TraceResultsSheet.swift - Purpose: Distance info navigation title (partial)
        public static let distanceInfoTitlePartial = L10n.tr("Contacts", "contacts.results.distanceInfoTitlePartial", fallback: "Distance Info")
        /// Location: TraceResultsSheet.swift - Purpose: Distance unavailable accessibility
        public static let distanceUnavailableLabel = L10n.tr("Contacts", "contacts.results.distanceUnavailableLabel", fallback: "Distance unavailable")
        /// Location: TraceResultsSheet.swift - Purpose: Full path section header
        public static let fullPathHeader = L10n.tr("Contacts", "contacts.results.fullPathHeader", fallback: "To Include Full Path")
        /// Location: TraceResultsSheet.swift - Purpose: Full path tip
        public static let fullPathTip = L10n.tr("Contacts", "contacts.results.fullPathTip", fallback: "Enable location services or set a location for your device to see the full path distance.")
        /// Location: TraceResultsSheet.swift - Purpose: Distance missing locations message
        public static let missingLocations = L10n.tr("Contacts", "contacts.results.missingLocations", fallback: "Distance cannot be calculated because the following repeaters don't have location coordinates set.")
        /// Location: TraceResultsSheet.swift - Purpose: Distance needs repeaters message
        public static let needsRepeaters = L10n.tr("Contacts", "contacts.results.needsRepeaters", fallback: "Distance calculation requires at least 2 repeaters in the path.")
        /// Location: TraceResultsSheet.swift - Purpose: Partial distance explanation
        public static let partialDistanceExplanation = L10n.tr("Contacts", "contacts.results.partialDistanceExplanation", fallback: "Distance shown is between repeaters only. Your device's distance to the first repeater is not included because device location is unavailable.")
        /// Location: TraceResultsSheet.swift - Purpose: Partial distance section header
        public static let partialDistanceHeader = L10n.tr("Contacts", "contacts.results.partialDistanceHeader", fallback: "Partial Distance")
        /// Location: TraceResultsSheet.swift - Purpose: Partial distance accessibility hint
        public static let partialDistanceHint = L10n.tr("Contacts", "contacts.results.partialDistanceHint", fallback: "Double tap to learn why device location is excluded")
        /// Location: TraceResultsSheet.swift - Purpose: Partial distance accessibility label
        public static let partialDistanceLabel = L10n.tr("Contacts", "contacts.results.partialDistanceLabel", fallback: "Partial distance")
        /// Location: TraceResultsSheet.swift - Purpose: Repeaters without locations section
        public static let repeatersWithoutLocations = L10n.tr("Contacts", "contacts.results.repeatersWithoutLocations", fallback: "Repeaters Without Locations")
        /// Location: TraceResultsSheet.swift - Purpose: Save path button
        public static let savePath = L10n.tr("Contacts", "contacts.results.savePath", fallback: "Save Path")
        /// Location: TraceResultsSheet.swift - Purpose: Navigation title
        public static let title = L10n.tr("Contacts", "contacts.results.title", fallback: "Trace Results")
        /// Location: TraceResultsSheet.swift - Purpose: Total distance label
        public static let totalDistance = L10n.tr("Contacts", "contacts.results.totalDistance", fallback: "Total Distance")
        /// Location: TraceResultsSheet.swift - Purpose: Distance unavailable
        public static let unavailable = L10n.tr("Contacts", "contacts.results.unavailable", fallback: "Unavailable")
        /// Location: TraceResultsSheet.swift - Purpose: View runs link
        public static func viewRuns(_ p1: Int) -> String {
          return L10n.tr("Contacts", "contacts.results.viewRuns", p1, fallback: "View %d runs")
        }
        public enum Comparison {
          /// Decreased
          public static let decreased = L10n.tr("Contacts", "contacts.results.comparison.decreased", fallback: "Decreased")
          /// Location: ComparisonRowView.swift - Purpose: Accessibility labels for change direction
          public static let increased = L10n.tr("Contacts", "contacts.results.comparison.increased", fallback: "Increased")
        }
        public enum Hop {
          /// Location: TraceResultsSheet.swift - Purpose: Average SNR display
          public static func avgSNR(_ p1: Any, _ p2: Any, _ p3: Any) -> String {
            return L10n.tr("Contacts", "contacts.results.hop.avgSNR", String(describing: p1), String(describing: p2), String(describing: p3), fallback: "Avg SNR: %@ dB (%@ – %@)")
          }
          /// Location: TraceResultsSheet.swift - Purpose: Average SNR accessibility
          public static func avgSNRLabel(_ p1: Any, _ p2: Any, _ p3: Any) -> String {
            return L10n.tr("Contacts", "contacts.results.hop.avgSNRLabel", String(describing: p1), String(describing: p2), String(describing: p3), fallback: "Average signal to noise ratio: %@ decibels, range %@ to %@")
          }
          /// Location: TraceResultsSheet.swift - Purpose: My Device placeholder
          public static let myDevice = L10n.tr("Contacts", "contacts.results.hop.myDevice", fallback: "My Device")
          /// Location: TraceResultsSheet.swift - Purpose: Received response label
          public static let received = L10n.tr("Contacts", "contacts.results.hop.received", fallback: "Received response")
          /// Location: TraceResultsSheet.swift - Purpose: Repeated label
          public static let repeated = L10n.tr("Contacts", "contacts.results.hop.repeated", fallback: "Repeated")
          /// Location: TraceResultsSheet.swift - Purpose: SNR display
          public static func snr(_ p1: Any) -> String {
            return L10n.tr("Contacts", "contacts.results.hop.snr", String(describing: p1), fallback: "SNR: %@ dB")
          }
          /// Location: TraceResultsSheet.swift - Purpose: Started trace label
          public static let started = L10n.tr("Contacts", "contacts.results.hop.started", fallback: "Started trace")
        }
      }
      public enum Route {
        /// Location: ContactDetailView.swift, ContactRowView.swift - Purpose: Direct routing label
        public static let direct = L10n.tr("Contacts", "contacts.route.direct", fallback: "Direct")
        /// Location: ContactDetailView.swift, ContactRowView.swift - Purpose: Flood routing label
        public static let flood = L10n.tr("Contacts", "contacts.route.flood", fallback: "Flood")
        /// Location: ContactRowView.swift - Purpose: Hops count display
        public static func hops(_ p1: Int) -> String {
          return L10n.tr("Contacts", "contacts.route.hops", p1, fallback: "%d hops")
        }
      }
      public enum Row {
        /// Location: ContactRowView.swift - Purpose: Distance suffix
        public static func away(_ p1: Any) -> String {
          return L10n.tr("Contacts", "contacts.row.away", String(describing: p1), fallback: "%@ away")
        }
        /// Location: ContactRowView.swift - Purpose: Blocked status accessibility label
        public static let blocked = L10n.tr("Contacts", "contacts.row.blocked", fallback: "Blocked")
        /// Location: ContactRowView.swift - Purpose: Favorite status accessibility label
        public static let favorite = L10n.tr("Contacts", "contacts.row.favorite", fallback: "Favorite")
        /// Location: ContactRowView.swift - Purpose: Location indicator accessibility label
        public static let location = L10n.tr("Contacts", "contacts.row.location", fallback: "Location")
      }
      public enum SavedPaths {
        /// Location: SavedPathsSheet.swift - Purpose: Delete dialog message
        public static func deleteMessage(_ p1: Any) -> String {
          return L10n.tr("Contacts", "contacts.savedPaths.deleteMessage", String(describing: p1), fallback: "Delete \"%@\"? This will remove the path and all run history.")
        }
        /// Location: SavedPathsSheet.swift - Purpose: Delete dialog title
        public static let deleteTitle = L10n.tr("Contacts", "contacts.savedPaths.deleteTitle", fallback: "Delete Path")
        /// Location: SavedPathsSheet.swift - Purpose: Health accessibility label
        public static func healthLabel(_ p1: Any, _ p2: Int) -> String {
          return L10n.tr("Contacts", "contacts.savedPaths.healthLabel", String(describing: p1), p2, fallback: "Path health: %@, %d%% success rate")
        }
        /// Location: SavedPathsSheet.swift - Purpose: Last run label
        public static func lastRun(_ p1: Any) -> String {
          return L10n.tr("Contacts", "contacts.savedPaths.lastRun", String(describing: p1), fallback: "Last: %@")
        }
        /// Location: SavedPathsSheet.swift - Purpose: No response data accessibility
        public static let noResponseData = L10n.tr("Contacts", "contacts.savedPaths.noResponseData", fallback: "No response time data")
        /// Location: SavedPathsSheet.swift - Purpose: Rename context menu
        public static let rename = L10n.tr("Contacts", "contacts.savedPaths.rename", fallback: "Rename")
        /// Location: SavedPathsSheet.swift - Purpose: Rename alert title
        public static let renameTitle = L10n.tr("Contacts", "contacts.savedPaths.renameTitle", fallback: "Rename Path")
        /// Location: SavedPathsSheet.swift - Purpose: Response times accessibility
        public static func responseTimes(_ p1: Int, _ p2: Any) -> String {
          return L10n.tr("Contacts", "contacts.savedPaths.responseTimes", p1, String(describing: p2), fallback: "Response times: average %dms, %@")
        }
        /// Location: SavedPathsSheet.swift - Purpose: Navigation title
        public static let title = L10n.tr("Contacts", "contacts.savedPaths.title", fallback: "Saved Paths")
        public enum Empty {
          /// Location: SavedPathsSheet.swift - Purpose: Empty state description
          public static let description = L10n.tr("Contacts", "contacts.savedPaths.empty.description", fallback: "Save paths after running traces to quickly re-run them later.")
          /// Location: SavedPathsSheet.swift - Purpose: Empty state title
          public static let title = L10n.tr("Contacts", "contacts.savedPaths.empty.title", fallback: "No Saved Paths")
        }
        public enum Health {
          /// Location: SavedPathsSheet.swift - Purpose: Degraded status accessibility
          public static let degraded = L10n.tr("Contacts", "contacts.savedPaths.health.degraded", fallback: "degraded")
          /// Location: SavedPathsSheet.swift - Purpose: Healthy status accessibility
          public static let healthy = L10n.tr("Contacts", "contacts.savedPaths.health.healthy", fallback: "healthy")
          /// Location: SavedPathsSheet.swift - Purpose: Poor status accessibility
          public static let poor = L10n.tr("Contacts", "contacts.savedPaths.health.poor", fallback: "poor")
        }
        public enum Runs {
          /// Location: SavedPathsSheet.swift - Purpose: Run count plural
          public static func plural(_ p1: Int) -> String {
            return L10n.tr("Contacts", "contacts.savedPaths.runs.plural", p1, fallback: "%d runs")
          }
          /// Location: SavedPathsSheet.swift - Purpose: Run count singular
          public static let singular = L10n.tr("Contacts", "contacts.savedPaths.runs.singular", fallback: "1 run")
        }
        public enum Trend {
          /// Location: SavedPathsSheet.swift - Purpose: Trend decreasing
          public static let decreasing = L10n.tr("Contacts", "contacts.savedPaths.trend.decreasing", fallback: "decreasing")
          /// Location: SavedPathsSheet.swift - Purpose: Trend increasing
          public static let increasing = L10n.tr("Contacts", "contacts.savedPaths.trend.increasing", fallback: "increasing")
          /// Location: SavedPathsSheet.swift - Purpose: Trend stable
          public static let stable = L10n.tr("Contacts", "contacts.savedPaths.trend.stable", fallback: "stable")
        }
      }
      public enum Scan {
        /// Location: ScanContactQRView.swift - Purpose: Importing progress
        public static let importing = L10n.tr("Contacts", "contacts.scan.importing", fallback: "Importing contact...")
        /// Location: ScanContactQRView.swift - Purpose: Scan instruction
        public static let instruction = L10n.tr("Contacts", "contacts.scan.instruction", fallback: "Point your camera at a contact QR code")
        /// Location: ScanContactQRView.swift - Purpose: Navigation title
        public static let title = L10n.tr("Contacts", "contacts.scan.title", fallback: "Scan QR Code")
        public enum Error {
          /// Location: ScanContactQRView.swift - Purpose: Contact import failed, %@ is the error description
          public static func importFailed(_ p1: Any) -> String {
            return L10n.tr("Contacts", "contacts.scan.error.importFailed", String(describing: p1), fallback: "Failed to import contact: %@")
          }
          /// Location: ScanContactQRView.swift - Purpose: Invalid QR format error
          public static let invalidFormat = L10n.tr("Contacts", "contacts.scan.error.invalidFormat", fallback: "Invalid QR code format")
          /// Location: ScanContactQRView.swift - Purpose: Invalid public key error
          public static let invalidKey = L10n.tr("Contacts", "contacts.scan.error.invalidKey", fallback: "Invalid QR code: invalid public key")
          /// Location: ScanContactQRView.swift - Purpose: Missing name error
          public static let missingName = L10n.tr("Contacts", "contacts.scan.error.missingName", fallback: "Invalid QR code: missing name")
        }
        public enum Permission {
          /// Location: ScanContactQRView.swift - Purpose: Camera permission description
          public static let description = L10n.tr("Contacts", "contacts.scan.permission.description", fallback: "Please enable camera access in Settings to scan QR codes.")
          /// Location: ScanContactQRView.swift - Purpose: Camera permission title
          public static let title = L10n.tr("Contacts", "contacts.scan.permission.title", fallback: "Camera Access Required")
        }
        public enum Unavailable {
          /// Location: ScanContactQRView.swift - Purpose: Scanner not available description
          public static let description = L10n.tr("Contacts", "contacts.scan.unavailable.description", fallback: "QR scanning is not supported on this device")
          /// Location: ScanContactQRView.swift - Purpose: Scanner not available title
          public static let title = L10n.tr("Contacts", "contacts.scan.unavailable.title", fallback: "Scanner Not Available")
        }
      }
      public enum Segment {
        /// Location: ContactsViewModel.swift - Purpose: Contacts segment
        public static let contacts = L10n.tr("Contacts", "contacts.segment.contacts", fallback: "Contacts")
        /// Location: ContactsViewModel.swift - Purpose: Favorites segment
        public static let favorites = L10n.tr("Contacts", "contacts.segment.favorites", fallback: "Favorites")
        /// Location: ContactsViewModel.swift - Purpose: Network segment
        public static let network = L10n.tr("Contacts", "contacts.segment.network", fallback: "Network")
      }
      public enum Share {
        /// Location: ContactQRShareSheet.swift - Purpose: Contact label in share text, %@ is contact name
        public static func contactLabel(_ p1: Any) -> String {
          return L10n.tr("Contacts", "contacts.share.contactLabel", String(describing: p1), fallback: "MeshCore One Contact: %@")
        }
        /// Location: ContactQRShareSheet.swift - Purpose: Key label in share text, %@ is hex public key
        public static func keyLabel(_ p1: Any) -> String {
          return L10n.tr("Contacts", "contacts.share.keyLabel", String(describing: p1), fallback: "Key: %@")
        }
      }
      public enum Sort {
        /// Location: ContactsViewModel.swift - Purpose: Distance sort option
        public static let distance = L10n.tr("Contacts", "contacts.sort.distance", fallback: "Distance")
        /// Location: ContactsViewModel.swift - Purpose: Last heard sort option
        public static let lastHeard = L10n.tr("Contacts", "contacts.sort.lastHeard", fallback: "Last Modified")
        /// Location: ContactsViewModel.swift - Purpose: Name sort option
        public static let name = L10n.tr("Contacts", "contacts.sort.name", fallback: "Name")
      }
      public enum StatsBadge {
        /// Location: StatsBadgeView.swift - Purpose: Distance and signal accessibility
        public static func accessibility(_ p1: Any, _ p2: Int) -> String {
          return L10n.tr("Contacts", "contacts.statsBadge.accessibility", String(describing: p1), p2, fallback: "Distance: %@, Signal: %d decibels")
        }
      }
      public enum Swipe {
        /// Location: ContactSwipeActionsModifier - Purpose: Block swipe action
        public static let block = L10n.tr("Contacts", "contacts.swipe.block", fallback: "Block")
        /// Location: ContactSwipeActionsModifier - Purpose: Unblock swipe action
        public static let unblock = L10n.tr("Contacts", "contacts.swipe.unblock", fallback: "Unblock")
        /// Location: ContactSwipeActionsModifier - Purpose: Unfavorite swipe action
        public static let unfavorite = L10n.tr("Contacts", "contacts.swipe.unfavorite", fallback: "Unfavorite")
      }
      public enum Trace {
        /// Location: TracePathView.swift - Purpose: Clear path dialog title
        public static let clearPath = L10n.tr("Contacts", "contacts.trace.clearPath", fallback: "Clear Path")
        /// Location: TracePathView.swift - Purpose: Clear path dialog message
        public static let clearPathMessage = L10n.tr("Contacts", "contacts.trace.clearPathMessage", fallback: "Remove all repeaters from the path?")
        /// Location: TracePathView.swift - Purpose: Trace failed alert title
        public static let failed = L10n.tr("Contacts", "contacts.trace.failed", fallback: "Trace Failed")
        /// Location: TracePathView.swift - Purpose: Jump button accessibility hint
        public static let jumpHint = L10n.tr("Contacts", "contacts.trace.jumpHint", fallback: "Double tap to scroll to the bottom of the path")
        /// Location: TracePathView.swift - Purpose: Jump button accessibility label
        public static let jumpLabel = L10n.tr("Contacts", "contacts.trace.jumpLabel", fallback: "Jump to Run Trace button")
        /// Location: TracePathView.swift - Purpose: Jump button label
        public static let runBelow = L10n.tr("Contacts", "contacts.trace.runBelow", fallback: "Run Below")
        /// Location: TracePathView.swift - Purpose: Saved toolbar button
        public static let saved = L10n.tr("Contacts", "contacts.trace.saved", fallback: "Saved")
        /// Location: TracePathView.swift - Purpose: Navigation title
        public static let title = L10n.tr("Contacts", "contacts.trace.title", fallback: "Trace Path")
        /// Location: TracePathView.swift - Purpose: View mode picker label
        public static let viewMode = L10n.tr("Contacts", "contacts.trace.viewMode", fallback: "View Mode")
        public enum Error {
          /// Location: TracePathViewModel.swift - Purpose: All traces failed error
          public static func allFailed(_ p1: Int) -> String {
            return L10n.tr("Contacts", "contacts.trace.error.allFailed", p1, fallback: "All %d traces failed")
          }
          /// Location: TracePathViewModel.swift - Purpose: No response error
          public static let noResponse = L10n.tr("Contacts", "contacts.trace.error.noResponse", fallback: "No response received")
          /// Location: TracePathViewModel.swift - Purpose: Send failed error
          public static let sendFailed = L10n.tr("Contacts", "contacts.trace.error.sendFailed", fallback: "Failed to send trace packet")
        }
        public enum List {
          /// Location: TracePathListView.swift - Purpose: Auto return toggle label
          public static let autoReturn = L10n.tr("Contacts", "contacts.trace.list.autoReturn", fallback: "Auto Return Path")
          /// Location: TracePathListView.swift - Purpose: Auto return toggle description
          public static let autoReturnDescription = L10n.tr("Contacts", "contacts.trace.list.autoReturnDescription", fallback: "Mirror outbound path for the return journey")
          /// Location: TracePathListView.swift - Purpose: Batch run accessibility hint
          public static func batchHint(_ p1: Int) -> String {
            return L10n.tr("Contacts", "contacts.trace.list.batchHint", p1, fallback: "Double tap to run %d traces")
          }
          /// Location: TracePathListView.swift - Purpose: Batch trace toggle label
          public static let batchTrace = L10n.tr("Contacts", "contacts.trace.list.batchTrace", fallback: "Batch Trace")
          /// Location: TracePathListView.swift - Purpose: Batch trace toggle description
          public static let batchTraceDescription = L10n.tr("Contacts", "contacts.trace.list.batchTraceDescription", fallback: "Run multiple traces and average the results")
          /// Location: TracePathListView.swift - Purpose: Code input footer
          public static let codeFooter = L10n.tr("Contacts", "contacts.trace.list.codeFooter", fallback: "Press Return to add repeaters")
          /// Location: TracePathListView.swift - Purpose: Code input placeholder
          public static let codePlaceholder = L10n.tr("Contacts", "contacts.trace.list.codePlaceholder", fallback: "Example: A1, 2B")
          /// Location: TracePathListView.swift - Purpose: Copy path button
          public static let copyPath = L10n.tr("Contacts", "contacts.trace.list.copyPath", fallback: "Copy Path")
          /// Location: TracePathListView.swift - Purpose: Empty path instruction
          public static let emptyPath = L10n.tr("Contacts", "contacts.trace.list.emptyPath", fallback: "Tap a repeater above to start building your path")
          /// Location: TracePathListView.swift - Purpose: Favorites filter toggle label
          public static let favoritesOnly = L10n.tr("Contacts", "contacts.trace.list.favoritesOnly", fallback: "Favorites Only")
          /// Location: TracePathListView.swift - Purpose: Hop row accessibility hint
          public static let hopHint = L10n.tr("Contacts", "contacts.trace.list.hopHint", fallback: "Swipe left to delete, use drag handle to reorder")
          /// Location: TracePathListView.swift - Purpose: Hop row accessibility label
          public static func hopLabel(_ p1: Int, _ p2: Any) -> String {
            return L10n.tr("Contacts", "contacts.trace.list.hopLabel", p1, String(describing: p2), fallback: "Hop %d: %@")
          }
          /// Location: TracePathListView.swift - Purpose: Toggle to include discovered repeaters
          public static let includeDiscovered = L10n.tr("Contacts", "contacts.trace.list.includeDiscovered", fallback: "Include Discovered")
          /// Location: TracePathListView.swift - Purpose: Toggle to include room servers in the list
          public static let includeRooms = L10n.tr("Contacts", "contacts.trace.list.includeRooms", fallback: "Include Rooms")
          /// Location: TracePathListView.swift - Purpose: Paste button
          public static let paste = L10n.tr("Contacts", "contacts.trace.list.paste", fallback: "Paste from clipboard")
          /// Location: TracePathListView.swift - Purpose: Range warning footer
          public static let rangeWarning = L10n.tr("Contacts", "contacts.trace.list.rangeWarning", fallback: "You must be within range of the last repeater to receive a response.")
          /// Location: TracePathListView.swift - Purpose: Repeaters section label
          public static let repeaters = L10n.tr("Contacts", "contacts.trace.list.repeaters", fallback: "Repeaters")
          /// Location: TracePathListView.swift - Purpose: Round trip path section header
          public static let roundTripPath = L10n.tr("Contacts", "contacts.trace.list.roundTripPath", fallback: "Round Trip Path")
          /// Location: TracePathListView.swift - Purpose: Running trace with batch count
          public static func runningBatch(_ p1: Int, _ p2: Int) -> String {
            return L10n.tr("Contacts", "contacts.trace.list.runningBatch", p1, p2, fallback: "Running Trace %d of %d")
          }
          /// Location: TracePathListView.swift - Purpose: Running batch accessibility label
          public static func runningBatchLabel(_ p1: Int, _ p2: Int) -> String {
            return L10n.tr("Contacts", "contacts.trace.list.runningBatchLabel", p1, p2, fallback: "Running trace %d of %d")
          }
          /// Location: TracePathListView.swift - Accessibility hint when trace is running
          public static let runningHint = L10n.tr("Contacts", "contacts.trace.list.runningHint", fallback: "Trace is in progress")
          /// Location: TracePathListView.swift - Purpose: Running accessibility label
          public static let runningLabel = L10n.tr("Contacts", "contacts.trace.list.runningLabel", fallback: "Running trace, please wait")
          /// Location: TracePathListView.swift - Purpose: Running trace progress
          public static let runningTrace = L10n.tr("Contacts", "contacts.trace.list.runningTrace", fallback: "Running Trace")
          /// Location: TracePathListView.swift - Purpose: Run trace button
          public static let runTrace = L10n.tr("Contacts", "contacts.trace.list.runTrace", fallback: "Run Trace")
          /// Location: TracePathListView.swift - Purpose: Run trace accessibility label
          public static let runTraceLabel = L10n.tr("Contacts", "contacts.trace.list.runTraceLabel", fallback: "Run trace")
          /// Location: TracePathListView.swift - Purpose: Single run accessibility hint
          public static let singleHint = L10n.tr("Contacts", "contacts.trace.list.singleHint", fallback: "Double tap to trace the path")
          /// Location: TracePathListView.swift - Purpose: Traces count label
          public static let traces = L10n.tr("Contacts", "contacts.trace.list.traces", fallback: "Traces:")
          public enum NoFavorites {
            /// Location: TracePathListView.swift - Purpose: No favorite repeaters empty description
            public static let description = L10n.tr("Contacts", "contacts.trace.list.noFavorites.description", fallback: "Mark repeaters as favorites in the Nodes tab to see them here.")
            /// Location: TracePathListView.swift - Purpose: No favorite repeaters empty title
            public static let title = L10n.tr("Contacts", "contacts.trace.list.noFavorites.title", fallback: "No Favorite Repeaters")
          }
        }
        public enum Map {
          /// Location: TracePathMapView.swift - Purpose: Center on path accessibility
          public static let centerOnPath = L10n.tr("Contacts", "contacts.trace.map.centerOnPath", fallback: "Center on path")
          /// Location: TracePathMapView.swift - Purpose: Clear button
          public static let clear = L10n.tr("Contacts", "contacts.trace.map.clear", fallback: "Clear")
          /// Location: TracePathMapViewModel.swift - Purpose: Default path name fallback
          public static let defaultPathName = L10n.tr("Contacts", "contacts.trace.map.defaultPathName", fallback: "Path")
          /// Location: TracePathMapView.swift - Purpose: Hide labels accessibility
          public static let hideLabels = L10n.tr("Contacts", "contacts.trace.map.hideLabels", fallback: "Hide labels")
          /// Location: TracePathMapView.swift - Purpose: Hops count in results banner
          public static func hops(_ p1: Int) -> String {
            return L10n.tr("Contacts", "contacts.trace.map.hops", p1, fallback: "%d hops")
          }
          /// Location: TracePathMapView.swift - Purpose: Path name placeholder
          public static let pathName = L10n.tr("Contacts", "contacts.trace.map.pathName", fallback: "Path name")
          /// Location: TracePathMapView.swift - Purpose: Path saved alert message
          public static let savedMessage = L10n.tr("Contacts", "contacts.trace.map.savedMessage", fallback: "The path has been saved successfully.")
          /// Location: TracePathMapView.swift - Purpose: Path saved alert title
          public static let savedTitle = L10n.tr("Contacts", "contacts.trace.map.savedTitle", fallback: "Path Saved")
          /// Location: TracePathMapView.swift - Purpose: Save failed alert message
          public static let saveFailedMessage = L10n.tr("Contacts", "contacts.trace.map.saveFailedMessage", fallback: "Failed to save the path. Please try again.")
          /// Location: TracePathMapView.swift - Purpose: Save failed alert title
          public static let saveFailedTitle = L10n.tr("Contacts", "contacts.trace.map.saveFailedTitle", fallback: "Save Failed")
          /// Location: TracePathMapView.swift - Purpose: Save path alert message
          public static let saveMessage = L10n.tr("Contacts", "contacts.trace.map.saveMessage", fallback: "Enter a name for this path")
          /// Location: TracePathMapView.swift - Purpose: Save path alert title
          public static let saveTitle = L10n.tr("Contacts", "contacts.trace.map.saveTitle", fallback: "Save Path")
          /// Location: TracePathMapView.swift - Purpose: Show labels accessibility
          public static let showLabels = L10n.tr("Contacts", "contacts.trace.map.showLabels", fallback: "Show labels")
          /// Location: TracePathMapView.swift - Purpose: View results button
          public static let viewResults = L10n.tr("Contacts", "contacts.trace.map.viewResults", fallback: "Results")
          public enum Cluster {
            /// Location: TracePathClusterView.swift - Accessibility hint for cluster annotation
            public static let hint = L10n.tr("Contacts", "contacts.trace.map.cluster.hint", fallback: "Double tap to zoom in")
            /// Location: TracePathClusterView.swift - Accessibility label for cluster annotation - %d is count
            public static func label(_ p1: Int) -> String {
              return L10n.tr("Contacts", "contacts.trace.map.cluster.label", p1, fallback: "%d repeaters")
            }
          }
          public enum Pin {
            /// Location: TracePathRepeaterPinView.swift - Accessibility hint for adding repeater to path
            public static let addHint = L10n.tr("Contacts", "contacts.trace.map.pin.addHint", fallback: "Double tap to add to path")
            /// Location: TracePathRepeaterPinView.swift - Accessibility label for available repeater - %@ is name
            public static func availableLabel(_ p1: Any) -> String {
              return L10n.tr("Contacts", "contacts.trace.map.pin.availableLabel", String(describing: p1), fallback: "Repeater: %@")
            }
            /// Location: TracePathRepeaterPinView.swift - Accessibility label for repeater in path - %1$@ is name, %2$d is hop number
            public static func inPathLabel(_ p1: Any, _ p2: Int) -> String {
              return L10n.tr("Contacts", "contacts.trace.map.pin.inPathLabel", String(describing: p1), p2, fallback: "Repeater: %1$@, hop %2$d in path")
            }
            /// Location: TracePathRepeaterPinView.swift - Accessibility hint for non-removable hop
            public static let notRemovableHint = L10n.tr("Contacts", "contacts.trace.map.pin.notRemovableHint", fallback: "This hop cannot be removed. Only the last hop can be removed.")
            /// Location: TracePathRepeaterPinView.swift - Accessibility hint for removable last hop
            public static let removableHint = L10n.tr("Contacts", "contacts.trace.map.pin.removableHint", fallback: "Double tap to remove from path")
          }
        }
        public enum Mode {
          /// Location: TracePathView.swift - Purpose: List view mode
          public static let list = L10n.tr("Contacts", "contacts.trace.mode.list", fallback: "List")
          /// Location: TracePathView.swift - Purpose: Map view mode
          public static let map = L10n.tr("Contacts", "contacts.trace.mode.map", fallback: "Map")
        }
      }
      public enum ViewModel {
        /// Location: ContactsViewModel.swift - Purpose: Delete requires connection error
        public static let connectToDelete = L10n.tr("Contacts", "contacts.viewModel.connectToDelete", fallback: "Connect to a radio to delete nodes")
      }
    }
  }
  public enum Localizable {
    public enum Accessibility {
      /// Accessibility value for toggle in Off state
      public static let off = L10n.tr("Localizable", "accessibility.off", fallback: "Off")
      /// Accessibility value for toggle in On state
      public static let on = L10n.tr("Localizable", "accessibility.on", fallback: "On")
      /// VoiceOver announcement when viewing cached data while disconnected from device
      public static let viewingCachedData = L10n.tr("Localizable", "accessibility.viewingCachedData", fallback: "Viewing cached data. Connect to device for updates.")
      public enum Connection {
        /// VoiceOver announcement when device connection is lost
        public static let deviceConnectionLost = L10n.tr("Localizable", "accessibility.connection.deviceConnectionLost", fallback: "Device connection lost")
        /// VoiceOver announcement when device reconnects
        public static let deviceReconnected = L10n.tr("Localizable", "accessibility.connection.deviceReconnected", fallback: "Device reconnected")
        /// VoiceOver announcement when sync fails and device is disconnecting
        public static let syncFailedDisconnecting = L10n.tr("Localizable", "accessibility.connection.syncFailedDisconnecting", fallback: "Sync failed. Disconnecting.")
      }
    }
    public enum Alert {
      public enum ConnectionFailed {
        /// Default message when device connection fails
        public static let defaultMessage = L10n.tr("Localizable", "alert.connectionFailed.defaultMessage", fallback: "Unable to connect to device.")
        /// Button to remove failed pairing and retry connection
        public static let removeAndRetry = L10n.tr("Localizable", "alert.connectionFailed.removeAndRetry", fallback: "Remove & Try Again")
        /// Alert title when device connection fails
        public static let title = L10n.tr("Localizable", "alert.connectionFailed.title", fallback: "Connection Failed")
      }
      public enum CouldNotConnect {
        /// Message suggesting another app may be connected to the device
        public static let otherAppMessage = L10n.tr("Localizable", "alert.couldNotConnect.otherAppMessage", fallback: "Ensure no other app is connected to the device, then try again.")
        /// Alert title when connection cannot be established
        public static let title = L10n.tr("Localizable", "alert.couldNotConnect.title", fallback: "Could Not Connect")
      }
    }
    public enum Common {
      /// Standard cancel button for dialogs and sheets
      public static let cancel = L10n.tr("Localizable", "common.cancel", fallback: "Cancel")
      /// Standard close button for dismissing views
      public static let close = L10n.tr("Localizable", "common.close", fallback: "Close")
      /// Standard delete button for removing items
      public static let delete = L10n.tr("Localizable", "common.delete", fallback: "Delete")
      /// Standard done button for completing an action
      public static let done = L10n.tr("Localizable", "common.done", fallback: "Done")
      /// Standard edit button for entering edit mode
      public static let edit = L10n.tr("Localizable", "common.edit", fallback: "Edit")
      /// Standard confirmation button for dialogs
      public static let ok = L10n.tr("Localizable", "common.ok", fallback: "OK")
      /// Standard remove button for removing items from a list or group
      public static let remove = L10n.tr("Localizable", "common.remove", fallback: "Remove")
      /// Standard save button for persisting changes
      public static let save = L10n.tr("Localizable", "common.save", fallback: "Save")
      /// Button to retry a failed operation
      public static let tryAgain = L10n.tr("Localizable", "common.tryAgain", fallback: "Try Again")
      public enum Accessibility {
        /// Location: SyncingPillView.swift - Accessibility hint for disconnected state
        public static let connectHint = L10n.tr("Localizable", "common.accessibility.connectHint", fallback: "Double tap to connect device")
      }
      public enum Error {
        /// API error with message - %@ is the error message
        public static func apiError(_ p1: Any) -> String {
          return L10n.tr("Localizable", "common.error.apiError", String(describing: p1), fallback: "API error: %@")
        }
        /// Generic connection error title
        public static let connectionError = L10n.tr("Localizable", "common.error.connectionError", fallback: "Connection error")
        /// Error label when content fails to load
        public static let failedToLoad = L10n.tr("Localizable", "common.error.failedToLoad", fallback: "Failed to load")
        /// Invalid response from API
        public static let invalidResponse = L10n.tr("Localizable", "common.error.invalidResponse", fallback: "Invalid response from elevation API")
        /// Network error with underlying error description - %@ is the error description
        public static func networkError(_ p1: Any) -> String {
          return L10n.tr("Localizable", "common.error.networkError", String(describing: p1), fallback: "Network error: %@")
        }
        /// No data returned from API
        public static let noElevationData = L10n.tr("Localizable", "common.error.noElevationData", fallback: "No elevation data returned")
        /// Rate limited by elevation API
        public static let rateLimited = L10n.tr("Localizable", "common.error.rateLimited", fallback: "Too many requests. Please try again shortly.")
      }
      public enum Status {
        /// Location: SyncingPillView.swift - Status shown when connecting to device
        public static let connecting = L10n.tr("Localizable", "common.status.connecting", fallback: "Connecting")
        /// Location: SyncingPillView.swift - Status shown when device is disconnected
        public static let disconnected = L10n.tr("Localizable", "common.status.disconnected", fallback: "Disconnected")
        /// Location: SyncingPillView.swift - Status shown when device is ready
        public static let ready = L10n.tr("Localizable", "common.status.ready", fallback: "Ready")
        /// Location: SyncingPillView.swift - Status shown when syncing data
        public static let syncing = L10n.tr("Localizable", "common.status.syncing", fallback: "Syncing")
      }
    }
    public enum NodeType {
      /// Node type for a person or contact
      public static let contact = L10n.tr("Localizable", "nodeType.contact", fallback: "Contact")
      /// Node type for a mesh network repeater device
      public static let repeater = L10n.tr("Localizable", "nodeType.repeater", fallback: "Repeater")
      /// Node type for a group chat room
      public static let room = L10n.tr("Localizable", "nodeType.room", fallback: "Room")
    }
    public enum Notifications {
      public enum Action {
        /// Notification action button to mark a message as read
        public static let markAsRead = L10n.tr("Localizable", "notifications.action.markAsRead", fallback: "Mark as Read")
        /// Notification action placeholder for quick reply text input
        public static let messagePlaceholder = L10n.tr("Localizable", "notifications.action.messagePlaceholder", fallback: "Message...")
        /// Notification action button to reply to a message
        public static let reply = L10n.tr("Localizable", "notifications.action.reply", fallback: "Reply")
        /// Notification action button to send a quick reply
        public static let send = L10n.tr("Localizable", "notifications.action.send", fallback: "Send")
      }
      public enum Discovery {
        /// Notification title when a new contact is discovered on the mesh network
        public static let contact = L10n.tr("Localizable", "notifications.discovery.contact", fallback: "New Contact Discovered")
        /// Notification title when a new repeater node is discovered on the mesh network
        public static let repeater = L10n.tr("Localizable", "notifications.discovery.repeater", fallback: "New Repeater Discovered")
        /// Notification title when a new room is discovered on the mesh network
        public static let room = L10n.tr("Localizable", "notifications.discovery.room", fallback: "New Room Discovered")
      }
      public enum LowBattery {
        /// Notification body for low battery warning - %1$@ is device name, %2$d is battery percentage
        public static func body(_ p1: Any, _ p2: Int) -> String {
          return L10n.tr("Localizable", "notifications.lowBattery.body", String(describing: p1), p2, fallback: "%1$@ battery is at %2$d%%")
        }
        /// Notification title for low battery warning
        public static let title = L10n.tr("Localizable", "notifications.lowBattery.title", fallback: "Low Battery")
      }
      public enum Reaction {
        /// Notification body when someone reacts to your message - %1$@ is the emoji, %2$@ is the message preview
        public static func body(_ p1: Any, _ p2: Any) -> String {
          return L10n.tr("Localizable", "notifications.reaction.body", String(describing: p1), String(describing: p2), fallback: "Reacted %1$@ to your message: \"%2$@\"")
        }
      }
    }
    public enum Permission {
      /// Permission level with full administrative access
      public static let admin = L10n.tr("Localizable", "permission.admin", fallback: "Admin")
      /// Permission level with limited access
      public static let guest = L10n.tr("Localizable", "permission.guest", fallback: "Guest")
      /// Permission level with standard access
      public static let member = L10n.tr("Localizable", "permission.member", fallback: "Member")
    }
    public enum StatusPill {
      /// Status pill message when sync has failed
      public static let syncFailed = L10n.tr("Localizable", "statusPill.syncFailed", fallback: "Sync Failed")
    }
    public enum Tabs {
      /// Tab bar title for the messaging/conversations screen
      public static let chats = L10n.tr("Localizable", "tabs.chats", fallback: "Chats")
      /// Tab bar title for the map screen showing node locations
      public static let map = L10n.tr("Localizable", "tabs.map", fallback: "Map")
      /// Tab bar title for the nodes/contacts list screen
      public static let nodes = L10n.tr("Localizable", "tabs.nodes", fallback: "Nodes")
      /// Tab bar title for the app settings screen
      public static let settings = L10n.tr("Localizable", "tabs.settings", fallback: "Settings")
      /// Tab bar title for the tools/utilities screen
      public static let tools = L10n.tr("Localizable", "tabs.tools", fallback: "Tools")
    }
  }
  public enum Map {
    public enum Map {
      public enum Annotation {
        /// Location: ContactAnnotation.swift - Purpose: Subtitle for favorite contacts
        public static let favorite = L10n.tr("Map", "map.annotation.favorite", fallback: "Favorite")
        /// Location: ContactAnnotation.swift - Purpose: Subtitle for repeater nodes
        public static let repeater = L10n.tr("Map", "map.annotation.repeater", fallback: "Repeater")
        /// Location: ContactAnnotation.swift - Purpose: Subtitle for room nodes
        public static let room = L10n.tr("Map", "map.annotation.room", fallback: "Room")
      }
      public enum Callout {
        /// Location: ContactCalloutContent.swift - Purpose: Button to view contact details
        public static let details = L10n.tr("Map", "map.callout.details", fallback: "Details")
        /// Location: ContactCalloutContent.swift - Purpose: Button to send message from callout
        public static let message = L10n.tr("Map", "map.callout.message", fallback: "Message")
        public enum NodeKind {
          /// Location: ContactCalloutContent.swift - Purpose: Display name for contact in callout
          public static let contact = L10n.tr("Map", "map.callout.nodeKind.contact", fallback: "Contact")
          /// Location: ContactCalloutContent.swift - Purpose: Display name for repeater in callout
          public static let repeater = L10n.tr("Map", "map.callout.nodeKind.repeater", fallback: "Repeater")
          /// Location: ContactCalloutContent.swift - Purpose: Display name for room in callout
          public static let room = L10n.tr("Map", "map.callout.nodeKind.room", fallback: "Room")
        }
      }
      public enum Common {
        /// Dismiss
        public static let dismissOverlay = L10n.tr("Map", "map.common.dismissOverlay", fallback: "Dismiss")
        /// Location: MapView.swift - Purpose: Done button for sheets
        public static let done = L10n.tr("Map", "map.common.done", fallback: "Done")
      }
      public enum Controls {
        /// Location: MapView.swift - Purpose: Accessibility label for center on all contacts button
        public static let centerAll = L10n.tr("Map", "map.controls.centerAll", fallback: "Center on all contacts")
        /// Location: MapControlsToolbar.swift - Purpose: Accessibility label for user location button
        public static let centerOnMyLocation = L10n.tr("Map", "map.controls.centerOnMyLocation", fallback: "Center on my location")
        /// Location: MapView.swift - Purpose: Accessibility label when labels are visible
        public static let hideLabels = L10n.tr("Map", "map.controls.hideLabels", fallback: "Hide labels")
        /// Location: MapControlsToolbar.swift - Purpose: Accessibility label for layers button
        public static let layers = L10n.tr("Map", "map.controls.layers", fallback: "Map layers")
        /// Location: MapCanvasView.swift - Purpose: Accessibility label for north lock button (lock)
        public static let lockNorth = L10n.tr("Map", "map.controls.lockNorth", fallback: "Lock to north")
        /// Location: MapView.swift - Purpose: Accessibility label for refresh button
        public static let refresh = L10n.tr("Map", "map.controls.refresh", fallback: "Refresh contacts")
        /// Location: MapView.swift - Purpose: Accessibility label when labels are hidden
        public static let showLabels = L10n.tr("Map", "map.controls.showLabels", fallback: "Show labels")
        /// Location: MapCanvasView.swift - Purpose: Accessibility label for north lock button (unlock)
        public static let unlockNorth = L10n.tr("Map", "map.controls.unlockNorth", fallback: "Unlock rotation")
      }
      public enum Detail {
        /// Location: MapView.swift ContactDetailSheet - Purpose: Value showing contact is favorited
        public static let favorite = L10n.tr("Map", "map.detail.favorite", fallback: "Favorite")
        /// Location: MapView.swift ContactDetailSheet - Purpose: Path length value with hop count
        public static func hops(_ p1: Int) -> String {
          return L10n.tr("Map", "map.detail.hops", p1, fallback: "%d hops")
        }
        /// Location: MapView.swift ContactDetailSheet - Purpose: Path length value for single hop
        public static let hopSingular = L10n.tr("Map", "map.detail.hopSingular", fallback: "1 hop")
        /// Location: MapView.swift ContactDetailSheet - Purpose: Label for last advertisement timestamp
        public static let lastAdvert = L10n.tr("Map", "map.detail.lastAdvert", fallback: "Last Advert")
        /// Location: MapView.swift ContactDetailSheet - Purpose: Label for latitude coordinate
        public static let latitude = L10n.tr("Map", "map.detail.latitude", fallback: "Latitude")
        /// Location: MapView.swift ContactDetailSheet - Purpose: Label for longitude coordinate
        public static let longitude = L10n.tr("Map", "map.detail.longitude", fallback: "Longitude")
        /// Location: MapView.swift ContactDetailSheet - Purpose: Label for contact name
        public static let name = L10n.tr("Map", "map.detail.name", fallback: "Name")
        /// Location: MapView.swift ContactDetailSheet - Purpose: Label for path length
        public static let pathLength = L10n.tr("Map", "map.detail.pathLength", fallback: "Path Length")
        /// Location: MapView.swift ContactDetailSheet - Purpose: Label for routing type
        public static let routing = L10n.tr("Map", "map.detail.routing", fallback: "Routing")
        /// Location: MapView.swift ContactDetailSheet - Purpose: Flood routing type value
        public static let routingFlood = L10n.tr("Map", "map.detail.routingFlood", fallback: "Flood")
        /// Location: MapView.swift ContactDetailSheet - Purpose: Label for favorite status
        public static let status = L10n.tr("Map", "map.detail.status", fallback: "Status")
        /// Location: MapView.swift ContactDetailSheet - Purpose: Label for contact type
        public static let type = L10n.tr("Map", "map.detail.type", fallback: "Type")
        public enum Action {
          /// Location: MapView.swift ContactDetailSheet - Purpose: Button to join a room
          public static let joinRoom = L10n.tr("Map", "map.detail.action.joinRoom", fallback: "Join Room")
          /// Location: MapView.swift ContactDetailSheet - Purpose: Button to manage repeater
          public static let management = L10n.tr("Map", "map.detail.action.management", fallback: "Management")
          /// Location: MapView.swift - Purpose: Saved History button for offline telemetry
          public static let savedHistory = L10n.tr("Map", "map.detail.action.savedHistory", fallback: "Saved History")
          /// Location: MapView.swift ContactDetailSheet - Purpose: Button to send a message
          public static let sendMessage = L10n.tr("Map", "map.detail.action.sendMessage", fallback: "Send Message")
          /// Location: MapView.swift ContactDetailSheet - Purpose: Button to view repeater telemetry
          public static let telemetry = L10n.tr("Map", "map.detail.action.telemetry", fallback: "Telemetry")
          /// Location: MapView.swift ContactDetailSheet - Purpose: Sheet title for telemetry authentication
          public static let telemetryAccessTitle = L10n.tr("Map", "map.detail.action.telemetryAccessTitle", fallback: "Telemetry Access")
        }
        public enum Section {
          /// Location: MapView.swift ContactDetailSheet - Purpose: Section header for contact information
          public static let contactInfo = L10n.tr("Map", "map.detail.section.contactInfo", fallback: "Contact Info")
          /// Location: MapView.swift ContactDetailSheet - Purpose: Section header for location coordinates
          public static let location = L10n.tr("Map", "map.detail.section.location", fallback: "Location")
          /// Location: MapView.swift ContactDetailSheet - Purpose: Section header for network path info
          public static let networkPath = L10n.tr("Map", "map.detail.section.networkPath", fallback: "Network Path")
        }
      }
      public enum NodeKind {
        /// Location: MapView.swift ContactDetailSheet - Purpose: Display name for chat contact type
        public static let chatContact = L10n.tr("Map", "map.nodeKind.chatContact", fallback: "Chat Contact")
        /// Location: MapView.swift ContactDetailSheet - Purpose: Display name for repeater type
        public static let repeater = L10n.tr("Map", "map.nodeKind.repeater", fallback: "Repeater")
        /// Location: MapView.swift ContactDetailSheet - Purpose: Display name for room type
        public static let room = L10n.tr("Map", "map.nodeKind.room", fallback: "Room")
      }
      public enum OfflineBadge {
        /// Label shown on map when device has no internet connection
        public static let label = L10n.tr("Map", "map.offlineBadge.label", fallback: "Offline")
      }
      public enum Style {
        /// Location: LayersMenu.swift - Purpose: Accessibility label for map style menu
        public static let accessibilityLabel = L10n.tr("Map", "map.style.accessibilityLabel", fallback: "Map style")
        /// Location: LayersMenu.swift - Purpose: Hint when no offline pack covers viewport
        public static let noOfflineCoverage = L10n.tr("Map", "map.style.noOfflineCoverage", fallback: "No offline map covers this area")
        /// Location: LayersMenu.swift - Purpose: Hint when style requires network
        public static let requiresNetwork = L10n.tr("Map", "map.style.requiresNetwork", fallback: "Requires network connection")
        /// Location: MapStyleSelection.swift - Purpose: Satellite map style option
        public static let satellite = L10n.tr("Map", "map.style.satellite", fallback: "Satellite")
        /// Location: MapStyleSelection.swift - Purpose: Standard map style option
        public static let standard = L10n.tr("Map", "map.style.standard", fallback: "Standard")
        /// Location: MapStyleSelection.swift - Purpose: Topo map style option
        public static let topo = L10n.tr("Map", "map.style.topo", fallback: "Topography")
      }
    }
  }
  public enum Onboarding {
    public enum DeviceScan {
      /// Location: DeviceScanView.swift - Button to add a new device
      public static let addDevice = L10n.tr("Onboarding", "deviceScan.addDevice", fallback: "Add Device")
      /// Location: DeviceScanView.swift - Message shown when device is already paired
      public static let alreadyPaired = L10n.tr("Onboarding", "deviceScan.alreadyPaired", fallback: "Your device is already paired")
      /// Location: DeviceScanView.swift - Button label while connecting
      public static let connecting = L10n.tr("Onboarding", "deviceScan.connecting", fallback: "Connecting...")
      /// Location: DeviceScanView.swift - Button to connect simulator (debug)
      public static let connectSimulator = L10n.tr("Onboarding", "deviceScan.connectSimulator", fallback: "Connect Simulator")
      /// Location: DeviceScanView.swift - Button to connect via WiFi
      public static let connectViaWifi = L10n.tr("Onboarding", "deviceScan.connectViaWifi", fallback: "Connect via WiFi")
      /// Location: DeviceScanView.swift - Button to continue after pairing
      public static let `continue` = L10n.tr("Onboarding", "deviceScan.continue", fallback: "Continue")
      /// Location: DeviceScanView.swift - Button to continue in demo mode
      public static let continueDemo = L10n.tr("Onboarding", "deviceScan.continueDemo", fallback: "Continue in Demo Mode")
      /// Location: DeviceScanView.swift - Button for troubleshooting
      public static let deviceNotAppearing = L10n.tr("Onboarding", "deviceScan.deviceNotAppearing", fallback: "Device not appearing?")
      /// Location: DeviceScanView.swift - Button to retry connection after other-app conflict
      public static let retryConnection = L10n.tr("Onboarding", "deviceScan.retryConnection", fallback: "Retry Connection")
      /// Location: DeviceScanView.swift - Subtitle with pairing instructions
      public static let subtitle = L10n.tr("Onboarding", "deviceScan.subtitle", fallback: "Make sure your MeshCore device is powered on and nearby")
      /// Location: DeviceScanView.swift - Screen title for device pairing
      public static let title = L10n.tr("Onboarding", "deviceScan.title", fallback: "Pair Your Device")
      public enum DemoModeAlert {
        /// Location: DeviceScanView.swift - Alert message for demo mode
        public static let message = L10n.tr("Onboarding", "deviceScan.demoModeAlert.message", fallback: "You can now continue without a device. Toggle demo mode in Settings anytime.")
        /// Location: DeviceScanView.swift - Alert title when demo mode is unlocked
        public static let title = L10n.tr("Onboarding", "deviceScan.demoModeAlert.title", fallback: "Demo Mode Unlocked")
      }
      public enum Error {
        /// Location: DeviceScanView.swift - Authentication failed alert message
        public static let authenticationFailed = L10n.tr("Onboarding", "deviceScan.error.authenticationFailed", fallback: "Authentication failed. The device was added but couldn't connect — this usually means the wrong PIN was entered.")
      }
      public enum Instruction {
        /// Location: DeviceScanView.swift - Instruction step 4
        public static let enterPin = L10n.tr("Onboarding", "deviceScan.instruction.enterPin", fallback: "Enter the PIN when prompted")
        /// Location: DeviceScanView.swift - Instruction step 1
        public static let powerOn = L10n.tr("Onboarding", "deviceScan.instruction.powerOn", fallback: "Power on your MeshCore device")
        /// Location: DeviceScanView.swift - Instruction step 3
        public static let selectDevice = L10n.tr("Onboarding", "deviceScan.instruction.selectDevice", fallback: "Select your device from the list")
        /// Location: DeviceScanView.swift - Instruction step 2
        public static let tapAdd = L10n.tr("Onboarding", "deviceScan.instruction.tapAdd", fallback: "Tap \"Add Device\" below")
      }
    }
    public enum MeshAnimation {
      /// Location: MeshAnimationView.swift - Accessibility label for mesh visualization
      public static let accessibilityLabel = L10n.tr("Onboarding", "meshAnimation.accessibilityLabel", fallback: "Mesh network visualization")
    }
    public enum Permissions {
      /// Location: PermissionsView.swift - Button to allow a permission
      public static let allow = L10n.tr("Onboarding", "permissions.allow", fallback: "Allow")
      /// Location: PermissionsView.swift - Button to go back
      public static let back = L10n.tr("Onboarding", "permissions.back", fallback: "Back")
      /// Location: PermissionsView.swift - Button when all permissions granted
      public static let `continue` = L10n.tr("Onboarding", "permissions.continue", fallback: "Continue")
      /// Location: PermissionsView.swift - Button to open system settings
      public static let openSettings = L10n.tr("Onboarding", "permissions.openSettings", fallback: "Settings")
      /// Location: PermissionsView.swift - Badge shown for optional permissions
      public static let `optional` = L10n.tr("Onboarding", "permissions.optional", fallback: "Optional")
      /// Location: PermissionsView.swift - Button when some permissions skipped
      public static let skipForNow = L10n.tr("Onboarding", "permissions.skipForNow", fallback: "Skip for Now")
      /// Location: PermissionsView.swift - Subtitle encouraging notification permission
      public static let subtitle = L10n.tr("Onboarding", "permissions.subtitle", fallback: "Allow Notifications for the best experience")
      /// Location: PermissionsView.swift - Screen title for permissions
      public static let title = L10n.tr("Onboarding", "permissions.title", fallback: "Permissions")
      public enum Location {
        /// Location: PermissionsView.swift - Permission card description for location
        public static let description = L10n.tr("Onboarding", "permissions.location.description", fallback: "See your location on the map")
        /// Location: PermissionsView.swift - Permission card title for location
        public static let title = L10n.tr("Onboarding", "permissions.location.title", fallback: "Location")
      }
      public enum LocationAlert {
        /// Location: PermissionsView.swift - Alert message explaining denied location permission
        public static let message = L10n.tr("Onboarding", "permissions.locationAlert.message", fallback: "Location permission was previously denied. Please enable it in Settings to share your location with mesh contacts.")
        /// Location: PermissionsView.swift - Alert button to open settings
        public static let openSettings = L10n.tr("Onboarding", "permissions.locationAlert.openSettings", fallback: "Open Settings")
        /// Location: PermissionsView.swift - Alert title for location permission
        public static let title = L10n.tr("Onboarding", "permissions.locationAlert.title", fallback: "Location Permission")
      }
      public enum Notifications {
        /// Location: PermissionsView.swift - Permission card description for notifications
        public static let description = L10n.tr("Onboarding", "permissions.notifications.description", fallback: "Receive alerts for new messages")
        /// Location: PermissionsView.swift - Permission card title for notifications
        public static let title = L10n.tr("Onboarding", "permissions.notifications.title", fallback: "Notifications")
      }
    }
    public enum RadioPreset {
      /// Location: RadioPresetOnboardingView.swift - Button to apply selected preset
      public static let apply = L10n.tr("Onboarding", "radioPreset.apply", fallback: "Apply")
      /// Location: RadioPresetOnboardingView.swift - Button label while applying preset
      public static let applying = L10n.tr("Onboarding", "radioPreset.applying", fallback: "Applying...")
      /// Location: RadioPresetOnboardingView.swift - Button to continue
      public static let `continue` = L10n.tr("Onboarding", "radioPreset.continue", fallback: "Continue")
      /// Location: RadioPresetOnboardingView.swift - Label for custom (non-preset) radio settings
      public static let custom = L10n.tr("Onboarding", "radioPreset.custom", fallback: "Custom")
      /// Location: RadioPresetOnboardingView.swift - Button to skip radio setup
      public static let skip = L10n.tr("Onboarding", "radioPreset.skip", fallback: "Skip")
      /// Location: RadioPresetOnboardingView.swift - Subtitle with instructions and Discord link
      public static let subtitle = L10n.tr("Onboarding", "radioPreset.subtitle", fallback: "You can change these settings at any time in MeshCore One's Settings. If you're not sure which preset to use, ask in the [MeshCore Discord](https://meshcore.co.uk/contact.html)")
      /// Location: RadioPresetOnboardingView.swift - Screen title for radio settings
      public static let title = L10n.tr("Onboarding", "radioPreset.title", fallback: "Radio Settings")
    }
    public enum Troubleshooting {
      /// Location: DeviceScanView.swift - Navigation title for troubleshooting sheet
      public static let title = L10n.tr("Onboarding", "troubleshooting.title", fallback: "Troubleshooting")
      public enum BasicChecks {
        /// Location: DeviceScanView.swift - Section header for basic checks
        public static let header = L10n.tr("Onboarding", "troubleshooting.basicChecks.header", fallback: "Basic Checks")
        /// Location: DeviceScanView.swift - Check to move device closer
        public static let moveCloser = L10n.tr("Onboarding", "troubleshooting.basicChecks.moveCloser", fallback: "Move the device closer to your phone")
        /// Location: DeviceScanView.swift - Check to ensure device is powered on
        public static let powerOn = L10n.tr("Onboarding", "troubleshooting.basicChecks.powerOn", fallback: "Make sure your device is powered on")
        /// Location: DeviceScanView.swift - Check to restart the device
        public static let restart = L10n.tr("Onboarding", "troubleshooting.basicChecks.restart", fallback: "Restart the MeshCore device")
      }
      public enum FactoryReset {
        /// Location: DeviceScanView.swift - Button to clear previous pairing
        public static let clearPairing = L10n.tr("Onboarding", "troubleshooting.factoryReset.clearPairing", fallback: "Clear Previous Pairing")
        /// Location: DeviceScanView.swift - Additional explanation about removal confirmation
        public static let confirmationNote = L10n.tr("Onboarding", "troubleshooting.factoryReset.confirmationNote", fallback: "Tapping below will ask you to confirm removing the old pairing. This is normal — it allows your reset device to appear again.")
        /// Location: DeviceScanView.swift - Explanation about stale pairings
        public static let explanation = L10n.tr("Onboarding", "troubleshooting.factoryReset.explanation", fallback: "If you factory-reset your MeshCore device, iOS may still have the old pairing stored. Clearing this in system Settings allows the device to appear again.")
        /// Location: DeviceScanView.swift - Section header for factory reset help
        public static let header = L10n.tr("Onboarding", "troubleshooting.factoryReset.header", fallback: "Factory Reset Device?")
        /// Location: DeviceScanView.swift - Footer when no pairings found
        public static let noPairings = L10n.tr("Onboarding", "troubleshooting.factoryReset.noPairings", fallback: "No previous pairings found.")
        /// Location: DeviceScanView.swift - Footer showing pairing count - uses stringsdict
        public static func pairingsFound(_ p1: Int) -> String {
          return L10n.tr("Onboarding", "troubleshooting.factoryReset.pairingsFound", p1, fallback: "Found %d previous pairing(s).")
        }
      }
      public enum SystemSettings {
        /// Location: DeviceScanView.swift - Section header for system settings info
        public static let header = L10n.tr("Onboarding", "troubleshooting.systemSettings.header", fallback: "System Settings")
        /// Location: DeviceScanView.swift - Info about managing accessories
        public static let manageAccessories = L10n.tr("Onboarding", "troubleshooting.systemSettings.manageAccessories", fallback: "You can also manage Bluetooth accessories in:")
        /// Location: DeviceScanView.swift - Path to accessories in settings
        public static let path = L10n.tr("Onboarding", "troubleshooting.systemSettings.path", fallback: "Settings → Privacy & Security → Accessories")
      }
    }
    public enum Welcome {
      /// Location: WelcomeView.swift - Button to proceed to next onboarding step
      public static let getStarted = L10n.tr("Onboarding", "welcome.getStarted", fallback: "Get Started")
      /// Location: WelcomeView.swift - Subtitle describing the app
      public static let subtitle = L10n.tr("Onboarding", "welcome.subtitle", fallback: "A MeshCore client for Apple devices")
      /// Location: WelcomeView.swift - App title displayed on welcome screen
      public static let title = L10n.tr("Onboarding", "welcome.title", fallback: "MeshCore One")
      public enum Feature {
        public enum Community {
          /// Location: WelcomeView.swift - Feature description for community network
          public static let description = L10n.tr("Onboarding", "welcome.feature.community.description", fallback: "Network built by users like you")
          /// Location: WelcomeView.swift - Feature title for community network
          public static let title = L10n.tr("Onboarding", "welcome.feature.community.title", fallback: "Community Network")
        }
        public enum MultiHop {
          /// Location: WelcomeView.swift - Feature description for multi-hop routing
          public static let description = L10n.tr("Onboarding", "welcome.feature.multiHop.description", fallback: "Your message finds a path across the mesh")
          /// Location: WelcomeView.swift - Feature title for multi-hop routing
          public static let title = L10n.tr("Onboarding", "welcome.feature.multiHop.title", fallback: "Multi-Hop Routing")
        }
      }
    }
    public enum WifiConnection {
      /// Location: WiFiConnectionSheet.swift - Button to initiate connection
      public static let connect = L10n.tr("Onboarding", "wifiConnection.connect", fallback: "Connect")
      /// Location: WiFiConnectionSheet.swift - Button label while connecting
      public static let connecting = L10n.tr("Onboarding", "wifiConnection.connecting", fallback: "Connecting...")
      /// Location: WiFiConnectionSheet.swift - Navigation title
      public static let title = L10n.tr("Onboarding", "wifiConnection.title", fallback: "Connect via WiFi")
      public enum ConnectionDetails {
        /// Location: WiFiConnectionSheet.swift - Footer explaining connection details
        public static let footer = L10n.tr("Onboarding", "wifiConnection.connectionDetails.footer", fallback: "Enter your MeshCore device's local network address. The default port is 5000.")
        /// Location: WiFiConnectionSheet.swift - Section header for connection details
        public static let header = L10n.tr("Onboarding", "wifiConnection.connectionDetails.header", fallback: "Connection Details")
      }
      public enum Error {
        /// Location: WiFiConnectionSheet.swift - Error message for invalid port
        public static let invalidPort = L10n.tr("Onboarding", "wifiConnection.error.invalidPort", fallback: "Invalid port number")
      }
      public enum IpAddress {
        /// Location: WiFiConnectionSheet.swift - Accessibility label for clear IP button
        public static let clearAccessibility = L10n.tr("Onboarding", "wifiConnection.ipAddress.clearAccessibility", fallback: "Clear IP address")
        /// Location: WiFiConnectionSheet.swift - Placeholder for IP address field
        public static let placeholder = L10n.tr("Onboarding", "wifiConnection.ipAddress.placeholder", fallback: "IP Address")
      }
      public enum Port {
        /// Location: WiFiConnectionSheet.swift - Accessibility label for clear port button
        public static let clearAccessibility = L10n.tr("Onboarding", "wifiConnection.port.clearAccessibility", fallback: "Clear port")
        /// Location: WiFiConnectionSheet.swift - Placeholder for port field
        public static let placeholder = L10n.tr("Onboarding", "wifiConnection.port.placeholder", fallback: "Port")
      }
    }
  }
  public enum RemoteNodes {
    public enum RemoteNodes {
      /// Location: Multiple files - Cancel button
      public static let cancel = L10n.tr("RemoteNodes", "remoteNodes.cancel", fallback: "Cancel")
      /// Location: Multiple files - Done button
      public static let done = L10n.tr("RemoteNodes", "remoteNodes.done", fallback: "Done")
      /// Location: Multiple files - Name label
      public static let name = L10n.tr("RemoteNodes", "remoteNodes.name", fallback: "Name")
      public enum Auth {
        /// Location: NodeAuthenticationSheet.swift - Authentication section header
        public static let authentication = L10n.tr("RemoteNodes", "remoteNodes.auth.authentication", fallback: "Authentication")
        /// Location: NodeAuthenticationSheet.swift - Cancel button
        public static let cancel = L10n.tr("RemoteNodes", "remoteNodes.auth.cancel", fallback: "Cancel")
        /// Location: NodeAuthenticationSheet.swift - Connect button
        public static let connect = L10n.tr("RemoteNodes", "remoteNodes.auth.connect", fallback: "Connect")
        /// Location: NodeAuthenticationSheet.swift - Error accessibility label prefix
        public static func errorPrefix(_ p1: Any) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.auth.errorPrefix", String(describing: p1), fallback: "Error: %@")
        }
        /// Location: NodeAuthenticationSheet.swift - Flood routing toggle
        public static let floodRouting = L10n.tr("RemoteNodes", "remoteNodes.auth.floodRouting", fallback: "Flood Routing")
        /// Location: NodeAuthenticationSheet.swift - Navigation title for room authentication
        public static let joinRoom = L10n.tr("RemoteNodes", "remoteNodes.auth.joinRoom", fallback: "Join Room")
        /// Location: NodeAuthenticationSheet.swift - Navigation title for repeater management
        public static let management = L10n.tr("RemoteNodes", "remoteNodes.auth.management", fallback: "Management")
        /// Location: NodeAuthenticationSheet.swift - Name label
        public static let name = L10n.tr("RemoteNodes", "remoteNodes.auth.name", fallback: "Name")
        /// Location: NodeAuthenticationSheet.swift - Node details section header
        public static let nodeDetails = L10n.tr("RemoteNodes", "remoteNodes.auth.nodeDetails", fallback: "Node Details")
        /// Location: NodeAuthenticationSheet.swift - Path section footer when no route is set
        public static let noRouteFooter = L10n.tr("RemoteNodes", "remoteNodes.auth.noRouteFooter", fallback: "No known route to this node. The network will find a path automatically.")
        /// Location: NodeAuthenticationSheet.swift - Label when no route is set
        public static let noRouteSet = L10n.tr("RemoteNodes", "remoteNodes.auth.noRouteSet", fallback: "No route set")
        /// Location: NodeAuthenticationSheet.swift - Password field placeholder
        public static let password = L10n.tr("RemoteNodes", "remoteNodes.auth.password", fallback: "Password")
        /// Location: NodeAuthenticationSheet.swift - Password too long warning for repeaters
        public static func passwordTooLongRepeaters(_ p1: Int) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.auth.passwordTooLongRepeaters", p1, fallback: "MeshCore repeaters only accept passwords up to %d characters. Extra characters will be ignored.")
        }
        /// Location: NodeAuthenticationSheet.swift - Password too long warning for rooms
        public static func passwordTooLongRooms(_ p1: Int) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.auth.passwordTooLongRooms", p1, fallback: "MeshCore rooms only accept passwords up to %d characters. Extra characters will be ignored.")
        }
        /// Location: NodeAuthenticationSheet.swift - Path section header
        public static let path = L10n.tr("RemoteNodes", "remoteNodes.auth.path", fallback: "Path")
        /// Location: NodeAuthenticationSheet.swift - Path section footer when stored path exists
        public static let pathFooter = L10n.tr("RemoteNodes", "remoteNodes.auth.pathFooter", fallback: "Use the known route to this node, or switch to flood routing to let the network find a path.")
        /// Location: NodeAuthenticationSheet.swift - Remember password toggle
        public static let rememberPassword = L10n.tr("RemoteNodes", "remoteNodes.auth.rememberPassword", fallback: "Remember Password")
        /// Location: NodeAuthenticationSheet.swift - Countdown text showing seconds remaining
        public static func secondsRemaining(_ p1: Int) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.auth.secondsRemaining", p1, fallback: "Up to %d seconds remaining")
        }
        /// Location: NodeAuthenticationSheet.swift - Accessibility announcement for countdown
        public static func secondsRemainingAnnouncement(_ p1: Int) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.auth.secondsRemainingAnnouncement", p1, fallback: "%d seconds remaining")
        }
        /// Location: NodeAuthenticationSheet.swift - Type label
        public static let type = L10n.tr("RemoteNodes", "remoteNodes.auth.type", fallback: "Type")
        /// Location: NodeAuthenticationSheet.swift - Repeater type value
        public static let typeRepeater = L10n.tr("RemoteNodes", "remoteNodes.auth.typeRepeater", fallback: "Repeater")
        /// Location: NodeAuthenticationSheet.swift - Room type value
        public static let typeRoom = L10n.tr("RemoteNodes", "remoteNodes.auth.typeRoom", fallback: "Room")
      }
      public enum History {
        /// Location: NeighborHistoryView.swift - Active status
        public static let active = L10n.tr("RemoteNodes", "remoteNodes.history.active", fallback: "Active")
        /// Location: NodeStatusHistoryView.swift - Time range picker
        public static let all = L10n.tr("RemoteNodes", "remoteNodes.history.all", fallback: "All")
        /// Location: NodeStatusHistoryView.swift - Battery chart title
        public static let battery = L10n.tr("RemoteNodes", "remoteNodes.history.battery", fallback: "Battery")
        /// Location: NodeStatusHistoryView.swift - Empty state message
        public static let checkBack = L10n.tr("RemoteNodes", "remoteNodes.history.checkBack", fallback: "A snapshot is recorded at most every 15 minutes. Check back after your next visit to see trends.")
        /// Location: NeighborHistoryView.swift - Last seen status
        public static func lastSeen(_ p1: Any) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.history.lastSeen", String(describing: p1), fallback: "Last seen %@")
        }
        /// Location: NodeStatusHistoryView.swift - Time range picker
        public static let month = L10n.tr("RemoteNodes", "remoteNodes.history.month", fallback: "1M")
        /// Location: NodeStatusHistoryView.swift - Neighbor count chart title
        public static let neighborCount = L10n.tr("RemoteNodes", "remoteNodes.history.neighborCount", fallback: "Neighbor Count")
        /// Location: NeighborHistoryView.swift - Neighbors section title
        public static let neighbors = L10n.tr("RemoteNodes", "remoteNodes.history.neighbors", fallback: "Neighbors")
        /// Location: TelemetryHistoryOverviewView.swift - Purpose: Neighbors section header
        public static let neighborsSection = L10n.tr("RemoteNodes", "remoteNodes.history.neighborsSection", fallback: "Neighbors")
        /// Location: NeighborRow - New neighbor badge
        public static let new = L10n.tr("RemoteNodes", "remoteNodes.history.new", fallback: "New")
        /// Location: NodeStatusHistoryView.swift - Noise floor chart title
        public static let noiseFloor = L10n.tr("RemoteNodes", "remoteNodes.history.noiseFloor", fallback: "Noise Floor")
        /// Location: TelemetryHistoryOverviewView.swift - Purpose: Empty state when no snapshots exist
        public static let noSnapshotsMessage = L10n.tr("RemoteNodes", "remoteNodes.history.noSnapshotsMessage", fallback: "Connect to this node at least once to see history.")
        /// Location: NeighborRow - Not seen status
        public static let notSeen = L10n.tr("RemoteNodes", "remoteNodes.history.notSeen", fallback: "Not seen")
        /// Location: TelemetryHistoryOverviewView.swift - Purpose: Navigation title
        public static let overviewTitle = L10n.tr("RemoteNodes", "remoteNodes.history.overviewTitle", fallback: "Telemetry History")
        /// Location: NodeStatusHistoryView.swift - Packets received chart title
        public static let packetsReceived = L10n.tr("RemoteNodes", "remoteNodes.history.packetsReceived", fallback: "Packets Received")
        /// Location: NodeStatusHistoryView.swift - Packets sent chart title
        public static let packetsSent = L10n.tr("RemoteNodes", "remoteNodes.history.packetsSent", fallback: "Packets Sent")
        /// Location: TelemetryHistoryOverviewView.swift - Purpose: Radio section header
        public static let radioSection = L10n.tr("RemoteNodes", "remoteNodes.history.radioSection", fallback: "Radio")
        /// Location: NodeStatusHistoryView.swift - Receive errors chart title
        public static let receiveErrors = L10n.tr("RemoteNodes", "remoteNodes.history.receiveErrors", fallback: "Packet Errors Received")
        /// Location: NodeStatusHistoryView.swift - Footer about data retention
        public static let retentionNotice = L10n.tr("RemoteNodes", "remoteNodes.history.retentionNotice", fallback: "History data older than one year is automatically removed.")
        /// Location: NodeStatusHistoryView.swift - RSSI chart title
        public static let rssi = L10n.tr("RemoteNodes", "remoteNodes.history.rssi", fallback: "RSSI")
        /// Location: TelemetryHistoryOverviewView.swift - Purpose: Empty state when section data not captured
        public static func sectionNotCaptured(_ p1: Any) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.history.sectionNotCaptured", String(describing: p1), fallback: "This data is captured when you view the %@ section during a live telemetry session.")
        }
        /// Location: TelemetryHistoryOverviewView.swift - Purpose: Sensors section header
        public static let sensorsSection = L10n.tr("RemoteNodes", "remoteNodes.history.sensorsSection", fallback: "Sensors")
        /// Location: NodeStatusHistoryView.swift - SNR chart title
        public static let snr = L10n.tr("RemoteNodes", "remoteNodes.history.snr", fallback: "SNR")
        /// Location: NodeStatusHistoryView.swift - Time range picker
        public static let threeMonths = L10n.tr("RemoteNodes", "remoteNodes.history.threeMonths", fallback: "3M")
        /// Location: HistoryTimeRangePicker.swift - Accessibility label for time range picker
        public static let timeRange = L10n.tr("RemoteNodes", "remoteNodes.history.timeRange", fallback: "Time Range")
        /// Location: NodeStatusHistoryView.swift - History navigation title
        public static let title = L10n.tr("RemoteNodes", "remoteNodes.history.title", fallback: "History")
        /// Location: RepeaterStatusViewModel.swift - Delta timestamp (date)
        public static func vsDate(_ p1: Any) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.history.vsDate", String(describing: p1), fallback: "Since %@")
        }
        /// Location: RepeaterStatusViewModel.swift - Delta timestamp (hours ago)
        public static func vsHoursAgo(_ p1: Int) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.history.vsHoursAgo", p1, fallback: "Since last visit (%dh ago)")
        }
        /// Location: RepeaterStatusViewModel.swift - Delta timestamp (minutes ago)
        public static func vsMinutesAgo(_ p1: Int) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.history.vsMinutesAgo", p1, fallback: "Since last visit (%dm ago)")
        }
        /// Location: NodeStatusHistoryView.swift - Time range picker
        public static let week = L10n.tr("RemoteNodes", "remoteNodes.history.week", fallback: "1W")
        public enum A11y {
          /// Location: StatusDeltaView.swift - Accessibility: metric decreased
          public static let decreased = L10n.tr("RemoteNodes", "remoteNodes.history.a11y.decreased", fallback: "decreased")
          /// Location: StatusDeltaView.swift - Accessibility: metric degraded
          public static let degraded = L10n.tr("RemoteNodes", "remoteNodes.history.a11y.degraded", fallback: "degraded")
          /// Location: StatusDeltaView.swift - Accessibility: delta description format (quality, direction, value, unit)
          public static func deltaDescription(_ p1: Any, _ p2: Any, _ p3: Any, _ p4: Any) -> String {
            return L10n.tr("RemoteNodes", "remoteNodes.history.a11y.deltaDescription", String(describing: p1), String(describing: p2), String(describing: p3), String(describing: p4), fallback: "%1$@, %2$@ by %3$@%4$@")
          }
          /// Location: StatusDeltaView.swift - Accessibility: metric improved
          public static let improved = L10n.tr("RemoteNodes", "remoteNodes.history.a11y.improved", fallback: "improved")
          /// Location: StatusDeltaView.swift - Accessibility: metric increased
          public static let increased = L10n.tr("RemoteNodes", "remoteNodes.history.a11y.increased", fallback: "increased")
        }
      }
      public enum Room {
        /// Location: RoomConversationView.swift - Activity section header
        public static let activity = L10n.tr("RemoteNodes", "remoteNodes.room.activity", fallback: "Activity")
        /// Location: RoomConversationView.swift - Empty state hint
        public static let beFirstToPost = L10n.tr("RemoteNodes", "remoteNodes.room.beFirstToPost", fallback: "Be the first to post")
        /// Location: RoomConversationView.swift - Connected status
        public static let connected = L10n.tr("RemoteNodes", "remoteNodes.room.connected", fallback: "Connected")
        /// Location: RoomConversationView.swift - Details section header
        public static let details = L10n.tr("RemoteNodes", "remoteNodes.room.details", fallback: "Details")
        /// Location: RoomConversationView.swift - Disconnected status
        public static let disconnected = L10n.tr("RemoteNodes", "remoteNodes.room.disconnected", fallback: "Disconnected")
        /// Location: RoomConversationView.swift - Disconnected banner text
        public static let disconnectedBanner = L10n.tr("RemoteNodes", "remoteNodes.room.disconnectedBanner", fallback: "Disconnected")
        /// Location: RoomConversationView.swift - Accessibility hint for disconnected banner
        public static let disconnectedHint = L10n.tr("RemoteNodes", "remoteNodes.room.disconnectedHint", fallback: "Tap to reconnect")
        /// Location: RoomConversationView.swift - Identification section header
        public static let identification = L10n.tr("RemoteNodes", "remoteNodes.room.identification", fallback: "Identification")
        /// Location: RoomConversationView.swift - Room info sheet title
        public static let infoTitle = L10n.tr("RemoteNodes", "remoteNodes.room.infoTitle", fallback: "Room Info")
        /// Location: RoomConversationView.swift - Last connected label
        public static let lastConnected = L10n.tr("RemoteNodes", "remoteNodes.room.lastConnected", fallback: "Last Connected")
        /// Location: RoomInfoSheet.swift - Management button
        public static let management = L10n.tr("RemoteNodes", "remoteNodes.room.management", fallback: "Management")
        /// Location: RoomConversationView.swift - Empty state title
        public static let noMessagesYet = L10n.tr("RemoteNodes", "remoteNodes.room.noMessagesYet", fallback: "No public messages yet")
        /// Location: RoomConversationView.swift - Permission label
        public static let permission = L10n.tr("RemoteNodes", "remoteNodes.room.permission", fallback: "Permission")
        /// Location: RoomConversationView.swift - Public key label
        public static let publicKey = L10n.tr("RemoteNodes", "remoteNodes.room.publicKey", fallback: "Public Key")
        /// Location: RoomConversationView.swift - Input placeholder
        public static let publicMessage = L10n.tr("RemoteNodes", "remoteNodes.room.publicMessage", fallback: "Public Message")
        /// Location: RoomConversationView.swift - VoiceOver announcement when room reconnects
        public static let reconnected = L10n.tr("RemoteNodes", "remoteNodes.room.reconnected", fallback: "Room reconnected")
        /// Location: RoomConversationView.swift - Status label
        public static let status = L10n.tr("RemoteNodes", "remoteNodes.room.status", fallback: "Status")
        /// Location: RoomInfoSheet.swift - Telemetry button
        public static let telemetry = L10n.tr("RemoteNodes", "remoteNodes.room.telemetry", fallback: "Telemetry")
        /// Location: RoomConversationView.swift - Read-only banner
        public static let viewOnlyBanner = L10n.tr("RemoteNodes", "remoteNodes.room.viewOnlyBanner", fallback: "View only - join as member to post")
        /// Location: RoomConversationView.swift - Hint text for read-only banner
        public static let viewOnlyHint = L10n.tr("RemoteNodes", "remoteNodes.room.viewOnlyHint", fallback: "Tap to log in")
        public enum Message {
          /// Location: RoomMessageBubble.swift - Accessibility hint for retry button
          public static let retryHint = L10n.tr("RemoteNodes", "remoteNodes.room.message.retryHint", fallback: "Double-tap to resend this message")
          public enum Status {
            /// Location: RoomMessageBubble.swift - Accessibility label for delivered message status
            public static let deliveredLabel = L10n.tr("RemoteNodes", "remoteNodes.room.message.status.deliveredLabel", fallback: "Message delivered")
            /// Location: RoomMessageBubble.swift - Accessibility label for failed message status
            public static let failedLabel = L10n.tr("RemoteNodes", "remoteNodes.room.message.status.failedLabel", fallback: "Message failed to send. Double tap retry button to resend.")
            /// Location: RoomMessageBubble.swift - Accessibility label for sending message status
            public static let sendingLabel = L10n.tr("RemoteNodes", "remoteNodes.room.message.status.sendingLabel", fallback: "Sending message")
          }
        }
      }
      public enum RoomSettings {
        /// Location: RoomSettingsView.swift - Allow read-only toggle label
        public static let allowReadOnly = L10n.tr("RemoteNodes", "remoteNodes.roomSettings.allowReadOnly", fallback: "Allow Read-Only")
        /// Location: RoomSettingsView.swift - Allow read-only footer
        public static let allowReadOnlyFooter = L10n.tr("RemoteNodes", "remoteNodes.roomSettings.allowReadOnlyFooter", fallback: "Allow users without a password to connect in read-only mode.")
        /// Location: RoomSettingsView.swift - Apply room settings button
        public static let applyRoomSettings = L10n.tr("RemoteNodes", "remoteNodes.roomSettings.applyRoomSettings", fallback: "Apply Room Settings")
        /// Location: RoomSettingsView.swift - Room behavior section footer
        public static let behaviorFooter = L10n.tr("RemoteNodes", "remoteNodes.roomSettings.behaviorFooter", fallback: "Advertisement intervals and flood hops.")
        /// Location: RoomSettingsView.swift - Clock ahead error
        public static let clockAheadError = L10n.tr("RemoteNodes", "remoteNodes.roomSettings.clockAheadError", fallback: "Room clock is ahead of phone time. If it's too far forward, reboot the room then sync time again.")
        /// Location: RoomSettingsView.swift - Guest password label
        public static let guestPassword = L10n.tr("RemoteNodes", "remoteNodes.roomSettings.guestPassword", fallback: "Guest Password")
        /// Location: RoomSettingsView.swift - Identity section footer
        public static let identityFooter = L10n.tr("RemoteNodes", "remoteNodes.roomSettings.identityFooter", fallback: "Room name and GPS coordinates for map display.")
        /// Location: RoomSettingsView.swift - No service error
        public static let noService = L10n.tr("RemoteNodes", "remoteNodes.roomSettings.noService", fallback: "Room service not available")
        /// Location: RoomSettingsView.swift - Not connected error
        public static let notConnected = L10n.tr("RemoteNodes", "remoteNodes.roomSettings.notConnected", fallback: "Not connected to room")
        /// Location: RoomSettingsView.swift - Radio restart warning
        public static let radioRestartWarning = L10n.tr("RemoteNodes", "remoteNodes.roomSettings.radioRestartWarning", fallback: "Applying these changes will restart the room")
        /// Location: RoomSettingsView.swift - Reboot confirmation title
        public static let rebootConfirmTitle = L10n.tr("RemoteNodes", "remoteNodes.roomSettings.rebootConfirmTitle", fallback: "Reboot Room?")
        /// Location: RoomSettingsView.swift - Reboot confirmation message
        public static let rebootMessage = L10n.tr("RemoteNodes", "remoteNodes.roomSettings.rebootMessage", fallback: "The room will restart and be temporarily unavailable.")
        /// Location: RoomSettingsView.swift - Room settings section footer
        public static let roomSettingsFooter = L10n.tr("RemoteNodes", "remoteNodes.roomSettings.roomSettingsFooter", fallback: "Guest password and read-only access.")
        /// Location: RoomSettingsView.swift - Room settings section header
        public static let roomSettingsSection = L10n.tr("RemoteNodes", "remoteNodes.roomSettings.roomSettingsSection", fallback: "Room Settings")
        /// Location: RoomSettingsView.swift - Navigation title
        public static let title = L10n.tr("RemoteNodes", "remoteNodes.roomSettings.title", fallback: "Room Settings")
      }
      public enum RoomStatus {
        /// Location: RoomStatusView.swift - Posts pushed label
        public static let postsPushed = L10n.tr("RemoteNodes", "remoteNodes.roomStatus.postsPushed", fallback: "Posts Pushed")
        /// Location: RoomStatusView.swift - Posts received label
        public static let postsReceived = L10n.tr("RemoteNodes", "remoteNodes.roomStatus.postsReceived", fallback: "Posts Received")
        /// Location: RoomStatusView.swift - Navigation title
        public static let title = L10n.tr("RemoteNodes", "remoteNodes.roomStatus.title", fallback: "Room Status")
      }
      public enum Settings {
        /// Location: RepeaterSettingsView.swift - Advert interval (0-hop) label
        public static let advertInterval0Hop = L10n.tr("RemoteNodes", "remoteNodes.settings.advertInterval0Hop", fallback: "Advert Interval (0-hop)")
        /// Location: RepeaterSettingsView.swift - Advert interval (flood) label
        public static let advertIntervalFlood = L10n.tr("RemoteNodes", "remoteNodes.settings.advertIntervalFlood", fallback: "Advert Interval (flood)")
        /// Location: RepeaterSettingsViewModel.swift - Advert interval validation error
        public static let advertIntervalValidation = L10n.tr("RemoteNodes", "remoteNodes.settings.advertIntervalValidation", fallback: "Accepts 0 (disabled) or 60-240 min")
        /// Location: RepeaterSettingsViewModel.swift - Advert sent success
        public static let advertSent = L10n.tr("RemoteNodes", "remoteNodes.settings.advertSent", fallback: "Advertisement sent")
        /// Location: RepeaterSettingsView.swift - Apply behavior settings button
        public static let applyBehaviorSettings = L10n.tr("RemoteNodes", "remoteNodes.settings.applyBehaviorSettings", fallback: "Apply Behavior Settings")
        /// Location: RepeaterSettingsView.swift - Apply contact info button
        public static let applyContactInfo = L10n.tr("RemoteNodes", "remoteNodes.settings.applyContactInfo", fallback: "Apply Contact Info")
        /// Location: RepeaterSettingsView.swift - Apply identity settings button
        public static let applyIdentitySettings = L10n.tr("RemoteNodes", "remoteNodes.settings.applyIdentitySettings", fallback: "Apply Identity Settings")
        /// Location: RepeaterSettingsView.swift - Apply radio settings button
        public static let applyRadioSettings = L10n.tr("RemoteNodes", "remoteNodes.settings.applyRadioSettings", fallback: "Apply Radio Settings")
        /// Location: RepeaterSettingsView.swift - Bandwidth accessibility hint
        public static let bandwidthHint = L10n.tr("RemoteNodes", "remoteNodes.settings.bandwidthHint", fallback: "Lower values increase range but decrease speed")
        /// Location: RepeaterSettingsView.swift - Bandwidth label
        public static let bandwidthKHz = L10n.tr("RemoteNodes", "remoteNodes.settings.bandwidthKHz", fallback: "Bandwidth (kHz)")
        /// Location: RepeaterSettingsView.swift - Behavior section title
        public static let behavior = L10n.tr("RemoteNodes", "remoteNodes.settings.behavior", fallback: "Behavior")
        /// Location: RepeaterSettingsView.swift - Behavior section footer
        public static let behaviorFooter = L10n.tr("RemoteNodes", "remoteNodes.settings.behaviorFooter", fallback: "Advertisement intervals, flood hops, and repeater mode.")
        /// Location: RepeaterSettingsView.swift - Change password button
        public static let changePassword = L10n.tr("RemoteNodes", "remoteNodes.settings.changePassword", fallback: "Change Password")
        /// Location: RepeaterSettingsViewModel.swift - Clock ahead error
        public static let clockAheadError = L10n.tr("RemoteNodes", "remoteNodes.settings.clockAheadError", fallback: "Repeater clock is ahead of phone time. If it's too far forward, reboot the repeater then sync time again.")
        /// Location: RepeaterSettingsView.swift - Coding rate label
        public static let codingRate = L10n.tr("RemoteNodes", "remoteNodes.settings.codingRate", fallback: "Coding Rate")
        /// Location: RepeaterSettingsView.swift - Coding rate accessibility hint
        public static let codingRateHint = L10n.tr("RemoteNodes", "remoteNodes.settings.codingRateHint", fallback: "Higher values add error correction but decrease speed")
        /// Location: RepeaterSettingsView.swift - Confirm password placeholder
        public static let confirmPassword = L10n.tr("RemoteNodes", "remoteNodes.settings.confirmPassword", fallback: "Confirm Password")
        /// Location: RepeaterSettingsView.swift - Contact info section title
        public static let contactInfo = L10n.tr("RemoteNodes", "remoteNodes.settings.contactInfo", fallback: "Contact Info")
        /// Location: RepeaterSettingsView.swift - Contact info character count format
        public static func contactInfoCharCount(_ p1: Int) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.settings.contactInfoCharCount", p1, fallback: "%d/119")
        }
        /// Location: RepeaterSettingsView.swift - Contact info footer
        public static let contactInfoFooter = L10n.tr("RemoteNodes", "remoteNodes.settings.contactInfoFooter", fallback: "Public contact details visible to other nodes. Use line breaks to separate fields.")
        /// Location: RepeaterSettingsView.swift - Contact info placeholder
        public static let contactInfoPlaceholder = L10n.tr("RemoteNodes", "remoteNodes.settings.contactInfoPlaceholder", fallback: "Frequency, operator name, website...")
        /// Location: RepeaterSettingsView.swift - dBm placeholder
        public static let dbm = L10n.tr("RemoteNodes", "remoteNodes.settings.dbm", fallback: "dBm")
        /// Location: RepeaterSettingsView.swift - Device actions section header
        public static let deviceActions = L10n.tr("RemoteNodes", "remoteNodes.settings.deviceActions", fallback: "Device Actions")
        /// Location: RepeaterSettingsView.swift - Device info section title
        public static let deviceInfo = L10n.tr("RemoteNodes", "remoteNodes.settings.deviceInfo", fallback: "Device Info")
        /// Location: RepeaterSettingsView.swift - Device info section footer
        public static let deviceInfoFooter = L10n.tr("RemoteNodes", "remoteNodes.settings.deviceInfoFooter", fallback: "Firmware version and device clock.")
        /// Location: RepeaterSettingsView.swift - Device time label
        public static let deviceTime = L10n.tr("RemoteNodes", "remoteNodes.settings.deviceTime", fallback: "Device Time")
        /// Location: RepeaterSettingsView.swift - Done button (used in multiple places)
        public static let done = L10n.tr("RemoteNodes", "remoteNodes.settings.done", fallback: "Done")
        /// Location: RepeaterSettingsView.swift - Failed to load placeholder
        public static let failedToLoad = L10n.tr("RemoteNodes", "remoteNodes.settings.failedToLoad", fallback: "Failed to load")
        /// Location: RepeaterSettingsView.swift - Firmware label
        public static let firmware = L10n.tr("RemoteNodes", "remoteNodes.settings.firmware", fallback: "Firmware")
        /// Location: RepeaterSettingsViewModel.swift - Flood interval validation error
        public static let floodIntervalValidation = L10n.tr("RemoteNodes", "remoteNodes.settings.floodIntervalValidation", fallback: "Accepts 0 (off) or 3-168 hours")
        /// Location: RepeaterSettingsViewModel.swift - Flood max hops validation error
        public static let floodMaxValidation = L10n.tr("RemoteNodes", "remoteNodes.settings.floodMaxValidation", fallback: "Accepts 0-64 hops")
        /// Location: RepeaterSettingsView.swift - Frequency label
        public static let frequencyMHz = L10n.tr("RemoteNodes", "remoteNodes.settings.frequencyMHz", fallback: "Frequency (MHz)")
        /// Location: RepeaterSettingsView.swift - Hops unit
        public static let hops = L10n.tr("RemoteNodes", "remoteNodes.settings.hops", fallback: "hops")
        /// Location: RepeaterSettingsView.swift - Hours unit
        public static let hrs = L10n.tr("RemoteNodes", "remoteNodes.settings.hrs", fallback: "hrs")
        /// Location: RepeaterSettingsView.swift - Identity section footer
        public static let identityFooter = L10n.tr("RemoteNodes", "remoteNodes.settings.identityFooter", fallback: "Repeater name and GPS coordinates for map display.")
        /// Location: RepeaterSettingsView.swift - Identity & location section title
        public static let identityLocation = L10n.tr("RemoteNodes", "remoteNodes.settings.identityLocation", fallback: "Identity & Location")
        /// Location: RepeaterSettingsView.swift - Lat placeholder
        public static let lat = L10n.tr("RemoteNodes", "remoteNodes.settings.lat", fallback: "Lat")
        /// Location: RepeaterSettingsView.swift - Latitude label
        public static let latitude = L10n.tr("RemoteNodes", "remoteNodes.settings.latitude", fallback: "Latitude")
        /// Location: RepeaterSettingsView.swift - Loading placeholder
        public static let loading = L10n.tr("RemoteNodes", "remoteNodes.settings.loading", fallback: "Loading...")
        /// Location: RepeaterSettingsView.swift - Lon placeholder
        public static let lon = L10n.tr("RemoteNodes", "remoteNodes.settings.lon", fallback: "Lon")
        /// Location: RepeaterSettingsView.swift - Longitude label
        public static let longitude = L10n.tr("RemoteNodes", "remoteNodes.settings.longitude", fallback: "Longitude")
        /// Location: RepeaterSettingsView.swift - Max flood hops label
        public static let maxFloodHops = L10n.tr("RemoteNodes", "remoteNodes.settings.maxFloodHops", fallback: "Max Flood Hops")
        /// Location: RepeaterSettingsView.swift - MHz placeholder
        public static let mhz = L10n.tr("RemoteNodes", "remoteNodes.settings.mhz", fallback: "MHz")
        /// Location: RepeaterSettingsView.swift - Minutes unit
        public static let min = L10n.tr("RemoteNodes", "remoteNodes.settings.min", fallback: "min")
        /// Location: RepeaterSettingsView.swift - New password placeholder
        public static let newPassword = L10n.tr("RemoteNodes", "remoteNodes.settings.newPassword", fallback: "New Password")
        /// Location: NodeSettingsHelper.swift - No service error
        public static let noService = L10n.tr("RemoteNodes", "remoteNodes.settings.noService", fallback: "Service not available")
        /// Location: RepeaterSettingsViewModel.swift - Not connected error
        public static let notConnected = L10n.tr("RemoteNodes", "remoteNodes.settings.notConnected", fallback: "Not connected to repeater")
        /// Location: RepeaterSettingsView.swift - OK button
        public static let ok = L10n.tr("RemoteNodes", "remoteNodes.settings.ok", fallback: "OK")
        /// Location: RepeaterSettingsViewModel.swift - Password changed success
        public static let passwordChangedSuccess = L10n.tr("RemoteNodes", "remoteNodes.settings.passwordChangedSuccess", fallback: "Password changed successfully")
        /// Location: RepeaterSettingsViewModel.swift - Password change failure
        public static let passwordChangeFailed = L10n.tr("RemoteNodes", "remoteNodes.settings.passwordChangeFailed", fallback: "Failed to change password")
        /// Location: RepeaterSettingsViewModel.swift - Empty password error
        public static let passwordEmpty = L10n.tr("RemoteNodes", "remoteNodes.settings.passwordEmpty", fallback: "Password cannot be empty")
        /// Location: RepeaterSettingsViewModel.swift - Password mismatch error
        public static let passwordMismatch = L10n.tr("RemoteNodes", "remoteNodes.settings.passwordMismatch", fallback: "Passwords do not match")
        /// Location: RepeaterSettingsView.swift - Pick on map button
        public static let pickOnMap = L10n.tr("RemoteNodes", "remoteNodes.settings.pickOnMap", fallback: "Pick on Map")
        /// Location: RepeaterSettingsViewModel.swift - Radio applied success
        public static let radioAppliedSuccess = L10n.tr("RemoteNodes", "remoteNodes.settings.radioAppliedSuccess", fallback: "Radio settings applied. Restart device to take effect.")
        /// Location: RepeaterSettingsViewModel.swift - Radio apply partial failure
        public static let radioApplyFailed = L10n.tr("RemoteNodes", "remoteNodes.settings.radioApplyFailed", fallback: "Some radio settings failed to apply")
        /// Location: RepeaterSettingsView.swift - Radio settings section footer
        public static let radioFooter = L10n.tr("RemoteNodes", "remoteNodes.settings.radioFooter", fallback: "Frequency, bandwidth, spreading factor, coding rate, and transmit power.")
        /// Location: RepeaterSettingsViewModel.swift - Radio not loaded error
        public static let radioNotLoaded = L10n.tr("RemoteNodes", "remoteNodes.settings.radioNotLoaded", fallback: "Radio settings not loaded")
        /// Location: RepeaterSettingsView.swift - Radio parameters section title
        public static let radioParameters = L10n.tr("RemoteNodes", "remoteNodes.settings.radioParameters", fallback: "Radio Parameters")
        /// Location: RepeaterSettingsView.swift - Radio restart warning
        public static let radioRestartWarning = L10n.tr("RemoteNodes", "remoteNodes.settings.radioRestartWarning", fallback: "Applying these changes will restart the repeater")
        /// Location: RepeaterSettingsView.swift - Reboot confirmation dialog button
        public static let reboot = L10n.tr("RemoteNodes", "remoteNodes.settings.reboot", fallback: "Reboot")
        /// Location: RepeaterSettingsView.swift - Reboot confirmation dialog title
        public static let rebootConfirmTitle = L10n.tr("RemoteNodes", "remoteNodes.settings.rebootConfirmTitle", fallback: "Reboot Repeater?")
        /// Location: RepeaterSettingsView.swift - Reboot device button
        public static let rebootDevice = L10n.tr("RemoteNodes", "remoteNodes.settings.rebootDevice", fallback: "Reboot Device")
        /// Location: RepeaterSettingsView.swift - Reboot confirmation message
        public static let rebootMessage = L10n.tr("RemoteNodes", "remoteNodes.settings.rebootMessage", fallback: "The repeater will restart and be temporarily unavailable.")
        /// Location: RepeaterSettingsViewModel.swift - Reboot sent success
        public static let rebootSent = L10n.tr("RemoteNodes", "remoteNodes.settings.rebootSent", fallback: "Reboot command sent")
        /// Location: RepeaterSettingsView.swift - Regions section title
        public static let regions = L10n.tr("RemoteNodes", "remoteNodes.settings.regions", fallback: "Regions")
        /// Location: RepeaterSettingsView.swift - Regions section footer
        public static let regionsFooter = L10n.tr("RemoteNodes", "remoteNodes.settings.regionsFooter", fallback: "Save to Repeater to keep changes across restarts.")
        /// Location: RepeaterSettingsView.swift - Repeater mode toggle
        public static let repeaterMode = L10n.tr("RemoteNodes", "remoteNodes.settings.repeaterMode", fallback: "Repeater Mode")
        /// Location: RepeaterSettingsView.swift - Security section title
        public static let security = L10n.tr("RemoteNodes", "remoteNodes.settings.security", fallback: "Security")
        /// Location: RepeaterSettingsView.swift - Security footer text
        public static let securityFooter = L10n.tr("RemoteNodes", "remoteNodes.settings.securityFooter", fallback: "Change the admin authentication password.")
        /// Location: RepeaterSettingsView.swift - Send advert button
        public static let sendAdvert = L10n.tr("RemoteNodes", "remoteNodes.settings.sendAdvert", fallback: "Send Advert")
        /// Location: RepeaterSettingsView.swift - Default success message
        public static let settingsApplied = L10n.tr("RemoteNodes", "remoteNodes.settings.settingsApplied", fallback: "Settings applied")
        /// Location: RepeaterSettingsViewModel.swift - General apply failure
        public static let someSettingsFailedToApply = L10n.tr("RemoteNodes", "remoteNodes.settings.someSettingsFailedToApply", fallback: "Some settings failed to apply")
        /// Location: RepeaterSettingsView.swift - Spreading factor label
        public static let spreadingFactor = L10n.tr("RemoteNodes", "remoteNodes.settings.spreadingFactor", fallback: "Spreading Factor")
        /// Location: RepeaterSettingsView.swift - Spreading factor accessibility hint
        public static let spreadingFactorHint = L10n.tr("RemoteNodes", "remoteNodes.settings.spreadingFactorHint", fallback: "Higher values increase range but decrease speed")
        /// Location: RepeaterSettingsView.swift - Success alert title
        public static let success = L10n.tr("RemoteNodes", "remoteNodes.settings.success", fallback: "Success")
        /// Location: RepeaterSettingsView.swift - Sync time button
        public static let syncTime = L10n.tr("RemoteNodes", "remoteNodes.settings.syncTime", fallback: "Sync Time")
        /// Location: RepeaterSettingsViewModel.swift - Sync time failure
        public static let syncTimeFailed = L10n.tr("RemoteNodes", "remoteNodes.settings.syncTimeFailed", fallback: "Failed to sync time")
        /// Location: RepeaterSettingsViewModel.swift - Timeout error
        public static let timeout = L10n.tr("RemoteNodes", "remoteNodes.settings.timeout", fallback: "Command timed out")
        /// Location: RepeaterSettingsViewModel.swift - Time synced success
        public static let timeSynced = L10n.tr("RemoteNodes", "remoteNodes.settings.timeSynced", fallback: "Time synced")
        /// Location: RepeaterSettingsView.swift - Navigation title
        public static let title = L10n.tr("RemoteNodes", "remoteNodes.settings.title", fallback: "Repeater Settings")
        /// Location: RepeaterSettingsView.swift - TX power label
        public static let txPowerDbm = L10n.tr("RemoteNodes", "remoteNodes.settings.txPowerDbm", fallback: "TX Power (dBm)")
        /// Location: RepeaterSettingsViewModel.swift - Unexpected response error
        public static func unexpectedResponse(_ p1: Any) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.settings.unexpectedResponse", String(describing: p1), fallback: "Unexpected response: %@")
        }
        public enum Accessibility {
          /// Location: RepeaterSettingsView.swift - Accessibility label for bandwidth picker options - %@ is formatted bandwidth
          public static func bandwidthLabel(_ p1: Any) -> String {
            return L10n.tr("RemoteNodes", "remoteNodes.settings.accessibility.bandwidthLabel", String(describing: p1), fallback: "%@ kilohertz")
          }
          /// Location: RepeaterSettingsView.swift - Accessibility label for coding rate picker options - %d is the rate
          public static func codingRateLabel(_ p1: Int) -> String {
            return L10n.tr("RemoteNodes", "remoteNodes.settings.accessibility.codingRateLabel", p1, fallback: "Coding rate %d")
          }
          /// Location: RepeaterSettingsView.swift - Accessibility label for spreading factor picker options - %d is the factor
          public static func spreadingFactorLabel(_ p1: Int) -> String {
            return L10n.tr("RemoteNodes", "remoteNodes.settings.accessibility.spreadingFactorLabel", p1, fallback: "Spreading factor %d")
          }
        }
        public enum Regions {
          /// Location: RepeaterSettingsViewModel.swift - Region add failure
          public static let addFailed = L10n.tr("RemoteNodes", "remoteNodes.settings.regions.addFailed", fallback: "Failed to add region")
          /// Location: RepeaterSettingsView.swift - Add region button
          public static let addRegion = L10n.tr("RemoteNodes", "remoteNodes.settings.regions.addRegion", fallback: "Add Region")
          /// Location: RepeaterSettingsView.swift - Add region alert title
          public static let addRegionTitle = L10n.tr("RemoteNodes", "remoteNodes.settings.regions.addRegionTitle", fallback: "Add Region")
          /// Location: RepeaterSettingsView.swift - Toggle label for flood allow per region
          public static let allowFlood = L10n.tr("RemoteNodes", "remoteNodes.settings.regions.allowFlood", fallback: "Allow Flood Traffic")
          /// Location: RepeaterSettingsView.swift - Wildcard region display name
          public static let allTraffic = L10n.tr("RemoteNodes", "remoteNodes.settings.regions.allTraffic", fallback: "All Traffic")
          /// Location: RepeaterSettingsView.swift - Wildcard with asterisk display
          public static let allTrafficWildcard = L10n.tr("RemoteNodes", "remoteNodes.settings.regions.allTrafficWildcard", fallback: "* (All Traffic)")
          /// Location: RepeaterSettingsViewModel.swift - No regions on device
          public static let empty = L10n.tr("RemoteNodes", "remoteNodes.settings.regions.empty", fallback: "No regions configured")
          /// Location: RepeaterSettingsView.swift - Accessibility hint for flood toggle
          public static let floodToggleHint = L10n.tr("RemoteNodes", "remoteNodes.settings.regions.floodToggleHint", fallback: "When off, flood packets from this region are dropped")
          /// Location: RepeaterSettingsView.swift - Home region picker label
          public static let homeRegion = L10n.tr("RemoteNodes", "remoteNodes.settings.regions.homeRegion", fallback: "Home Region")
          /// Location: RepeaterSettingsView.swift - No home region set
          public static let noHome = L10n.tr("RemoteNodes", "remoteNodes.settings.regions.noHome", fallback: "None")
          /// Location: RepeaterSettingsViewModel.swift - Region has children error
          public static let notEmpty = L10n.tr("RemoteNodes", "remoteNodes.settings.regions.notEmpty", fallback: "Remove child regions first")
          /// Location: RepeaterSettingsView.swift - Region name placeholder
          public static let regionName = L10n.tr("RemoteNodes", "remoteNodes.settings.regions.regionName", fallback: "Region name")
          /// Location: RepeaterSettingsViewModel.swift - Region remove failure
          public static let removeFailed = L10n.tr("RemoteNodes", "remoteNodes.settings.regions.removeFailed", fallback: "Failed to remove region")
          /// Location: RepeaterSettingsViewModel.swift - Region save failure
          public static let saveFailed = L10n.tr("RemoteNodes", "remoteNodes.settings.regions.saveFailed", fallback: "Failed to save regions")
          /// Location: RepeaterSettingsViewModel.swift - Region save success
          public static let saveSuccess = L10n.tr("RemoteNodes", "remoteNodes.settings.regions.saveSuccess", fallback: "Regions saved to device")
          /// Location: RepeaterSettingsView.swift - Save regions to device button
          public static let saveToDevice = L10n.tr("RemoteNodes", "remoteNodes.settings.regions.saveToDevice", fallback: "Save to Repeater")
          /// Location: RepeaterSettingsViewModel.swift - Region not found error
          public static let unknownRegion = L10n.tr("RemoteNodes", "remoteNodes.settings.regions.unknownRegion", fallback: "Unknown region")
        }
      }
      public enum Status {
        /// Location: SharedNodeViews.swift - Airtime label
        public static let airtime = L10n.tr("RemoteNodes", "remoteNodes.status.airtime", fallback: "Airtime")
        /// Location: RepeaterStatusView.swift - Battery label
        public static let battery = L10n.tr("RemoteNodes", "remoteNodes.status.battery", fallback: "Battery")
        /// Location: RepeaterStatusView.swift - Battery curve section label
        public static let batteryCurve = L10n.tr("RemoteNodes", "remoteNodes.status.batteryCurve", fallback: "Battery Curve")
        /// Location: RepeaterStatusView.swift - Battery curve section footer
        public static let batteryCurveFooter = L10n.tr("RemoteNodes", "remoteNodes.status.batteryCurveFooter", fallback: "Voltage-to-percentage mapping used for battery level estimation.")
        /// Location: RepeaterStatusView.swift - Channel header for grouped telemetry
        public static func channel(_ p1: Int) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.status.channel", p1, fallback: "Channel %d")
        }
        /// Location: RepeaterStatusView.swift - Discovery in progress with countdown
        public static func discoveringSeconds(_ p1: Int) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.status.discoveringSeconds", p1, fallback: "Discovering... %ds")
        }
        /// Location: RepeaterStatusView.swift - Discover neighbours button label
        public static let discoverNeighbors = L10n.tr("RemoteNodes", "remoteNodes.status.discoverNeighbors", fallback: "Discover Neighbours")
        /// Location: RepeaterStatusView.swift - Guest mode badge in header
        public static let guestMode = L10n.tr("RemoteNodes", "remoteNodes.status.guestMode", fallback: "Guest Mode")
        /// Location: RepeaterStatusView.swift - Hours ago format
        public static func hoursAgo(_ p1: Int) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.status.hoursAgo", p1, fallback: "%dh ago")
        }
        /// Location: RepeaterStatusView.swift - Last RSSI label
        public static let lastRssi = L10n.tr("RemoteNodes", "remoteNodes.status.lastRssi", fallback: "Last RSSI")
        /// Location: RepeaterStatusView.swift - Last SNR label
        public static let lastSnr = L10n.tr("RemoteNodes", "remoteNodes.status.lastSnr", fallback: "Last SNR")
        /// Location: RepeaterStatusView.swift - Minutes ago format
        public static func minutesAgo(_ p1: Int) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.status.minutesAgo", p1, fallback: "%dm ago")
        }
        /// Location: RepeaterStatusView.swift - Neighbors section label
        public static let neighbors = L10n.tr("RemoteNodes", "remoteNodes.status.neighbors", fallback: "Neighbors")
        /// Location: RepeaterStatusView.swift - Neighbors section footer
        public static let neighborsFooter = L10n.tr("RemoteNodes", "remoteNodes.status.neighborsFooter", fallback: "Other nodes discovered by this repeater and their signal quality.")
        /// Location: RepeaterStatusView.swift - Noise floor label
        public static let noiseFloor = L10n.tr("RemoteNodes", "remoteNodes.status.noiseFloor", fallback: "Noise Floor")
        /// Location: RepeaterStatusView.swift - No neighbors empty state
        public static let noNeighbors = L10n.tr("RemoteNodes", "remoteNodes.status.noNeighbors", fallback: "No neighbors discovered")
        /// Location: RepeaterStatusView.swift - No owner info empty state
        public static let noOwnerInfo = L10n.tr("RemoteNodes", "remoteNodes.status.noOwnerInfo", fallback: "No contact info")
        /// Location: RepeaterStatusView.swift - No sensor data empty state
        public static let noSensorData = L10n.tr("RemoteNodes", "remoteNodes.status.noSensorData", fallback: "No sensor data")
        /// Location: RepeaterStatusView.swift - No telemetry data empty state
        public static let noTelemetryData = L10n.tr("RemoteNodes", "remoteNodes.status.noTelemetryData", fallback: "No telemetry data")
        /// Location: RepeaterStatusViewModel.swift - Failed to load OCV settings
        public static let ocvLoadFailed = L10n.tr("RemoteNodes", "remoteNodes.status.ocvLoadFailed", fallback: "Failed to load battery curve settings")
        /// Location: RepeaterStatusViewModel.swift - OCV save failed
        public static func ocvSaveFailed(_ p1: Any) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.status.ocvSaveFailed", String(describing: p1), fallback: "Failed to save: %@")
        }
        /// Location: RepeaterStatusViewModel.swift - Cannot save OCV error
        public static let ocvSaveNoContact = L10n.tr("RemoteNodes", "remoteNodes.status.ocvSaveNoContact", fallback: "Cannot save: contact not found")
        /// Location: RepeaterStatusView.swift - Owner info section label
        public static let ownerInfo = L10n.tr("RemoteNodes", "remoteNodes.status.ownerInfo", fallback: "Contact Info")
        /// Location: RepeaterStatusView.swift - Packets received label
        public static let packetsReceived = L10n.tr("RemoteNodes", "remoteNodes.status.packetsReceived", fallback: "Packets Received")
        /// Location: RepeaterStatusView.swift - Packets sent label
        public static let packetsSent = L10n.tr("RemoteNodes", "remoteNodes.status.packetsSent", fallback: "Packets Sent")
        /// Location: RepeaterStatusView.swift - Receive errors label
        public static let receiveErrors = L10n.tr("RemoteNodes", "remoteNodes.status.receiveErrors", fallback: "Packet Errors Received")
        /// Location: RepeaterStatusView.swift, RoomStatusView.swift - Refresh button accessibility label
        public static let refresh = L10n.tr("RemoteNodes", "remoteNodes.status.refresh", fallback: "Refresh")
        /// Location: RepeaterStatusViewModel.swift - Request timed out
        public static let requestTimedOut = L10n.tr("RemoteNodes", "remoteNodes.status.requestTimedOut", fallback: "Request timed out")
        /// Location: RepeaterStatusView.swift - Seconds ago format
        public static func secondsAgo(_ p1: Int) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.status.secondsAgo", p1, fallback: "%ds ago")
        }
        /// Location: RepeaterStatusView.swift - SNR display format
        public static func snrFormat(_ p1: Any) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.status.snrFormat", String(describing: p1), fallback: "SNR %@dB")
        }
        /// Location: RepeaterStatusView.swift - Status section header
        public static let statusSection = L10n.tr("RemoteNodes", "remoteNodes.status.statusSection", fallback: "Status")
        /// Location: RepeaterStatusView.swift - Telemetry section label
        public static let telemetry = L10n.tr("RemoteNodes", "remoteNodes.status.telemetry", fallback: "Telemetry")
        /// Location: RepeaterStatusView.swift - Telemetry section footer
        public static let telemetryFooter = L10n.tr("RemoteNodes", "remoteNodes.status.telemetryFooter", fallback: "Sensor readings such as temperature, humidity, and voltage.")
        /// Timed out or contact has telemetry disabled
        public static let telemetryTimedOut = L10n.tr("RemoteNodes", "remoteNodes.status.telemetryTimedOut", fallback: "Timed out or contact has telemetry disabled")
        /// Location: RepeaterStatusView.swift - Navigation title
        public static let title = L10n.tr("RemoteNodes", "remoteNodes.status.title", fallback: "Repeater Status")
        /// Location: RepeaterStatusView.swift - Unknown neighbor name
        public static let unknown = L10n.tr("RemoteNodes", "remoteNodes.status.unknown", fallback: "Unknown")
        /// Location: RepeaterStatusView.swift - Uptime label
        public static let uptime = L10n.tr("RemoteNodes", "remoteNodes.status.uptime", fallback: "Uptime")
        /// Location: RepeaterStatusViewModel.swift - Uptime 1 day format
        public static func uptime1Day(_ p1: Int, _ p2: Int) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.status.uptime1Day", p1, p2, fallback: "1 day %dh %dm")
        }
        /// Location: RepeaterStatusViewModel.swift - Uptime multiple days format
        public static func uptimeDays(_ p1: Int, _ p2: Int, _ p3: Int) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.status.uptimeDays", p1, p2, p3, fallback: "%d days %dh %dm")
        }
        /// Location: RepeaterStatusViewModel.swift - Uptime hours format
        public static func uptimeHours(_ p1: Int, _ p2: Int) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.status.uptimeHours", p1, p2, fallback: "%dh %dm")
        }
        /// Location: RepeaterStatusViewModel.swift - Uptime minutes format
        public static func uptimeMinutes(_ p1: Int) -> String {
          return L10n.tr("RemoteNodes", "remoteNodes.status.uptimeMinutes", p1, fallback: "%dm")
        }
      }
    }
  }
  public enum Settings {
    /// Build number display with build number
    public static func build(_ p1: Any) -> String {
      return L10n.tr("Settings", "build", String(describing: p1), fallback: "Build %@")
    }
    /// Placeholder shown in split view detail when no setting is selected
    public static let selectSetting = L10n.tr("Settings", "selectSetting", fallback: "Select a setting")
    /// Navigation title for the main settings screen
    public static let title = L10n.tr("Settings", "title", fallback: "Settings")
    /// Version display prefix with version number
    public static func version(_ p1: Any) -> String {
      return L10n.tr("Settings", "version", String(describing: p1), fallback: "v%@")
    }
    public enum About {
      /// Link to GitHub repository
      public static let github = L10n.tr("Settings", "about.github", fallback: "GitHub")
      /// Section header for about links
      public static let header = L10n.tr("Settings", "about.header", fallback: "About")
      /// Link to MeshCore online map
      public static let onlineMap = L10n.tr("Settings", "about.onlineMap", fallback: "MeshCore Online Map")
      /// Link to MeshCore website
      public static let website = L10n.tr("Settings", "about.website", fallback: "MeshCore Website")
    }
    public enum AdvancedRadio {
      /// Button to apply radio settings
      public static let apply = L10n.tr("Settings", "advancedRadio.apply", fallback: "Apply Radio Settings")
      /// Label for bandwidth picker
      public static let bandwidth = L10n.tr("Settings", "advancedRadio.bandwidth", fallback: "Bandwidth (kHz)")
      /// Label for coding rate picker
      public static let codingRate = L10n.tr("Settings", "advancedRadio.codingRate", fallback: "Coding Rate")
      /// Footer warning about incorrect radio settings
      public static let footer = L10n.tr("Settings", "advancedRadio.footer", fallback: "Warning: Incorrect settings may prevent communication with other mesh devices.")
      /// Label for frequency input
      public static let frequency = L10n.tr("Settings", "advancedRadio.frequency", fallback: "Frequency (MHz)")
      /// Placeholder for frequency text field
      public static let frequencyPlaceholder = L10n.tr("Settings", "advancedRadio.frequencyPlaceholder", fallback: "MHz")
      /// Section header for radio configuration
      public static let header = L10n.tr("Settings", "advancedRadio.header", fallback: "Radio Configuration")
      /// Error message for invalid input
      public static let invalidInput = L10n.tr("Settings", "advancedRadio.invalidInput", fallback: "Invalid input values or device not connected")
      /// Toggle label for repeat mode in advanced radio
      public static let repeatMode = L10n.tr("Settings", "advancedRadio.repeatMode", fallback: "Repeat Mode")
      /// Label for spreading factor picker
      public static let spreadingFactor = L10n.tr("Settings", "advancedRadio.spreadingFactor", fallback: "Spreading Factor")
      /// Label for TX power input
      public static let txPower = L10n.tr("Settings", "advancedRadio.txPower", fallback: "TX Power (dBm)")
      /// Placeholder for TX power text field
      public static let txPowerPlaceholder = L10n.tr("Settings", "advancedRadio.txPowerPlaceholder", fallback: "dBm")
      public enum Accessibility {
        /// Accessibility hint for bandwidth picker
        public static let bandwidthHint = L10n.tr("Settings", "advancedRadio.accessibility.bandwidthHint", fallback: "Lower values increase range but decrease speed")
        /// Accessibility label for bandwidth picker options - %@ is formatted bandwidth value
        public static func bandwidthLabel(_ p1: Any) -> String {
          return L10n.tr("Settings", "advancedRadio.accessibility.bandwidthLabel", String(describing: p1), fallback: "%@ kilohertz")
        }
        /// Accessibility hint for coding rate picker
        public static let codingRateHint = L10n.tr("Settings", "advancedRadio.accessibility.codingRateHint", fallback: "Higher values add error correction but decrease speed")
        /// Accessibility label for coding rate picker options - %d is the rate value
        public static func codingRateLabel(_ p1: Int) -> String {
          return L10n.tr("Settings", "advancedRadio.accessibility.codingRateLabel", p1, fallback: "Coding rate %d")
        }
        /// Accessibility hint for spreading factor picker
        public static let spreadingFactorHint = L10n.tr("Settings", "advancedRadio.accessibility.spreadingFactorHint", fallback: "Higher values increase range but decrease speed")
        /// Accessibility label for spreading factor picker options - %d is the factor value
        public static func spreadingFactorLabel(_ p1: Int) -> String {
          return L10n.tr("Settings", "advancedRadio.accessibility.spreadingFactorLabel", p1, fallback: "Spreading factor %d")
        }
      }
      public enum RepeatMode {
        /// Footer explaining repeat mode in advanced radio
        public static let footer = L10n.tr("Settings", "advancedRadio.repeatMode.footer", fallback: "Creates a local repeater on a dedicated frequency. Useful for hiking and remote areas. Valid frequencies: 433, 869, 918 MHz.")
      }
    }
    public enum AdvancedSettings {
      /// Footer text for the advanced settings row
      public static let footer = L10n.tr("Settings", "advancedSettings.footer", fallback: "Radio tuning, telemetry, contact settings, and device management")
      /// Label for the advanced settings navigation row
      public static let title = L10n.tr("Settings", "advancedSettings.title", fallback: "Advanced Settings")
    }
    public enum Alert {
      public enum Error {
        /// Alert title for generic errors
        public static let title = L10n.tr("Settings", "alert.error.title", fallback: "Error")
      }
      public enum Retry {
        /// Alert title for connection errors
        public static let connectionError = L10n.tr("Settings", "alert.retry.connectionError", fallback: "Connection Error")
        /// Alert message when max retries exceeded
        public static let ensureConnected = L10n.tr("Settings", "alert.retry.ensureConnected", fallback: "Please ensure your device is connected.")
        /// Fallback message for retry alerts when error description is unavailable
        public static let fallbackMessage = L10n.tr("Settings", "alert.retry.fallbackMessage", fallback: "Please ensure device is connected and try again.")
        /// Button to retry the operation
        public static let retry = L10n.tr("Settings", "alert.retry.retry", fallback: "Retry")
        /// Alert title when max retries exceeded
        public static let unableToSave = L10n.tr("Settings", "alert.retry.unableToSave", fallback: "Unable to Save Setting")
      }
    }
    public enum AppSettings {
      /// Section header for app-level settings
      public static let header = L10n.tr("Settings", "appSettings.header", fallback: "App Settings")
    }
    public enum BatteryCurve {
      /// Option for custom battery curve
      public static let custom = L10n.tr("Settings", "batteryCurve.custom", fallback: "Custom")
      /// Disclosure group label for editing values
      public static let editValues = L10n.tr("Settings", "batteryCurve.editValues", fallback: "Edit Values")
      /// Footer explaining battery curve configuration
      public static let footer = L10n.tr("Settings", "batteryCurve.footer", fallback: "Configure the voltage-to-percentage curve for your device's battery.")
      /// Section header for battery curve
      public static let header = L10n.tr("Settings", "batteryCurve.header", fallback: "Battery Curve")
      /// Unit label for millivolts
      public static let mv = L10n.tr("Settings", "batteryCurve.mV", fallback: "mV")
      /// Label for preset picker
      public static let preset = L10n.tr("Settings", "batteryCurve.preset", fallback: "Preset")
      public enum Accessibility {
        /// Accessibility hint for voltage text field
        public static let voltageHint = L10n.tr("Settings", "batteryCurve.accessibility.voltageHint", fallback: "Enter the expected voltage at this charge level")
        /// Accessibility label for voltage text field - %d is the percentage level
        public static func voltageLabel(_ p1: Int) -> String {
          return L10n.tr("Settings", "batteryCurve.accessibility.voltageLabel", p1, fallback: "Voltage at %d percent")
        }
        /// Accessibility value for voltage text field - %d is the millivolt value
        public static func voltageValue(_ p1: Int) -> String {
          return L10n.tr("Settings", "batteryCurve.accessibility.voltageValue", p1, fallback: "%d millivolts")
        }
      }
      public enum Validation {
        /// Validation error for non-descending values
        public static let notDescending = L10n.tr("Settings", "batteryCurve.validation.notDescending", fallback: "Values must be in descending order")
        /// Validation error for value out of range - %d is the percentage level
        public static func outOfRange(_ p1: Int) -> String {
          return L10n.tr("Settings", "batteryCurve.validation.outOfRange", p1, fallback: "Value at %d%% must be 1000-9000 mV")
        }
      }
    }
    public enum BleStatus {
      /// Accessibility label for BLE status indicator
      public static let accessibilityLabel = L10n.tr("Settings", "bleStatus.accessibilityLabel", fallback: "Bluetooth connection status")
      /// Menu item to change the connected device
      public static let changeDevice = L10n.tr("Settings", "bleStatus.changeDevice", fallback: "Change Device")
      /// Menu item to disconnect from the current device
      public static let disconnect = L10n.tr("Settings", "bleStatus.disconnect", fallback: "Disconnect")
      /// Label shown in BLE status menu when repeat mode is active
      public static let repeatModeActive = L10n.tr("Settings", "bleStatus.repeatModeActive", fallback: "Repeat Mode Active")
      /// Menu item to send a flood advertisement
      public static let sendFloodAdvert = L10n.tr("Settings", "bleStatus.sendFloodAdvert", fallback: "Send Flood Advert")
      /// Menu item to send a zero-hop advertisement
      public static let sendZeroHopAdvert = L10n.tr("Settings", "bleStatus.sendZeroHopAdvert", fallback: "Send Zero-Hop Advert")
      public enum AccessibilityHint {
        /// Accessibility hint when connected
        public static let connected = L10n.tr("Settings", "bleStatus.accessibilityHint.connected", fallback: "Shows device connection options")
        /// Accessibility hint when disconnected
        public static let disconnected = L10n.tr("Settings", "bleStatus.accessibilityHint.disconnected", fallback: "Double tap to connect device")
      }
      public enum SendFloodAdvert {
        /// Accessibility hint for flood advert button
        public static let hint = L10n.tr("Settings", "bleStatus.sendFloodAdvert.hint", fallback: "Floods advertisement across entire mesh")
      }
      public enum SendZeroHopAdvert {
        /// Accessibility hint for zero-hop advert button
        public static let hint = L10n.tr("Settings", "bleStatus.sendZeroHopAdvert.hint", fallback: "Broadcasts to direct neighbors only")
      }
      public enum Status {
        /// Status shown when device is connected but not ready
        public static let connected = L10n.tr("Settings", "bleStatus.status.connected", fallback: "Connected")
        /// Status shown when device is connecting
        public static let connecting = L10n.tr("Settings", "bleStatus.status.connecting", fallback: "Connecting...")
        /// Status shown when device is disconnected
        public static let disconnected = L10n.tr("Settings", "bleStatus.status.disconnected", fallback: "Disconnected")
        /// Status shown when device is ready
        public static let ready = L10n.tr("Settings", "bleStatus.status.ready", fallback: "Ready")
        /// Status shown when device is syncing data
        public static let syncing = L10n.tr("Settings", "bleStatus.status.syncing", fallback: "Syncing")
      }
    }
    public enum Blocking {
      /// Location: BlockingSection.swift - Purpose: Channel senders row
      public static let channelSenders = L10n.tr("Settings", "blocking.channelSenders", fallback: "Channel Senders")
      /// Location: BlockingSection.swift - Purpose: Contacts row
      public static let contacts = L10n.tr("Settings", "blocking.contacts", fallback: "Contacts")
      /// Location: BlockingSection.swift - Purpose: Section header
      public static let header = L10n.tr("Settings", "blocking.header", fallback: "Blocking")
      public enum ChannelSenders {
        /// Location: BlockedChannelSendersView.swift - Purpose: Navigation title
        public static let title = L10n.tr("Settings", "blocking.channelSenders.title", fallback: "Blocked Channel Senders")
        /// Location: BlockedChannelSendersView.swift - Purpose: Swipe action to unblock
        public static let unblock = L10n.tr("Settings", "blocking.channelSenders.unblock", fallback: "Unblock")
        public enum Empty {
          /// Location: BlockedChannelSendersView.swift - Purpose: Empty state description
          public static let description = L10n.tr("Settings", "blocking.channelSenders.empty.description", fallback: "Channel sender names you block will appear here.")
          /// Location: BlockedChannelSendersView.swift - Purpose: Empty state title
          public static let title = L10n.tr("Settings", "blocking.channelSenders.empty.title", fallback: "No Blocked Users")
        }
      }
    }
    public enum Bluetooth {
      /// Button to change the device display name
      public static let changeDisplayName = L10n.tr("Settings", "bluetooth.changeDisplayName", fallback: "Change Display Name")
      /// Button to change the PIN
      public static let changePin = L10n.tr("Settings", "bluetooth.changePin", fallback: "Change PIN")
      /// Label showing current PIN
      public static let currentPin = L10n.tr("Settings", "bluetooth.currentPin", fallback: "Current PIN")
      /// Footer explaining default PIN
      public static let defaultPinFooter = L10n.tr("Settings", "bluetooth.defaultPinFooter", fallback: "Default PIN is 123456. Devices with screens show their own PIN.")
      /// Section header for Bluetooth settings
      public static let header = L10n.tr("Settings", "bluetooth.header", fallback: "Bluetooth")
      /// Placeholder for PIN text field
      public static let pinPlaceholder = L10n.tr("Settings", "bluetooth.pinPlaceholder", fallback: "6-digit PIN")
      /// Label for PIN type picker
      public static let pinType = L10n.tr("Settings", "bluetooth.pinType", fallback: "PIN Type")
      /// Button to set the PIN
      public static let setPin = L10n.tr("Settings", "bluetooth.setPin", fallback: "Set PIN")
      public enum Alert {
        /// Button to confirm change
        public static let change = L10n.tr("Settings", "bluetooth.alert.change", fallback: "Change")
        public enum ChangePin {
          /// Alert message for changing PIN
          public static let message = L10n.tr("Settings", "bluetooth.alert.changePin.message", fallback: "Enter a new 6-digit PIN. The device will reboot to apply the change.")
          /// Alert title for changing custom PIN
          public static let title = L10n.tr("Settings", "bluetooth.alert.changePin.title", fallback: "Change Custom PIN")
        }
        public enum ChangePinType {
          /// Alert message for PIN type change
          public static let message = L10n.tr("Settings", "bluetooth.alert.changePinType.message", fallback: "The device will reboot to apply the change.")
          /// Alert title for confirming PIN type change
          public static let title = L10n.tr("Settings", "bluetooth.alert.changePinType.title", fallback: "Change PIN Type?")
        }
        public enum SetPin {
          /// Alert message for setting PIN
          public static let message = L10n.tr("Settings", "bluetooth.alert.setPin.message", fallback: "Enter a 6-digit PIN. The device will reboot to apply the change.")
          /// Alert title for setting custom PIN
          public static let title = L10n.tr("Settings", "bluetooth.alert.setPin.title", fallback: "Set Custom PIN")
        }
      }
      public enum Error {
        /// Error for invalid PIN format
        public static let invalidPin = L10n.tr("Settings", "bluetooth.error.invalidPin", fallback: "PIN must be a 6-digit number between 100000 and 999999")
      }
      public enum PinType {
        /// PIN type option for custom PIN
        public static let custom = L10n.tr("Settings", "bluetooth.pinType.custom", fallback: "Custom PIN")
        /// PIN type option for default PIN
        public static let `default` = L10n.tr("Settings", "bluetooth.pinType.default", fallback: "Default")
      }
    }
    public enum Chart {
      /// Accessibility label for battery curve chart
      public static let accessibility = L10n.tr("Settings", "chart.accessibility", fallback: "Battery discharge curve showing voltage at each percentage level")
      /// Chart X axis label
      public static let percent = L10n.tr("Settings", "chart.percent", fallback: "Percent")
      /// Chart Y axis label
      public static let voltage = L10n.tr("Settings", "chart.voltage", fallback: "Voltage (V)")
    }
    public enum ChatSettings {
      /// Navigation title for chat settings page
      public static let title = L10n.tr("Settings", "chatSettings.title", fallback: "Chats")
    }
    public enum ConfigExport {
      /// Channels
      public static let channels = L10n.tr("Settings", "configExport.channels", fallback: "Channels")
      /// Contacts
      public static let contacts = L10n.tr("Settings", "configExport.contacts", fallback: "Contacts")
      /// Customize export disclosure label
      public static let customize = L10n.tr("Settings", "configExport.customize", fallback: "Customize Export")
      /// Export navigation row label
      public static let export = L10n.tr("Settings", "configExport.export", fallback: "Export Config")
      /// Export full config button
      public static let exportFull = L10n.tr("Settings", "configExport.exportFull", fallback: "Export Full Config")
      /// Export selected sections button
      public static let exportSelected = L10n.tr("Settings", "configExport.exportSelected", fallback: "Export Selected Sections")
      /// Toggle labels for export sections
      public static let nodeIdentity = L10n.tr("Settings", "configExport.nodeIdentity", fallback: "Node Identity (Name & Keys)")
      /// Other Settings
      public static let otherSettings = L10n.tr("Settings", "configExport.otherSettings", fallback: "Other Settings")
      /// Position
      public static let positionSettings = L10n.tr("Settings", "configExport.positionSettings", fallback: "Position")
      /// Radio Settings
      public static let radioSettings = L10n.tr("Settings", "configExport.radioSettings", fallback: "Radio Settings")
      /// Section footer explaining config export/import
      public static let sectionFooter = L10n.tr("Settings", "configExport.sectionFooter", fallback: "Export or import device settings, channels, and contacts as a JSON file.")
      /// Section title for config export/import
      public static let sectionTitle = L10n.tr("Settings", "configExport.sectionTitle", fallback: "Device Configuration")
      /// Select all toggle label
      public static let selectAll = L10n.tr("Settings", "configExport.selectAll", fallback: "Select All")
      /// Export screen navigation title
      public static let title = L10n.tr("Settings", "configExport.title", fallback: "Export Configuration")
      public enum Channels {
        /// All channels and their encryption keys
        public static let description = L10n.tr("Settings", "configExport.channels.description", fallback: "All channels and their encryption keys")
      }
      public enum Contacts {
        /// Names, keys, and last known positions
        public static let description = L10n.tr("Settings", "configExport.contacts.description", fallback: "Names, keys, and last known positions")
      }
      public enum NodeIdentity {
        /// Device name, public key, and private key
        public static let description = L10n.tr("Settings", "configExport.nodeIdentity.description", fallback: "Device name, public key, and private key")
      }
      public enum OtherSettings {
        /// Contact permissions, location sharing
        public static let description = L10n.tr("Settings", "configExport.otherSettings.description", fallback: "Contact permissions, location sharing")
      }
      public enum PositionSettings {
        /// Latitude and longitude
        public static let description = L10n.tr("Settings", "configExport.positionSettings.description", fallback: "Latitude and longitude")
      }
      public enum RadioSettings {
        /// Frequency, bandwidth, spreading factor, coding rate, TX power
        public static let description = L10n.tr("Settings", "configExport.radioSettings.description", fallback: "Frequency, bandwidth, spreading factor, coding rate, TX power")
      }
    }
    public enum ConfigImport {
      /// Apply button label
      public static let applyButton = L10n.tr("Settings", "configImport.applyButton", fallback: "Apply to Device")
      /// Apply button label (additive only — channels/contacts)
      public static let applyButtonAdd = L10n.tr("Settings", "configImport.applyButtonAdd", fallback: "Add to Device")
      /// Apply button label (overwrite settings)
      public static let applyButtonOverwrite = L10n.tr("Settings", "configImport.applyButtonOverwrite", fallback: "Overwrite Settings")
      /// Import cancelled
      public static let cancelled = L10n.tr("Settings", "configImport.cancelled", fallback: "Import was cancelled.")
      /// Cannot access file error
      public static let cannotAccess = L10n.tr("Settings", "configImport.cannotAccess", fallback: "Cannot access the selected file.")
      /// Channel count (param: integer)
      public static func channelCount(_ p1: Int) -> String {
        return L10n.tr("Settings", "configImport.channelCount", p1, fallback: "Will add/update %d channels on device")
      }
      /// Confirmation alert message (param: device name)
      public static func confirmMessage(_ p1: Any) -> String {
        return L10n.tr("Settings", "configImport.confirmMessage", String(describing: p1), fallback: "This will merge the selected settings into %@. Existing channels and contacts will be updated or added.")
      }
      /// Confirmation alert message (additive only, param: device name)
      public static func confirmMessageAdd(_ p1: Any) -> String {
        return L10n.tr("Settings", "configImport.confirmMessageAdd", String(describing: p1), fallback: "This will add channels and contacts to %@. Existing data will not be removed.")
      }
      /// Confirmation alert message (mixed, param: device name)
      public static func confirmMessageMixed(_ p1: Any) -> String {
        return L10n.tr("Settings", "configImport.confirmMessageMixed", String(describing: p1), fallback: "This will overwrite settings and add channels/contacts on %@.")
      }
      /// Confirmation alert message (overwrite only, param: device name)
      public static func confirmMessageOverwrite(_ p1: Any) -> String {
        return L10n.tr("Settings", "configImport.confirmMessageOverwrite", String(describing: p1), fallback: "This will overwrite the selected settings on %@. Current values will be replaced.")
      }
      /// Confirmation alert title
      public static let confirmTitle = L10n.tr("Settings", "configImport.confirmTitle", fallback: "Apply Configuration?")
      /// Confirmation alert title (additive only)
      public static let confirmTitleAdd = L10n.tr("Settings", "configImport.confirmTitleAdd", fallback: "Add to Device?")
      /// Confirmation alert title (overwrite settings)
      public static let confirmTitleOverwrite = L10n.tr("Settings", "configImport.confirmTitleOverwrite", fallback: "Overwrite Settings?")
      /// Contact count (param: integer)
      public static func contactCount(_ p1: Int) -> String {
        return L10n.tr("Settings", "configImport.contactCount", p1, fallback: "Will add/update %d contacts on device")
      }
      /// Current: %@
      public static func current(_ p1: Any) -> String {
        return L10n.tr("Settings", "configImport.current", String(describing: p1), fallback: "Current: %@")
      }
      /// Import navigation row label
      public static let importConfig = L10n.tr("Settings", "configImport.importConfig", fallback: "Import Config")
      /// Import success
      public static let importSuccess = L10n.tr("Settings", "configImport.importSuccess", fallback: "Configuration imported successfully")
      /// New: %@
      public static func new(_ p1: Any) -> String {
        return L10n.tr("Settings", "configImport.new", String(describing: p1), fallback: "New: %@")
      }
      /// Private key warning
      public static let privateKeyWarning = L10n.tr("Settings", "configImport.privateKeyWarning", fallback: "This will replace the node's cryptographic identity")
      /// Proximity warning
      public static let proximityWarning = L10n.tr("Settings", "configImport.proximityWarning", fallback: "Keep devices close together during import")
      /// Radio settings change warning
      public static let radioWarning = L10n.tr("Settings", "configImport.radioWarning", fallback: "Changing radio settings will disconnect from the current mesh network")
      /// Select file button
      public static let selectFile = L10n.tr("Settings", "configImport.selectFile", fallback: "Select JSON File")
      /// Import screen navigation title
      public static let title = L10n.tr("Settings", "configImport.title", fallback: "Import Configuration")
    }
    public enum Contacts {
      /// Toggle label for auto-add nodes
      public static let autoAdd = L10n.tr("Settings", "contacts.autoAdd", fallback: "Auto-Add Nodes")
      /// Description for auto-add nodes toggle
      public static let autoAddDescription = L10n.tr("Settings", "contacts.autoAddDescription", fallback: "Automatically add nodes from received advertisements")
      /// Section header for nodes/contacts settings
      public static let header = L10n.tr("Settings", "contacts.header", fallback: "Nodes")
    }
    public enum DangerZone {
      /// Button to factory reset the device
      public static let factoryReset = L10n.tr("Settings", "dangerZone.factoryReset", fallback: "Factory Reset Device")
      /// Footer explaining factory reset
      public static let footer = L10n.tr("Settings", "dangerZone.footer", fallback: "Factory reset erases all contacts, messages, and settings on the device.")
      /// Button to forget/unpair the device
      public static let forgetDevice = L10n.tr("Settings", "dangerZone.forgetDevice", fallback: "Forget Device")
      /// Section header for danger zone
      public static let header = L10n.tr("Settings", "dangerZone.header", fallback: "Danger Zone")
      /// Label shown after successful removal
      public static let removed = L10n.tr("Settings", "dangerZone.removed", fallback: "Nodes Removed")
      /// Button to remove non-favorite nodes
      public static let removeUnfavorited = L10n.tr("Settings", "dangerZone.removeUnfavorited", fallback: "Remove Non-Favorite Nodes")
      /// Text shown while removing unfavorited nodes
      public static let removing = L10n.tr("Settings", "dangerZone.removing", fallback: "Removing...")
      /// Text shown while resetting
      public static let resetting = L10n.tr("Settings", "dangerZone.resetting", fallback: "Resetting...")
      public enum Alert {
        public enum RemoveUnfavorited {
          /// Button to confirm removal
          public static let confirm = L10n.tr("Settings", "dangerZone.alert.removeUnfavorited.confirm", fallback: "Remove Nodes")
          /// Alert message for remove unfavorited (%d = count)
          public static func message(_ p1: Int) -> String {
            return L10n.tr("Settings", "dangerZone.alert.removeUnfavorited.message", p1, fallback: "This will permanently delete %d nodes not marked as favorite from the device and the app, along with their messages.")
          }
          /// Message when no non-favorite nodes to remove
          public static let noneFound = L10n.tr("Settings", "dangerZone.alert.removeUnfavorited.noneFound", fallback: "No non-favorite nodes to remove.")
          /// Partial success message (%d = removed, %d = total)
          public static func partial(_ p1: Int, _ p2: Int) -> String {
            return L10n.tr("Settings", "dangerZone.alert.removeUnfavorited.partial", p1, p2, fallback: "Removed %d of %d nodes. The connection was interrupted — tap the button again to retry removing the remaining nodes.")
          }
          /// Alert title for removal result
          public static let resultTitle = L10n.tr("Settings", "dangerZone.alert.removeUnfavorited.resultTitle", fallback: "Removal Result")
          /// Alert title for remove non-favorite confirmation
          public static let title = L10n.tr("Settings", "dangerZone.alert.removeUnfavorited.title", fallback: "Remove Non-Favorite Nodes")
        }
        public enum Reset {
          /// Button to confirm reset
          public static let confirm = L10n.tr("Settings", "dangerZone.alert.reset.confirm", fallback: "Reset")
          /// Alert message for factory reset
          public static let message = L10n.tr("Settings", "dangerZone.alert.reset.message", fallback: "This will erase all device data including contacts, messages, and settings. Tap Remove when prompted to unpair the device.")
          /// Alert title for factory reset confirmation
          public static let title = L10n.tr("Settings", "dangerZone.alert.reset.title", fallback: "Factory Reset")
        }
      }
      public enum Dialog {
        public enum Forget {
          /// Button to forget device and delete all associated data
          public static let deleteAll = L10n.tr("Settings", "dangerZone.dialog.forget.deleteAll", fallback: "Forget Device & Delete Data")
          /// Button to forget device but keep data
          public static let keepData = L10n.tr("Settings", "dangerZone.dialog.forget.keepData", fallback: "Forget Device")
          /// Confirmation dialog message for forget device
          public static let message = L10n.tr("Settings", "dangerZone.dialog.forget.message", fallback: "Your device will be unpaired. You can choose to keep or delete your messages, contacts, and channels.")
          /// Confirmation dialog title for forget device
          public static let title = L10n.tr("Settings", "dangerZone.dialog.forget.title", fallback: "Forget Device")
        }
      }
      public enum Error {
        /// Error when services are not available
        public static let servicesUnavailable = L10n.tr("Settings", "dangerZone.error.servicesUnavailable", fallback: "Services not available")
      }
    }
    public enum DefaultFloodScope {
      /// Button to apply a custom region name
      public static let apply = L10n.tr("Settings", "defaultFloodScope.apply", fallback: "Add")
      /// Placeholder for custom region name field
      public static let customPlaceholder = L10n.tr("Settings", "defaultFloodScope.customPlaceholder", fallback: "Custom region name")
      /// Option to clear the persisted default flood scope
      public static let disabled = L10n.tr("Settings", "defaultFloodScope.disabled", fallback: "Disabled")
      /// Footer explaining default flood scope
      public static let footer = L10n.tr("Settings", "defaultFloodScope.footer", fallback: "When set, the device applies this scope to flood sends until a channel-specific scope overrides it. The scope is saved on the device and survives reboots.")
      /// Section header for default flood scope picker
      public static let header = L10n.tr("Settings", "defaultFloodScope.header", fallback: "Default Flood Scope")
    }
    public enum DemoMode {
      /// Toggle label to enable demo mode
      public static let enabled = L10n.tr("Settings", "demoMode.enabled", fallback: "Enabled")
      /// Footer explaining what demo mode does
      public static let footer = L10n.tr("Settings", "demoMode.footer", fallback: "Demo mode allows testing without hardware using mock data.")
      /// Section header for demo mode
      public static let header = L10n.tr("Settings", "demoMode.header", fallback: "Demo Mode")
    }
    public enum Device {
      /// Button to connect a device
      public static let connect = L10n.tr("Settings", "device.connect", fallback: "Connect Device")
      /// Status shown when device is connected
      public static let connected = L10n.tr("Settings", "device.connected", fallback: "Connected")
      /// Section header for device information
      public static let header = L10n.tr("Settings", "device.header", fallback: "Device")
      /// Footer shown when no device is connected
      public static let noDeviceConnected = L10n.tr("Settings", "device.noDeviceConnected", fallback: "No MeshCore device connected")
    }
    public enum DeviceActions {
      /// Section header for device actions
      public static let header = L10n.tr("Settings", "deviceActions.header", fallback: "Device")
      /// Button to reboot the device
      public static let rebootDevice = L10n.tr("Settings", "deviceActions.rebootDevice", fallback: "Reboot Device")
      /// Text shown while rebooting
      public static let rebooting = L10n.tr("Settings", "deviceActions.rebooting", fallback: "Rebooting...")
      public enum Alert {
        public enum Reboot {
          /// Button to confirm reboot
          public static let confirm = L10n.tr("Settings", "deviceActions.alert.reboot.confirm", fallback: "Reboot")
          /// Alert message for reboot confirmation
          public static let message = L10n.tr("Settings", "deviceActions.alert.reboot.message", fallback: "The device will restart and temporarily disconnect.")
          /// Alert title for reboot confirmation
          public static let title = L10n.tr("Settings", "deviceActions.alert.reboot.title", fallback: "Reboot Device?")
        }
      }
    }
    public enum DeviceInfo {
      /// Label for battery level
      public static let battery = L10n.tr("Settings", "deviceInfo.battery", fallback: "Battery")
      /// Combined label for battery and storage when loading
      public static let batteryAndStorage = L10n.tr("Settings", "deviceInfo.batteryAndStorage", fallback: "Battery & Storage")
      /// Label for build date
      public static let buildDate = L10n.tr("Settings", "deviceInfo.buildDate", fallback: "Build Date")
      /// Fallback manufacturer name
      public static let defaultManufacturer = L10n.tr("Settings", "deviceInfo.defaultManufacturer", fallback: "MeshCore Device")
      /// Label for firmware version
      public static let firmwareVersion = L10n.tr("Settings", "deviceInfo.firmwareVersion", fallback: "Firmware Version")
      /// Label for manufacturer
      public static let manufacturer = L10n.tr("Settings", "deviceInfo.manufacturer", fallback: "Manufacturer")
      /// Label for max channels capability
      public static let maxChannels = L10n.tr("Settings", "deviceInfo.maxChannels", fallback: "Max Channels")
      /// Label for max nodes capability
      public static let maxNodes = L10n.tr("Settings", "deviceInfo.maxNodes", fallback: "Max Nodes")
      /// Label for max TX power capability
      public static let maxTxPower = L10n.tr("Settings", "deviceInfo.maxTxPower", fallback: "Max TX Power")
      /// Label for public key
      public static let publicKey = L10n.tr("Settings", "deviceInfo.publicKey", fallback: "Public Key")
      /// Button to share contact information
      public static let shareContact = L10n.tr("Settings", "deviceInfo.shareContact", fallback: "Share My Contact")
      /// Label for storage used
      public static let storageUsed = L10n.tr("Settings", "deviceInfo.storageUsed", fallback: "Storage Used")
      /// Navigation title for device info screen
      public static let title = L10n.tr("Settings", "deviceInfo.title", fallback: "Device Info")
      /// TX power display format with dBm unit
      public static func txPowerFormat(_ p1: Any) -> String {
        return L10n.tr("Settings", "deviceInfo.txPowerFormat", String(describing: p1), fallback: "%@ dBm")
      }
      /// Placeholder when a value is unknown
      public static let unknown = L10n.tr("Settings", "deviceInfo.unknown", fallback: "Unknown")
      public enum Capabilities {
        /// Section header for device capabilities
        public static let header = L10n.tr("Settings", "deviceInfo.capabilities.header", fallback: "Capabilities")
      }
      public enum Connection {
        /// Section header for connection status
        public static let header = L10n.tr("Settings", "deviceInfo.connection.header", fallback: "Connection")
        /// Label for connection status
        public static let status = L10n.tr("Settings", "deviceInfo.connection.status", fallback: "Status")
      }
      public enum Firmware {
        /// Section header for firmware information
        public static let header = L10n.tr("Settings", "deviceInfo.firmware.header", fallback: "Firmware")
      }
      public enum Identity {
        /// Section header for identity information
        public static let header = L10n.tr("Settings", "deviceInfo.identity.header", fallback: "Identity")
      }
      public enum NoDevice {
        /// Description for ContentUnavailableView when no device is connected
        public static let description = L10n.tr("Settings", "deviceInfo.noDevice.description", fallback: "Connect to a MeshCore device to view its information")
        /// Title for ContentUnavailableView when no device is connected
        public static let title = L10n.tr("Settings", "deviceInfo.noDevice.title", fallback: "No Device Connected")
      }
      public enum PowerStorage {
        /// Section header for power and storage
        public static let header = L10n.tr("Settings", "deviceInfo.powerStorage.header", fallback: "Power & Storage")
      }
    }
    public enum DeviceSelection {
      /// Fallback connection type description
      public static let bluetooth = L10n.tr("Settings", "deviceSelection.bluetooth", fallback: "Bluetooth")
      /// Button to connect to selected device
      public static let connect = L10n.tr("Settings", "deviceSelection.connect", fallback: "Connect")
      /// Label shown when device is connected to another app
      public static let connectedElsewhere = L10n.tr("Settings", "deviceSelection.connectedElsewhere", fallback: "Connected elsewhere")
      /// Button to connect via WiFi
      public static let connectViaWifi = L10n.tr("Settings", "deviceSelection.connectViaWifi", fallback: "Connect via WiFi")
      /// Description for empty state
      public static let noPairedDescription = L10n.tr("Settings", "deviceSelection.noPairedDescription", fallback: "You haven't paired any devices yet.")
      /// Title for empty state when no devices are paired
      public static let noPairedDevices = L10n.tr("Settings", "deviceSelection.noPairedDevices", fallback: "No Paired Devices")
      /// Section header for previously paired devices
      public static let previouslyPaired = L10n.tr("Settings", "deviceSelection.previouslyPaired", fallback: "Previously Paired")
      /// Button to scan for Bluetooth devices
      public static let scanBluetooth = L10n.tr("Settings", "deviceSelection.scanBluetooth", fallback: "Scan for Bluetooth Device")
      /// Button to scan for new devices
      public static let scanForDevices = L10n.tr("Settings", "deviceSelection.scanForDevices", fallback: "Scan for Devices")
      /// Footer text prompting user to select a device
      public static let selectToReconnect = L10n.tr("Settings", "deviceSelection.selectToReconnect", fallback: "Select a device to reconnect")
      /// Navigation title for device selection
      public static let title = L10n.tr("Settings", "deviceSelection.title", fallback: "Connect Device")
      public enum Accessibility {
        /// Accessibility hint for a device connected to another app
        public static let connectedElsewhereHint = L10n.tr("Settings", "deviceSelection.accessibility.connectedElsewhereHint", fallback: "This device is in use by another app. Connecting may cause communication issues.")
        /// Accessibility label for a device connected to another app - %1$@ is device name
        public static func connectedElsewhereLabel(_ p1: Any) -> String {
          return L10n.tr("Settings", "deviceSelection.accessibility.connectedElsewhereLabel", String(describing: p1), fallback: "%1$@, connected to another app")
        }
        /// Accessibility label for a device - %1$@ is device name, %2$@ is connection type
        public static func deviceLabel(_ p1: Any, _ p2: Any) -> String {
          return L10n.tr("Settings", "deviceSelection.accessibility.deviceLabel", String(describing: p1), String(describing: p2), fallback: "%1$@, %2$@")
        }
        /// Accessibility hint for a device that is out of Bluetooth range
        public static let outOfRangeHint = L10n.tr("Settings", "deviceSelection.accessibility.outOfRangeHint", fallback: "Device is out of Bluetooth range")
        /// Accessibility hint for selecting a device
        public static let selectHint = L10n.tr("Settings", "deviceSelection.accessibility.selectHint", fallback: "Double tap to connect")
      }
    }
    public enum Diagnostics {
      /// Button to clear debug logs
      public static let clearLogs = L10n.tr("Settings", "diagnostics.clearLogs", fallback: "Clear Debug Logs")
      /// Button to export debug logs
      public static let exportLogs = L10n.tr("Settings", "diagnostics.exportLogs", fallback: "Export Debug Logs")
      /// Footer explaining log export
      public static let footer = L10n.tr("Settings", "diagnostics.footer", fallback: "Export includes debug logs from the last 24 hours across app sessions. Logs are stored locally and automatically pruned.")
      /// Section header for diagnostics
      public static let header = L10n.tr("Settings", "diagnostics.header", fallback: "Diagnostics")
      public enum Alert {
        public enum Clear {
          /// Button to confirm clear
          public static let confirm = L10n.tr("Settings", "diagnostics.alert.clear.confirm", fallback: "Clear")
          /// Alert message for clear logs
          public static let message = L10n.tr("Settings", "diagnostics.alert.clear.message", fallback: "This will delete all stored debug logs. Exported log files will not be affected.")
          /// Alert title for clear logs confirmation
          public static let title = L10n.tr("Settings", "diagnostics.alert.clear.title", fallback: "Clear Debug Logs")
        }
      }
      public enum Error {
        /// Error when export fails
        public static let exportFailed = L10n.tr("Settings", "diagnostics.error.exportFailed", fallback: "Failed to create export file")
      }
    }
    public enum DirectMessages {
      /// Picker label for number of acknowledgments
      public static let acknowledgments = L10n.tr("Settings", "directMessages.acknowledgments", fallback: "Acknowledgments")
      /// Footer explaining the acknowledgments setting
      public static let footer = L10n.tr("Settings", "directMessages.footer", fallback: "Number of acknowledgments sent per direct message. Use 2 for better delivery confirmation over unreliable links.")
      /// Section header for direct message settings
      public static let header = L10n.tr("Settings", "directMessages.header", fallback: "Direct Messages")
    }
    public enum ImportKey {
      /// Button to import the key
      public static let `import` = L10n.tr("Settings", "importKey.import", fallback: "Import Key")
      /// Progress label while importing
      public static let importing = L10n.tr("Settings", "importKey.importing", fallback: "Importing…")
      /// Button label to open import key sheet
      public static let title = L10n.tr("Settings", "importKey.title", fallback: "Import Key")
      public enum Error {
        /// Error when hex input is wrong length or invalid
        public static let invalidHex = L10n.tr("Settings", "importKey.error.invalidHex", fallback: "Key must be exactly 128 hex characters")
        /// Error when key fails Ed25519 validation
        public static let invalidKey = L10n.tr("Settings", "importKey.error.invalidKey", fallback: "Not a valid Ed25519 private key")
      }
      public enum KeyInput {
        /// Label for the private key input field
        public static let label = L10n.tr("Settings", "importKey.keyInput.label", fallback: "Private Key")
        /// Placeholder for the key input field
        public static let placeholder = L10n.tr("Settings", "importKey.keyInput.placeholder", fallback: "Paste 128-character hex key")
      }
      public enum Sheet {
        /// Explanation of what importing a key does
        public static let explanation = L10n.tr("Settings", "importKey.sheet.explanation", fallback: "Paste an existing Ed25519 private key (128 hex characters) to replace your device's current identity.")
        /// Navigation title for the import key sheet
        public static let title = L10n.tr("Settings", "importKey.sheet.title", fallback: "Import Key")
      }
    }
    public enum InlineImages {
      /// Toggle label for auto-play GIFs
      public static let autoPlayGifs = L10n.tr("Settings", "inlineImages.autoPlayGifs", fallback: "Auto-play GIFs")
      /// Footer explaining inline image privacy implications
      public static let footer = L10n.tr("Settings", "inlineImages.footer", fallback: "Fetching images from URLs may reveal your IP address to the server hosting the image.")
      /// Toggle label for inline images
      public static let toggle = L10n.tr("Settings", "inlineImages.toggle", fallback: "Inline Images")
    }
    public enum Language {
      /// Location: SettingsView.swift - Purpose: Language row title
      public static let title = L10n.tr("Settings", "language.title", fallback: "Language")
    }
    public enum LinkPreviews {
      /// Footer explaining link preview privacy implications
      public static let footer = L10n.tr("Settings", "linkPreviews.footer", fallback: "Link previews fetch data from the web, which may reveal your IP address to the server hosting the link.")
      /// Section header for link preview settings
      public static let header = L10n.tr("Settings", "linkPreviews.header", fallback: "Link Previews")
      /// Toggle label for showing previews in channels
      public static let showInChannels = L10n.tr("Settings", "linkPreviews.showInChannels", fallback: "Show in Channels")
      /// Toggle label for showing previews in DMs
      public static let showInDMs = L10n.tr("Settings", "linkPreviews.showInDMs", fallback: "Show in Direct Messages")
      /// Toggle label for link previews
      public static let toggle = L10n.tr("Settings", "linkPreviews.toggle", fallback: "Link Previews")
    }
    public enum LiveActivity {
      /// Label for the Live Activity toggle in App Settings
      public static let title = L10n.tr("Settings", "liveActivity.title", fallback: "Live Activity")
      public enum Tip {
        /// Message for the Live Activity tip
        public static let message = L10n.tr("Settings", "liveActivity.tip.message", fallback: "Your connection, battery, and messages stay visible — even without opening the app.")
        /// Title for the Live Activity tip shown after first connection
        public static let title = L10n.tr("Settings", "liveActivity.tip.title", fallback: "Radio status at a glance")
      }
    }
    public enum Location {
      /// Toggle label for auto-update location
      public static let autoUpdate = L10n.tr("Settings", "location.autoUpdate", fallback: "Auto-Update Location")
      /// Footer for location settings section
      public static let footer = L10n.tr("Settings", "location.footer", fallback: "When Share Location Publicly is enabled, your location will be broadcast across the mesh when sending adverts. Enabling Auto-Update Location will automatically update your device's location before sending adverts.")
      /// Label for GPS source picker
      public static let gpsSource = L10n.tr("Settings", "location.gpsSource", fallback: "GPS Source")
      /// Section header for location settings
      public static let header = L10n.tr("Settings", "location.header", fallback: "Location")
      /// Detail text when location is not being shared
      public static let notSharing = L10n.tr("Settings", "location.notSharing", fallback: "Not sharing")
      /// Detail text when location is being shared publicly
      public static let sharingPublicly = L10n.tr("Settings", "location.sharingPublicly", fallback: "Sharing publicly")
      public enum DeviceGps {
        /// Footer for device GPS controls
        public static let footer = L10n.tr("Settings", "location.deviceGps.footer", fallback: "Turns the radio's built-in GPS on or off. Saving a manual map location turns Device GPS off.")
        /// Section header for device GPS controls
        public static let header = L10n.tr("Settings", "location.deviceGps.header", fallback: "Device GPS")
        /// Toggle label for device GPS power
        public static let toggle = L10n.tr("Settings", "location.deviceGps.toggle", fallback: "Enable Device GPS")
      }
      public enum GpsSource {
        /// GPS source option: device GPS
        public static let device = L10n.tr("Settings", "location.gpsSource.device", fallback: "Device GPS")
        /// GPS source option: phone GPS
        public static let phone = L10n.tr("Settings", "location.gpsSource.phone", fallback: "Phone GPS")
      }
    }
    public enum LocationPicker {
      /// Button to clear the selected location
      public static let clearLocation = L10n.tr("Settings", "locationPicker.clearLocation", fallback: "Clear Location")
      /// Button to drop a pin at the map center
      public static let dropPin = L10n.tr("Settings", "locationPicker.dropPin", fallback: "Drop Pin at Center")
      /// Label for latitude display
      public static let latitude = L10n.tr("Settings", "locationPicker.latitude", fallback: "Latitude:")
      /// Label for longitude display
      public static let longitude = L10n.tr("Settings", "locationPicker.longitude", fallback: "Longitude:")
      /// Marker title for node location on map
      public static let markerTitle = L10n.tr("Settings", "locationPicker.markerTitle", fallback: "Node Location")
      /// Navigation title for location picker
      public static let title = L10n.tr("Settings", "locationPicker.title", fallback: "Set Location")
    }
    public enum Messages {
      /// Footer explaining what the message display options show
      public static let footer = L10n.tr("Settings", "messages.footer", fallback: "Display routing information inside incoming message bubbles.")
      /// Section header for messages settings in advanced
      public static let header = L10n.tr("Settings", "messages.header", fallback: "Messages")
      /// Toggle label for showing hop count on incoming messages
      public static let showIncomingHopCount = L10n.tr("Settings", "messages.showIncomingHopCount", fallback: "Show Incoming Hop Count")
      /// Toggle label for showing routing path on incoming messages
      public static let showIncomingPath = L10n.tr("Settings", "messages.showIncomingPath", fallback: "Show Incoming Path")
    }
    public enum MyDevice {
      /// Section header for device-specific settings
      public static let header = L10n.tr("Settings", "myDevice.header", fallback: "My Device")
    }
    public enum Node {
      /// Button text to copy
      public static let copy = L10n.tr("Settings", "node.copy", fallback: "Copy")
      /// Footer explaining node visibility
      public static let footer = L10n.tr("Settings", "node.footer", fallback: "Your node name is visible to other mesh users when shared.")
      /// Section header for node settings
      public static let header = L10n.tr("Settings", "node.header", fallback: "Node")
      /// Text shown when location is not set
      public static let locationNotSet = L10n.tr("Settings", "node.locationNotSet", fallback: "Not Set")
      /// Text shown when location is set
      public static let locationSet = L10n.tr("Settings", "node.locationSet", fallback: "Set")
      /// Label for node name
      public static let name = L10n.tr("Settings", "node.name", fallback: "Node Name")
      /// Label for set location button
      public static let setLocation = L10n.tr("Settings", "node.setLocation", fallback: "Set Location")
      /// Toggle label for share location publicly
      public static let shareLocationPublicly = L10n.tr("Settings", "node.shareLocationPublicly", fallback: "Share Location Publicly")
      /// Default node name when unknown
      public static let unknown = L10n.tr("Settings", "node.unknown", fallback: "Unknown")
      public enum Alert {
        public enum EditName {
          /// Alert title for editing node name
          public static let title = L10n.tr("Settings", "node.alert.editName.title", fallback: "Edit Node Name")
        }
      }
    }
    public enum Nodes {
      /// Toggle label for auto-add contacts
      public static let autoAddContacts = L10n.tr("Settings", "nodes.autoAddContacts", fallback: "Contacts")
      /// Label for auto-add mode picker
      public static let autoAddMode = L10n.tr("Settings", "nodes.autoAddMode", fallback: "Auto-Add Mode")
      /// Toggle label for auto-add repeaters
      public static let autoAddRepeaters = L10n.tr("Settings", "nodes.autoAddRepeaters", fallback: "Repeaters")
      /// Toggle label for auto-add room servers
      public static let autoAddRoomServers = L10n.tr("Settings", "nodes.autoAddRoomServers", fallback: "Room Servers")
      /// Section header for nodes settings
      public static let header = L10n.tr("Settings", "nodes.header", fallback: "Nodes")
      /// Picker label for max hop distance
      public static let maxHops = L10n.tr("Settings", "nodes.maxHops", fallback: "Auto-Add Max Hop Limit")
      /// Toggle label for overwrite oldest
      public static let overwriteOldest = L10n.tr("Settings", "nodes.overwriteOldest", fallback: "Overwrite Oldest")
      /// Description for overwrite oldest toggle
      public static let overwriteOldestDescription = L10n.tr("Settings", "nodes.overwriteOldestDescription", fallback: "When storage is full, replace the oldest non-favorite node")
      public enum AutoAddMode {
        /// Auto-add mode: all
        public static let all = L10n.tr("Settings", "nodes.autoAddMode.all", fallback: "All")
        /// Auto-add mode: all description
        public static let allDescription = L10n.tr("Settings", "nodes.autoAddMode.allDescription", fallback: "Auto-add every discovered node")
        /// Auto-add mode: manual
        public static let manual = L10n.tr("Settings", "nodes.autoAddMode.manual", fallback: "Manual")
        /// Auto-add mode: manual description
        public static let manualDescription = L10n.tr("Settings", "nodes.autoAddMode.manualDescription", fallback: "Review all nodes in Discover before adding")
        /// Auto-add mode: selected types
        public static let selectedTypes = L10n.tr("Settings", "nodes.autoAddMode.selectedTypes", fallback: "Selected Types")
        /// Auto-add mode: selected types description
        public static let selectedTypesDescription = L10n.tr("Settings", "nodes.autoAddMode.selectedTypesDescription", fallback: "Auto-add only the types enabled below")
      }
      public enum AutoAddTypes {
        /// Section header for auto-add types
        public static let header = L10n.tr("Settings", "nodes.autoAddTypes.header", fallback: "Auto-Add Types")
      }
      public enum MaxHops {
        /// Direct only option (0 hops)
        public static let directOnly = L10n.tr("Settings", "nodes.maxHops.directOnly", fallback: "Direct Only")
        /// Footer text when hop limit is active
        public static let footerActive = L10n.tr("Settings", "nodes.maxHops.footerActive", fallback: "Nodes beyond the selected hop distance will not be auto-added.")
        /// Plural hops format string
        public static func hops(_ p1: Int) -> String {
          return L10n.tr("Settings", "nodes.maxHops.hops", p1, fallback: "%d Hops")
        }
        /// No limit option
        public static let noLimit = L10n.tr("Settings", "nodes.maxHops.noLimit", fallback: "No Limit")
        /// Singular 1 hop option
        public static let oneHop = L10n.tr("Settings", "nodes.maxHops.oneHop", fallback: "1 Hop")
      }
      public enum StaleCleanup {
        /// Threshold option: number of days (%d = day count)
        public static func days(_ p1: Int) -> String {
          return L10n.tr("Settings", "nodes.staleCleanup.days", p1, fallback: "%d days")
        }
        /// Footer describing the auto-remove feature (shown when toggle is off)
        public static let footerDisabled = L10n.tr("Settings", "nodes.staleCleanup.footerDisabled", fallback: "Automatically removes non-favorite nodes that haven't been heard from after a set period. Favorites are never removed.")
        /// Footer when enabled but device is disconnected
        public static let footerDisconnected = L10n.tr("Settings", "nodes.staleCleanup.footerDisconnected", fallback: "Will check for old nodes on next connection. Favorites are never removed.")
        /// Footer when auto-remove is enabled (%d = day count)
        public static func footerEnabled(_ p1: Int) -> String {
          return L10n.tr("Settings", "nodes.staleCleanup.footerEnabled", p1, fallback: "Non-favorite nodes not heard from in %d days are automatically removed on connection.")
        }
        /// Footer when toggle is on but no threshold selected yet
        public static let footerSelect = L10n.tr("Settings", "nodes.staleCleanup.footerSelect", fallback: "Choose how long to keep nodes. Favorites are never removed.")
        /// Toggle label / section header for stale node cleanup
        public static let header = L10n.tr("Settings", "nodes.staleCleanup.header", fallback: "Auto-Remove Old Nodes")
        /// Last cleanup date row (%@ = relative date)
        public static func lastRun(_ p1: Any) -> String {
          return L10n.tr("Settings", "nodes.staleCleanup.lastRun", String(describing: p1), fallback: "Last checked %@")
        }
        /// Picker placeholder when no threshold is selected
        public static let select = L10n.tr("Settings", "nodes.staleCleanup.select", fallback: "Select")
        /// Label for threshold picker
        public static let threshold = L10n.tr("Settings", "nodes.staleCleanup.threshold", fallback: "Remove nodes older than")
      }
      public enum Storage {
        /// Section header for storage settings
        public static let header = L10n.tr("Settings", "nodes.storage.header", fallback: "Storage")
      }
    }
    public enum Notifications {
      /// Toggle label for channel messages notifications
      public static let channelMessages = L10n.tr("Settings", "notifications.channelMessages", fallback: "Channel Messages")
      /// Message shown when device not connected
      public static let connectDevice = L10n.tr("Settings", "notifications.connectDevice", fallback: "Connect a device to configure notifications")
      /// Toggle label for contact messages notifications
      public static let contactMessages = L10n.tr("Settings", "notifications.contactMessages", fallback: "Contact Messages")
      /// Label shown when notifications are disabled
      public static let disabled = L10n.tr("Settings", "notifications.disabled", fallback: "Notifications Disabled")
      /// Toggle label for companion discovery notifications
      public static let discoveryContact = L10n.tr("Settings", "notifications.discoveryContact", fallback: "Companion")
      /// Toggle label for repeater discovery notifications
      public static let discoveryRepeater = L10n.tr("Settings", "notifications.discoveryRepeater", fallback: "Repeater")
      /// Toggle label for room discovery notifications
      public static let discoveryRoom = L10n.tr("Settings", "notifications.discoveryRoom", fallback: "Room")
      /// Button to enable notifications
      public static let enable = L10n.tr("Settings", "notifications.enable", fallback: "Enable Notifications")
      /// Footer for notifications navigation row
      public static let footer = L10n.tr("Settings", "notifications.footer", fallback: "Message alerts, contact discoveries, and low battery warnings.")
      /// Section header for notifications
      public static let header = L10n.tr("Settings", "notifications.header", fallback: "Notifications")
      /// Toggle label for low battery warnings
      public static let lowBattery = L10n.tr("Settings", "notifications.lowBattery", fallback: "Low Battery Warnings")
      /// Toggle label for new contact discovered notifications
      public static let newContactDiscovered = L10n.tr("Settings", "notifications.newContactDiscovered", fallback: "New Contact Discovered")
      /// Button to open system settings
      public static let openSettings = L10n.tr("Settings", "notifications.openSettings", fallback: "Open Settings")
      /// Toggle label for reaction notifications
      public static let reactions = L10n.tr("Settings", "notifications.reactions", fallback: "Reactions")
      /// Toggle label for room messages notifications
      public static let roomMessages = L10n.tr("Settings", "notifications.roomMessages", fallback: "Room Messages")
    }
    public enum OfflineMaps {
      /// Cancel button
      public static let cancel = L10n.tr("Settings", "offlineMaps.cancel", fallback: "Cancel")
      /// Status when pack download is complete
      public static let complete = L10n.tr("Settings", "offlineMaps.complete", fallback: "Downloaded")
      /// Delete button
      public static let delete = L10n.tr("Settings", "offlineMaps.delete", fallback: "Delete")
      /// Delete confirmation message
      public static let deleteMessage = L10n.tr("Settings", "offlineMaps.deleteMessage", fallback: "The downloaded map data will be removed.")
      /// Delete confirmation title
      public static let deleteTitle = L10n.tr("Settings", "offlineMaps.deleteTitle", fallback: "Delete Offline Map?")
      /// Button to start download
      public static let download = L10n.tr("Settings", "offlineMaps.download", fallback: "Download")
      /// Hint shown before estimate is available
      public static let downloadHint = L10n.tr("Settings", "offlineMaps.downloadHint", fallback: "Enter a name and select an area to download.")
      /// Status when pack is downloading
      public static let downloading = L10n.tr("Settings", "offlineMaps.downloading", fallback: "Downloading…")
      /// Button to download a new offline region
      public static let downloadRegion = L10n.tr("Settings", "offlineMaps.downloadRegion", fallback: "Download Region")
      /// Description for empty state
      public static let emptyDescription = L10n.tr("Settings", "offlineMaps.emptyDescription", fallback: "Download map regions for use without internet.")
      /// Title for empty state when no offline packs exist
      public static let emptyTitle = L10n.tr("Settings", "offlineMaps.emptyTitle", fallback: "No Offline Maps")
      /// Estimated download size
      public static func estimatedSize(_ p1: Any) -> String {
        return L10n.tr("Settings", "offlineMaps.estimatedSize", String(describing: p1), fallback: "Estimated size: ~%@")
      }
      /// Download exceeds available storage
      public static let exceedsStorage = L10n.tr("Settings", "offlineMaps.exceedsStorage", fallback: "Not enough storage on this device. Zoom in to select a smaller area.")
      /// Include layers prompt
      public static let includeLayers = L10n.tr("Settings", "offlineMaps.includeLayers", fallback: "Include additional layers for offline use.")
      /// Large tile download warning
      public static let largeTileWarning = L10n.tr("Settings", "offlineMaps.largeTileWarning", fallback: "Large download area. This may take a while and use significant storage.")
      /// Layers section header
      public static let layers = L10n.tr("Settings", "offlineMaps.layers", fallback: "Layers")
      /// No network available
      public static let noNetwork = L10n.tr("Settings", "offlineMaps.noNetwork", fallback: "An internet connection is required to download maps.")
      /// Pause download button
      public static let pause = L10n.tr("Settings", "offlineMaps.pause", fallback: "Pause")
      /// Paused status label
      public static let paused = L10n.tr("Settings", "offlineMaps.paused", fallback: "Paused")
      /// Navigation title for region picker sheet
      public static let pickRegion = L10n.tr("Settings", "offlineMaps.pickRegion", fallback: "Select Region")
      /// Placeholder for region name text field
      public static let regionName = L10n.tr("Settings", "offlineMaps.regionName", fallback: "Region Name")
      /// Resume download button
      public static let resume = L10n.tr("Settings", "offlineMaps.resume", fallback: "Resume")
      /// Section header for storage info
      public static let storage = L10n.tr("Settings", "offlineMaps.storage", fallback: "Storage")
      /// Storage section footer
      public static let storageFooter = L10n.tr("Settings", "offlineMaps.storageFooter", fallback: "Includes map data and internal indexes. Total may be larger than the sum of individual downloads.")
      /// Label for total storage used
      public static let storageUsed = L10n.tr("Settings", "offlineMaps.storageUsed", fallback: "Storage Used")
      /// Navigation title for offline maps settings
      public static let title = L10n.tr("Settings", "offlineMaps.title", fallback: "Offline Maps")
      /// Fallback name for unknown region
      public static let unknownRegion = L10n.tr("Settings", "offlineMaps.unknownRegion", fallback: "Unknown Region")
      public enum Error {
        /// Error: insufficient disk space
        public static let insufficientDiskSpace = L10n.tr("Settings", "offlineMaps.error.insufficientDiskSpace", fallback: "Not enough storage space. At least 100 MB is required.")
        /// Error: tile limit reached
        public static let tileLimitReached = L10n.tr("Settings", "offlineMaps.error.tileLimitReached", fallback: "The download tile limit has been reached.")
      }
      public enum Layer {
        /// Layer type labels
        public static let base = L10n.tr("Settings", "offlineMaps.layer.base", fallback: "Base Map")
        /// Topography
        public static let topo = L10n.tr("Settings", "offlineMaps.layer.topo", fallback: "Topography")
      }
    }
    public enum PathHashMode {
      /// Footer explaining path hash mode tradeoff
      public static let footer = L10n.tr("Settings", "pathHashMode.footer", fallback: "Larger hashes reduce routing collisions but limit the maximum number of hops per path. Repeaters running firmware older than 1.14.0 will not repeat messages with a hash size greater than 1 byte.")
      /// Section header for path hash mode
      public static let header = L10n.tr("Settings", "pathHashMode.header", fallback: "Path Hash Size")
      /// Label for path hash mode picker
      public static let label = L10n.tr("Settings", "pathHashMode.label", fallback: "Hash Size")
      /// Path hash mode option: 1 byte
      public static let oneByte = L10n.tr("Settings", "pathHashMode.oneByte", fallback: "1 Byte")
      /// Path hash mode option: 3 bytes
      public static let threeBytes = L10n.tr("Settings", "pathHashMode.threeBytes", fallback: "3 Bytes")
      /// Path hash mode option: 2 bytes
      public static let twoBytes = L10n.tr("Settings", "pathHashMode.twoBytes", fallback: "2 Bytes")
    }
    public enum PrivateKey {
      /// Section header describing the key type
      public static let header = L10n.tr("Settings", "privateKey.header", fallback: "64-byte Ed25519 Private Key")
    }
    public enum PublicKey {
      /// Button to copy key to clipboard
      public static let copy = L10n.tr("Settings", "publicKey.copy", fallback: "Copy to Clipboard")
      /// Footer explaining the public key's purpose
      public static let footer = L10n.tr("Settings", "publicKey.footer", fallback: "This key uniquely identifies your device on the mesh network")
      /// Section header describing the key type
      public static let header = L10n.tr("Settings", "publicKey.header", fallback: "32-byte Ed25519 Public Key")
      /// Navigation title for public key screen
      public static let title = L10n.tr("Settings", "publicKey.title", fallback: "Public Key")
      public enum Base64 {
        /// Section header for base64 representation
        public static let header = L10n.tr("Settings", "publicKey.base64.header", fallback: "Base64")
      }
    }
    public enum Radio {
      /// Footer explaining radio presets
      public static let footer = L10n.tr("Settings", "radio.footer", fallback: "Choose a preset matching your region. MeshCore devices must use the same radio settings in order to communicate.")
      /// Section header for radio settings
      public static let header = L10n.tr("Settings", "radio.header", fallback: "Radio")
      /// Label for radio preset picker
      public static let preset = L10n.tr("Settings", "radio.preset", fallback: "Radio Preset")
      /// Toggle label for repeat mode
      public static let repeatMode = L10n.tr("Settings", "radio.repeatMode", fallback: "Repeat Mode")
      public enum RepeatMode {
        /// Accessibility hint for repeat mode toggle
        public static let accessibilityHint = L10n.tr("Settings", "radio.repeatMode.accessibilityHint", fallback: "Enabling this will disconnect you from the main mesh network")
        /// Footer explaining repeat mode
        public static let footer = L10n.tr("Settings", "radio.repeatMode.footer", fallback: "Creates a local repeater on a dedicated frequency. Useful for hiking and remote areas.")
        public enum Confirm {
          /// Confirmation dialog enable button
          public static let enable = L10n.tr("Settings", "radio.repeatMode.confirm.enable", fallback: "Enable")
          /// Confirmation dialog message for enabling repeat mode
          public static let message = L10n.tr("Settings", "radio.repeatMode.confirm.message", fallback: "You will be disconnected from the main mesh network. Only devices on the same Repeat Mode frequency will be reachable.")
          /// Confirmation dialog title for enabling repeat mode
          public static let title = L10n.tr("Settings", "radio.repeatMode.confirm.title", fallback: "Enable Repeat Mode?")
        }
      }
    }
    public enum RegenerateIdentity {
      /// Button to generate a new key
      public static let generate = L10n.tr("Settings", "regenerateIdentity.generate", fallback: "Generate Key")
      /// Button to generate another key after the first
      public static let generateAnother = L10n.tr("Settings", "regenerateIdentity.generateAnother", fallback: "Generate Another")
      /// Progress label while generating
      public static let generating = L10n.tr("Settings", "regenerateIdentity.generating", fallback: "Generating…")
      /// Section header for regenerate identity
      public static let header = L10n.tr("Settings", "regenerateIdentity.header", fallback: "Identity")
      /// Progress label while importing key to device
      public static let importing = L10n.tr("Settings", "regenerateIdentity.importing", fallback: "Importing…")
      /// Button to apply the generated key to the device
      public static let replace = L10n.tr("Settings", "regenerateIdentity.replace", fallback: "Use This Key")
      /// Button label to open regenerate identity sheet
      public static let title = L10n.tr("Settings", "regenerateIdentity.title", fallback: "Regenerate Key")
      public enum Alert {
        public enum Replace {
          /// Alert confirm button for replace identity
          public static let confirm = L10n.tr("Settings", "regenerateIdentity.alert.replace.confirm", fallback: "Replace")
          /// Alert message for replace identity
          public static let message = L10n.tr("Settings", "regenerateIdentity.alert.replace.message", fallback: "This will permanently replace your device's identity. Other devices will see you as a new contact.")
          /// Alert title for replace identity confirmation
          public static let title = L10n.tr("Settings", "regenerateIdentity.alert.replace.title", fallback: "Replace Identity?")
        }
      }
      public enum Error {
        /// Error when device rejects the key
        public static let deviceRejected = L10n.tr("Settings", "regenerateIdentity.error.deviceRejected", fallback: "The device rejected the key. Please try again.")
        /// Error when firmware does not support key import
        public static let featureDisabled = L10n.tr("Settings", "regenerateIdentity.error.featureDisabled", fallback: "Key import is not supported by this firmware version")
      }
      public enum Prefix {
        /// Footer explaining vanity prefix
        public static let footer = L10n.tr("Settings", "regenerateIdentity.prefix.footer", fallback: "Optionally specify up to 4 hex characters your public key should start with. Longer prefixes take more time.")
        /// Disclosure group label for custom prefix
        public static let label = L10n.tr("Settings", "regenerateIdentity.prefix.label", fallback: "Custom Prefix")
        /// Placeholder for prefix text field
        public static let placeholder = L10n.tr("Settings", "regenerateIdentity.prefix.placeholder", fallback: "e.g. 2BA1")
        public enum Error {
          /// Error when prefix is not 1–4 hex characters
          public static let invalidLength = L10n.tr("Settings", "regenerateIdentity.prefix.error.invalidLength", fallback: "Prefix must be 1–4 hex characters")
          /// Error when prefix is 00 or FF
          public static let reserved = L10n.tr("Settings", "regenerateIdentity.prefix.error.reserved", fallback: "00 and FF are reserved and cannot be used")
        }
      }
      public enum Sheet {
        /// Explanation of what regenerating identity does
        public static let explanation = L10n.tr("Settings", "regenerateIdentity.sheet.explanation", fallback: "Generate a new Ed25519 keypair to replace your device's current identity. All contacts will need to re-discover your device.")
        /// Navigation title for the regenerate identity sheet
        public static let title = L10n.tr("Settings", "regenerateIdentity.sheet.title", fallback: "Regenerate Key")
      }
    }
    public enum ReplyWithQuote {
      /// Replying includes a preview of the original message.
      public static let footer = L10n.tr("Settings", "replyWithQuote.footer", fallback: "Replying includes a preview of the original message.")
      /// Reply with Quote
      public static let toggle = L10n.tr("Settings", "replyWithQuote.toggle", fallback: "Reply with Quote")
    }
    public enum Settings {
      public enum Backup {
        /// Navigation title for backup & restore screen
        public static let title = L10n.tr("Settings", "settings.backup.title", fallback: "Backup & Restore")
        public enum Error {
          /// Backup error: manifest counts do not match arrays
          public static let corruptedManifest = L10n.tr("Settings", "settings.backup.error.corrupted_manifest", fallback: "The backup file appears to be corrupted. The declared item counts do not match the actual data.")
          /// Backup error: decompressed size exceeds safety cap; %d is max MB uncompressed
          public static func decompressedTooLarge(_ p1: Int) -> String {
            return L10n.tr("Settings", "settings.backup.error.decompressed_too_large", p1, fallback: "The backup file expands past the safe size limit (%d MB uncompressed).")
          }
          /// Backup error: export failed; %@ is underlying error
          public static func exportFailed(_ p1: Any) -> String {
            return L10n.tr("Settings", "settings.backup.error.export_failed", String(describing: p1), fallback: "Failed to create backup: %@")
          }
          /// Backup error: file exceeds parser size cap; %1$d actual MB, %2$d max MB
          public static func fileTooLarge(_ p1: Int, _ p2: Int) -> String {
            return L10n.tr("Settings", "settings.backup.error.file_too_large", p1, p2, fallback: "The backup file is too large to import (%1$d MB; limit is %2$d MB).")
          }
          /// Backup error: import failed; %@ is underlying error
          public static func importFailed(_ p1: Any) -> String {
            return L10n.tr("Settings", "settings.backup.error.import_failed", String(describing: p1), fallback: "Failed to import backup: %@")
          }
          /// Backup error: file is corrupt or unreadable
          public static let invalidFile = L10n.tr("Settings", "settings.backup.error.invalid_file", fallback: "The backup file is invalid or could not be read.")
          /// Backup error: file version too new; %1$d found, %2$d max supported
          public static func unsupportedVersion(_ p1: Int, _ p2: Int) -> String {
            return L10n.tr("Settings", "settings.backup.error.unsupported_version", p1, p2, fallback: "This backup was created with a newer format (version %1$d). This app supports up to version %2$d. Please update the app and try again.")
          }
        }
        public enum Export {
          /// Default filename for an exported backup; %@ is an ISO-style timestamp
          public static func defaultFilename(_ p1: Any) -> String {
            return L10n.tr("Settings", "settings.backup.export.default_filename", String(describing: p1), fallback: "MC1 Backup %@.mc1backup")
          }
          /// Export progress label
          public static let progress = L10n.tr("Settings", "settings.backup.export.progress", fallback: "Preparing backup…")
          /// Export row subtitle
          public static let subtitle = L10n.tr("Settings", "settings.backup.export.subtitle", fallback: "Save to a .mc1backup file")
          /// Export row title
          public static let title = L10n.tr("Settings", "settings.backup.export.title", fallback: "Export App Data")
          public enum Alert {
            /// Export confirmation alert cancel button
            public static let cancel = L10n.tr("Settings", "settings.backup.export.alert.cancel", fallback: "Cancel")
            /// Export confirmation alert export button
            public static let export = L10n.tr("Settings", "settings.backup.export.alert.export", fallback: "Export")
            /// Export confirmation alert message
            public static let message = L10n.tr("Settings", "settings.backup.export.alert.message", fallback: "This backup includes channel encryption keys, message history, and your contact list. Store the exported file securely.")
            /// Export confirmation alert title
            public static let title = L10n.tr("Settings", "settings.backup.export.alert.title", fallback: "Security Notice")
          }
          public enum Success {
            /// VoiceOver announcement when the export success sheet appears; %@ is the filename
            public static func announcement(_ p1: Any) -> String {
              return L10n.tr("Settings", "settings.backup.export.success.announcement", String(describing: p1), fallback: "Backup saved as %@")
            }
            /// Primary button on the export success sheet
            public static let done = L10n.tr("Settings", "settings.backup.export.success.done", fallback: "Done")
            /// Section header listing record counts included in the export
            public static let includedSection = L10n.tr("Settings", "settings.backup.export.success.included_section", fallback: "Included in backup")
            /// Hero title on the backup export success sheet
            public static let title = L10n.tr("Settings", "settings.backup.export.success.title", fallback: "Backup saved")
          }
        }
        public enum FileBackup {
          /// Section footer for file backup
          public static let footer = L10n.tr("Settings", "settings.backup.file_backup.footer", fallback: "Export or restore messages, contacts, channels, saved paths, and settings. Radio configuration is read from the device on each connection.")
          /// Section header for file backup
          public static let header = L10n.tr("Settings", "settings.backup.file_backup.header", fallback: "File Backup")
        }
        public enum Import {
          /// Import cancelling label shown after the user taps Cancel during an active import
          public static let cancelling = L10n.tr("Settings", "settings.backup.import.cancelling", fallback: "Cancelling…")
          /// Footer shown above the backup section when a radio is connected, explaining why import is disabled
          public static let disabledWhenConnected = L10n.tr("Settings", "settings.backup.import.disabled_when_connected", fallback: "Disconnect from your radio before importing a backup.")
          /// File access error
          public static let fileAccessError = L10n.tr("Settings", "settings.backup.import.file_access_error", fallback: "Could not access the selected file.")
          /// Import parsing label
          public static let parsing = L10n.tr("Settings", "settings.backup.import.parsing", fallback: "Reading backup…")
          /// Import progress label
          public static let progress = L10n.tr("Settings", "settings.backup.import.progress", fallback: "Importing data…")
          /// Import row subtitle
          public static let subtitle = L10n.tr("Settings", "settings.backup.import.subtitle", fallback: "Restore from a .mc1backup file")
          /// Import row title
          public static let title = L10n.tr("Settings", "settings.backup.import.title", fallback: "Import App Data")
          public enum Cancelled {
            /// Import cancelled message — rollback ran and nothing on device was changed
            public static let message = L10n.tr("Settings", "settings.backup.import.cancelled.message", fallback: "No data was changed.")
            /// Import cancelled title shown after user cancels an import before it commits
            public static let title = L10n.tr("Settings", "settings.backup.import.cancelled.title", fallback: "Import Cancelled")
          }
          public enum Error {
            /// Import error dismiss button
            public static let dismiss = L10n.tr("Settings", "settings.backup.import.error.dismiss", fallback: "Dismiss")
            /// Import failure title
            public static let title = L10n.tr("Settings", "settings.backup.import.error.title", fallback: "Import Failed")
          }
          public enum NothingToImport {
            /// Import subtitle when the backup had nothing new to add
            public static let subtitle = L10n.tr("Settings", "settings.backup.import.nothing_to_import.subtitle", fallback: "This backup's contents are already on this device.")
            /// Import header title when the backup had nothing new to add (every record was skipped)
            public static let title = L10n.tr("Settings", "settings.backup.import.nothing_to_import.title", fallback: "Everything is already here")
          }
          public enum Preview {
            /// Import preview label: app version
            public static let appVersion = L10n.tr("Settings", "settings.backup.import.preview.app_version", fallback: "App Version")
            /// Import preview manifest label: blocked senders
            public static let blockedSenders = L10n.tr("Settings", "settings.backup.import.preview.blocked_senders", fallback: "Blocked Senders")
            /// Import preview import button
            public static let button = L10n.tr("Settings", "settings.backup.import.preview.button", fallback: "Import Data")
            /// Import preview cancel / dismiss toolbar button
            public static let cancel = L10n.tr("Settings", "settings.backup.import.preview.cancel", fallback: "Cancel")
            /// Import preview manifest label: channels
            public static let channels = L10n.tr("Settings", "settings.backup.import.preview.channels", fallback: "Channels")
            /// Import preview manifest label: contacts
            public static let contacts = L10n.tr("Settings", "settings.backup.import.preview.contacts", fallback: "Contacts")
            /// Import preview section header: contents
            public static let contents = L10n.tr("Settings", "settings.backup.import.preview.contents", fallback: "Contents")
            /// Import preview section header: backup details
            public static let details = L10n.tr("Settings", "settings.backup.import.preview.details", fallback: "Backup Details")
            /// Import preview manifest label: devices
            public static let devices = L10n.tr("Settings", "settings.backup.import.preview.devices", fallback: "Devices")
            /// Import preview label: export date
            public static let exported = L10n.tr("Settings", "settings.backup.import.preview.exported", fallback: "Export Date")
            /// Import preview info text
            public static let info = L10n.tr("Settings", "settings.backup.import.preview.info", fallback: "Only new data is added; existing records aren't overwritten. Contacts you deleted after this backup was made will reappear, and any block, mute, or favorite flags from the backup will be re-applied.")
            /// Import preview manifest label: message repeats
            public static let messageRepeats = L10n.tr("Settings", "settings.backup.import.preview.message_repeats", fallback: "Message Repeats")
            /// Import preview manifest label: messages
            public static let messages = L10n.tr("Settings", "settings.backup.import.preview.messages", fallback: "Messages")
            /// Import preview manifest label: node status snapshots
            public static let nodeStatusSnapshots = L10n.tr("Settings", "settings.backup.import.preview.node_status_snapshots", fallback: "Node Snapshots")
            /// Import preview manifest label: reactions
            public static let reactions = L10n.tr("Settings", "settings.backup.import.preview.reactions", fallback: "Reactions")
            /// Import preview manifest label: remote node sessions
            public static let remoteNodeSessions = L10n.tr("Settings", "settings.backup.import.preview.remote_node_sessions", fallback: "Remote Node Sessions")
            /// Import preview manifest label: room messages
            public static let roomMessages = L10n.tr("Settings", "settings.backup.import.preview.room_messages", fallback: "Room Messages")
            /// Import preview manifest label: saved paths
            public static let savedPaths = L10n.tr("Settings", "settings.backup.import.preview.saved_paths", fallback: "Saved Paths")
            /// Import preview navigation title
            public static let title = L10n.tr("Settings", "settings.backup.import.preview.title", fallback: "Import Preview")
          }
          public enum Success {
            /// Import success section header: items that were newly added to this device
            public static let addedSection = L10n.tr("Settings", "settings.backup.import.success.added_section", fallback: "Added to this device")
            /// Import success section footer: always visible under the "Already on this device" section
            public static let alreadyHereFooter = L10n.tr("Settings", "settings.backup.import.success.already_here_footer", fallback: "Your device's existing data was kept as-is; nothing was overwritten.")
            /// Import success footer line appended when some of the already-here items had metadata refreshed (pluralized in .stringsdict)
            public static func alreadyHereRefreshed(_ p1: Int) -> String {
              return L10n.tr("Settings", "settings.backup.import.success.already_here_refreshed", p1, fallback: "%d of these were refreshed with newer info from the backup.")
            }
            /// Import success section header: items in the backup that were already on this device
            public static let alreadyHereSection = L10n.tr("Settings", "settings.backup.import.success.already_here_section", fallback: "Already on this device")
            /// Import success disclosure row summary for the "Already on this device" section (pluralized in .stringsdict)
            public static func alreadyHereSummary(_ p1: Int) -> String {
              return L10n.tr("Settings", "settings.backup.import.success.already_here_summary", p1, fallback: "%d items already here")
            }
            /// Import success done button
            public static let done = L10n.tr("Settings", "settings.backup.import.success.done", fallback: "Done")
            /// Import success hero subtitle when new items were added (pluralized in .stringsdict)
            public static func subtitleAdded(_ p1: Int) -> String {
              return L10n.tr("Settings", "settings.backup.import.success.subtitle_added", p1, fallback: "%d items added.")
            }
            /// Import success hero subtitle when only existing items were refreshed (pluralized in .stringsdict)
            public static func subtitleRefreshed(_ p1: Int) -> String {
              return L10n.tr("Settings", "settings.backup.import.success.subtitle_refreshed", p1, fallback: "%d existing items refreshed.")
            }
            /// Import success title
            public static let title = L10n.tr("Settings", "settings.backup.import.success.title", fallback: "Import Complete")
          }
        }
      }
    }
    public enum Telemetry {
      /// Toggle label for allowing telemetry requests
      public static let allowRequests = L10n.tr("Settings", "telemetry.allowRequests", fallback: "Allow Telemetry Requests")
      /// Description for telemetry requests toggle
      public static let allowRequestsDescription = L10n.tr("Settings", "telemetry.allowRequestsDescription", fallback: "Required for other users to manually trace a path to you. Shares battery level.")
      /// Footer explaining telemetry
      public static let footer = L10n.tr("Settings", "telemetry.footer", fallback: "When enabled, other nodes can request your device's telemetry data.")
      /// Section header for telemetry settings
      public static let header = L10n.tr("Settings", "telemetry.header", fallback: "Telemetry")
      /// Toggle label for including environment sensors
      public static let includeEnvironment = L10n.tr("Settings", "telemetry.includeEnvironment", fallback: "Include Environment Sensors")
      /// Description for include environment toggle
      public static let includeEnvironmentDescription = L10n.tr("Settings", "telemetry.includeEnvironmentDescription", fallback: "Share temperature, humidity, etc.")
      /// Toggle label for including location in telemetry
      public static let includeLocation = L10n.tr("Settings", "telemetry.includeLocation", fallback: "Include Location")
      /// Description for include location toggle
      public static let includeLocationDescription = L10n.tr("Settings", "telemetry.includeLocationDescription", fallback: "Share GPS coordinates in telemetry")
      /// Link to manage trusted contacts
      public static let manageTrusted = L10n.tr("Settings", "telemetry.manageTrusted", fallback: "Manage Trusted Contacts")
      /// Toggle label for trusted contacts only
      public static let trustedOnly = L10n.tr("Settings", "telemetry.trustedOnly", fallback: "Only Share with Trusted Contacts")
      /// Description for trusted contacts toggle
      public static let trustedOnlyDescription = L10n.tr("Settings", "telemetry.trustedOnlyDescription", fallback: "Limit telemetry to selected contacts")
    }
    public enum TrustedContacts {
      /// Apply button label
      public static let apply = L10n.tr("Settings", "trustedContacts.apply", fallback: "Apply")
      /// Toggle label for showing only favorite contacts
      public static let favoritesOnly = L10n.tr("Settings", "trustedContacts.favoritesOnly", fallback: "Favorites Only")
      /// Title for empty state when no contacts exist
      public static let noContacts = L10n.tr("Settings", "trustedContacts.noContacts", fallback: "No Contacts")
      /// Description for empty state
      public static let noContactsDescription = L10n.tr("Settings", "trustedContacts.noContactsDescription", fallback: "Add contacts to select trusted ones")
      /// Title for empty state when search returns no results
      public static let noResults = L10n.tr("Settings", "trustedContacts.noResults", fallback: "No Results")
      /// Description for empty state when search returns no results
      public static func noResultsDescription(_ p1: Any) -> String {
        return L10n.tr("Settings", "trustedContacts.noResultsDescription", String(describing: p1), fallback: "No contacts match \"%@\"")
      }
      /// Search prompt for trusted contacts list
      public static let searchPrompt = L10n.tr("Settings", "trustedContacts.searchPrompt", fallback: "Search Contacts")
      /// Navigation title for trusted contacts picker
      public static let title = L10n.tr("Settings", "trustedContacts.title", fallback: "Trusted Contacts")
    }
    public enum Wifi {
      /// Label for IP address
      public static let address = L10n.tr("Settings", "wifi.address", fallback: "Address")
      /// Button to edit WiFi connection
      public static let editConnection = L10n.tr("Settings", "wifi.editConnection", fallback: "Edit Connection")
      /// Footer explaining WiFi address
      public static let footer = L10n.tr("Settings", "wifi.footer", fallback: "Your device's local network address")
      /// Section header for WiFi settings
      public static let header = L10n.tr("Settings", "wifi.header", fallback: "WiFi")
      /// Label for port number
      public static let port = L10n.tr("Settings", "wifi.port", fallback: "Port")
    }
    public enum WifiEdit {
      /// Accessibility label for clear IP button
      public static let clearIp = L10n.tr("Settings", "wifiEdit.clearIp", fallback: "Clear IP address")
      /// Accessibility label for clear port button
      public static let clearPort = L10n.tr("Settings", "wifiEdit.clearPort", fallback: "Clear port")
      /// Section header for connection details
      public static let connectionDetails = L10n.tr("Settings", "wifiEdit.connectionDetails", fallback: "Connection Details")
      /// Footer explaining reconnection
      public static let footer = L10n.tr("Settings", "wifiEdit.footer", fallback: "Changing these values will disconnect and reconnect to the new address.")
      /// Placeholder for IP address field
      public static let ipPlaceholder = L10n.tr("Settings", "wifiEdit.ipPlaceholder", fallback: "IP Address")
      /// Placeholder for port field
      public static let portPlaceholder = L10n.tr("Settings", "wifiEdit.portPlaceholder", fallback: "Port")
      /// Text shown while reconnecting
      public static let reconnecting = L10n.tr("Settings", "wifiEdit.reconnecting", fallback: "Reconnecting...")
      /// Button to save changes
      public static let saveChanges = L10n.tr("Settings", "wifiEdit.saveChanges", fallback: "Save Changes")
      /// Navigation title for WiFi edit sheet
      public static let title = L10n.tr("Settings", "wifiEdit.title", fallback: "Edit WiFi Connection")
      public enum Error {
        /// Error for invalid port
        public static let invalidPort = L10n.tr("Settings", "wifiEdit.error.invalidPort", fallback: "Invalid port number")
      }
    }
  }
  public enum Tools {
    public enum Tools {
      /// Location: CLIToolView.swift - Tool selection label
      public static let cli = L10n.tr("Tools", "tools.cli", fallback: "CLI")
      /// Location: ToolsView.swift - Tool selection label
      public static let lineOfSight = L10n.tr("Tools", "tools.lineOfSight", fallback: "Line of Sight")
      /// Location: ToolsView.swift - Tool selection label
      public static let nodeDiscovery = L10n.tr("Tools", "tools.nodeDiscovery", fallback: "Discover Nodes")
      /// Location: ToolsView.swift - Tool selection label
      public static let noiseFloor = L10n.tr("Tools", "tools.noiseFloor", fallback: "Noise Floor")
      /// Location: ToolsView.swift - Tool selection label
      public static let rxLog = L10n.tr("Tools", "tools.rxLog", fallback: "RX Log")
      /// Location: ToolsView.swift - Empty state when no tool selected
      public static let selectTool = L10n.tr("Tools", "tools.selectTool", fallback: "Select a tool")
      /// Location: ToolsView.swift - Navigation title
      public static let title = L10n.tr("Tools", "tools.title", fallback: "Tools")
      /// Location: ToolsView.swift - Tool selection label
      public static let tracePath = L10n.tr("Tools", "tools.tracePath", fallback: "Trace Path")
      public enum Cli {
        /// Location: CLIToolView.swift - Command cancelled
        public static let cancelled = L10n.tr("Tools", "tools.cli.cancelled", fallback: "Command cancelled")
        /// Location: CLIInputAccessoryView.swift - Cancel operation button label
        public static let cancelOperation = L10n.tr("Tools", "tools.cli.cancelOperation", fallback: "Cancel operation")
        /// Location: CLIToolViewModel+Sessions.swift - Channel empty name placeholder
        public static let channelEmpty = L10n.tr("Tools", "tools.cli.channelEmpty", fallback: "empty")
        /// Location: CLIToolViewModel+Sessions.swift - Channels header with count
        public static func channelsHeader(_ p1: Int) -> String {
          return L10n.tr("Tools", "tools.cli.channelsHeader", p1, fallback: "Channels (%lld):")
        }
        /// Location: CLIToolView.swift - Accessory button: clear
        public static let clear = L10n.tr("Tools", "tools.cli.clear", fallback: "Clear")
        /// Location: CLIToolView.swift - Accessibility label for command input
        public static let commandInput = L10n.tr("Tools", "tools.cli.commandInput", fallback: "Command input")
        /// Location: CLIToolView.swift - Accessibility label for command prompt
        public static let commandPrompt = L10n.tr("Tools", "tools.cli.commandPrompt", fallback: "Command prompt")
        /// Location: CLIToolViewModel.swift - Command timeout (post-login)
        public static let commandTimeout = L10n.tr("Tools", "tools.cli.commandTimeout", fallback: "Request timed out")
        /// Accessibility label for completion suggestions container
        public static let completionSuggestions = L10n.tr("Tools", "tools.cli.completionSuggestions", fallback: "Completion suggestions")
        /// Accessibility value for completion suggestions - %lld is count, %@ is selected
        public static func completionSuggestionsValue(_ p1: Int, _ p2: Any) -> String {
          return L10n.tr("Tools", "tools.cli.completionSuggestionsValue", p1, String(describing: p2), fallback: "%lld available, %@ selected")
        }
        /// Location: CLIInputAccessoryView.swift - Cursor left button
        public static let cursorLeft = L10n.tr("Tools", "tools.cli.cursorLeft", fallback: "Move cursor left")
        /// Location: CLIInputAccessoryView.swift - Cursor right button
        public static let cursorRight = L10n.tr("Tools", "tools.cli.cursorRight", fallback: "Move cursor right")
        /// Location: CLIToolView.swift - Default device name
        public static let defaultDevice = L10n.tr("Tools", "tools.cli.defaultDevice", fallback: "Device")
        /// Location: CLIToolView.swift - Disconnected prompt
        public static let disconnected = L10n.tr("Tools", "tools.cli.disconnected", fallback: "disconnected")
        /// Location: CLIToolView.swift - Accessory button: dismiss
        public static let dismiss = L10n.tr("Tools", "tools.cli.dismiss", fallback: "Dismiss keyboard")
        /// Location: CLIToolViewModel.swift - Help: channels command
        public static let helpChannels = L10n.tr("Tools", "tools.cli.helpChannels", fallback: "  channels\n    Show list of channels with device slot numbers")
        /// Location: CLIToolViewModel.swift - Help: clear command
        public static let helpClear = L10n.tr("Tools", "tools.cli.helpClear", fallback: "  clear\n    Clear terminal")
        /// Location: CLIToolView.swift - Help command output header
        public static let helpHeader = L10n.tr("Tools", "tools.cli.helpHeader", fallback: "Available commands:")
        /// Location: CLIToolViewModel.swift - Help: help command
        public static let helpHelp = L10n.tr("Tools", "tools.cli.helpHelp", fallback: "  help\n    Show this help")
        /// Location: CLIToolViewModel.swift - Help: login command
        public static let helpLogin = L10n.tr("Tools", "tools.cli.helpLogin", fallback: "  login [-f] <node>\n    Login to repeater (-f: forget saved password)")
        /// Location: CLIToolViewModel.swift - Help: logout command
        public static let helpLogout = L10n.tr("Tools", "tools.cli.helpLogout", fallback: "  logout\n    End remote session")
        /// Location: CLIToolViewModel.swift - Help: nodes command
        public static let helpNodes = L10n.tr("Tools", "tools.cli.helpNodes", fallback: "  nodes\n    Show list of repeaters and rooms")
        /// Location: CLIToolViewModel.swift - Help: repeater commands header
        public static let helpRepeaterHeader = L10n.tr("Tools", "tools.cli.helpRepeaterHeader", fallback: "Repeater commands (passthrough):")
        /// Location: CLIToolViewModel.swift - Help: repeater commands list 1
        public static let helpRepeaterList1 = L10n.tr("Tools", "tools.cli.helpRepeaterList1", fallback: "  ver, clock, reboot, advert, neighbors")
        /// Location: CLIToolViewModel.swift - Help: repeater commands list 2
        public static let helpRepeaterList2 = L10n.tr("Tools", "tools.cli.helpRepeaterList2", fallback: "  get/set <param>, password <new>")
        /// Location: CLIToolViewModel.swift - Help: repeater commands list 3
        public static let helpRepeaterList3 = L10n.tr("Tools", "tools.cli.helpRepeaterList3", fallback: "  log start/stop/erase")
        /// Location: CLIToolViewModel.swift - Help: repeater commands list 4
        public static let helpRepeaterList4 = L10n.tr("Tools", "tools.cli.helpRepeaterList4", fallback: "  setperm, tempradio, neighbor.remove")
        /// Location: CLIToolViewModel.swift - Help: session list command
        public static let helpSessionList = L10n.tr("Tools", "tools.cli.helpSessionList", fallback: "  session list\n    Show active sessions")
        /// Location: CLIToolViewModel.swift - Help: session local command
        public static let helpSessionLocal = L10n.tr("Tools", "tools.cli.helpSessionLocal", fallback: "  session local\n    Switch to local")
        /// Location: CLIToolViewModel.swift - Help: session name command
        public static let helpSessionName = L10n.tr("Tools", "tools.cli.helpSessionName", fallback: "  session <name>\n    Switch to session")
        /// Location: CLIToolViewModel.swift - Help: session shortcut
        public static let helpSessionShortcut = L10n.tr("Tools", "tools.cli.helpSessionShortcut", fallback: "  s<n>\n    Switch to session n (e.g., s1, s2)")
        /// Location: CLIToolView.swift - Accessory button: history down
        public static let historyDown = L10n.tr("Tools", "tools.cli.historyDown", fallback: "Next command")
        /// Location: CLIToolView.swift - History empty message
        public static let historyEmpty = L10n.tr("Tools", "tools.cli.historyEmpty", fallback: "No command history")
        /// Location: CLIToolView.swift - Accessory button: history up
        public static let historyUp = L10n.tr("Tools", "tools.cli.historyUp", fallback: "Previous command")
        /// Location: CLIToolView.swift - Jump to bottom button
        public static let jumpToBottom = L10n.tr("Tools", "tools.cli.jumpToBottom", fallback: "Jump to bottom")
        /// Location: CLIToolViewModel.swift - Login countdown
        public static func loggingIn(_ p1: Int) -> String {
          return L10n.tr("Tools", "tools.cli.loggingIn", p1, fallback: "Logging in... (%ds)")
        }
        /// Location: CLIToolView.swift - Login failed
        public static let loginFailed = L10n.tr("Tools", "tools.cli.loginFailed", fallback: "Login failed:")
        /// Location: CLIToolView.swift - Login failed reason
        public static let loginFailedAuth = L10n.tr("Tools", "tools.cli.loginFailedAuth", fallback: "Authentication failed")
        /// Location: CLIToolView.swift - Login from local only
        public static let loginFromLocalOnly = L10n.tr("Tools", "tools.cli.loginFromLocalOnly", fallback: "Login only available from local session")
        /// Location: CLIToolView.swift - Login success
        public static let loginSuccess = L10n.tr("Tools", "tools.cli.loginSuccess", fallback: "Logged in to")
        /// Location: CLIToolView.swift - Login usage
        public static let loginUsage = L10n.tr("Tools", "tools.cli.loginUsage", fallback: "Usage: login [-f] <node>")
        /// Location: CLIToolView.swift - Logout success
        public static let logoutSuccess = L10n.tr("Tools", "tools.cli.logoutSuccess", fallback: "Logged out")
        /// Location: CLIToolViewModel+Sessions.swift - No channels found
        public static let noChannels = L10n.tr("Tools", "tools.cli.noChannels", fallback: "No channels found")
        /// Location: CLIToolView.swift - Node not found error
        public static let nodeNotFound = L10n.tr("Tools", "tools.cli.nodeNotFound", fallback: "Node not found:")
        /// Location: CLIToolViewModel+Sessions.swift - Nodes header with count
        public static func nodesHeader(_ p1: Int) -> String {
          return L10n.tr("Tools", "tools.cli.nodesHeader", p1, fallback: "Nodes (%lld):")
        }
        /// Location: CLIToolViewModel+Sessions.swift - No nodes found
        public static let noNodes = L10n.tr("Tools", "tools.cli.noNodes", fallback: "No nodes found")
        /// Location: CLIToolView.swift - No sessions message
        public static let noSessions = L10n.tr("Tools", "tools.cli.noSessions", fallback: "No active sessions")
        /// Location: CLIToolView.swift - Disconnected state title
        public static let notConnected = L10n.tr("Tools", "tools.cli.notConnected", fallback: "Not Connected")
        /// Location: CLIToolView.swift - Disconnected state description
        public static let notConnectedDescription = L10n.tr("Tools", "tools.cli.notConnectedDescription", fallback: "Connect to a mesh radio to use the CLI.")
        /// Location: CLIToolView.swift - Not logged in error
        public static let notLoggedIn = L10n.tr("Tools", "tools.cli.notLoggedIn", fallback: "Not logged in to any repeater")
        /// Location: CLIToolViewModel.swift - Password prompt
        public static let passwordPrompt = L10n.tr("Tools", "tools.cli.passwordPrompt", fallback: "Password:")
        /// Location: CLIToolView.swift - Password required error
        public static let passwordRequired = L10n.tr("Tools", "tools.cli.passwordRequired", fallback: "Password required")
        /// Location: CLIInputAccessoryView.swift - Paste button
        public static let paste = L10n.tr("Tools", "tools.cli.paste", fallback: "Paste")
        /// Location: CLIToolView.swift - Prompt suffix
        public static let promptSuffix = L10n.tr("Tools", "tools.cli.promptSuffix", fallback: ">")
        /// Location: CLIToolViewModel.swift - Reboot command confirmation
        public static let rebootSent = L10n.tr("Tools", "tools.cli.rebootSent", fallback: "Reboot command sent")
        /// Location: CLIToolView.swift - Session list header
        public static let sessionListHeader = L10n.tr("Tools", "tools.cli.sessionListHeader", fallback: "Active sessions:")
        /// Location: CLIToolView.swift - Local session label
        public static let sessionLocal = L10n.tr("Tools", "tools.cli.sessionLocal", fallback: "local")
        /// Location: CLIToolView.swift - Session not found
        public static let sessionNotFound = L10n.tr("Tools", "tools.cli.sessionNotFound", fallback: "Session not found:")
        /// Location: CLIToolView.swift - Accessory button: sessions
        public static let sessions = L10n.tr("Tools", "tools.cli.sessions", fallback: "Sessions")
        /// Location: CLIToolView.swift - Session switched
        public static let sessionSwitched = L10n.tr("Tools", "tools.cli.sessionSwitched", fallback: "Switched to")
        /// Location: CLIToolView.swift - Accessory button: tab complete
        public static let tabComplete = L10n.tr("Tools", "tools.cli.tabComplete", fallback: "Tab complete")
        /// Location: CLIToolView.swift - Command timeout
        public static let timeout = L10n.tr("Tools", "tools.cli.timeout", fallback: "Timeout waiting for response")
        /// Location: CLIToolView.swift - Unknown command error
        public static let unknownCommand = L10n.tr("Tools", "tools.cli.unknownCommand", fallback: "Unknown command:")
        /// Location: CLIToolView.swift - Waiting indicator
        public static let waiting = L10n.tr("Tools", "tools.cli.waiting", fallback: "...")
        /// Location: CLIToolViewModel.swift - Welcome banner line 2
        public static func welcomeConnected(_ p1: Any) -> String {
          return L10n.tr("Tools", "tools.cli.welcomeConnected", String(describing: p1), fallback: "Connected to %@")
        }
        /// Location: CLIToolViewModel.swift - Welcome banner line 3
        public static let welcomeHint = L10n.tr("Tools", "tools.cli.welcomeHint", fallback: "Type 'help' for available commands.")
        /// Location: CLIToolViewModel.swift - Welcome banner line 1
        public static let welcomeLine1 = L10n.tr("Tools", "tools.cli.welcomeLine1", fallback: "MeshCore One CLI")
      }
      public enum LineOfSight {
        /// Location: LineOfSightView.swift - Additional height label
        public static let additionalHeight = L10n.tr("Tools", "tools.lineOfSight.additionalHeight", fallback: "Additional height")
        /// Location: LineOfSightView.swift - Add repeater button
        public static let addRepeater = L10n.tr("Tools", "tools.lineOfSight.addRepeater", fallback: "Add Repeater")
        /// Location: LineOfSightView.swift - Analysis failed title
        public static let analysisFailed = L10n.tr("Tools", "tools.lineOfSight.analysisFailed", fallback: "Analysis Failed")
        /// Location: LineOfSightView.swift - Analyze button
        public static let analyze = L10n.tr("Tools", "tools.lineOfSight.analyze", fallback: "Analyze Line of Sight")
        /// Location: LineOfSightView.swift - Analyzing progress
        public static let analyzing = L10n.tr("Tools", "tools.lineOfSight.analyzing", fallback: "Analyzing path...")
        /// Location: LineOfSightView.swift - Back button label
        public static let back = L10n.tr("Tools", "tools.lineOfSight.back", fallback: "Back")
        /// Location: LineOfSightView.swift - Cancel button
        public static let cancel = L10n.tr("Tools", "tools.lineOfSight.cancel", fallback: "Cancel")
        /// Location: LineOfSightView.swift - Drop pin mode enabled
        public static let cancelDropPin = L10n.tr("Tools", "tools.lineOfSight.cancelDropPin", fallback: "Cancel drop pin")
        /// Location: LineOfSightView.swift - Clear button
        public static let clear = L10n.tr("Tools", "tools.lineOfSight.clear", fallback: "Clear")
        /// Location: ResultsCardView.swift - Clearance section title
        public static let clearance = L10n.tr("Tools", "tools.lineOfSight.clearance", fallback: "Clearance")
        /// Location: ClearanceStatusView.swift - Clearance percentage, %lld is percent
        public static func clearancePercent(_ p1: Int) -> String {
          return L10n.tr("Tools", "tools.lineOfSight.clearancePercent", p1, fallback: "%lld%% clearance")
        }
        /// Location: LineOfSightView.swift - Copy coordinates button
        public static let copyCoordinates = L10n.tr("Tools", "tools.lineOfSight.copyCoordinates", fallback: "Copy Coordinates")
        /// Location: ResultsCardView.swift - Diffraction loss label
        public static let diffractionLoss = L10n.tr("Tools", "tools.lineOfSight.diffractionLoss", fallback: "Diffraction loss")
        /// Location: ResultsCardView.swift - Distance label
        public static let distance = L10n.tr("Tools", "tools.lineOfSight.distance", fallback: "Distance")
        /// Location: LineOfSightView.swift - Done button
        public static let done = L10n.tr("Tools", "tools.lineOfSight.done", fallback: "Done")
        /// Location: LineOfSightView.swift - Drag to adjust tooltip
        public static let dragToAdjust = L10n.tr("Tools", "tools.lineOfSight.dragToAdjust", fallback: "Drag to adjust")
        /// Location: LineOfSightViewModel.swift - Dropped pin display name
        public static let droppedPin = L10n.tr("Tools", "tools.lineOfSight.droppedPin", fallback: "Dropped pin")
        /// Location: LineOfSightView.swift - Drop pin mode disabled
        public static let dropPin = L10n.tr("Tools", "tools.lineOfSight.dropPin", fallback: "Drop pin")
        /// Location: LineOfSightView.swift - Earth curvature note, %@ is k-factor
        public static func earthCurvature(_ p1: Any) -> String {
          return L10n.tr("Tools", "tools.lineOfSight.earthCurvature", String(describing: p1), fallback: "Adjusted for earth curvature (%@)")
        }
        /// Location: LineOfSightView.swift - Edit button
        public static let edit = L10n.tr("Tools", "tools.lineOfSight.edit", fallback: "Edit")
        /// Location: TerrainProfileCanvas.swift - Elevation data attribution
        public static let elevationAttribution = L10n.tr("Tools", "tools.lineOfSight.elevationAttribution", fallback: "Elevation data: Copernicus DEM GLO-90 via Open-Meteo")
        /// Location: LineOfSightView.swift - Elevation unavailable warning
        public static let elevationUnavailable = L10n.tr("Tools", "tools.lineOfSight.elevationUnavailable", fallback: "Elevation data unavailable. Using sea level (0m) as approximation.")
        /// Location: ResultsCardView.swift - Free space loss label
        public static let freeSpaceLoss = L10n.tr("Tools", "tools.lineOfSight.freeSpaceLoss", fallback: "Free space loss")
        /// Location: LineOfSightView.swift - Frequency label
        public static let frequency = L10n.tr("Tools", "tools.lineOfSight.frequency", fallback: "Frequency")
        /// Location: LineOfSightView.swift - Ground elevation label
        public static let groundElevation = L10n.tr("Tools", "tools.lineOfSight.groundElevation", fallback: "Ground elevation")
        /// Location: TerrainProfileCanvas.swift - Indirect route label
        public static let indirectRoute = L10n.tr("Tools", "tools.lineOfSight.indirectRoute", fallback: "Indirect route via R · Relocate on map to adjust")
        /// Location: LineOfSightView.swift - Loading elevation status
        public static let loadingElevation = L10n.tr("Tools", "tools.lineOfSight.loadingElevation", fallback: "Loading elevation...")
        /// Location: ResultsCardView.swift - Loss suffix
        public static let loss = L10n.tr("Tools", "tools.lineOfSight.loss", fallback: "loss")
        /// Location: LineOfSightView.swift - MHz unit
        public static let mhz = L10n.tr("Tools", "tools.lineOfSight.mhz", fallback: "MHz")
        /// Location: TerrainProfileCanvas.swift - Empty state title
        public static let noData = L10n.tr("Tools", "tools.lineOfSight.noData", fallback: "No Data")
        /// Location: LineOfSightView.swift - Not selected placeholder
        public static let notSelected = L10n.tr("Tools", "tools.lineOfSight.notSelected", fallback: "Not selected")
        /// Location: ResultsCardView.swift - Obstructions found label
        public static let obstructionsFound = L10n.tr("Tools", "tools.lineOfSight.obstructionsFound", fallback: "Obstructions found")
        /// Location: LineOfSightView.swift - Open in Maps button
        public static let openInMaps = L10n.tr("Tools", "tools.lineOfSight.openInMaps", fallback: "Open in Maps")
        /// Location: ResultsCardView.swift - Path loss breakdown section
        public static let pathLossBreakdown = L10n.tr("Tools", "tools.lineOfSight.pathLossBreakdown", fallback: "Path Loss Breakdown")
        /// Location: LineOfSightView.swift - Point A annotation
        public static let pointA = L10n.tr("Tools", "tools.lineOfSight.pointA", fallback: "Point A")
        /// Location: LineOfSightView.swift - Point B annotation
        public static let pointB = L10n.tr("Tools", "tools.lineOfSight.pointB", fallback: "Point B")
        /// Location: LineOfSightView.swift - Points section title
        public static let points = L10n.tr("Tools", "tools.lineOfSight.points", fallback: "Points")
        /// Location: LineOfSightView.swift - Refraction label
        public static let refraction = L10n.tr("Tools", "tools.lineOfSight.refraction", fallback: "Refraction")
        /// Location: LineOfSightView.swift - Relocate button
        public static let relocate = L10n.tr("Tools", "tools.lineOfSight.relocate", fallback: "Relocate")
        /// Location: LineOfSightView.swift - Relocating message, %@ is point name
        public static func relocating(_ p1: Any) -> String {
          return L10n.tr("Tools", "tools.lineOfSight.relocating", String(describing: p1), fallback: "Relocating %@...")
        }
        /// Location: LineOfSightView.swift - Repeater annotation
        public static let repeater = L10n.tr("Tools", "tools.lineOfSight.repeater", fallback: "Repeater")
        /// Location: LineOfSightView.swift - Repeater location map item name
        public static let repeaterLocation = L10n.tr("Tools", "tools.lineOfSight.repeaterLocation", fallback: "Repeater Location")
        /// Location: ResultsCardView.swift - Section title
        public static let results = L10n.tr("Tools", "tools.lineOfSight.results", fallback: "Results")
        /// Location: LineOfSightView.swift - Retry button
        public static let retry = L10n.tr("Tools", "tools.lineOfSight.retry", fallback: "Retry")
        /// Location: LineOfSightView.swift - RF Settings section
        public static let rfSettings = L10n.tr("Tools", "tools.lineOfSight.rfSettings", fallback: "RF Settings")
        /// Location: LineOfSightView.swift - Select points hint
        public static let selectPointsHint = L10n.tr("Tools", "tools.lineOfSight.selectPointsHint", fallback: "Tap the pin button on the map to select points")
        /// Location: TerrainProfileCanvas.swift - Empty state description
        public static let selectTwoPoints = L10n.tr("Tools", "tools.lineOfSight.selectTwoPoints", fallback: "Select two points to analyze")
        /// Location: LineOfSightView.swift - Share button
        public static let share = L10n.tr("Tools", "tools.lineOfSight.share", fallback: "Share...")
        /// Location: LineOfSightView.swift - Share label
        public static let shareLabel = L10n.tr("Tools", "tools.lineOfSight.shareLabel", fallback: "Share")
        /// Location: ResultsCardView.swift - Status label
        public static let status = L10n.tr("Tools", "tools.lineOfSight.status", fallback: "Status")
        /// Location: LineOfSightView.swift - Tap map instruction
        public static let tapMapInstruction = L10n.tr("Tools", "tools.lineOfSight.tapMapInstruction", fallback: "Tap the map to set a new location")
        /// Location: LineOfSightView.swift - Terrain profile section
        public static let terrainProfile = L10n.tr("Tools", "tools.lineOfSight.terrainProfile", fallback: "Terrain Profile")
        /// Location: ResultsCardView.swift - Total label
        public static let total = L10n.tr("Tools", "tools.lineOfSight.total", fallback: "Total")
        /// Location: LineOfSightView.swift - Total height label
        public static let totalHeight = L10n.tr("Tools", "tools.lineOfSight.totalHeight", fallback: "Total height")
        /// Location: ResultsCardView.swift - Worst clearance label
        public static func worstClearance(_ p1: Int) -> String {
          return L10n.tr("Tools", "tools.lineOfSight.worstClearance", p1, fallback: "Worst clearance (% of 1st Fresnel)")
        }
        /// Location: ResultsCardView.swift - Worst clearance short label
        public static let worstClearanceShort = L10n.tr("Tools", "tools.lineOfSight.worstClearanceShort", fallback: "Worst clearance")
        public enum Legend {
          /// Location: TerrainProfileCanvas.swift - Legend: clear
          public static let clear = L10n.tr("Tools", "tools.lineOfSight.legend.clear", fallback: "Clear")
          /// Location: TerrainProfileCanvas.swift - Legend: line of sight
          public static let los = L10n.tr("Tools", "tools.lineOfSight.legend.los", fallback: "LOS")
          /// Location: TerrainProfileCanvas.swift - Legend: obstructed
          public static let obstructed = L10n.tr("Tools", "tools.lineOfSight.legend.obstructed", fallback: "Obstructed")
          /// Location: TerrainProfileCanvas.swift - Legend: terrain
          public static let terrain = L10n.tr("Tools", "tools.lineOfSight.legend.terrain", fallback: "Terrain")
        }
        public enum MapStyle {
          /// Location: LineOfSightView.swift - Map style: standard
          public static let standard = L10n.tr("Tools", "tools.lineOfSight.mapStyle.standard", fallback: "Standard")
          /// Location: LineOfSightView.swift - Map style: terrain
          public static let terrain = L10n.tr("Tools", "tools.lineOfSight.mapStyle.terrain", fallback: "Terrain")
        }
        public enum PointPin {
          /// Location: LOSPointPinView.swift - Accessibility hint for point pins
          public static let accessibilityHint = L10n.tr("Tools", "tools.lineOfSight.pointPin.accessibilityHint", fallback: "Line of sight analysis point")
        }
        public enum Refraction {
          /// Location: LineOfSightView.swift - Refraction: ducting
          public static let ducting = L10n.tr("Tools", "tools.lineOfSight.refraction.ducting", fallback: "Ducting (k=4)")
          /// Location: LineOfSightView.swift - Refraction: none
          public static let `none` = L10n.tr("Tools", "tools.lineOfSight.refraction.none", fallback: "None")
          /// Location: LineOfSightView.swift - Refraction: standard
          public static let standard = L10n.tr("Tools", "tools.lineOfSight.refraction.standard", fallback: "Standard (k=1.33)")
        }
        public enum RepeaterPin {
          /// Location: LOSRepeaterPinView.swift - Accessibility hint for repeater pins
          public static let accessibilityHint = L10n.tr("Tools", "tools.lineOfSight.repeaterPin.accessibilityHint", fallback: "Double tap to select as analysis point")
        }
        public enum RepeaterTarget {
          /// Location: LOSRepeaterTargetPinView.swift - Accessibility hint for repeater target
          public static let accessibilityHint = L10n.tr("Tools", "tools.lineOfSight.repeaterTarget.accessibilityHint", fallback: "Repeater placement marker")
        }
        public enum Status {
          /// Location: ClearanceStatusView.swift, ResultsCardView.swift - Clearance status: blocked
          public static let blocked = L10n.tr("Tools", "tools.lineOfSight.status.blocked", fallback: "Blocked")
          /// Location: ResultsCardView.swift - Subtitle shown when path is blocked
          public static let blockedSubtitle = L10n.tr("Tools", "tools.lineOfSight.status.blockedSubtitle", fallback: "Direct path intersects terrain")
          /// Location: ClearanceStatusView.swift, ResultsCardView.swift - Clearance status: clear
          public static let clear = L10n.tr("Tools", "tools.lineOfSight.status.clear", fallback: "Clear")
          /// Location: ClearanceStatusView.swift, ResultsCardView.swift - Clearance status: marginal
          public static let marginal = L10n.tr("Tools", "tools.lineOfSight.status.marginal", fallback: "Marginal")
          /// Location: ClearanceStatusView.swift, ResultsCardView.swift - Clearance status: partial obstruction
          public static let partialObstruction = L10n.tr("Tools", "tools.lineOfSight.status.partialObstruction", fallback: "Partial obstruction")
        }
      }
      public enum NodeDiscovery {
        /// Location: NodeDiscoveryView.swift - Error alert title
        public static let errorTitle = L10n.tr("Tools", "tools.nodeDiscovery.errorTitle", fallback: "Scan Failed")
        /// Location: NodeDiscoveryView.swift - No results empty state title
        public static func noResults(_ p1: Any) -> String {
          return L10n.tr("Tools", "tools.nodeDiscovery.noResults", String(describing: p1), fallback: "No %@ Found")
        }
        /// Location: NodeDiscoveryView.swift - No results empty state description
        public static func noResultsDescription(_ p1: Any) -> String {
          return L10n.tr("Tools", "tools.nodeDiscovery.noResultsDescription", String(describing: p1), fallback: "No %@ responded to the discovery ping. Try again or move closer to the mesh.")
        }
        /// Location: NodeDiscoveryView.swift - Disconnected state description
        public static func notConnectedDescription(_ p1: Any) -> String {
          return L10n.tr("Tools", "tools.nodeDiscovery.notConnectedDescription", String(describing: p1), fallback: "Connect to a mesh radio to discover %@.")
        }
        /// Location: NodeDiscoveryView.swift - Segment label
        public static let repeaters = L10n.tr("Tools", "tools.nodeDiscovery.repeaters", fallback: "Repeaters")
        /// Location: NodeDiscoveryRowView.swift - RSSI label
        public static func rssi(_ p1: Any) -> String {
          return L10n.tr("Tools", "tools.nodeDiscovery.rssi", String(describing: p1), fallback: "RSSI %@ dBm")
        }
        /// Location: NodeDiscoveryView.swift - Scan button label (idle)
        public static func scanButton(_ p1: Any) -> String {
          return L10n.tr("Tools", "tools.nodeDiscovery.scanButton", String(describing: p1), fallback: "Scan for %@")
        }
        /// Location: NodeDiscoveryView.swift - Initial empty state title
        public static func scanPrompt(_ p1: Any) -> String {
          return L10n.tr("Tools", "tools.nodeDiscovery.scanPrompt", String(describing: p1), fallback: "Scan for %@")
        }
        /// Location: NodeDiscoveryView.swift - Initial empty state description
        public static func scanPromptDescription(_ p1: Any) -> String {
          return L10n.tr("Tools", "tools.nodeDiscovery.scanPromptDescription", String(describing: p1), fallback: "Tap the button below to discover nearby %@.")
        }
        /// Location: NodeDiscoveryView.swift - Segment label
        public static let sensors = L10n.tr("Tools", "tools.nodeDiscovery.sensors", fallback: "Sensors")
        /// Location: NodeDiscoveryRowView.swift - SNR label (local receive)
        public static func snrDown(_ p1: Any) -> String {
          return L10n.tr("Tools", "tools.nodeDiscovery.snrDown", String(describing: p1), fallback: "↓ %@ dB")
        }
        /// Location: NodeDiscoveryRowView.swift - SNR label (remote receive)
        public static func snrUp(_ p1: Any) -> String {
          return L10n.tr("Tools", "tools.nodeDiscovery.snrUp", String(describing: p1), fallback: "↑ %@ dB")
        }
        /// Location: NodeDiscoveryView.swift - Sort menu accessibility label
        public static let sortMenu = L10n.tr("Tools", "tools.nodeDiscovery.sortMenu", fallback: "Sort nodes")
        /// Location: NodeDiscoveryView.swift - Sort menu accessibility hint
        public static let sortMenuHint = L10n.tr("Tools", "tools.nodeDiscovery.sortMenuHint", fallback: "Changes the sort order of discovered nodes")
        /// Location: NodeDiscoveryView.swift - Sort option
        public static let sortName = L10n.tr("Tools", "tools.nodeDiscovery.sortName", fallback: "Name")
        /// Location: NodeDiscoveryView.swift - Sort option
        public static let sortSignal = L10n.tr("Tools", "tools.nodeDiscovery.sortSignal", fallback: "Signal Strength")
        /// Location: NodeDiscoveryView.swift - Scan button label (scanning)
        public static let stopButton = L10n.tr("Tools", "tools.nodeDiscovery.stopButton", fallback: "Stop Scanning")
        /// Location: NodeDiscoveryRowView.swift - Node type: repeater
        public static let typeRepeater = L10n.tr("Tools", "tools.nodeDiscovery.typeRepeater", fallback: "Repeater")
        /// Location: NodeDiscoveryRowView.swift - Node type: sensor
        public static let typeSensor = L10n.tr("Tools", "tools.nodeDiscovery.typeSensor", fallback: "Sensor")
        /// Location: NodeDiscoveryRowView.swift - Unknown node name fallback
        public static let unknownNode = L10n.tr("Tools", "tools.nodeDiscovery.unknownNode", fallback: "Unknown")
      }
      public enum NoiseFloor {
        /// Location: NoiseFloorView.swift - Average label
        public static let average = L10n.tr("Tools", "tools.noiseFloor.average", fallback: "Average")
        /// Location: NoiseFloorView.swift - Chart accessibility, %lld readings, %lld min, %lld max, %lld avg, %@ trend
        public static func chartAccessibility(_ p1: Int, _ p2: Int, _ p3: Int, _ p4: Int, _ p5: Any) -> String {
          return L10n.tr("Tools", "tools.noiseFloor.chartAccessibility", p1, p2, p3, p4, String(describing: p5), fallback: "Noise floor history: %lld readings, minimum %lld dBm, maximum %lld dBm, average %lld dBm, trend %@")
        }
        /// Location: NoiseFloorView.swift - Chart accessibility when empty
        public static let chartAccessibilityEmpty = L10n.tr("Tools", "tools.noiseFloor.chartAccessibilityEmpty", fallback: "Noise floor history chart, no data")
        /// Location: NoiseFloorView.swift - Collecting data title
        public static let collectingData = L10n.tr("Tools", "tools.noiseFloor.collectingData", fallback: "Collecting Data...")
        /// Location: NoiseFloorView.swift - Collecting data description
        public static let collectingDataDescription = L10n.tr("Tools", "tools.noiseFloor.collectingDataDescription", fallback: "Noise floor readings will appear as they are collected.")
        /// Location: NoiseFloorView.swift - Unit label for decibels
        public static let db = L10n.tr("Tools", "tools.noiseFloor.dB", fallback: "dB")
        /// Location: NoiseFloorView.swift - Unit label
        public static let dBm = L10n.tr("Tools", "tools.noiseFloor.dBm", fallback: "dBm")
        /// Location: NoiseFloorView.swift - Last RSSI label
        public static let lastRssi = L10n.tr("Tools", "tools.noiseFloor.lastRssi", fallback: "Last RSSI")
        /// Location: NoiseFloorView.swift - Last SNR label
        public static let lastSnr = L10n.tr("Tools", "tools.noiseFloor.lastSnr", fallback: "Last SNR")
        /// Location: NoiseFloorView.swift - Maximum label
        public static let maximum = L10n.tr("Tools", "tools.noiseFloor.maximum", fallback: "Maximum")
        /// Location: NoiseFloorView.swift - Minimum label
        public static let minimum = L10n.tr("Tools", "tools.noiseFloor.minimum", fallback: "Minimum")
        /// Location: NoiseFloorView.swift - No reading accessibility
        public static let noReading = L10n.tr("Tools", "tools.noiseFloor.noReading", fallback: "No reading available")
        /// Location: NoiseFloorView.swift - Disconnected state description
        public static let notConnectedDescription = L10n.tr("Tools", "tools.noiseFloor.notConnectedDescription", fallback: "Connect to a mesh radio to measure noise floor.")
        /// Location: NoiseFloorView.swift - Statistics section title
        public static let statistics = L10n.tr("Tools", "tools.noiseFloor.statistics", fallback: "Statistics")
        /// Location: NoiseFloorView.swift - Trend: decreasing
        public static let trendDecreasing = L10n.tr("Tools", "tools.noiseFloor.trendDecreasing", fallback: "decreasing")
        /// Location: NoiseFloorView.swift - Trend: increasing
        public static let trendIncreasing = L10n.tr("Tools", "tools.noiseFloor.trendIncreasing", fallback: "increasing")
        /// Location: NoiseFloorView.swift - Trend: stable
        public static let trendStable = L10n.tr("Tools", "tools.noiseFloor.trendStable", fallback: "stable")
        public enum Error {
          /// Location: NoiseFloorViewModel.swift - Error: device disconnected
          public static let disconnected = L10n.tr("Tools", "tools.noiseFloor.error.disconnected", fallback: "Device disconnected")
          /// Location: NoiseFloorViewModel.swift - Error: unable to read stats
          public static let unableToRead = L10n.tr("Tools", "tools.noiseFloor.error.unableToRead", fallback: "Unable to read radio stats")
        }
        public enum Quality {
          /// Location: NoiseFloorViewModel.swift - Signal quality: excellent
          public static let excellent = L10n.tr("Tools", "tools.noiseFloor.quality.excellent", fallback: "Excellent")
          /// Location: NoiseFloorViewModel.swift - Signal quality: fair
          public static let fair = L10n.tr("Tools", "tools.noiseFloor.quality.fair", fallback: "Fair")
          /// Location: NoiseFloorViewModel.swift - Signal quality: good
          public static let good = L10n.tr("Tools", "tools.noiseFloor.quality.good", fallback: "Good")
          /// Location: NoiseFloorViewModel.swift - Signal quality: poor
          public static let poor = L10n.tr("Tools", "tools.noiseFloor.quality.poor", fallback: "Poor")
          /// Location: NoiseFloorViewModel.swift - Signal quality: unknown
          public static let unknown = L10n.tr("Tools", "tools.noiseFloor.quality.unknown", fallback: "Unknown")
        }
      }
      public enum RxLog {
        /// Location: RxLogView.swift - Bytes suffix for size display
        public static let bytes = L10n.tr("Tools", "tools.rxLog.bytes", fallback: "bytes")
        /// Location: RxLogView.swift - Channel hash label
        public static let channelHashLabel = L10n.tr("Tools", "tools.rxLog.channelHashLabel", fallback: "Channel Hash:")
        /// Location: RxLogView.swift - Channel name label
        public static let channelNameLabel = L10n.tr("Tools", "tools.rxLog.channelNameLabel", fallback: "Channel Name:")
        /// Location: RxLogView.swift - Copy button
        public static let copy = L10n.tr("Tools", "tools.rxLog.copy", fallback: "Copy")
        /// Location: RxLogView.swift - Filter menu section header
        public static let decryptStatus = L10n.tr("Tools", "tools.rxLog.decryptStatus", fallback: "Decrypt Status")
        /// Location: RxLogView.swift - Delete confirmation button
        public static let delete = L10n.tr("Tools", "tools.rxLog.delete", fallback: "Delete")
        /// Location: RxLogView.swift - Delete confirmation dialog title
        public static let deleteConfirmation = L10n.tr("Tools", "tools.rxLog.deleteConfirmation", fallback: "Delete all logs?")
        /// Location: RxLogView.swift - Delete logs button
        public static let deleteLogs = L10n.tr("Tools", "tools.rxLog.deleteLogs", fallback: "Delete Logs")
        /// Location: RxLogView.swift - Direct route label
        public static let direct = L10n.tr("Tools", "tools.rxLog.direct", fallback: "Direct")
        /// Location: RxLogView.swift - Filter button label
        public static let filter = L10n.tr("Tools", "tools.rxLog.filter", fallback: "Filter")
        /// Location: RxLogView.swift - From label
        public static let fromLabel = L10n.tr("Tools", "tools.rxLog.fromLabel", fallback: "From:")
        /// Location: RxLogView.swift - Group duplicates toggle
        public static let groupDuplicates = L10n.tr("Tools", "tools.rxLog.groupDuplicates", fallback: "Group Duplicates")
        /// Location: RxLogView.swift - Hash label
        public static let hashLabel = L10n.tr("Tools", "tools.rxLog.hashLabel", fallback: "Hash:")
        /// Location: RxLogView.swift - Path detail for multiple hops
        public static let hopPlural = L10n.tr("Tools", "tools.rxLog.hopPlural", fallback: "hops")
        /// Location: RxLogView.swift - Path detail for single hop
        public static let hopSingular = L10n.tr("Tools", "tools.rxLog.hopSingular", fallback: "hop")
        /// Location: RxLogView.swift - Empty state title when listening
        public static let listening = L10n.tr("Tools", "tools.rxLog.listening", fallback: "Listening...")
        /// Location: RxLogView.swift - Empty state description
        public static let listeningDescription = L10n.tr("Tools", "tools.rxLog.listeningDescription", fallback: "RF packets will appear here as they arrive.")
        /// Location: RxLogView.swift - Live status indicator
        public static let live = L10n.tr("Tools", "tools.rxLog.live", fallback: "Live")
        /// Location: RxLogView.swift - Overflow menu button label
        public static let more = L10n.tr("Tools", "tools.rxLog.more", fallback: "More")
        /// Location: RxLogView.swift - Disconnected state title
        public static let notConnected = L10n.tr("Tools", "tools.rxLog.notConnected", fallback: "Not Connected")
        /// Location: RxLogView.swift - Disconnected state description
        public static let notConnectedDescription = L10n.tr("Tools", "tools.rxLog.notConnectedDescription", fallback: "Connect to a mesh radio to view RF packets.")
        /// Location: RxLogView.swift - Offline status indicator
        public static let offline = L10n.tr("Tools", "tools.rxLog.offline", fallback: "Offline")
        /// Location: RxLogView.swift - Packet count in header, %lld is count
        public static func packetsCount(_ p1: Int) -> String {
          return L10n.tr("Tools", "tools.rxLog.packetsCount", p1, fallback: "%lld packets")
        }
        /// Location: RxLogView.swift - Path label
        public static let pathLabel = L10n.tr("Tools", "tools.rxLog.pathLabel", fallback: "Path:")
        /// Location: RxLogRowView - Label for local device in path display
        public static let pathYou = L10n.tr("Tools", "tools.rxLog.pathYou", fallback: "You")
        /// Location: RxLogView.swift - Raw payload section title
        public static let rawPayload = L10n.tr("Tools", "tools.rxLog.rawPayload", fallback: "Raw Payload")
        /// Location: RxLogView.swift - Duplicate count accessibility label, %lld is count
        public static func receivedTimes(_ p1: Int) -> String {
          return L10n.tr("Tools", "tools.rxLog.receivedTimes", p1, fallback: "Received %lld times")
        }
        /// Location: RxLogView.swift - Filter menu section header
        public static let routeType = L10n.tr("Tools", "tools.rxLog.routeType", fallback: "Route Type")
        /// Location: RxLogView.swift - RSSI label
        public static let rssiLabel = L10n.tr("Tools", "tools.rxLog.rssiLabel", fallback: "RSSI:")
        /// Location: RxLogView.swift - Signal strength accessibility label, %@ is quality
        public static func signalStrength(_ p1: Any) -> String {
          return L10n.tr("Tools", "tools.rxLog.signalStrength", String(describing: p1), fallback: "Signal strength: %@")
        }
        /// Location: RxLogView.swift - Size label
        public static let sizeLabel = L10n.tr("Tools", "tools.rxLog.sizeLabel", fallback: "Size:")
        /// Location: RxLogView.swift - SNR label
        public static let snrLabel = L10n.tr("Tools", "tools.rxLog.snrLabel", fallback: "SNR:")
        /// Location: RxLogView.swift - Text label
        public static let textLabel = L10n.tr("Tools", "tools.rxLog.textLabel", fallback: "Text:")
        /// Location: RxLogView.swift - To label
        public static let toLabel = L10n.tr("Tools", "tools.rxLog.toLabel", fallback: "To:")
        /// Location: RxLogRowView - Route label for TRACE target hashes
        public static let traceRouteLabel = L10n.tr("Tools", "tools.rxLog.traceRouteLabel", fallback: "Route:")
        /// Location: RxLogView.swift - Type label
        public static let typeLabel = L10n.tr("Tools", "tools.rxLog.typeLabel", fallback: "Type:")
        public enum Filter {
          /// Location: RxLogViewModel.swift - Route filter: all
          public static let all = L10n.tr("Tools", "tools.rxLog.filter.all", fallback: "All")
          /// Location: RxLogViewModel.swift - Decrypt filter: decrypted
          public static let decrypted = L10n.tr("Tools", "tools.rxLog.filter.decrypted", fallback: "Decrypted")
          /// Location: RxLogViewModel.swift - Route filter: direct only
          public static let directOnly = L10n.tr("Tools", "tools.rxLog.filter.directOnly", fallback: "Direct Only")
          /// Location: RxLogViewModel.swift - Decrypt filter: failed
          public static let failed = L10n.tr("Tools", "tools.rxLog.filter.failed", fallback: "Failed")
          /// Location: RxLogViewModel.swift - Route filter: flood only
          public static let floodOnly = L10n.tr("Tools", "tools.rxLog.filter.floodOnly", fallback: "Flood Only")
        }
      }
    }
  }
}
// swiftlint:enable explicit_type_interface function_parameter_count identifier_name line_length
// swiftlint:enable nesting type_body_length type_name vertical_whitespace_opening_braces

// MARK: - Implementation Details

extension L10n {
  private static func tr(_ table: String, _ key: String, _ args: CVarArg..., fallback value: String) -> String {
    let format = BundleToken.bundle.localizedString(forKey: key, value: value, table: table)
    return String(format: format, locale: Locale.current, arguments: args)
  }
}

// swiftlint:disable convenience_type
private final class BundleToken {
  static let bundle: Bundle = {
    #if SWIFT_PACKAGE
    return Bundle.module
    #else
    return Bundle(for: BundleToken.self)
    #endif
  }()
}
// swiftlint:enable convenience_type
