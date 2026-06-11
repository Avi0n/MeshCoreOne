import SwiftUI
import VisionKit
import MC1Services
import os

/// View for scanning a contact QR code to import
struct ScanContactQRView: View {
    @Environment(\.appState) private var appState
    @Environment(\.openURL) private var openURL
    @Environment(\.dismiss) private var dismiss

    let onScan: (String, Data) -> Void

    @State private var isImporting = false
    @State private var errorMessage: String?
    @State private var cameraPermissionDenied = false
    @State private var scanSuccessTrigger = false

    private let logger = Logger(subsystem: "com.mc1", category: "ScanContactQRView")

    // MARK: - Constants

    private enum Constants {
        static let scanFrameSize: CGFloat = 250
        static let overlayOpacity: CGFloat = 0.6
        static let errorOpacity: CGFloat = 0.8
        static let bottomPadding: CGFloat = 50
    }

    var body: some View {
        Group {
            if cameraPermissionDenied {
                cameraPermissionDeniedView
            } else {
                scannerView
            }
        }
        .navigationTitle(L10n.Contacts.Contacts.Scan.title)
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Scanner View

    private var scannerView: some View {
        ZStack {
            if QRDataScannerView.isSupported && QRDataScannerView.isAvailable {
                QRDataScannerView { result in
                    handleScanResult(result)
                } onPermissionDenied: {
                    cameraPermissionDenied = true
                }
            } else {
                // Fallback for unsupported devices
                ContentUnavailableView(
                    L10n.Contacts.Contacts.Scan.Unavailable.title,
                    systemImage: "qrcode.viewfinder",
                    description: Text(L10n.Contacts.Contacts.Scan.Unavailable.description)
                )
            }

            // Overlay with scan frame
            VStack {
                Spacer()

                RoundedRectangle(cornerRadius: 20)
                    .stroke(.white, lineWidth: 3)
                    .frame(width: Constants.scanFrameSize, height: Constants.scanFrameSize)

                Spacer()

                if isImporting {
                    VStack(spacing: 12) {
                        ProgressView()
                        Text(L10n.Contacts.Contacts.Scan.importing)
                    }
                    .font(.subheadline)
                    .foregroundStyle(.white)
                    .padding()
                    .background(.black.opacity(Constants.overlayOpacity), in: .capsule)
                    .padding(.bottom, Constants.bottomPadding)
                } else if let errorMessage {
                    Button {
                        self.errorMessage = nil
                    } label: {
                        Text(errorMessage)
                            .font(.subheadline)
                            .foregroundStyle(.white)
                            .padding()
                            .background(.red.opacity(Constants.errorOpacity), in: .capsule)
                    }
                    .buttonStyle(.plain)
                    .padding(.bottom, Constants.bottomPadding)
                } else {
                    Text(L10n.Contacts.Contacts.Scan.instruction)
                        .font(.subheadline)
                        .foregroundStyle(.white)
                        .padding()
                        .background(.black.opacity(Constants.overlayOpacity), in: .capsule)
                        .padding(.bottom, Constants.bottomPadding)
                }
            }
        }
        .sensoryFeedback(.success, trigger: scanSuccessTrigger)
        .ignoresSafeArea()
    }

    // MARK: - Permission Denied View

    private var cameraPermissionDeniedView: some View {
        VStack(spacing: 20) {
            Image(systemName: "camera.fill")
                .font(.system(size: 60))
                .foregroundStyle(.secondary)

            Text(L10n.Contacts.Contacts.Scan.Permission.title)
                .font(.title2)
                .bold()

            Text(L10n.Contacts.Contacts.Scan.Permission.description)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)

            Button(L10n.Contacts.Contacts.List.openSettings) {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    openURL(url)
                }
            }
            .buttonStyle(.borderedProminent)
        }
        .padding()
    }

    // MARK: - Private Methods

    private func handleScanResult(_ result: String) {
        guard !isImporting else { return }

        guard let parsed = MeshCoreURLParser.parseContactURL(result) else {
            logger.error("Invalid QR code format: \(result)")
            errorMessage = L10n.Contacts.Contacts.Scan.Error.invalidFormat
            return
        }

        scanSuccessTrigger.toggle()

        // Claim the import synchronously so a second DataScanner callback can't slip
        // past the guard before the async import flips the flag.
        isImporting = true
        errorMessage = nil

        Task {
            await importContact(parsed)
        }
    }

    @MainActor
    private func importContact(_ contact: MeshCoreURLParser.ContactResult) async {
        guard let services = appState.services,
              let device = appState.connectedDevice else {
            logger.error("Services or device not available")
            errorMessage = L10n.Contacts.Contacts.Add.Error.notConnected
            isImporting = false
            return
        }

        let radioID = device.radioID
        let maxContacts = device.maxContacts

        do {
            let currentTimestamp = UInt32(Date().timeIntervalSince1970)

            let contactFrame = ContactFrame(
                publicKey: contact.publicKey,
                type: contact.contactType,
                flags: 0,
                outPathLength: PacketBuilder.floodPathSentinel,
                outPath: Data(),
                name: contact.name,
                lastAdvertTimestamp: 0,
                latitude: 0,
                longitude: 0,
                lastModified: currentTimestamp
            )

            logger.info("Importing contact: \(contact.name) (\(contact.publicKey.uppercaseHexString()))")
            try await services.contactService.addOrUpdateContact(radioID: radioID, contact: contactFrame)
            logger.info("Contact imported successfully")

            // Reset state and dismiss before calling completion handler
            isImporting = false
            dismiss()

            onScan(contact.name, contact.publicKey)
        } catch ContactServiceError.contactTableFull {
            logger.error("Node list is full")
            errorMessage = L10n.Contacts.Contacts.Add.Error.nodeListFull(Int(maxContacts))
            isImporting = false
        } catch {
            logger.error("Failed to import contact: \(error.localizedDescription)")
            errorMessage = L10n.Contacts.Contacts.Scan.Error.importFailed(error.localizedDescription)
            isImporting = false
        }
    }
}

#Preview {
    NavigationStack {
        ScanContactQRView { _, _ in }
    }
    .environment(\.appState, AppState())
}
