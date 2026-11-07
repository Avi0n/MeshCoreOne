#if canImport(UIKit)
  import AccessorySetupKit
  import CoreBluetooth
  import os
  import UIKit

  /// Delegate protocol for accessory state changes
  /// Must be @MainActor since implementations access main-actor-isolated state
  @MainActor
  protocol AccessorySetupKitServiceDelegate: AnyObject {
    /// Called when an accessory is removed from Settings > Accessories
    func accessorySetupKitService(_ service: AccessorySetupKitService, didRemoveAccessoryWithID bluetoothID: UUID)

    /// Called when pairing fails (e.g., wrong PIN). The device should be cleaned up from local storage.
    func accessorySetupKitService(_ service: AccessorySetupKitService, didFailPairingForAccessoryWithID bluetoothID: UUID)
  }

  /// Owns ASK session activation and native picker callbacks.
  @Observable
  @MainActor
  final class AccessorySetupKitService {
    private let logger = PersistentLogger(subsystem: "com.mc1", category: "AccessorySetupKit")

    private var session: ASAccessorySession?

    /// Previously paired accessories available after session activation
    private(set) var pairedAccessories: [ASAccessory] = []

    /// Whether the session is currently active
    private(set) var isSessionActive = false

    /// Delegate for accessory state changes
    weak var delegate: AccessorySetupKitServiceDelegate?

    private var pickerState = AccessoryPickerState()

    /// Pending activation continuation
    private var activationContinuation: CheckedContinuation<Void, Error>?

    /// Display items accumulated across `accessoryDiscovered` events and re-sent in full on
    /// each `updatePicker`. Keyed by the accessory, not `bluetoothIdentifier` (a discovery
    /// lacks one until it pairs); `AnyHashable` keeps the iOS 26.1 type off the property.
    private var discoveredDisplayItems: [AnyHashable: ASPickerDisplayItem] = [:]

    /// Product image rendered once per `showPicker` and reused by the discovery handler.
    private var currentProductImage: UIImage?

    /// Static label used on systems without filtered discovery and as the fallback when a
    /// discovered accessory advertises no local name. Mirrors `Settings.deviceInfo.defaultManufacturer`.
    private static let defaultAccessoryName = "MeshCore Device"

    init() {}

    // MARK: - Session Management

    func activateSession() async throws {
      guard session == nil else { return }

      let newSession = ASAccessorySession()
      session = newSession

      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        self.activationContinuation = continuation

        newSession.activate(on: .main) { [weak self] event in
          guard let self, session === newSession else { return }
          handleEvent(event)

          // Resume activation continuation only once
          if event.eventType == .activated {
            isSessionActive = true
            pairedAccessories = newSession.accessories
            logger.info("ASAccessorySession activated with \(pairedAccessories.count) accessories")
            activationContinuation?.resume()
            activationContinuation = nil
          } else if event.eventType == .invalidated {
            activationContinuation?.resume(throwing: AccessorySetupKitError.sessionInvalidated)
            activationContinuation = nil
          }
        }
      }
    }

    /// Handle incoming ASK events
    /// Called directly from session's event handler closure
    private func handleEvent(_ event: ASAccessoryEvent) {
      switch event.eventType {
      case .activated:
        // Handled in activateSession continuation
        break

      case .invalidated:
        logger.error("ASAccessorySession invalidated")
        isSessionActive = false
        pairedAccessories = []
        // Release the invalidated session so the next activation can create a usable one.
        session = nil
        pickerState.invalidate()
        clearPickerDisplayItems()

      case .accessoryAdded:
        if let accessory = event.accessory {
          switch pickerState.accessoryAdded(bluetoothID: accessory.bluetoothIdentifier) {
          case .removeAccessory:
            logger.info("[ASK] Removing orphaned accessory after picker cancellation: \(accessory.displayName)")
            Task { [weak self] in
              guard let self else { return }
              do {
                try await removeAccessory(accessory)
              } catch {
                logger.warning("[ASK] Failed to remove orphaned accessory: \(error.localizedDescription)")
              }
            }
            return
          case .ignored:
            pairedAccessories = session?.accessories ?? []
            return
          case .selected, .unclaimed:
            break
          }

          pairedAccessories = session?.accessories ?? []
          logger.info("Accessory added: \(accessory.displayName)")
          logger.info(
            AccessorySetupKitLogFormatter.selectionMessage(
              accessoryName: accessory.displayName,
              bluetoothID: accessory.bluetoothIdentifier,
              elapsed: pickerElapsedTime
            )
          )
        }

      case .accessoryRemoved:
        if let accessory = event.accessory,
           let bluetoothID = accessory.bluetoothIdentifier {
          logger.info("Accessory removed: \(accessory.displayName)")
          pairedAccessories = session?.accessories ?? []
          // Notify delegate to clear stored state
          delegate?.accessorySetupKitService(self, didRemoveAccessoryWithID: bluetoothID)
        }

      case .accessoryChanged:
        pairedAccessories = session?.accessories ?? []
        logger.info("Accessory changed")

      case .accessoryDiscovered:
        // Under filtered discovery (iOS 26.1+) the picker hands each match to us so we can
        // relabel it with the device's advertised name before it appears for selection.
        if #available(iOS 26.1, *), pickerState.attempt?.filteredDiscovery == true {
          handleDiscoveredAccessory(event.accessory)
        }

      case .pickerDidPresent:
        pickerState.didPresent()
        logger.info("Picker presented")

      case .pickerDidDismiss:
        let attempt = pickerState.dismiss()
        logger.info(
          AccessorySetupKitLogFormatter.dismissalMessage(
            outcome: attempt?.outcome.rawValue ?? "cancelled",
            pairedCount: pairedAccessories.count,
            elapsed: attempt.map { Date().timeIntervalSince($0.presentedAt) },
            filteredDiscovery: attempt?.filteredDiscovery ?? false
          )
        )
        clearPickerDisplayItems()

      case .pickerSetupBridging:
        logger.info("Picker bridging...")

      case .pickerSetupPairing:
        logger.info("User entering PIN...")

      case .pickerSetupFailed:
        if let error = event.error {
          pickerState.didPresent()
          logger.error("Pairing failed: \(error.localizedDescription)")

          if let accessory = event.accessory,
             let bluetoothID = accessory.bluetoothIdentifier {
            logger.info("Cleaning up failed pairing for \(accessory.displayName)")

            delegate?.accessorySetupKitService(self, didFailPairingForAccessoryWithID: bluetoothID)

            if pairedAccessories.contains(where: { $0.bluetoothIdentifier == bluetoothID }) {
              Task {
                do {
                  try await self.removeAccessory(accessory)
                  self.logger.info("Removed failed accessory from ASK")
                } catch {
                  self.logger.warning("Failed to remove accessory from ASK: \(error.localizedDescription)")
                }
              }
            }
          }

          if let attemptID = pickerState.attempt?.id {
            pickerState.fail(
              AccessorySetupKitError.pairingFailed(error.localizedDescription),
              attemptID: attemptID
            )
          }
        }

      case .pickerSetupRename:
        logger.info("Picker rename step")

      case .migrationComplete:
        logger.info("Migration complete")

      case .unknown:
        // Explicit handling per Apple sample code
        logger.info("Received unknown event type")

      @unknown default:
        // Reserve for future event types
        logger.warning("Received future event type: \(String(describing: event.eventType))")
      }
    }

    /// Show the accessory picker for new device pairing
    /// - Returns: The Bluetooth identifier (UUID) for the paired device
    func showPicker() async throws -> UUID {
      guard let session else {
        throw AccessorySetupKitError.sessionNotActive
      }

      guard !pickerState.isActive else {
        throw AccessorySetupKitError.pickerAlreadyActive
      }

      discoveredDisplayItems.removeAll()
      var usedFilteredDiscovery = false

      if #available(iOS 26.1, *), AccessorySetupKitDiscoveryCriteria.usesFilteredDiscovery {
        let settings = session.pickerDisplaySettings ?? ASPickerDisplaySettings()
        settings.options.insert(.filterDiscoveryResults)
        session.pickerDisplaySettings = settings
        usedFilteredDiscovery = true
      } else if #available(iOS 26.0, *) {
        if session.pickerDisplaySettings == nil {
          session.pickerDisplaySettings = ASPickerDisplaySettings()
        }
      }

      let productImage = createGenericProductImage()
      currentProductImage = productImage
      let displayItems = makePickerDisplayItems(productImage: productImage)
      let attemptID = UUID()
      logger.info(
        """
        [ASK] Presenting picker on iOS \(currentOSVersion), \
        sessionActive: \(isSessionActive), \
        pairedCount: \(pairedAccessories.count), \
        displayItems: \(displayItems.count), \
        filteredDiscovery: \(usedFilteredDiscovery), \
        serviceUUID: \(AccessorySetupKitDiscoveryCriteria.bluetoothServiceUUID)
        """
      )

      return try await withTaskCancellationHandler {
        try await withCheckedThrowingContinuation { continuation in
          guard !Task.isCancelled else {
            continuation.resume(throwing: CancellationError())
            return
          }
          guard pickerState.begin(
            id: attemptID,
            presentedAt: Date(),
            filteredDiscovery: usedFilteredDiscovery,
            continuation: continuation
          ) else { return }

          session.showPicker(for: displayItems) { [weak self] error in
            guard let self else { return }

            Task { @MainActor in
              self.handlePickerError(error, attemptID: attemptID)
            }
          }
        }
      } onCancel: { [weak self] in
        Task { @MainActor in
          self?.handlePickerCancellation(attemptID: attemptID)
        }
      }
    }

    private func handlePickerCancellation(attemptID: UUID) {
      guard pickerState.attempt?.id == attemptID else { return }
      pickerState.cancel(attemptID: attemptID)
      logger.warning("[ASK] Picker cancelled programmatically; awaiting Task unwound")
    }

    private func handlePickerError(_ error: Error?, attemptID: UUID) {
      guard let error, pickerState.attempt?.id == attemptID else { return }
      logger.error("Picker error: \(error.localizedDescription)")
      let mappedError: AccessorySetupKitError
      if let error = error as? ASError {
        switch error.code {
        case .pickerRestricted:
          mappedError = .pickerRestricted
        case .pickerAlreadyActive:
          mappedError = .pickerAlreadyActive
        case .userCancelled:
          pickerState.userCancelled(attemptID: attemptID)
          return
        case .discoveryTimeout:
          mappedError = .discoveryTimeout
        case .connectionFailed:
          mappedError = .connectionFailed
        default:
          logger.error("Unexpected picker error code: \(error.code.rawValue)")
          mappedError = .connectionFailed
        }
      } else {
        mappedError = .pairingFailed(error.localizedDescription)
      }
      pickerState.fail(mappedError, attemptID: attemptID)
      if !pickerState.isActive {
        clearPickerDisplayItems()
      }
    }

    /// Remove an accessory from the system
    /// Note: iOS 26 shows a confirmation dialog to the user
    func removeAccessory(_ accessory: ASAccessory) async throws {
      guard let session else {
        throw AccessorySetupKitError.sessionNotActive
      }

      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        session.removeAccessory(accessory) { error in
          if let error {
            continuation.resume(throwing: error)
          } else {
            continuation.resume()
          }
        }
      }

      pairedAccessories = session.accessories
    }

    /// Shows the system rename sheet for an accessory
    /// - Parameter accessory: The accessory to rename
    func renameAccessory(_ accessory: ASAccessory) async throws {
      guard let session else {
        throw AccessorySetupKitError.sessionNotActive
      }

      try await session.renameAccessory(accessory)
      pairedAccessories = session.accessories
    }

    /// Find a paired accessory by its Bluetooth identifier
    func accessory(for bluetoothID: UUID) -> ASAccessory? {
      pairedAccessories.first { $0.bluetoothIdentifier == bluetoothID }
    }

    /// Invalidate the session
    func invalidateSession() {
      pickerState.invalidate()
      clearPickerDisplayItems()
      activationContinuation?.resume(throwing: AccessorySetupKitError.sessionInvalidated)
      activationContinuation = nil
      session?.invalidate()
      session = nil
      isSessionActive = false
      pairedAccessories = []
    }

    // MARK: - Private Helpers

    private var currentOSVersion: String {
      let version = ProcessInfo.processInfo.operatingSystemVersion
      return "\(version.majorVersion).\(version.minorVersion).\(version.patchVersion)"
    }

    private var pickerElapsedTime: TimeInterval? {
      pickerState.attempt.map { Date().timeIntervalSince($0.presentedAt) }
    }

    private func clearPickerDisplayItems() {
      discoveredDisplayItems.removeAll()
      currentProductImage = nil
    }

    private func makePickerDisplayItems(productImage: UIImage) -> [ASPickerDisplayItem] {
      let descriptor = ASDiscoveryDescriptor()
      descriptor.bluetoothServiceUUID = CBUUID(
        string: AccessorySetupKitDiscoveryCriteria.bluetoothServiceUUID
      )

      return [
        ASPickerDisplayItem(
          name: Self.defaultAccessoryName,
          productImage: productImage,
          descriptor: descriptor
        )
      ]
    }

    /// Relabels a filtered-discovery match with its advertised BLE name, falling back to
    /// `defaultAccessoryName` when the advertisement carries no usable local name.
    @available(iOS 26.1, *)
    private func handleDiscoveredAccessory(_ accessory: ASAccessory?) {
      guard let session,
            let discovered = accessory as? ASDiscoveredAccessory else { return }

      let advertisedName = (discovered.bluetoothAdvertisementData?[CBAdvertisementDataLocalNameKey] as? String)?
        .trimmingCharacters(in: .whitespacesAndNewlines)
      let name = advertisedName.flatMap { $0.isEmpty ? nil : $0 } ?? Self.defaultAccessoryName
      let productImage = currentProductImage ?? createGenericProductImage()

      logger.info("[ASK] Discovered accessory '\(name)', RSSI: \(discovered.bluetoothRSSI.map(String.init) ?? "n/a")")

      discoveredDisplayItems[AnyHashable(discovered)] = ASDiscoveredDisplayItem(
        name: name,
        productImage: productImage,
        accessory: discovered
      )

      let items = discoveredDisplayItems.values.compactMap { $0 as? ASDiscoveredDisplayItem }
      logger.info("[ASK] updatePicker with \(items.count) discovered item(s)")
      session.updatePicker(showing: items) { [weak self] error in
        guard let error else { return }
        Task { @MainActor in
          self?.logger.warning("[ASK] updatePicker failed: \(error.localizedDescription)")
        }
      }
    }

    /// Uses a generic image because the radio model is unknown until CMD_DEVICE_QUERY.
    private func createGenericProductImage() -> UIImage {
      let size = CGSize(width: 180, height: 120)
      let renderer = UIGraphicsImageRenderer(size: size)

      return renderer.image { context in
        // Transparent background (required for light/dark mode)
        UIColor.clear.setFill()
        context.fill(CGRect(origin: .zero, size: size))

        // Draw centered SF Symbol with padding for visual balance
        let config = UIImage.SymbolConfiguration(pointSize: 50, weight: .regular)
        guard let symbol = UIImage(systemName: "antenna.radiowaves.left.and.right", withConfiguration: config)?
          .withTintColor(.systemBlue, renderingMode: .alwaysOriginal) else { return }

        let symbolSize = symbol.size
        let origin = CGPoint(
          x: (size.width - symbolSize.width) / 2,
          y: (size.height - symbolSize.height) / 2
        )
        symbol.draw(at: origin)
      }
    }
  }

  // MARK: - Errors

  public enum AccessorySetupKitError: LocalizedError, Sendable {
    case sessionNotActive
    case sessionInvalidated
    case pickerDismissed
    case pickerRestricted
    case pickerAlreadyActive
    case pairingFailed(String)
    case noBluetoothIdentifier
    case discoveryTimeout
    case connectionFailed

    public var errorDescription: String? {
      switch self {
      case .sessionNotActive:
        "Bluetooth is not ready. Please ensure Bluetooth is enabled and try again."
      case .sessionInvalidated:
        "Bluetooth session ended unexpectedly. Please restart the app."
      case .pickerDismissed:
        "Device selection was cancelled."
      case .pickerRestricted:
        "Cannot show device picker. Please check that Bluetooth is enabled, wait a moment, and try again."
      case .pickerAlreadyActive:
        "Device picker is already showing."
      case let .pairingFailed(reason):
        "Pairing failed: \(reason)"
      case .noBluetoothIdentifier:
        "Selected device does not support Bluetooth connection."
      case .discoveryTimeout:
        "No devices found. Make sure your device is powered on and nearby."
      case .connectionFailed:
        "Could not connect to the device. Please try again."
      }
    }
  }

