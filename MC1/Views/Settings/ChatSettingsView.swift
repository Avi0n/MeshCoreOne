import SwiftUI

struct ChatSettingsView: View {
    @AppStorage("replyWithQuote") private var replyWithQuote = false

    var body: some View {
        List {
            Section {
                Toggle(isOn: $replyWithQuote) {
                    TintedLabel(L10n.Settings.ReplyWithQuote.toggle, systemImage: "text.quote")
                }
            } footer: {
                Text(L10n.Settings.ReplyWithQuote.footer)
            }

            LinkPreviewSettingsSection()
            MapPreviewSettingsSection()
            MessagesSettingsSection()
            BlockingSection()
        }
        .navigationTitle(L10n.Settings.ChatSettings.title)
        .navigationBarTitleDisplayMode(.inline)
    }
}
