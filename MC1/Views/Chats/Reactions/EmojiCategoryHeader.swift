import SwiftUI

/// Section header for emoji picker categories
struct EmojiCategoryHeader: View {
  let title: String

  var body: some View {
    HStack {
      Text(title)
        .font(.subheadline)
        .bold()
        .foregroundStyle(.secondary)
      Spacer()
    }
    .padding(.top, 16)
    .padding(.bottom, 8)
  }
}

#Preview {
  VStack {
    EmojiCategoryHeader(title: "Frequently Used")
    EmojiCategoryHeader(title: "Smileys & People")
  }
  .padding()
}
