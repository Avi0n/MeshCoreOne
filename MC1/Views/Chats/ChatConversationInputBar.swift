import MC1Services
import SwiftUI

/// Input bar for chat conversations, configured per conversation type.
struct ChatConversationInputBar: View {
  let conversationType: ChatConversationType
  @Binding var composingText: String
  @Binding var focusRequest: Int
  let nodeNameByteCount: Int
  let onSend: (String) async -> Bool
  let onWillSend: () -> Void

  @Environment(\.appState) private var appState
  @State private var drawing = PixelPaintDraft()
  @State private var isShowingPainter = false
  @State private var submissionTask: Task<Void, Never>?
  private static let previewSide: CGFloat = 56

  var body: some View {
    VStack(spacing: 0) {
      if drawing.isStaged { stagedDrawing }
      inputBar
    }
    .fullScreenCover(isPresented: $isShowingPainter) {
      PixelPaintEditor(draft: drawing)
    }
    .errorAlert($drawing.errorMessage)
    .onChange(of: conversationType.coordinatorID) {
      submissionTask?.cancel()
      drawing.remove()
      isShowingPainter = false
    }
    .onDisappear { submissionTask?.cancel() }
  }

  @ViewBuilder
  private var inputBar: some View {
    switch conversationType {
    case .dm:
      ChatInputBar(
        text: $composingText,
        focusRequest: focusRequest,
        placeholder: L10n.Chats.Chats.Input.Placeholder.directMessage,
        maxBytes: ProtocolLimits.maxDirectMessageLength,
        isEncrypted: true,
        leading: { ChatShareMenu(onInsert: insertShared, onPaint: openPainter) },
        onSend: send,
        hasAttachment: drawing.isStaged,
        isSubmittingAttachment: drawing.isSubmitting,
        onSendAttachment: sendDrawing
      )

    case let .channel(channel):
      let maxBytes = ProtocolLimits.maxChannelMessageLength(
        nodeNameByteCount: nodeNameByteCount
      )
      ChatInputBar(
        text: $composingText,
        focusRequest: focusRequest,
        placeholder: conversationType.isPublicStyleChannel
          ? L10n.Chats.Chats.Channel.typePublic
          : L10n.Chats.Chats.Channel.typePrivate,
        maxBytes: maxBytes,
        isEncrypted: channel.isEncryptedChannel,
        leading: { ChatShareMenu(onInsert: insertShared, onPaint: openPainter) },
        onSend: send,
        hasAttachment: drawing.isStaged,
        isSubmittingAttachment: drawing.isSubmitting,
        onSendAttachment: sendDrawing
      )
    }
  }

  private var stagedDrawing: some View {
    HStack {
      Button(action: openPainter) {
        MCOImageRenderer(content: .raster(drawing.image))
          .frame(width: Self.previewSide, height: Self.previewSide)
      }
      .accessibilityLabel(L10n.Chats.Chats.Mcoimg.edit)
      .accessibilityIdentifier("stagedPixelDrawing")
      Text(L10n.Chats.Chats.Mcoimg.draft).font(.caption)
      Spacer()
      if drawing.isSubmitting {
        ProgressView().accessibilityLabel(L10n.Chats.Chats.Mcoimg.encoding)
      }
      Button(L10n.Chats.Chats.Mcoimg.remove, systemImage: "xmark.circle") { drawing.remove() }
        .labelStyle(.iconOnly)
    }
    .disabled(drawing.isSubmitting)
    .padding(.horizontal)
  }

  private func openPainter() {
    guard !drawing.isSubmitting else { return }
    ChatKeyboardLift.resignFirstResponder()
    isShowingPainter = true
  }

  private func sendDrawing() {
    guard drawing.isStaged, !drawing.isSubmitting, appState.connectionState == .ready else { return }
    let snapshot = drawing.image
    let isChannel: Bool = if case .channel = conversationType { true } else { false }
    drawing.isSubmitting = true
    drawing.errorMessage = nil
    submissionTask = Task {
      defer { drawing.isSubmitting = false }
      do {
        let text = try await encodeDrawing(snapshot, channel: isChannel)
        try Task.checkCancellation()
        guard appState.connectionState == .ready,
              appState.connectedDevice?.radioID == conversationType.radioID else {
          drawing.errorMessage = L10n.Chats.Chats.Alert.UnableToSend.message
          return
        }
        let bytes = if isChannel {
          try MCOImageTransport.encode(text: text, senderName: appState.connectedDevice?.nodeName ?? "").count
        } else {
          text.utf8.count
        }
        let limit = isChannel ? MCOImageWireBudget.maximumChannelDataBytes : ProtocolLimits.maxDirectMessageLength
        guard bytes <= limit else {
          drawing.errorMessage = L10n.Chats.Chats.Mcoimg.tooLarge(bytes, limit)
          return
        }
        onWillSend()
        if await onSend(text) { drawing.remove() }
      } catch is CancellationError {
        return
      } catch {
        drawing.errorMessage = L10n.Chats.Chats.Mcoimg.encodingFailed
      }
    }
  }

  private func send(_ text: String) {
    let draft = $composingText
    onWillSend()
    Task {
      guard await !onSend(text) else { return }
      let newerText = draft.wrappedValue
      draft.wrappedValue = newerText.isEmpty ? text : text + "\n" + newerText
    }
  }

  /// Insert shared text with a separating space when needed, then request composer focus.
  private func insertShared(_ shared: String) {
    if !composingText.isEmpty, let last = composingText.last, !last.isWhitespace {
      composingText.append(" ")
    }
    composingText.append(shared)
    focusRequest += 1
  }
}

@concurrent
private func encodeDrawing(_ image: MCOImageRaster, channel: Bool) async throws -> String {
  let body = try MCOImageV3Codec.encode(image, nonce: UInt8.random(in: .min ... .max))
  let payload = channel
    ? try Data([MCOImageCodec.v4Subtype]) + MCOImageV4Codec.wrappingV3Body(body)
    : Data([MCOImageCodec.v3Subtype]) + body
  return try MCOImageCodec.text(fromAppPayload: payload)
}
