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
  let onDrawingRejected: () -> Void

  @Environment(\.appState) private var appState
  @State private var drawing = PixelPaintDraft()
  @State private var isShowingPainter = false

  var body: some View {
    inputBar
    .fullScreenCover(isPresented: $isShowingPainter) {
      PixelPaintEditor(draft: drawing, onSend: sendDrawing)
    }
    .onChange(of: conversationType.coordinatorID) {
      drawing.remove()
      isShowingPainter = false
    }
    .onDisappear { drawing.remove() }
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
        onSend: send
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
        onSend: send
      )
    }
  }

  private func openPainter() {
    guard !drawing.isSubmitting else { return }
    ChatKeyboardLift.resignFirstResponder()
    isShowingPainter = true
  }

  private func sendDrawing() {
    let isChannel: Bool = if case .channel = conversationType { true } else { false }
    let radioID = conversationType.radioID
    let ready = {
      appState.connectionState == .ready && appState.connectedDevice?.radioID == radioID
    }
    guard let attempt = drawing.begin(isReady: ready) else { return }
    let task = Task {
      await drawing.run(
        attempt,
        isChannel: isChannel,
        senderName: { appState.connectedDevice?.nodeName ?? "" },
        isReady: ready,
        onSend: onSend,
        onWillSend: onWillSend,
        onRejected: onDrawingRejected,
        onAdmitted: { isShowingPainter = false }
      )
    }
    drawing.track(task)
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
