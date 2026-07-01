import Foundation
@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing

@Suite("MessageFragmentBuilder")
@MainActor
struct MessageFragmentBuilderTests {
  @Test
  func `plain text produces a single text fragment`() {
    let message = makeMessage(text: "hello")
    let inputs = makeInputs(messageID: message.id)
    let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
    #expect(item.content.count == 1)
    guard case let .text(text) = item.content[0] else {
      Issue.record("expected .text fragment")
      return
    }
    #expect(text.raw == "hello")
  }

  @Test
  func `reaction summary appears after the text fragment`() {
    let message = makeMessage(text: "hi", reactionSummary: "👍:1")
    let inputs = makeInputs(messageID: message.id)
    let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
    #expect(item.content.count == 2)
    guard case .text = item.content[0] else {
      Issue.record("expected .text fragment at index 0")
      return
    }
    guard case let .reactionSummary(summary) = item.content[1] else {
      Issue.record("expected .reactionSummary fragment at index 1")
      return
    }
    #expect(summary == "👍:1")
  }

  /// Adding a reaction must flip `MessageItem.hashValue` so the diffable
  /// data source reloads the cell. Without this, an in-progress reaction
  /// add would not visibly update until another property (status, etc.)
  /// changes. Covers the rebuild-trigger contract for the reaction path.
  @Test
  func `adding a reaction flips the item hash`() {
    let messageID = UUID()
    let messageWithoutReaction = makeMessage(id: messageID, text: "hi")
    let messageWithReaction = makeMessage(id: messageID, text: "hi", reactionSummary: "👍:1")
    let inputs = makeInputs(messageID: messageID)
    let itemA = MessageFragmentBuilder.makeItem(for: messageWithoutReaction, inputs: inputs, envInputs: makeEnvInputs())
    let itemB = MessageFragmentBuilder.makeItem(for: messageWithReaction, inputs: inputs, envInputs: makeEnvInputs())
    #expect(itemA.hashValue != itemB.hashValue)
    #expect(itemA != itemB)
  }

  @Test
  func `malware warning replaces preview and inline image fragments`() throws {
    let message = makeMessage(text: "click me")
    let inputs = try makeInputs(
      messageID: message.id,
      previewState: .malwareWarning,
      cachedURL: #require(URL(string: "https://bad.example")),
      hasCachedURLEntry: true
    )
    let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
    let kinds: [FragmentKind] = item.content.map(Self.kind(of:))
    #expect(kinds == [.text, .malwareWarning])
  }

