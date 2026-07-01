import MC1Services
import SwiftUI

/// Read-only display of a device public key with copy support, pushed from `DeviceInfoView`
/// via `SettingsSubpage.publicKey`.
struct PublicKeyView: View {
  let publicKey: Data

  @Environment(\.appTheme) private var theme
  @State private var copyHapticTrigger = 0

  var body: some View {
    List {
      Section {
        Text(publicKey.uppercaseHexString(separator: " "))
          .font(.system(.body, design: .monospaced))
          .textSelection(.enabled)
      } header: {
        Text(L10n.Settings.PublicKey.header)
      } footer: {
        Text(L10n.Settings.PublicKey.footer)
      }
      .themedRowBackground(theme)

      Section {
        Button {
          copyHapticTrigger += 1
          UIPasteboard.general.string = publicKey.uppercaseHexString()
        } label: {
          Label(L10n.Settings.PublicKey.copy, systemImage: "doc.on.doc")
        }

        // Base64 representation
        Text(publicKey.base64EncodedString())
          .font(.system(.caption, design: .monospaced))
          .foregroundStyle(.secondary)
          .textSelection(.enabled)
      } header: {
        Text(L10n.Settings.PublicKey.Base64.header)
      }
      .themedRowBackground(theme)
    }
    .themedCanvas(theme)
    .navigationTitle(L10n.Settings.PublicKey.title)
    .sensoryFeedback(.success, trigger: copyHapticTrigger)
  }
}
