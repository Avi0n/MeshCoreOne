import SwiftUI
import MC1Services

/// Sheet for editing WiFi connection parameters.
/// Pre-populates with current connection details and allows updating them.
struct WiFiEditSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appState) private var appState
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    /// Optional initial values for editing a saved (non-connected) device
    var initialHost: String?
    var initialPort: UInt16?

    @State private var ipAddress = ""
    @State private var port = "5000"
    @State private var isReconnecting = false
    @State private var errorMessage: String?

    @FocusState private var focusedField: Field?

    enum Field {
        case ipAddress, port
    }

    private var currentConnection: ConnectionMethod? {
        appState.connectedDevice?.connectionMethods.first { $0.isWiFi }
    }

    private var originalHost: String? {
        if let initialHost { return initialHost }
        if case .wifi(let host, _, _) = currentConnection { return host }
        return nil
    }

    private var originalPort: UInt16? {
        if let initialPort { return initialPort }
        if case .wifi(_, let port, _) = currentConnection { return port }
        return nil
    }

    private var isValidInput: Bool {
        isValidIPAddress(ipAddress) && isValidPort(port)
    }

    private var hasChanges: Bool {
        guard let host = originalHost, let currentPort = originalPort else { return true }
        return ipAddress != host || port != String(currentPort)
    }

    private var usesFullKeyboardInput: Bool {
        horizontalSizeClass == .regular
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        TextField(L10n.Settings.WifiEdit.ipPlaceholder, text: $ipAddress)
                            .keyboardType(usesFullKeyboardInput ? .numbersAndPunctuation : .decimalPad)
                            .environment(\.locale, Locale(identifier: "en_US"))
                            .textContentType(.none)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .submitLabel(.next)
                            .focused($focusedField, equals: .ipAddress)
                            .onChange(of: ipAddress) { _, newValue in
                                let replaced = newValue.replacing(",", with: ".")
                                if replaced != newValue {
                                    ipAddress = replaced
                                }
                            }
                            .onSubmit {
                                focusedField = .port
                            }

                        if !ipAddress.isEmpty {
                            Button {
                                ipAddress = ""
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundStyle(.secondary)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(L10n.Settings.WifiEdit.clearIp)
                        }
                    }

                    HStack {
                        TextField(L10n.Settings.WifiEdit.portPlaceholder, text: $port)
                            .keyboardType(usesFullKeyboardInput ? .numbersAndPunctuation : .numberPad)
                            .submitLabel(.done)
                            .focused($focusedField, equals: .port)
                            .onSubmit {
                                saveChanges()
                            }

                        if !port.isEmpty {
                            Button {
                                port = ""
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundStyle(.secondary)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(L10n.Settings.WifiEdit.clearPort)
                        }
                    }
                } header: {
                    Text(L10n.Settings.WifiEdit.connectionDetails)
                } footer: {
                    Text(L10n.Settings.WifiEdit.footer)
                }

                if let errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    Button {
                        saveChanges()
                    } label: {
                        HStack {
                            Spacer()
                            if isReconnecting {
                                ProgressView()
                                    .controlSize(.small)
                                Text(L10n.Settings.WifiEdit.reconnecting)
                            } else {
                                Text(L10n.Settings.WifiEdit.saveChanges)
                            }
                            Spacer()
                        }
                    }
                    .disabled(!isValidInput || !hasChanges || isReconnecting)
                }
            }
            .navigationTitle(L10n.Settings.WifiEdit.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.Localizable.Common.cancel) {
                        focusedField = nil
                        dismiss()
                    }
                    .disabled(isReconnecting)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if usesFullKeyboardInput, focusedField != nil {
                        Button(L10n.Localizable.Common.done) {
                            focusedField = nil
                        }
                    }
                }
                if !usesFullKeyboardInput {
                    ToolbarItemGroup(placement: .keyboard) {
                        Spacer()
                        Button(L10n.Localizable.Common.done) {
                            focusedField = nil
                        }
                    }
                }
            }
            .interactiveDismissDisabled(isReconnecting)
            .onAppear {
                populateCurrentValues()
            }
        }
        .presentationSizing(.page)
    }

    private func populateCurrentValues() {
        if let host = originalHost {
            ipAddress = host
        }
        if let currentPort = originalPort {
            port = String(currentPort)
        }
    }

    private func saveChanges() {
        focusedField = nil

        guard let portNumber = UInt16(port) else {
            errorMessage = L10n.Settings.WifiEdit.Error.invalidPort
            return
        }

        isReconnecting = true
        errorMessage = nil

        Task {
            do {
                // Disconnect from current connection, then connect to new address
                await appState.disconnect(reason: .wifiAddressChange)
                try await appState.connectViaWiFi(host: ipAddress, port: portNumber, forceFullSync: true)
                dismiss()
            } catch {
                errorMessage = error.localizedDescription
                isReconnecting = false
            }
        }
    }

    private func isValidIPAddress(_ ipString: String) -> Bool {
        let parts = ipString.split(separator: ".")
        guard parts.count == 4 else { return false }
        return parts.allSatisfy { part in
            guard let num = Int(part) else { return false }
            return num >= 0 && num <= 255
        }
    }

    private func isValidPort(_ port: String) -> Bool {
        guard let num = UInt16(port) else { return false }
        return num > 0
    }
}

#Preview {
    WiFiEditSheet()
        .environment(\.appState, AppState())
}