  @Test
  func `legacy link preview surfaces with persisted fields when state is idle`() {
    let message = makeMessage(
      text: "see",
      linkPreviewURL: "https://example.com",
      linkPreviewTitle: "Example"
    )
    let inputs = makeInputs(
      messageID: message.id,
      previewState: .idle,
      previewsEnabled: true
    )
    let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs(previewsEnabled: true))
    guard case let .linkPreview(state) = item.content.last,
          case let .legacy(url, title, _, _) = state.mode else {
      Issue.record("expected .legacy preview")
      return
    }
    #expect(url.absoluteString == "https://example.com")
    #expect(title == "Example")
  }

  @Test
  func `legacy link preview carries image reference when inputs say so`() {
    let message = makeMessage(
      text: "see",
      linkPreviewURL: "https://example.com",
      linkPreviewTitle: "Example"
    )
    let inputs = makeInputs(
      messageID: message.id,
      previewState: .idle,
      hasPreviewImageRef: true,
      previewsEnabled: true
    )
    let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs(previewsEnabled: true))
    guard case let .linkPreview(state) = item.content.last,
          case let .legacy(_, _, imageRef, _) = state.mode else {
      Issue.record("expected .legacy preview")
      return
    }
    #expect(imageRef == ImageReference(cacheKey: message.id, role: .linkPreviewImage))
  }

  @Test
  func `same inputs produce equal items and equal hashes`() {
    let message = makeMessage(text: "hello")
    let inputs = makeInputs(messageID: message.id)
    let a = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
    let b = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
    #expect(a == b)
    #expect(a.hashValue == b.hashValue)
  }

  @Test
  func `shouldRequestPreviewFetch is true on idle with URL and no legacy fields`() throws {
    let message = makeMessage(text: "hi")
    let inputs = try makeInputs(
      messageID: message.id,
      previewState: .idle,
      cachedURL: #require(URL(string: "https://example.com")),
      hasCachedURLEntry: true
    )
    let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
    #expect(item.shouldRequestPreviewFetch == true)
  }

  @Test
  func `shouldRequestPreviewFetch is false on legacy message`() throws {
    let message = makeMessage(text: "hi", linkPreviewURL: "https://example.com")
    let inputs = try makeInputs(
      messageID: message.id,
      previewState: .idle,
      cachedURL: #require(URL(string: "https://example.com")),
      hasCachedURLEntry: true
    )
    let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
    #expect(item.shouldRequestPreviewFetch == false)
  }

  @Test
  func `envelope captures containsSelfMention from message`() {
    let message = makeMessage(text: "hi", containsSelfMention: true)
    let inputs = makeInputs(messageID: message.id)
    let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
    #expect(item.envelope.containsSelfMention == true)
  }

  @Test
  func `envelope captures mentionSeen from message`() {
    let message = makeMessage(text: "hi", containsSelfMention: true, mentionSeen: true)
    let inputs = makeInputs(messageID: message.id)
    let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
    #expect(item.envelope.mentionSeen == true)
  }

  @Test
  func `envelope date is the message send time, not its drain time`() {
    // A drained backlog row sent three days before it was received. The centered
    // divider is the sole time surface, so it must read send time — otherwise a
    // days-old message gets relabeled "Today" at the block's delivery time.
    let drainTime = Self.referenceDate
    let sendTime = Self.referenceDate.addingTimeInterval(-3 * 24 * 60 * 60)
    let message = MessageDTO(
      id: UUID(),
      radioID: Self.radioID,
      contactID: Self.contactID,
      channelIndex: nil,
      text: "older message just arrived",
      timestamp: UInt32(sendTime.timeIntervalSince1970),
      createdAt: drainTime,
      direction: .incoming,
      status: .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      senderKeyPrefix: nil,
      senderNodeName: nil,
      isRead: true,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
    let inputs = makeInputs(messageID: message.id)
    let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())

    #expect(item.envelope.date == message.senderDate)
    #expect(message.senderDate != message.date, "test must distinguish send time from drain time")
  }

  @Test
  func `footer captures heardRepeats from message`() {
    let message = makeMessage(text: "hi", heardRepeats: 3)
    let inputs = makeInputs(messageID: message.id)
    let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
    #expect(item.footer.heardRepeats == 3)
  }

  @Test
  func `footer captures retryAttempt from message`() {
    let message = makeMessage(text: "hi", retryAttempt: 2)
    let inputs = makeInputs(messageID: message.id)
    let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
    #expect(item.footer.retryAttempt == 2)
  }

  @Test
  func `footer captures maxRetryAttempts from message`() {
    let message = makeMessage(text: "hi", maxRetryAttempts: 5)
    let inputs = makeInputs(messageID: message.id)
    let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
    #expect(item.footer.maxRetryAttempts == 5)
  }

  // MARK: - Incoming send time

  @Test
  func `footer shows send time on incoming message when enabled`() {
    let wire: UInt32 = 1_700_000_000
    let message = makeIncomingMessage(timestamp: wire)
    let inputs = makeInputs(messageID: message.id)
    let item = MessageFragmentBuilder.makeItem(
      for: message, inputs: inputs, envInputs: makeEnvInputs(showIncomingSendTime: true)
    )
    #expect(item.footer.sendTimeToShow == Date(timeIntervalSince1970: TimeInterval(wire)))
    #expect(item.footer.sendTimeWasCorrected == false)
  }

  @Test
  func `footer hides send time when the toggle is off`() {
    let message = makeIncomingMessage(timestamp: 1_700_000_000)
    let inputs = makeInputs(messageID: message.id)
    let item = MessageFragmentBuilder.makeItem(
      for: message, inputs: inputs, envInputs: makeEnvInputs(showIncomingSendTime: false)
    )
    #expect(item.footer.sendTimeToShow == nil)
  }

  @Test
  func `footer hides send time on outgoing messages even when enabled`() {
    let message = makeMessage(text: "hi") // outgoing by default
    let inputs = makeInputs(messageID: message.id)
    let item = MessageFragmentBuilder.makeItem(
      for: message, inputs: inputs, envInputs: makeEnvInputs(showIncomingSendTime: true)
    )
    #expect(item.footer.sendTimeToShow == nil)
  }

  @Test
  func `footer send time uses the corrected value and flags correction`() {
    let rawWire: UInt32 = 100 // sender's skewed clock, surfaced only in the info sheet
    let corrected: UInt32 = 1_700_000_000 // value the app substituted for ordering
    let message = makeIncomingMessage(
      timestamp: corrected, senderTimestamp: rawWire, timestampCorrected: true
    )
    let inputs = makeInputs(messageID: message.id)
    let item = MessageFragmentBuilder.makeItem(
      for: message, inputs: inputs, envInputs: makeEnvInputs(showIncomingSendTime: true)
    )
    #expect(item.footer.sendTimeToShow == Date(timeIntervalSince1970: TimeInterval(corrected)))
    #expect(item.footer.sendTimeWasCorrected == true)
  }

  // MARK: - Hash propagation regression

  @Test
  func `previewState change flips the item hash`() throws {
    let messageID = UUID()
    let message = makeMessage(id: messageID, text: "hi")
    let url = try #require(URL(string: "https://example.com"))
    let idle = makeInputs(
      messageID: messageID,
      previewState: .idle,
      cachedURL: url,
      previewsEnabled: true
    )
    let loaded = makeInputs(
      messageID: messageID,
      previewState: .loaded,
      cachedURL: url,
      previewsEnabled: true
    )
    let env = makeEnvInputs(previewsEnabled: true)
    let a = MessageFragmentBuilder.makeItem(for: message, inputs: idle, envInputs: env)
    let b = MessageFragmentBuilder.makeItem(for: message, inputs: loaded, envInputs: env)
    #expect(a.hashValue != b.hashValue)
  }

  @Test
  func `loadedPreview change flips the item hash`() {
    let messageID = UUID()
    let message = makeMessage(id: messageID, text: "hi")
    let preview = LinkPreviewDataDTO(
      url: "https://example.com",
      title: "Example",
      fetchedAt: Date(timeIntervalSince1970: 1_700_000_000)
    )
    let withoutPreview = makeInputs(
      messageID: messageID,
      previewState: .loaded,
      previewsEnabled: true
    )
    let withPreview = makeInputs(
      messageID: messageID,
      previewState: .loaded,
      loadedPreview: preview,
      previewsEnabled: true
    )
    let env = makeEnvInputs(previewsEnabled: true)
    let a = MessageFragmentBuilder.makeItem(for: message, inputs: withoutPreview, envInputs: env)
    let b = MessageFragmentBuilder.makeItem(for: message, inputs: withPreview, envInputs: env)
    #expect(a.hashValue != b.hashValue)
  }

  // MARK: - Preview fetch task identity

  /// `previewFetchTaskID` must be message-scoped so the bubble's fetch
  /// `.task(id:)` produces an edge when a reused `UIHostingConfiguration` cell
  /// moves from one fetch-wanting message to another. Keying on the bare
  /// `shouldRequestPreviewFetch` flag collides (`true` equals `true`) and
  /// strands the second message's fetch until the chat is re-entered.
  @Test
  func `preview fetch task id is distinct per fetch-wanting message`() throws {
    let url = try #require(URL(string: "https://example.com"))
    let env = makeEnvInputs(previewsEnabled: true)
    let messageA = makeMessage(text: "see https://example.com")
    let messageB = makeMessage(text: "also https://example.com")
    let inputsA = makeInputs(messageID: messageA.id, previewState: .idle, cachedURL: url, previewsEnabled: true)
    let inputsB = makeInputs(messageID: messageB.id, previewState: .idle, cachedURL: url, previewsEnabled: true)
    let itemA = MessageFragmentBuilder.makeItem(for: messageA, inputs: inputsA, envInputs: env)
    let itemB = MessageFragmentBuilder.makeItem(for: messageB, inputs: inputsB, envInputs: env)

    #expect(itemA.shouldRequestPreviewFetch)
    #expect(itemB.shouldRequestPreviewFetch)
    // The bare flag collides across the two messages; the task id does not.
    #expect(itemA.shouldRequestPreviewFetch == itemB.shouldRequestPreviewFetch)
    #expect(itemA.previewFetchTaskID == itemA.id)
    #expect(itemB.previewFetchTaskID == itemB.id)
    #expect(itemA.previewFetchTaskID != itemB.previewFetchTaskID)
  }

  /// No fetch wanted means a nil task id, and the idle-to-loading transition
  /// flips the id so a same-cell reconfigure still re-runs the task once.
  @Test
  func `preview fetch task id is nil when no fetch is wanted`() throws {
    let messageID = UUID()
    let message = makeMessage(id: messageID, text: "hi")
    let url = try #require(URL(string: "https://example.com"))
    let env = makeEnvInputs(previewsEnabled: true)
    let idle = makeInputs(messageID: messageID, previewState: .idle, cachedURL: url, previewsEnabled: true)
    let loading = makeInputs(messageID: messageID, previewState: .loading, cachedURL: url, previewsEnabled: true)
    let noURL = makeInputs(messageID: messageID, previewState: .idle, previewsEnabled: true)

    let idleItem = MessageFragmentBuilder.makeItem(for: message, inputs: idle, envInputs: env)
    let loadingItem = MessageFragmentBuilder.makeItem(for: message, inputs: loading, envInputs: env)
    let noURLItem = MessageFragmentBuilder.makeItem(for: message, inputs: noURL, envInputs: env)

    #expect(idleItem.previewFetchTaskID == messageID)
    #expect(loadingItem.previewFetchTaskID == nil)
    #expect(noURLItem.previewFetchTaskID == nil)
  }

  @Test(arguments: hashFlipScenarios)
  func `MessageDTO field change flips the item hash`(scenario: HashFlipScenario) {
    let messageID = UUID()
    let inputs = makeInputs(messageID: messageID)
    let env = makeEnvInputs()
    let a = MessageFragmentBuilder.makeItem(
      for: makeMessageVariant(id: messageID, factor: scenario.factorA),
      inputs: inputs,
      envInputs: env
    )
    let b = MessageFragmentBuilder.makeItem(
      for: makeMessageVariant(id: messageID, factor: scenario.factorB),
      inputs: inputs,
      envInputs: env
    )
    #expect(a.hashValue != b.hashValue, "\(scenario.name) change must flip the item hash")
  }

  nonisolated static let hashFlipScenarios: [HashFlipScenario] = [
    HashFlipScenario(name: "heardRepeats", factorA: .heardRepeats(0), factorB: .heardRepeats(3)),
    HashFlipScenario(name: "retryAttempt", factorA: .retryAttempt(0), factorB: .retryAttempt(1)),
    HashFlipScenario(name: "maxRetryAttempts", factorA: .maxRetryAttempts(3), factorB: .maxRetryAttempts(5)),
    HashFlipScenario(name: "status", factorA: .status(.sent), factorB: .status(.delivered)),
    HashFlipScenario(name: "containsSelfMention", factorA: .containsSelfMention(false), factorB: .containsSelfMention(true)),
    HashFlipScenario(name: "mentionSeen", factorA: .mentionSeen(false), factorB: .mentionSeen(true)),
  ]

  struct HashFlipScenario: CustomStringConvertible {
    let name: String
    let factorA: HashFlipFactor
    let factorB: HashFlipFactor
    var description: String {
      name
    }
  }

  enum HashFlipFactor {
    case heardRepeats(Int)
    case retryAttempt(Int)
    case maxRetryAttempts(Int)
    case status(MessageStatus)
    case containsSelfMention(Bool)
    /// Implies `containsSelfMention: true` so the hash distinction is the
    /// `mentionSeen` flag alone.
    case mentionSeen(Bool)
  }

  private func makeMessageVariant(id: UUID, factor: HashFlipFactor) -> MessageDTO {
    switch factor {
    case let .heardRepeats(value):
      makeMessage(id: id, heardRepeats: value)
    case let .retryAttempt(value):
      makeMessage(id: id, retryAttempt: value)
    case let .maxRetryAttempts(value):
      makeMessage(id: id, maxRetryAttempts: value)
    case let .status(value):
      makeMessage(id: id, status: value)
    case let .containsSelfMention(value):
      makeMessage(id: id, containsSelfMention: value)
    case let .mentionSeen(value):
      makeMessage(id: id, containsSelfMention: true, mentionSeen: value)
    }
  }

  // MARK: - Helpers

  private static let radioID = UUID(uuidString: "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")!
  private static let contactID = UUID(uuidString: "BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")!
  private static let referenceDate = Date(timeIntervalSince1970: 1_700_000_000)

  private func makeMessage(
    id: UUID = UUID(),
    text: String = "hello",
    status: MessageStatus = .sent,
    reactionSummary: String? = nil,
    linkPreviewURL: String? = nil,
    linkPreviewTitle: String? = nil,
    heardRepeats: Int = 0,
    retryAttempt: Int = 0,
    maxRetryAttempts: Int = 0,
    containsSelfMention: Bool = false,
    mentionSeen: Bool = false
  ) -> MessageDTO {
    MessageDTO(
      id: id,
      radioID: Self.radioID,
      contactID: Self.contactID,
      channelIndex: nil,
      text: text,
      timestamp: UInt32(Self.referenceDate.timeIntervalSince1970),
      createdAt: Self.referenceDate,
      direction: .outgoing,
      status: status,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      senderKeyPrefix: nil,
      senderNodeName: nil,
      isRead: true,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: heardRepeats,
      retryAttempt: retryAttempt,
      maxRetryAttempts: maxRetryAttempts,
      linkPreviewURL: linkPreviewURL,
      linkPreviewTitle: linkPreviewTitle,
      containsSelfMention: containsSelfMention,
      mentionSeen: mentionSeen,
      reactionSummary: reactionSummary
    )
  }

  private func makeIncomingMessage(
    timestamp: UInt32,
    senderTimestamp: UInt32? = nil,
    timestampCorrected: Bool = false
  ) -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: Self.radioID,
      contactID: Self.contactID,
      channelIndex: nil,
      text: "hi",
      timestamp: timestamp,
      createdAt: Self.referenceDate,
      direction: .incoming,
      status: .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      senderKeyPrefix: nil,
      senderNodeName: nil,
      isRead: true,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0,
      timestampCorrected: timestampCorrected,
      senderTimestamp: senderTimestamp
    )
  }

  private func makeEnvInputs(
    showInlineImages: Bool = true,
    autoPlayGIFs: Bool = true,
    showIncomingPath: Bool = false,
    showIncomingHopCount: Bool = false,
    showIncomingRegion: Bool = false,
    showIncomingSendTime: Bool = false,
    previewsEnabled: Bool = false,
    isHighContrast: Bool = false,
    isDark: Bool = false,
    showMapPreviews: Bool = true,
    isOffline: Bool = false,
    currentUserName: String = "Me"
  ) -> EnvInputs {
    EnvInputs(
      showInlineImages: showInlineImages,
      autoPlayGIFs: autoPlayGIFs,
      showIncomingPath: showIncomingPath,
      showIncomingHopCount: showIncomingHopCount,
      showIncomingRegion: showIncomingRegion,
      showIncomingSendTime: showIncomingSendTime,
      previewsEnabled: previewsEnabled,
      isHighContrast: isHighContrast,
      isDark: isDark,
      showMapPreviews: showMapPreviews,
      isOffline: isOffline,
      currentUserName: currentUserName,
      themeID: "default",
      contentSizeCategory: EnvInputs.defaultContentSizeCategory
    )
  }

  @Test
  func `A coordinate in build inputs emits a mapPreview fragment`() {
    let message = MessageFragmentBuilderFixtures.makeMessage(text: "Meet at 37.7749, -122.4194")
    let inputs = MessageFragmentBuilderFixtures.makeInputs(
      for: message,
      mapPreviewLatitude: 37.7749,
      mapPreviewLongitude: -122.4194,
      isMapPreviewReady: true
    )
    let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs(isDark: true))

    guard case let .mapPreview(state) = item.content.last else {
      Issue.record("expected a trailing mapPreview fragment")
      return
    }
    #expect(state.latitude == 37.7749)
    #expect(state.longitude == -122.4194)
    #expect(state.isDark == true)
    #expect(state.isReady == true)
  }

  @Test
  func `No coordinate means no mapPreview fragment`() {
    let message = MessageFragmentBuilderFixtures.makeMessage(text: "no coordinates here")
    let inputs = MessageFragmentBuilderFixtures.makeInputs(for: message)
    let item = MessageFragmentBuilder.makeItem(for: message, inputs: inputs, envInputs: makeEnvInputs())
    #expect(!item.content.contains { if case .mapPreview = $0 { true } else { false } })
  }

  private func makeInputs(
    messageID: UUID,
    previewState: PreviewLoadState = .idle,
    loadedPreview: LinkPreviewDataDTO? = nil,
    cachedURL: URL? = nil,
    hasCachedURLEntry: Bool = false,
    hasInlineImageRef: Bool = false,
    hasPreviewImageRef: Bool = false,
    hasPreviewIconRef: Bool = false,
    imageIsGIF: Bool = false,
    formattedText: AttributedString? = nil,
    showInlineImages: Bool = true,
    autoPlayGIFs: Bool = true,
    previewsEnabled: Bool = false,
    currentUserName: String = "Me",
    baseColor: BaseColorSlot = .incoming,
    formattedPath: String? = nil,
    showIncomingHopCount: Bool = false,
    showIncomingRegion: Bool = false,
    configurationShowSenderName: Bool = false,
    senderResolution: NodeNameResolution = NodeNameResolution(displayName: "Sender", matchKind: .exact),
    showTimestamp: Bool = false,
    showDirectionGap: Bool = false,
    showSenderName: Bool = false,
    showNewMessagesDivider: Bool = false
  ) -> MessageBuildInputs {
    MessageBuildInputs(
      messageID: messageID,
      previewState: previewState,
      loadedPreview: loadedPreview,
      cachedURL: cachedURL,
      hasInlineImageRef: hasInlineImageRef,
      hasPreviewImageRef: hasPreviewImageRef,
      hasPreviewIconRef: hasPreviewIconRef,
      imageIsGIF: imageIsGIF,
      formattedText: formattedText,
      baseColor: baseColor,
      formattedPath: formattedPath,
      senderResolution: senderResolution,
      showTimestamp: showTimestamp,
      showDirectionGap: showDirectionGap,
      showSenderName: showSenderName,
      showNewMessagesDivider: showNewMessagesDivider
    )
  }

  private enum FragmentKind: Equatable {
    case text, inlineImage, linkPreview, mapPreview, malwareWarning, reactionSummary
  }

  private static func kind(of fragment: MessageFragment) -> FragmentKind {
    switch fragment {
    case .text: .text
    case .inlineImage: .inlineImage
    case .linkPreview: .linkPreview
    case .mapPreview: .mapPreview
    case .malwareWarning: .malwareWarning
    case .reactionSummary: .reactionSummary
    }
  }
}
