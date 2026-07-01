import SwiftUI

struct ActionsEmojiSection: View {
  let recentEmojis: [String]
  @Binding var showEmojiPicker: Bool
  let onSelectEmoji: (String) -> Void

  @State private var pickerSelection: String?

  var body: some View {
    EmojiPickerRow(
      emojis: recentEmojis,
      onSelect: onSelectEmoji,
      onOpenKeyboard: { showEmojiPicker = true }
    )
    .padding(.vertical, 4)
    // The full picker is a child of the actions sheet. Reacting also dismisses
    // the actions sheet, so defer that to the picker's onDismiss: dismissing the
    // parent while the child is still presented can strand the actions sheet open.
    .sheet(isPresented: $showEmojiPicker, onDismiss: {
      if let emoji = pickerSelection {
        pickerSelection = nil
        onSelectEmoji(emoji)
      }
    }) {
      EmojiPickerSheet(onSelect: { pickerSelection = $0 })
    }
  }
}
