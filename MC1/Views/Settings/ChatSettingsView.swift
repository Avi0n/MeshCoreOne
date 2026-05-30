import SwiftUI

struct ChatSettingsView: View {
    @Environment(\.appTheme) private var theme
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
            .themedRowBackground(theme)

            LinkPreviewSettingsSection()
            MapPreviewSettingsSection()
            MessagesSettingsSection()
            BlockingSection()
        }
        .themedCanvas(theme)
        .navigationTitle(L10n.Settings.ChatSettings.title)
        .navigationBarTitleDisplayMode(.inline)
    }
}