#else
  // macOS stubs for compilation
  import Foundation

  struct ASAccessory {
    var bluetoothIdentifier: UUID?
    var displayName: String
    init(bluetoothIdentifier: UUID? = nil, displayName: String = "") {
      self.bluetoothIdentifier = bluetoothIdentifier
      self.displayName = displayName
    }
  }

  @MainActor
  protocol AccessorySetupKitServiceDelegate: AnyObject {
    func accessorySetupKitService(_ service: AccessorySetupKitService, didRemoveAccessoryWithID bluetoothID: UUID)
    func accessorySetupKitService(_ service: AccessorySetupKitService, didFailPairingForAccessoryWithID bluetoothID: UUID)
  }

  @Observable
  @MainActor
  final class AccessorySetupKitService {
    private(set) var pairedAccessories: [ASAccessory] = []
    private(set) var isSessionActive = false
    weak var delegate: AccessorySetupKitServiceDelegate?

    init() {}

    func activateSession() async throws {}
    func showPicker() async throws -> UUID {
      throw AccessorySetupKitError.sessionNotActive
    }

    func removeAccessory(_ accessory: ASAccessory) async throws {}
    func renameAccessory(_ accessory: ASAccessory) async throws {}
    func accessory(for bluetoothID: UUID) -> ASAccessory? {
      nil
    }

    func invalidateSession() {}
  }

  public enum AccessorySetupKitError: LocalizedError, Sendable {
    case sessionNotActive
    case sessionInvalidated
    case pickerDismissed
    case pickerRestricted
    case pickerAlreadyActive
    case pairingFailed(String)
    case noBluetoothIdentifier
    case discoveryTimeout
    case connectionFailed

    public var errorDescription: String? {
      switch self {
      case .sessionNotActive:
        "Bluetooth is not ready. Please ensure Bluetooth is enabled and try again."
      case .sessionInvalidated:
        "Bluetooth session ended unexpectedly. Please restart the app."
      case .pickerDismissed:
        "Device selection was cancelled."
      case .pickerRestricted:
        "Cannot show device picker. Please check that Bluetooth is enabled, wait a moment, and try again."
      case .pickerAlreadyActive:
        "Device picker is already showing."
      case let .pairingFailed(reason):
        "Pairing failed: \(reason)"
      case .noBluetoothIdentifier:
        "Selected device does not support Bluetooth connection."
      case .discoveryTimeout:
        "No devices found. Make sure your device is powered on and nearby."
      case .connectionFailed:
        "Could not connect to the device. Please try again."
      }
    }
  }
#endif
