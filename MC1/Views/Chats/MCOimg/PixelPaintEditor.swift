import MC1Services
import SwiftUI

struct PixelPaintEditor: View {
  @Bindable var draft: PixelPaintDraft
  let transport: PixelPaintTransport
  let senderName: String
  let onSend: () -> Void
  @Environment(\.dismiss) private var dismiss
  @Environment(\.appState) private var appState
  @AppStorage(AppStorageKey.pixelPaintShowsGrid.rawValue)
  private var showsGrid = AppStorageKey.defaultPixelPaintShowsGrid
  @State private var isWide = false
  @State private var isConfirmingDiscard = false
  private static let maximumCanvasSize: CGFloat = 480
  private static let contentSpacing: CGFloat = 24

  var body: some View {
    NavigationStack {
      // `AnyLayout` keeps the canvas identity, and its VoiceOver cursor, across a rotation.
      let layout = isWide
        ? AnyLayout(HStackLayout(spacing: Self.contentSpacing))
        : AnyLayout(VStackLayout(spacing: Self.contentSpacing))
      layout {
        PixelPaintCanvas(draft: draft, showsGrid: showsGrid)
          .frame(maxWidth: Self.maximumCanvasSize, maxHeight: Self.maximumCanvasSize)
        VStack(spacing: Self.contentSpacing) {
          PixelPaintStatusRow(draft: draft, transport: transport, senderName: senderName)
          PixelPaintControls(draft: draft)
        }
        .frame(maxWidth: Self.maximumCanvasSize)
      }
      .padding()
      .frame(maxWidth: .infinity, maxHeight: .infinity)
      .onGeometryChange(for: Bool.self) { $0.size.width > $0.size.height } action: { isWide = $0 }
      .navigationTitle(L10n.Chats.Chats.Mcoimg.title)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar { toolbar }
    }
    .interactiveDismissDisabled()
    .errorAlert($draft.errorMessage, title: draft.errorTitle)
    .accessibilityIdentifier("pixelPaintEditor")
  }

  @ToolbarContentBuilder
  private var toolbar: some ToolbarContent {
    ToolbarItem(placement: .cancellationAction) {
      Button(L10n.Localizable.Common.cancel, systemImage: "xmark", action: cancel)
        .disabled(draft.isAdmitting)
        .accessibilityHint(draft.isAdmitting ? L10n.Chats.Chats.Mcoimg.sending : "")
        .accessibilityIdentifier("pixelPaintCancel")
        .confirmationDialog(
          L10n.Chats.Chats.Mcoimg.discardTitle,
          isPresented: $isConfirmingDiscard,
          titleVisibility: .visible
        ) {
          Button(L10n.Chats.Chats.Mcoimg.discard, role: .destructive, action: discard)
        }
    }
    ToolbarItem(placement: .topBarTrailing) {
      Toggle(L10n.Chats.Chats.Mcoimg.showGrid, systemImage: "grid", isOn: $showsGrid)
        .toggleStyle(.button)
        .accessibilityIdentifier("pixelPaintGrid")
    }
    ToolbarItem(placement: .confirmationAction) {
      Button(L10n.Chats.Chats.Mcoimg.send, systemImage: "arrow.up") {
        draft.endStroke()
        onSend()
      }
      .disabled(!canSend)
      .accessibilityHint(sendAccessibilityHint)
      .accessibilityIdentifier("pixelPaintSend")
    }
  }

  private var canSend: Bool {
    appState.connectionState == .ready && !draft.isSubmitting && !draft.isOverBudget(for: transport)
  }

  private var sendAccessibilityHint: String {
    if draft.isSubmitting {
      return draft.isAdmitting ? L10n.Chats.Chats.Mcoimg.sending : L10n.Chats.Chats.Mcoimg.encoding
    }
    if appState.connectionState != .ready {
      return L10n.Chats.Chats.Input.requiresConnection
    }
    if draft.isOverBudget(for: transport) {
      return L10n.Chats.Chats.Mcoimg.tooLargeInline
    }
    return ""
  }

  /// A submission that hasn't been admitted is retired at once, so it can't send behind a dialog.
  private func cancel() {
    draft.endStroke()
    if draft.isSubmitting || draft.isBlank {
      discard()
    } else {
      isConfirmingDiscard = true
    }
  }

  private func discard() {
    guard !draft.isAdmitting else { return }
    draft.remove()
    dismiss()
  }
}
