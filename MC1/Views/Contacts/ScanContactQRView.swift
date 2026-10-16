import Accessibility
import MC1Services
import os
import SwiftUI
import VisionKit

struct ScanContactQRView: View {
  @Environment(\.appState) private var appState
  @Environment(\.openURL) private var openURL
  @Environment(\.dismiss) private var dismiss

  let onComplete: (ContactDTO) -> Void

  @State private var scannedContact: MeshCoreURLParser.ContactResult?
  @State private var existingContact: ContactDTO?
  @State private var isResolvingScan = false
  @State private var isImporting = false
  @State private var errorMessage: String?
  @State private var cameraPermissionDenied = false
  @State private var parseSelectionTrigger = 0
  @State private var successTrigger = 0
  @State private var errorHapticTrigger = 0

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
      if let scannedContact {
        ContactAddConfirmationContent(
          contactResult: scannedContact,
          existingContact: existingContact,
          errorMessage: errorMessage,
          isAdding: isImporting,
          onAdd: {
            if let existingContact {
              onComplete(existingContact)
              dismiss()
            } else {
              Task { await importContact(scannedContact) }
            }
          },
          onScanAgain: resetToScanner
        )
      } else if cameraPermissionDenied {
        cameraPermissionDeniedView
      } else {
        scannerView
      }
    }
    .navigationTitle(confirmationTitle)
    .navigationBarTitleDisplayMode(.inline)
    .sensoryFeedback(.selection, trigger: parseSelectionTrigger)
    .sensoryFeedback(.success, trigger: successTrigger)
    .sensoryFeedback(.error, trigger: errorHapticTrigger)
  }

  // MARK: - Scanner View

  private var scannerView: some View {
    ZStack {
      if QRDataScannerView.isSupported, QRDataScannerView.isAvailable {
        QRDataScannerView { result in
          handleScanResult(result)
        } onPermissionDenied: {
          cameraPermissionDenied = true
        }
      } else {
        ContentUnavailableView(
          L10n.Contacts.Contacts.Scan.Unavailable.title,
          systemImage: "qrcode.viewfinder",
          description: Text(L10n.Contacts.Contacts.Scan.Unavailable.description)
        )
      }

      VStack {
        Spacer()

        RoundedRectangle(cornerRadius: 20)
          .stroke(.white, lineWidth: 3)
          .frame(width: Constants.scanFrameSize, height: Constants.scanFrameSize)

        Spacer()

        if let errorMessage {
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

  private var confirmationTitle: String {
    if scannedContact == nil {
      L10n.Contacts.Contacts.Scan.title
    } else if let existingContact {
      existingContact.displayName
    } else {
      L10n.Contacts.Contacts.Add.nodeTitle
    }
  }

  private func handleScanResult(_ result: String) {
    guard scannedContact == nil, !isImporting, !isResolvingScan else { return }

    guard let parsed = MeshCoreURLParser.parseContactURL(result) else {
      logger.error("Invalid QR code format: \(result)")
      errorMessage = L10n.Contacts.Contacts.Scan.Error.invalidFormat
      errorHapticTrigger += 1
      return
    }

    parseSelectionTrigger += 1
    errorMessage = nil
    // Claim the scan before the lookup so a second DataScanner callback cannot present another contact.
    isResolvingScan = true
    Task { await presentScannedContact(parsed) }
  }

  @MainActor
  private func presentScannedContact(_ parsed: MeshCoreURLParser.ContactResult) async {
    defer { isResolvingScan = false }
    guard scannedContact == nil, !isImporting else { return }

    // Resolve the saved row before showing the review so the first paint is View, not Add.
    existingContact = await fetchExistingContact(publicKey: parsed.publicKey)
    scannedContact = parsed

    let announcement = if let existingContact {
      "\(L10n.Contacts.Contacts.Add.alreadyAdded), \(existingContact.displayName)"
    } else {
      "\(parsed.name), \(parsed.contactType.localizedName)"
    }
    AccessibilityNotification.Announcement(announcement).post()
  }

  private func fetchExistingContact(publicKey: Data) async -> ContactDTO? {
    guard let radioID = appState.currentRadioID else { return nil }
    let store = appState.services?.dataStore ?? appState.offlineDataStore
    return try? await store?.fetchContact(radioID: radioID, publicKey: publicKey)
  }

  private func resetToScanner() {
    scannedContact = nil
    existingContact = nil
    errorMessage = nil
    isImporting = false
    isResolvingScan = false
  }

  @MainActor
  private func importContact(_ contact: MeshCoreURLParser.ContactResult) async {
    guard !isImporting else { return }

    guard let services = appState.services,
          let device = appState.connectedDevice else {
      logger.error("Services or device not available")
      presentImportFailure(L10n.Contacts.Contacts.Add.Error.notConnected)
      return
    }

    let radioID = device.radioID
    let maxContacts = device.maxContacts

    isImporting = true
    errorMessage = nil

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

      // ContactDetailView takes a ContactDTO; the QR payload is not a list row.
      guard let addedContact = try await services.dataStore.fetchContact(
        radioID: radioID,
        publicKey: contact.publicKey
      ) else {
        logger.error("Imported contact was not in the store")
        presentImportFailure(L10n.Contacts.Contacts.Common.errorOccurred)
        isImporting = false
        return
      }

      logger.info("Contact imported successfully")
      successTrigger += 1
      AccessibilityNotification.Announcement(
        L10n.Contacts.Contacts.Scan.Accessibility.added(contact.name)
      ).post()
      onComplete(addedContact)
      dismiss()
    } catch ContactServiceError.contactTableFull {
      logger.error("Node list is full")
      presentImportFailure(L10n.Contacts.Contacts.Add.Error.nodeListFull(Int(maxContacts)))
      isImporting = false
    } catch {
      logger.error("Failed to import contact: \(error.localizedDescription)")
      presentImportFailure(L10n.Contacts.Contacts.Scan.Error.importFailed(error.userFacingMessage))
      isImporting = false
    }
  }

  private func presentImportFailure(_ message: String) {
    errorMessage = message
    errorHapticTrigger += 1
    AccessibilityNotification.Announcement(message).post()
  }
}

#Preview {
  NavigationStack {
    ScanContactQRView { _ in }
  }
  .environment(\.appState, AppState())
}
