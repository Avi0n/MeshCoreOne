import SwiftUI
import MC1Services
import CoreImage.CIFilterBuiltins

/// View for creating a private channel with auto-generated secret and QR code
struct CreatePrivateChannelView: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme

    let availableSlots: [UInt8]
    let onComplete: (ChannelDTO?) -> Void

    @State private var channelName = ""
    @State private var selectedSlot: UInt8
    @State private var generatedSecret: Data?
    @State private var isCreating = false
    @State private var createdChannel: ChannelDTO?
    @State private var errorMessage: String?
    @State private var copyHapticTrigger = 0

    private var isCreated: Bool { createdChannel != nil }

    init(availableSlots: [UInt8], onComplete: @escaping (ChannelDTO?) -> Void) {
        self.availableSlots = availableSlots
        self.onComplete = onComplete
        self._selectedSlot = State(initialValue: availableSlots.first ?? 1)
    }

    var body: some View {
        Form {
            if !isCreated {
                CreateChannelFormContent(
                    channelName: $channelName,
                    generatedSecret: generatedSecret,
                    isCreating: isCreating,
                    errorMessage: errorMessage,
                    onCreate: { Task { await createChannel() } }
                )
            } else {
                ShareChannelContent(
                    channelName: channelName,
                    generatedSecret: generatedSecret,
                    copyHapticTrigger: $copyHapticTrigger
                )
            }
        }
        .themedCanvas(theme)
        .navigationTitle(isCreated ? L10n.Chats.Chats.CreatePrivate.titleShare : L10n.Chats.Chats.CreatePrivate.titleCreate)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if isCreated {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.Chats.Chats.Common.done) {
                        onComplete(createdChannel)
                    }
                }
            }
        }
        .onAppear {
            generateSecret()
        }
        .sensoryFeedback(.success, trigger: copyHapticTrigger)
    }

    // MARK: - Private Methods

    private func generateSecret() {
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        generatedSecret = Data(bytes)
    }

    private func createChannel() async {
        guard let radioID = appState.connectedDevice?.radioID,
              let secret = generatedSecret else {
            errorMessage = L10n.Chats.Chats.Error.noDeviceConnected
            return
        }

        isCreating = true
        defer { isCreating = false }
        errorMessage = nil

        do {
            guard let channelService = appState.services?.channelService else {
                errorMessage = L10n.Chats.Chats.Error.servicesUnavailable
                return
            }
            try await channelService.setChannelWithSecret(
                radioID: radioID,
                index: selectedSlot,
                name: channelName,
                secret: secret
            )

            // Fetch the created channel to return it
            if let channels = try? await appState.services?.dataStore.fetchChannels(radioID: radioID) {
                createdChannel = channels.first { $0.index == selectedSlot }
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - Extracted Views

private struct CreateChannelFormContent: View {
    @Environment(\.appTheme) private var theme
    @Binding var channelName: String
    let generatedSecret: Data?
    let isCreating: Bool
    let errorMessage: String?
    let onCreate: () -> Void

    var body: some View {
        Group {
            Section {
                TextField(L10n.Chats.Chats.CreatePrivate.channelName, text: $channelName)
                    .textContentType(.name)
                    .onChange(of: channelName) { _, newValue in
                        if newValue.utf8.count > ProtocolLimits.maxUsableNameBytes {
                            channelName = newValue.utf8Prefix(maxBytes: ProtocolLimits.maxUsableNameBytes)
                        }
                    }
            } header: {
                Text(L10n.Chats.Chats.CreatePrivate.Section.details)
            }
            .themedRowBackground(theme)

            Section {
                if let secret = generatedSecret {
                    LabeledContent(L10n.Chats.Chats.ChannelInfo.secretKey) {
                        Text(secret.hexString())
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }
                }
            } header: {
                Text(L10n.Chats.Chats.CreatePrivate.Section.secret)
            } footer: {
                Text(L10n.Chats.Chats.CreatePrivate.secretFooter)
            }
            .themedRowBackground(theme)

            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .foregroundStyle(.red)
                }
                .themedRowBackground(theme)
            }

            Section {
                Button(action: onCreate) {
                    HStack {
                        Spacer()
                        if isCreating {
                            ProgressView()
                        } else {
                            Text(L10n.Chats.Chats.CreatePrivate.createButton)
                        }
                        Spacer()
                    }
                }
                .disabled(channelName.isEmpty || isCreating || generatedSecret == nil)
            }
            .themedRowBackground(theme)
        }
    }
}

private struct ShareChannelContent: View {
    @Environment(\.appTheme) private var theme
    let channelName: String
    let generatedSecret: Data?
    @Binding var copyHapticTrigger: Int

    var body: some View {
        Group {
            Section {
                HStack {
                    Spacer()
                    VStack(spacing: 16) {
                        if let qrImage = generateQRCode() {
                            Image(uiImage: qrImage)
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 200, height: 200)
                        }

                        Text(channelName)
                            .font(.headline)

                        Text(L10n.Chats.Chats.ChannelInfo.scanToJoin)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding()
                    Spacer()
                }
            }
            .themedRowBackground(theme)

            Section {
                if let secret = generatedSecret {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(L10n.Chats.Chats.ChannelInfo.secretKey)
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        HStack {
                            Text(secret.hexString())
                                .font(.system(.body, design: .monospaced))

                            Spacer()

                            Button(L10n.Chats.Chats.ChannelInfo.copy, systemImage: "doc.on.doc") {
                                copyHapticTrigger += 1
                                UIPasteboard.general.string = secret.hexString()
                            }
                            .labelStyle(.iconOnly)
                        }
                    }
                }
            } header: {
                Text(L10n.Chats.Chats.CreatePrivate.Section.shareManually)
            } footer: {
                Text(L10n.Chats.Chats.CreatePrivate.shareManuallyFooter)
            }
            .themedRowBackground(theme)
        }
    }

    private func generateQRCode() -> UIImage? {
        guard let secret = generatedSecret else { return nil }

        // Format: meshcore://channel/add?name=<name>&secret=<hex>
        let urlString = "meshcore://channel/add?name=\(channelName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")&secret=\(secret.hexString())"

        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(urlString.utf8)
        filter.correctionLevel = "M"

        guard let outputImage = filter.outputImage else { return nil }

        // Scale up for better quality
        let scale = 10.0
        let scaledImage = outputImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

        guard let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) else { return nil }

        return UIImage(cgImage: cgImage)
    }
}

#Preview {
    NavigationStack {
        CreatePrivateChannelView(availableSlots: [1, 2, 3], onComplete: { _ in })
    }
    .environment(\.appState, AppState())
}
