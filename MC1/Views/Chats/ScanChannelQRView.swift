import AudioToolbox
import MC1Services
import OSLog
import SwiftUI
import VisionKit

/// View for scanning a channel QR code to join
struct ScanChannelQRView: View {
  @Environment(\.appState) private var appState

  let availableSlots: [UInt8]
  let onComplete: (ChannelDTO?) -> Void

  @State private var scannedChannel: MeshCoreURLParser.ChannelResult?
  @State private var selectedSlot: UInt8
  @State private var isJoining = false
  @State private var errorMessage: String?
  @State private var cameraPermissionDenied = false

  init(availableSlots: [UInt8], onComplete: @escaping (ChannelDTO?) -> Void) {
    self.availableSlots = availableSlots
    self.onComplete = onComplete
    _selectedSlot = State(initialValue: availableSlots.first ?? 1)
  }

  var body: some View {
    Group {
      if let channel = scannedChannel {
        ScanConfirmationContent(
          scannedChannel: channel,
          isJoining: isJoining,
          errorMessage: errorMessage,
          onJoin: { Task { await joinChannel() } },
          onScanAgain: {
            scannedChannel = nil
            errorMessage = nil
          }
        )
      } else if cameraPermissionDenied {
        CameraPermissionDeniedContent()
      } else {
        ScannerContent(
          onScanResult: handleScanResult,
          cameraPermissionDenied: $cameraPermissionDenied
        )
      }
    }
    .navigationTitle(L10n.Chats.Chats.ScanQR.title)
    .navigationBarTitleDisplayMode(.inline)
  }

  // MARK: - Private Methods

  private func handleScanResult(_ result: String) {
    guard let parsed = MeshCoreURLParser.parseChannelURL(result) else {
      errorMessage = L10n.Chats.Chats.ScanQR.Error.invalidFormat
      return
    }
    scannedChannel = parsed
  }

  private func joinChannel() async {
    guard let radioID = appState.connectedDevice?.radioID,
          let channel = scannedChannel else {
      errorMessage = L10n.Chats.Chats.Error.noDeviceConnected
      return
    }

    isJoining = true
    defer { isJoining = false }
    errorMessage = nil

    do {
      guard let channelService = appState.services?.channelService else {
        errorMessage = L10n.Chats.Chats.Error.servicesUnavailable
        return
      }
      try await channelService.setChannelWithSecret(
        radioID: radioID,
        index: selectedSlot,
        name: channel.name,
        secret: channel.secret
      )

      // Fetch the joined channel to return it
      guard let dataStore = appState.services?.dataStore,
            let channels = try? await dataStore.fetchChannels(radioID: radioID),
            let joined = channels.first(where: { $0.index == selectedSlot }) else {
        onComplete(nil)
        return
      }

      let completed = await ChannelJoinFloodScopeApplier.applyIfNeeded(
        channel: joined,
        regionScope: channel.regionScope,
        setFloodScope: { channelID, scope in
          try await dataStore.setChannelFloodScope(channelID, floodScope: scope)
        },
        logger: Logger(subsystem: "com.mc1", category: "ScanChannelQRView")
      )
      onComplete(completed)
    } catch {
      errorMessage = error.userFacingMessage
    }
  }
}

// MARK: - Extracted Views

private struct ScannerContent: View {
  let onScanResult: (String) -> Void
  @Binding var cameraPermissionDenied: Bool

  var body: some View {
    ZStack {
      if QRDataScannerView.isSupported, QRDataScannerView.isAvailable {
        QRDataScannerView { result in
          onScanResult(result)
        } onPermissionDenied: {
          cameraPermissionDenied = true
        }
      } else {
        // Fallback for unsupported devices
        ContentUnavailableView(
          L10n.Chats.Chats.ScanQR.NotAvailable.title,
          systemImage: "qrcode.viewfinder",
          description: Text(L10n.Chats.Chats.ScanQR.NotAvailable.description)
        )
      }

      // Overlay with scan frame
      VStack {
        Spacer()

        RoundedRectangle(cornerRadius: 20)
          .stroke(.white, lineWidth: 3)
          .frame(width: 250, height: 250)

        Spacer()

        Text(L10n.Chats.Chats.ScanQR.instruction)
          .font(.subheadline)
          .foregroundStyle(.white)
          .padding()
          .background(.black.opacity(0.6), in: .capsule)
          .padding(.bottom, 50)
      }
    }
    .ignoresSafeArea()
  }
}

private struct ScanConfirmationContent: View {
  @Environment(\.appTheme) private var theme

  let scannedChannel: MeshCoreURLParser.ChannelResult
  let isJoining: Bool
  let errorMessage: String?
  let onJoin: () -> Void
  let onScanAgain: () -> Void

