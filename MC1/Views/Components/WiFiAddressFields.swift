import SwiftUI

enum WiFiField: Hashable {
  case ipAddress, port
}

private enum WiFiHostLimits {
  static let hostnameMaxLength = 253
  static let hostnameLabelMaxLength = 63
  static let hostnameLabelCharacters = CharacterSet(
    charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-"
  )
}

/// Shared host and port input fields used by WiFi connection sheets.
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
          .keyboardType(.URL)
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

  /// Host checks do not touch view state, so they stay off the main actor.
  nonisolated static func isValidHost(_ raw: String) -> Bool {
    let host = normalizedHost(raw)
    guard !host.isEmpty else { return false }
    if looksLikeIPv4(host) {
      return isValidIPAddress(host)
    }
    return isValidHostname(host)
  }

  nonisolated static func normalizedHost(_ raw: String) -> String {
    raw.trimmingCharacters(in: .whitespacesAndNewlines)
  }

  nonisolated static func isValidIPAddress(_ ip: String) -> Bool {
    let parts = ip.split(separator: ".")
    guard parts.count == 4 else { return false }
    return parts.allSatisfy { part in
      guard let num = Int(part) else { return false }
      return num >= 0 && num <= 255
    }
  }

  nonisolated static func isValidPort(_ port: String) -> Bool {
    guard let num = UInt16(port) else { return false }
    return num > 0
  }

  private nonisolated static func looksLikeIPv4(_ host: String) -> Bool {
    let parts = host.split(separator: ".", omittingEmptySubsequences: false)
    guard !parts.isEmpty else { return false }
    return parts.allSatisfy { part in
      !part.isEmpty && part.allSatisfy { $0.isASCII && $0.isNumber }
    }
  }

  private nonisolated static func isValidHostname(_ host: String) -> Bool {
    let candidate = host.hasSuffix(".") ? String(host.dropLast()) : host
    guard (1...WiFiHostLimits.hostnameMaxLength).contains(candidate.count) else { return false }
    let labels = candidate.split(separator: ".", omittingEmptySubsequences: false)
    return !labels.isEmpty && labels.allSatisfy(isValidHostnameLabel)
  }

  private nonisolated static func isValidHostnameLabel(_ label: Substring) -> Bool {
    guard (1...WiFiHostLimits.hostnameLabelMaxLength).contains(label.count) else { return false }
    guard let first = label.first, let last = label.last else { return false }
    guard label.unicodeScalars.allSatisfy({ WiFiHostLimits.hostnameLabelCharacters.contains($0) }) else {
      return false
    }
    return first != "-" && last != "-"
  }
}
