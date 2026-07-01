import Emojibase
import SwiftUI

/// Full emoji picker sheet with categories, search, and frequently used
struct EmojiPickerSheet: View {
  let onSelect: (String) -> Void

  @Environment(\.dismiss) private var dismiss
  @State private var viewModel = EmojiPickerViewModel()

  @ScaledMetric private var emojiSize: CGFloat = 44

  var body: some View {
    NavigationStack {
      ScrollView {
        LazyVGrid(
          columns: [GridItem(.adaptive(minimum: emojiSize))],
          spacing: 8
        ) {
          ForEach(viewModel.categories) { category in
            Section {
              ForEach(category.emojis) { emoji in
                emojiButton(emoji)
              }
            } header: {
              EmojiCategoryHeader(title: category.localizedName)
            }
          }
        }
        .padding(.horizontal)
      }
      .navigationTitle(L10n.Chats.Reactions.title)
      .navigationBarTitleDisplayMode(.inline)
      .searchable(
        text: $viewModel.searchQuery,
        placement: .navigationBarDrawer(displayMode: .always),
        prompt: L10n.Chats.Reactions.Emoji.searchPlaceholder
      )
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.Localizable.Common.cancel) {
            dismiss()
          }
        }
      }
      .task {
        await viewModel.load()
      }
    }
    .presentationDetents([.medium, .large])
    .presentationDragIndicator(.visible)
  }

  private func emojiButton(_ emoji: EmojiItem) -> some View {
    Button {
      viewModel.markAsFrequentlyUsed(emoji.unicode)
      onSelect(emoji.unicode)
      dismiss()
    } label: {
      Text(emoji.unicode)
        .font(.largeTitle)
        .frame(width: emojiSize, height: emojiSize)
    }
    .buttonStyle(.plain)
    .accessibilityLabel(emoji.label.isEmpty ? emoji.unicode : emoji.label)
  }
}

#Preview {
  EmojiPickerSheet { emoji in
    print("Selected: \(emoji)")
  }
}
