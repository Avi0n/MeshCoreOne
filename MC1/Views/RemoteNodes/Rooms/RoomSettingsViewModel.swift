import SwiftUI
import MC1Services
import OSLog

@Observable
@MainActor
final class RoomSettingsViewModel {

    // MARK: - Shared Helper

    var helper = NodeSettingsHelper()

    // MARK: - Room Access (guest password + read-only)

    var guestPassword: String?
    var allowReadOnly: Bool?
    private var originalGuestPassword: String?
    private var originalAllowReadOnly: Bool?
    var isLoadingRoomAccess = false
    var roomAccessError = false
    var isApplyingRoomAccess = false
    var roomAccessApplySuccess = false
    var isRoomAccessExpanded = false

    var roomAccessLoaded: Bool { guestPassword != nil || allowReadOnly != nil }

    var roomAccessModified: Bool {
        (guestPassword != nil && guestPassword != originalGuestPassword) ||
        (allowReadOnly != nil && allowReadOnly != originalAllowReadOnly)
    }

    // MARK: - Behavior (advert intervals + flood)

    var advertIntervalMinutes: Int?
    var floodAdvertIntervalHours: Int?
    var floodMaxHops: Int?
    private var originalAdvertIntervalMinutes: Int?
    private var originalFloodAdvertIntervalHours: Int?
    private var originalFloodMaxHops: Int?
    var isLoadingBehavior = false
    var behaviorError = false
    var isApplyingBehavior = false
    var behaviorApplySuccess = false
    var isBehaviorExpanded = false

    var advertIntervalError: String?
    var floodAdvertIntervalError: String?
    var floodMaxHopsError: String?

    var behaviorLoaded: Bool { advertIntervalMinutes != nil || floodAdvertIntervalHours != nil || floodMaxHops != nil }

    var behaviorModified: Bool {
        (advertIntervalMinutes != nil && advertIntervalMinutes != originalAdvertIntervalMinutes) ||
        (floodAdvertIntervalHours != nil && floodAdvertIntervalHours != originalFloodAdvertIntervalHours) ||
        (floodMaxHops != nil && floodMaxHops != originalFloodMaxHops)
    }

    // MARK: - Dependencies

    private var roomAdminService: RoomAdminService?
    private let logger = Logger(subsystem: "com.mc1", category: "RoomSettings")

    // MARK: - Cleanup

    func cleanup() async {
        await roomAdminService?.setCLIHandler { _, _ in }
        helper.cleanup()
    }

    // MARK: - Configuration

    func configure(appState: AppState, session: RemoteNodeSessionDTO) async {
        self.roomAdminService = appState.services?.roomAdminService

        guard let roomAdminService else { return }

        helper.configure(
            session: session,
            sendCommand: { [roomAdminService] id, cmd, timeout in
                try await roomAdminService.sendCommand(sessionID: id, command: cmd, timeout: timeout)
            },
            sendRawCommand: { [roomAdminService] id, cmd, timeout in
                try await roomAdminService.sendRawCommand(sessionID: id, command: cmd, timeout: timeout)
            }
        )

        helper.setNodeInfo(firmwareVersion: nil, name: session.name, ownerInfo: nil)

        // Room doesn't have binary protocol for node info — firmware fetched via CLI
        helper.onPreFetchNodeInfo = nil

        // Register CLI handler for late responses
        await roomAdminService.setCLIHandler { [weak self] message, _ in
            await MainActor.run {
                self?.handleLateResponse(message.text)
            }
        }

        Task { await helper.fetchDeviceInfo() }
    }

    /// Builds the node-CLI send closure, pre-binding this session's id and
    /// capturing the private admin service (a thin pass-through to
    /// `RemoteNodeService.sendRawCLICommand`). Returns nil if not configured.
    func makeNodeCLISendClosure(
        session: RemoteNodeSessionDTO
    ) -> (@MainActor (_ command: String, _ timeout: Duration) async throws -> String)? {
        guard let roomAdminService else { return nil }
        return { [roomAdminService, sessionID = session.id] command, timeout in
            try await roomAdminService.sendRawCommand(
                sessionID: sessionID, command: command, timeout: timeout)
        }
    }

    // MARK: - Late Response Handling

    private func handleLateResponse(_ response: String) {
        // Try shared sections first
        if helper.handleCommonLateResponse(response) { return }

        // Behavior settings
        if !isLoadingBehavior && behaviorError {
            if let result = NodeSettingsHelper.parseBehaviorLateResponse(
                response,
                hasAdvertInterval: originalAdvertIntervalMinutes != nil,
                hasFloodInterval: originalFloodAdvertIntervalHours != nil,
                hasFloodMaxHops: originalFloodMaxHops != nil
            ) {
                switch result {
                case .advertInterval(let interval):
                    self.advertIntervalMinutes = interval
                    self.originalAdvertIntervalMinutes = interval
                case .floodAdvertInterval(let interval):
                    self.floodAdvertIntervalHours = interval
                    self.originalFloodAdvertIntervalHours = interval
                case .floodMax(let hops):
                    self.floodMaxHops = hops
                    self.originalFloodMaxHops = hops
                }
                self.behaviorError = false
                return
            }
        }
    }

    // MARK: - Room Access Fetch/Apply