  var body: some View {
    Form {
      Section {
        LabeledContent(L10n.Chats.Chats.CreatePrivate.channelName, value: scannedChannel.name)

        LabeledContent(L10n.Chats.Chats.ChannelInfo.secretKey) {
          Text(scannedChannel.secret.uppercaseHexString())
            .font(.system(.caption, design: .monospaced))
            .foregroundStyle(.secondary)
        }

        if let regionScope = scannedChannel.regionScope {
          LabeledContent(L10n.Chats.Chats.ChannelInfo.region, value: regionScope)
        }
      } header: {
        Text(L10n.Chats.Chats.CreatePrivate.Section.details)
      } footer: {
        if scannedChannel.hasHashtagSecretMismatch {
          Text(L10n.Chats.Chats.JoinFromMessage.hashtagSecretMismatch)
        }
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
        Button(action: onJoin) {
          HStack {
            Spacer()
            if isJoining {
              ProgressView()
            } else {
              Text(L10n.Chats.Chats.JoinPrivate.joinButton)
            }
            Spacer()
          }
        }
        .disabled(isJoining)
        .alignmentGuide(.listRowSeparatorLeading) { _ in 0 }

        Button(action: onScanAgain) {
          HStack {
            Spacer()
            Text(L10n.Chats.Chats.ScanQR.scanAgain)
            Spacer()
          }
        }
      }
      .themedRowBackground(theme)
    }
    .themedCanvas(theme)
  }
}

private struct CameraPermissionDeniedContent: View {
  var body: some View {
    VStack(spacing: 20) {
      Image(systemName: "camera.fill")
        .font(.system(size: 60))
        .foregroundStyle(.secondary)

      Text(L10n.Chats.Chats.ScanQR.PermissionDenied.title)
        .font(.title2)
        .bold()

      Text(L10n.Chats.Chats.ScanQR.PermissionDenied.message)
        .font(.subheadline)
        .foregroundStyle(.secondary)
        .multilineTextAlignment(.center)
        .padding(.horizontal)

      Button(L10n.Chats.Chats.ScanQR.openSettings) {
        if let url = URL(string: UIApplication.openSettingsURLString) {
          UIApplication.shared.open(url)
        }
      }
      .buttonStyle(.borderedProminent)
    }
    .padding()
  }
}

// MARK: - QR Scanner using DataScannerViewController

struct QRDataScannerView: UIViewControllerRepresentable {
  let onScan: (String) -> Void
  let onPermissionDenied: () -> Void

  static var isSupported: Bool {
    DataScannerViewController.isSupported
  }

  static var isAvailable: Bool {
    DataScannerViewController.isAvailable
  }

  func makeUIViewController(context: Context) -> DataScannerViewController {
    let scanner = DataScannerViewController(
      recognizedDataTypes: [.barcode(symbologies: [.qr])],
      qualityLevel: .balanced,
      recognizesMultipleItems: false,
      isHighFrameRateTrackingEnabled: false,
      isHighlightingEnabled: true
    )
    scanner.delegate = context.coordinator
    return scanner
  }

  func updateUIViewController(_ controller: DataScannerViewController, context: Context) {
    // Start scanning when view appears
    if !controller.isScanning {
      try? controller.startScanning()
    }
  }

  static func dismantleUIViewController(_ controller: DataScannerViewController, coordinator: Coordinator) {
    controller.stopScanning()
  }

  func makeCoordinator() -> Coordinator {
    Coordinator(onScan: onScan)
  }

  @MainActor
  class Coordinator: NSObject, DataScannerViewControllerDelegate {
    let onScan: (String) -> Void
    private var hasScanned = false

    init(onScan: @escaping (String) -> Void) {
      self.onScan = onScan
    }

    func dataScanner(_ dataScanner: DataScannerViewController, didTapOn item: RecognizedItem) {
      processItem(item, scanner: dataScanner)
    }

    func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
      // Auto-capture first QR code detected
      guard !hasScanned, let item = addedItems.first else { return }
      processItem(item, scanner: dataScanner)
    }

    private func processItem(_ item: RecognizedItem, scanner: DataScannerViewController) {
      guard !hasScanned else { return }

      if case let .barcode(barcode) = item,
         let payload = barcode.payloadStringValue {
        hasScanned = true
        scanner.stopScanning()
        AudioServicesPlaySystemSound(SystemSoundID(kSystemSoundID_Vibrate))
        onScan(payload)
      }
    }
  }
}

#Preview {
  NavigationStack {
    ScanChannelQRView(availableSlots: [1, 2, 3], onComplete: { _ in })
  }
  .environment(\.appState, AppState())
}
