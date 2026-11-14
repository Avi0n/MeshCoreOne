import MC1Services
import SwiftUI

struct PixelPaintEditor: View {
  @Bindable var draft: PixelPaintDraft
  let onSend: () -> Void
  @Environment(\.dismiss) private var dismiss
  @Environment(\.appState) private var appState
  private static let swatchSize: CGFloat = 36
  private static let maximumCanvasSize: CGFloat = 480
  private static let gridWidth: CGFloat = 0.5
  private static let contentSpacing: CGFloat = 24
  private static let paletteColumns = 4
  private static let swatchPadding: CGFloat = 4

  var body: some View {
    NavigationStack {
      ScrollView {
        VStack(spacing: Self.contentSpacing) {
          coverStatus
          canvas
            .aspectRatio(1, contentMode: .fit)
            .frame(maxWidth: Self.maximumCanvasSize)
          LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: Self.paletteColumns)) {
            ForEach(0..<MCOImagePalette.count(profile: PixelPaintDraft.profile), id: \.self) { index in
              Button {
                draft.selectedColor = index
              } label: {
                Circle()
                  .fill(MCOImageRenderer.color(index, profile: PixelPaintDraft.profile))
                  .overlay {
                    Circle().strokeBorder(index == draft.selectedColor ? Color.accentColor : .gray,
                                          lineWidth: index == draft.selectedColor ? 4 : 1)
                  }
                  .frame(width: Self.swatchSize, height: Self.swatchSize)
                  .padding(Self.swatchPadding)
              }
              .buttonStyle(.plain)
              .accessibilityLabel(L10n.Chats.Chats.Mcoimg.color(index + 1))
              .accessibilityAddTraits(index == draft.selectedColor ? .isSelected : [])
              .accessibilityIdentifier("pixelColor\(index)")
            }
          }
          Button(L10n.Chats.Chats.Mcoimg.clear, systemImage: "eraser") { draft.clear() }
            .disabled(draft.isSubmitting)
        }
        .padding()
        .frame(maxWidth: .infinity)
      }
      .navigationTitle(L10n.Chats.Chats.Mcoimg.title)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.Localizable.Common.cancel, systemImage: "xmark") {
            draft.endStroke()
            draft.remove()
            dismiss()
          }
          .disabled(draft.isAdmitting)
          .accessibilityHint(draft.isAdmitting ? L10n.Chats.Chats.Mcoimg.sending : "")
          .accessibilityIdentifier("pixelPaintCancel")
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
    }
    .interactiveDismissDisabled()
    .errorAlert($draft.errorMessage, title: draft.errorTitle)
    .accessibilityIdentifier("pixelPaintEditor")
  }

  private var canSend: Bool {
    appState.connectionState == .ready && !draft.isSubmitting
  }

  private var sendAccessibilityHint: String {
    if draft.isSubmitting {
      return draft.isAdmitting ? L10n.Chats.Chats.Mcoimg.sending : L10n.Chats.Chats.Mcoimg.encoding
    }
    if appState.connectionState != .ready {
      return L10n.Chats.Chats.Input.requiresConnection
    }
    return ""
  }

  @ViewBuilder
  private var coverStatus: some View {
    if draft.isSubmitting {
      ProgressView(draft.isAdmitting ? L10n.Chats.Chats.Mcoimg.sending : L10n.Chats.Chats.Mcoimg.encoding)
    } else if appState.connectionState != .ready {
      Text(L10n.Chats.Chats.Input.requiresConnection)
        .font(.footnote)
        .foregroundStyle(.secondary)
    }
  }

  private var canvas: some View {
    Canvas { context, size in
      let cellSize = size.width / Double(PixelPaintDraft.side)
      for row in 0..<PixelPaintDraft.side {
        for column in 0..<PixelPaintDraft.side {
          let rect = CGRect(x: Double(column) * cellSize, y: Double(row) * cellSize,
                            width: cellSize, height: cellSize)
          context.fill(Path(rect), with: .color(MCOImageRenderer.color(
            draft.pixels[row * PixelPaintDraft.side + column], profile: PixelPaintDraft.profile
          )))
          context.stroke(Path(rect), with: .color(.gray), lineWidth: Self.gridWidth)
        }
      }
    }
    .gesture(DragGesture(minimumDistance: 0).onChanged { value in
      // Hit testing uses cell edges, matching `MCOImageRenderer` raster origins.
      let side = min(Self.maximumCanvasSize, canvasWidth)
      guard side > 0 else { return }
      draft.paint(x: Int(floor(value.location.x / side * Double(PixelPaintDraft.side))),
                  y: Int(floor(value.location.y / side * Double(PixelPaintDraft.side))))
    }.onEnded { _ in draft.endStroke() })
    .allowsHitTesting(!draft.isSubmitting)
    .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { canvasWidth = $0 }
    .accessibilityLabel(L10n.Chats.Chats.Mcoimg.canvas)
    .accessibilityIdentifier("pixelPaintCanvas")
    .accessibilityRepresentation { accessiblePixels.disabled(draft.isSubmitting) }
  }

  @State private var canvasWidth: CGFloat = 0

  private var accessiblePixels: some View {
    VStack {
      ForEach(0..<PixelPaintDraft.side, id: \.self) { row in
        HStack {
          ForEach(0..<PixelPaintDraft.side, id: \.self) { column in
            Button(L10n.Chats.Chats.Mcoimg.pixel(column + 1, row + 1)) {
              draft.endStroke()
              draft.paint(x: column, y: row)
              draft.endStroke()
            }
          }
        }
      }
    }
  }
}