    func fetchRoomAccess() async {
        isLoadingRoomAccess = true
        roomAccessError = false

        do {
            let response = try await helper.sendAndWait("get guest.password", rawMatching: true)
            let parsed = CLIResponse.parse(response, forQuery: "get guest.password")
            switch parsed {
            case .ok, .error, .unknownCommand:
                self.guestPassword = ""
                self.originalGuestPassword = ""
            default:
                let trimmed = response.trimmingCharacters(in: .whitespacesAndNewlines)
                let value = trimmed.hasPrefix("> ") ? String(trimmed.dropFirst(2)) : trimmed
                self.guestPassword = value
                self.originalGuestPassword = value
            }
        } catch {
            if case RemoteNodeError.timeout = error { roomAccessError = true }
            logger.warning("Failed to get guest password: \(error)")
        }

        do {
            let response = try await helper.sendAndWait("get allow.read.only", rawMatching: true)
            let parsed = CLIResponse.parse(response, forQuery: "get allow.read.only")
            switch parsed {
            case .raw(let value):
                let isOn = value.lowercased() == "on"
                self.allowReadOnly = isOn
                self.originalAllowReadOnly = isOn
            default:
                break
            }
        } catch {
            if case RemoteNodeError.timeout = error { roomAccessError = true }
            logger.warning("Failed to get allow read only: \(error)")
        }

        isLoadingRoomAccess = false
    }

    func applyRoomAccess() async {
        isApplyingRoomAccess = true
        helper.errorMessage = nil

        do {
            var allSucceeded = true

            if let guestPassword, guestPassword != originalGuestPassword {
                let response = try await helper.sendAndWait("set guest.password \(guestPassword)")
                if case .ok = CLIResponse.parse(response) {
                    originalGuestPassword = guestPassword
                } else {
                    allSucceeded = false
                }
            }

            if let allowReadOnly, allowReadOnly != originalAllowReadOnly {
                let response = try await helper.sendAndWait("set allow.read.only \(allowReadOnly ? "on" : "off")")
                if case .ok = CLIResponse.parse(response) {
                    originalAllowReadOnly = allowReadOnly
                } else {
                    allSucceeded = false
                }
            }

            if allSucceeded {
                await helper.flashSuccess(
                    setApplying: { isApplyingRoomAccess = $0 },
                    setSuccess: { roomAccessApplySuccess = $0 }
                )
                return
            } else {
                helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.someSettingsFailedToApply
            }
        } catch {
            helper.errorMessage = error.localizedDescription
        }

        isApplyingRoomAccess = false
    }

    // MARK: - Behavior Fetch/Apply

    func fetchBehaviorSettings() async {
        isLoadingBehavior = true
        behaviorError = false
        var hadTimeout = false

        do {
            let response = try await helper.sendAndWait("get advert.interval")
            if case .advertInterval(let minutes) = CLIResponse.parse(response, forQuery: "get advert.interval") {
                self.advertIntervalMinutes = minutes
                self.originalAdvertIntervalMinutes = minutes
            }
        } catch {
            if case RemoteNodeError.timeout = error { hadTimeout = true }
            logger.warning("Failed to get advert interval: \(error)")
        }

        do {
            let response = try await helper.sendAndWait("get flood.advert.interval")
            if case .floodAdvertInterval(let hours) = CLIResponse.parse(response, forQuery: "get flood.advert.interval") {
                self.floodAdvertIntervalHours = hours
                self.originalFloodAdvertIntervalHours = hours
            }
        } catch {
            if case RemoteNodeError.timeout = error { hadTimeout = true }
            logger.warning("Failed to get flood advert interval: \(error)")
        }

        do {
            let response = try await helper.sendAndWait("get flood.max")
            if case .floodMax(let hops) = CLIResponse.parse(response, forQuery: "get flood.max") {
                self.floodMaxHops = hops
                self.originalFloodMaxHops = hops
            }
        } catch {
            if case RemoteNodeError.timeout = error { hadTimeout = true }
            logger.warning("Failed to get flood max: \(error)")
        }

        if hadTimeout {
            behaviorError = true
        }

        isLoadingBehavior = false
    }

    func applyBehaviorSettings() async {
        let validation = NodeSettingsHelper.validateBehaviorFields(
            advertInterval: advertIntervalMinutes,
            floodInterval: floodAdvertIntervalHours,
            floodMaxHops: floodMaxHops
        )
        advertIntervalError = validation.advertInterval
        floodAdvertIntervalError = validation.floodInterval
        floodMaxHopsError = validation.floodMaxHops

        if validation.hasErrors { return }

        isApplyingBehavior = true
        helper.errorMessage = nil

        do {
            var allSucceeded = true

            if let advertIntervalMinutes, advertIntervalMinutes != originalAdvertIntervalMinutes {
                let response = try await helper.sendAndWait("set advert.interval \(advertIntervalMinutes)")
                if case .ok = CLIResponse.parse(response) {
                    originalAdvertIntervalMinutes = advertIntervalMinutes
                } else {
                    allSucceeded = false
                }
            }

            if let floodAdvertIntervalHours, floodAdvertIntervalHours != originalFloodAdvertIntervalHours {
                let response = try await helper.sendAndWait("set flood.advert.interval \(floodAdvertIntervalHours)")
                if case .ok = CLIResponse.parse(response) {
                    originalFloodAdvertIntervalHours = floodAdvertIntervalHours
                } else {
                    allSucceeded = false
                }
            }

            if let floodMaxHops, floodMaxHops != originalFloodMaxHops {
                let response = try await helper.sendAndWait("set flood.max \(floodMaxHops)")
                if case .ok = CLIResponse.parse(response) {
                    originalFloodMaxHops = floodMaxHops
                } else {
                    allSucceeded = false
                }
            }

            if allSucceeded {
                await helper.flashSuccess(
                    setApplying: { isApplyingBehavior = $0 },
                    setSuccess: { behaviorApplySuccess = $0 }
                )
                return
            } else {
                helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.someSettingsFailedToApply
            }
        } catch {
            helper.errorMessage = error.localizedDescription
        }

        isApplyingBehavior = false
    }

}
