import SwiftUI
import MC1Services

/// Sheet for editing WiFi connection parameters.
/// Pre-populates with current connection details and allows updating them.
struct WiFiEditSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme

    /// Optional initial values for editing a saved (non-connected) device
    var initialHost: String?
    var initialPort: UInt16?

    @State private var ipAddress = ""
    @State private var port = "5000"
    @State private var isReconnecting = false
    @State private var errorMessage: String?

    @FocusState private var focusedField: WiFiField?

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
        WiFiAddressFields.isValidIPAddress(ipAddress) && WiFiAddressFields.isValidPort(port)
    }

    private var hasChanges: Bool {
        guard let host = originalHost, let currentPort = originalPort else { return true }
        return ipAddress != host || port != String(currentPort)
    }

    var body: some View {
        NavigationStack {
            Form {
                WiFiAddressFields(
                    ipAddress: $ipAddress,
                    port: $port,
                    focusedField: $focusedField,
                    sectionHeader: L10n.Settings.WifiEdit.connectionDetails,
                    sectionFooter: L10n.Settings.WifiEdit.footer,
                    onPortSubmit: { saveChanges() }
                )

                if let errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }
                    .themedRowBackground(theme)
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
                .themedRowBackground(theme)
            }
            .themedCanvas(theme)
            .navigationTitle(L10n.Settings.WifiEdit.title)
            .navigationBarTitleDisplayMode(.inline)
            .wifiSheetToolbar(focusedField: $focusedField, isProcessing: isReconnecting)
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

}

#Preview {
    WiFiEditSheet()
        .environment(\.appState, AppState())
}
