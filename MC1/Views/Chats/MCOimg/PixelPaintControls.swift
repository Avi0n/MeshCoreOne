import MC1Services
import SwiftUI

struct PixelPaintControls: View {
  @Bindable var draft: PixelPaintDraft
  private static let swatchSize: CGFloat = 44
  private static let ringWidth: CGFloat = 3
  private static let swatchBorderWidth: CGFloat = 1
  private static let paletteColumns = 4
  private static let spacing: CGFloat = 16

  var body: some View {
    VStack(spacing: Self.spacing) {
      Picker(L10n.Chats.Chats.Mcoimg.toolPicker, selection: $draft.tool) {
        ForEach(PixelPaintTool.allCases, id: \.self) { tool in
          Label(tool.title, systemImage: tool.systemImage).tag(tool)
        }
      }
      .pickerStyle(.segmented)
      history
      palette
    }
    .disabled(draft.isSubmitting)
    .sensoryFeedback(.selection, trigger: draft.selectedColor)
    .sensoryFeedback(.selection, trigger: draft.tool)
  }

  private var history: some View {
    HStack {
      Button(L10n.Chats.Chats.Mcoimg.undo, systemImage: "arrow.uturn.backward") { draft.undo() }
        .labelStyle(.iconOnly)
        .keyboardShortcut("z", modifiers: .command)
        .disabled(!draft.canUndo)
        .accessibilityIdentifier("pixelPaintUndo")
      Button(L10n.Chats.Chats.Mcoimg.redo, systemImage: "arrow.uturn.forward") { draft.redo() }
        .labelStyle(.iconOnly)
        .keyboardShortcut("z", modifiers: [.command, .shift])
        .disabled(!draft.canRedo)
        .accessibilityIdentifier("pixelPaintRedo")
      Spacer()
      Button(L10n.Chats.Chats.Mcoimg.clear, systemImage: "trash") { draft.clear() }
        .disabled(draft.isBlank)
        .accessibilityIdentifier("pixelPaintClear")
    }
    .buttonStyle(.bordered)
    .controlSize(.large)
  }

  private var palette: some View {
    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: Self.paletteColumns)) {
      ForEach(0..<MCOImagePalette.count(profile: PixelPaintDraft.profile), id: \.self) { index in
        let isSelected = index == draft.selectedColor
        Button {
          draft.selectedColor = index
          if draft.tool == .eraser { draft.tool = .brush }
        } label: {
          swatch(index, isSelected: isSelected)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(PixelPaintDraft.colorName(index))
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityIdentifier("pixelColor\(index)")
      }
    }
  }

  /// The `.primary` and `.background` rings contrast with every palette colour, including the accent colour.
  private func swatch(_ index: Int, isSelected: Bool) -> some View {
    ZStack {
      if isSelected {
        Circle().fill(.primary)
        Circle().fill(.background).padding(Self.ringWidth)
      }
      Circle()
        .fill(MCOImageRenderer.color(index, profile: PixelPaintDraft.profile))
        .overlay { Circle().strokeBorder(.gray, lineWidth: Self.swatchBorderWidth) }
        .padding(Self.ringWidth * 2)
    }
    .frame(width: Self.swatchSize, height: Self.swatchSize)
    .contentShape(Circle())
  }
}
