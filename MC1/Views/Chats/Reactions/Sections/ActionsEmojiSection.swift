import SwiftUI

struct ActionsEmojiSection: View {
    let recentEmojis: [String]
    @Binding var showEmojiPicker: Bool
    let onSelectEmoji: (String) -> Void

    var body: some View {
        EmojiPickerRow(
            emojis: recentEmojis,
            onSelect: onSelectEmoji,
            onOpenKeyboard: { showEmojiPicker = true }
        )
        .padding(.vertical, 4)
        .sheet(isPresented: $showEmojiPicker) {
            EmojiPickerSheet(onSelect: onSelectEmoji)
        }
    }
}
