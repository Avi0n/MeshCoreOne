import SwiftUI

enum WiFiField: Hashable {
  case ipAddress, port
}

/// Shared IP address and port input fields used by WiFi connection sheets.
struct WiFiAddressFields: View {
  @Binding var ipAddress: String
  @Binding var port: String
  var focusedField: FocusState<WiFiField?>.Binding
  let sectionHeader: String
  let sectionFooter: String
  let onPortSubmit: () -> Void

  @Environment(\.horizontalSizeClass) private var horizontalSizeClass

  private var usesFullKeyboardInput: Bool {
    horizontalSizeClass == .regular
  }

  var body: some View {
    Section {
      HStack {
        TextField(L10n.Onboarding.WifiConnection.IpAddress.placeholder, text: $ipAddress)
          .keyboardType(usesFullKeyboardInput ? .numbersAndPunctuation : .decimalPad)
          .environment(\.locale, Locale(identifier: "en_US"))
          .textContentType(.none)
          .textInputAutocapitalization(.never)
          .autocorrectionDisabled()
          .submitLabel(.next)
          .focused(focusedField, equals: .ipAddress)
          .onChange(of: ipAddress) { _, newValue in
            let replaced = newValue.replacing(",", with: ".")
            if replaced != newValue {
              ipAddress = replaced
            }
          }
          .onSubmit {
            focusedField.wrappedValue = .port
          }

        if !ipAddress.isEmpty {
          Button {
            ipAddress = ""
          } label: {
            Image(systemName: "xmark.circle.fill")
              .foregroundStyle(.secondary)
          }
          .buttonStyle(.plain)
          .accessibilityLabel(L10n.Onboarding.WifiConnection.IpAddress.clearAccessibility)
        }
      }

      HStack {
        TextField(L10n.Onboarding.WifiConnection.Port.placeholder, text: $port)
          .keyboardType(usesFullKeyboardInput ? .numbersAndPunctuation : .numberPad)
          .submitLabel(.done)
          .focused(focusedField, equals: .port)
          .onSubmit {
            onPortSubmit()
          }

        if !port.isEmpty {
          Button {
            port = ""
          } label: {
            Image(systemName: "xmark.circle.fill")
              .foregroundStyle(.secondary)
          }
          .buttonStyle(.plain)
          .accessibilityLabel(L10n.Onboarding.WifiConnection.Port.clearAccessibility)
        }
      }
    } header: {
      Text(sectionHeader)
    } footer: {
      Text(sectionFooter)
    }
  }

  // MARK: - Validation

  static func isValidIPAddress(_ ip: String) -> Bool {
    let parts = ip.split(separator: ".")
    guard parts.count == 4 else { return false }
    return parts.allSatisfy { part in
      guard let num = Int(part) else { return false }
      return num >= 0 && num <= 255
    }
  }

  static func isValidPort(_ port: String) -> Bool {
    guard let num = UInt16(port) else { return false }
    return num > 0
  }
}
