import MC1Services
import SwiftUI

struct PixelPaintStatusRow: View {
  let draft: PixelPaintDraft
  let transport: PixelPaintTransport
  let senderName: String
  @Environment(\.appState) private var appState
  private static let previewSize: CGFloat = 44
  private static let previewBorderWidth: CGFloat = 1
  private static let spacing: CGFloat = 12

  var body: some View {
    HStack(spacing: Self.spacing) {
      MCOImageRenderer(content: .raster(draft.image))
        .frame(width: Self.previewSize, height: Self.previewSize)
        .overlay { Rectangle().strokeBorder(.secondary, lineWidth: Self.previewBorderWidth) }
        .accessibilityLabel(L10n.Chats.Chats.Mcoimg.image)
      status
        .frame(maxWidth: .infinity, alignment: .leading)
    }
    // Lives here rather than on the editor so a paint only invalidates the views that show pixels.
    .task(id: MeasurementKey(pixels: draft.pixels, transport: transport, senderName: senderName)) {
      await draft.measure(transport: transport, senderName: senderName)
    }
    .sensoryFeedback(.warning, trigger: isOverBudget) { wasOver, isOver in !wasOver && isOver }
  }

  private var isOverBudget: Bool {
    draft.isOverBudget(for: transport)
  }

  @ViewBuilder
  private var status: some View {
    if draft.isAdmitting {
      ProgressView(L10n.Chats.Chats.Mcoimg.sending)
    } else if appState.connectionState != .ready {
      Text(L10n.Chats.Chats.Input.requiresConnection)
        .font(.footnote)
        .foregroundStyle(.secondary)
    } else if let bytes = draft.encodedByteCount {
      budget(bytes: bytes, limit: transport.byteLimit)
    }
  }

  private func budget(bytes: Int, limit: Int) -> some View {
    VStack(alignment: .leading) {
      Gauge(value: Double(min(bytes, limit)), in: 0...Double(limit)) {
        Text(L10n.Chats.Chats.Mcoimg.budget(bytes, limit))
      }
      .gaugeStyle(.accessoryLinearCapacity)
      .labelsHidden()
      .tint(isOverBudget ? .red : .secondary)
      HStack {
        Text("\(bytes)/\(limit)")
          .monospacedDigit()
        if isOverBudget {
          Text(L10n.Chats.Chats.Mcoimg.tooLargeInline)
        }
      }
      .font(.caption2)
      .foregroundStyle(isOverBudget ? .red : .secondary)
    }
    .accessibilityElement(children: .ignore)
    .accessibilityLabel(L10n.Chats.Chats.Mcoimg.budget(bytes, limit))
    .accessibilityValue(isOverBudget ? L10n.Chats.Chats.Mcoimg.tooLargeInline : "")
  }
}

private struct MeasurementKey: Equatable {
  let pixels: [Int]
  let transport: PixelPaintTransport
  let senderName: String
}
