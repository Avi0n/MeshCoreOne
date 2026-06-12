import SwiftUI
import MC1Services
import OSLog

@Observable
@MainActor
final class RepeaterSettingsViewModel {

    // MARK: - Shared Helper

    var helper = NodeSettingsViewModel()

    // MARK: - Repeater-Only: Behavior Settings

    var advertIntervalMinutes: Int?
    var floodAdvertIntervalHours: Int?
    var floodMaxHops: Int?
    var repeaterEnabled: Bool?
    private var originalAdvertIntervalMinutes: Int?
    private var originalFloodAdvertIntervalHours: Int?
    private var originalFloodMaxHops: Int?
    private var originalRepeaterEnabled: Bool?
    var isLoadingBehavior = false
    var behaviorError = false
    var behaviorLoaded: Bool { repeaterEnabled != nil || advertIntervalMinutes != nil }

    var advertIntervalError: String?
    var floodAdvertIntervalError: String?
    var floodMaxHopsError: String?

    var behaviorApplySuccess = false

    var behaviorSettingsModified: Bool {
        (repeaterEnabled != nil && repeaterEnabled != originalRepeaterEnabled) ||
        (advertIntervalMinutes != nil && advertIntervalMinutes != originalAdvertIntervalMinutes) ||
        (floodAdvertIntervalHours != nil && floodAdvertIntervalHours != originalFloodAdvertIntervalHours) ||
        (floodMaxHops != nil && floodMaxHops != originalFloodMaxHops)
    }

    // MARK: - Repeater-Only: Region Settings

    nonisolated static let wildcardName = "*"
    var regions: [RepeaterRegionEntry] = []
    private var originalRegions: [RepeaterRegionEntry]?
    var isLoadingRegions = false
    var regionsError = false
    var regionsLoaded: Bool { originalRegions != nil }
    var hasUnsavedRegionChanges = false
    var isAddingRegion = false
    var newRegionName = ""
    var regionsSaveSuccess = false

    // MARK: - Expansion State (repeater-only sections)

    var isBehaviorExpanded = false
    var isRegionsExpanded = false

    // MARK: - Dependencies

    private var repeaterAdminService: RepeaterAdminService?
    private let logger = Logger(subsystem: "com.mc1", category: "RepeaterSettings")

    // MARK: - Cleanup

    func cleanup() async {
        await repeaterAdminService?.setCLIHandler { _, _ in }
        helper.cleanup()
    }

    // MARK: - Configuration

    /// Nil service mirrors a disconnected state; commands then no-op.
    func configure(repeaterAdminService: RepeaterAdminService?, session: RemoteNodeSessionDTO) async {
        self.repeaterAdminService = repeaterAdminService

        guard let repeaterAdminService else { return }

        helper.configure(
            session: session,
            sendCommand: { [repeaterAdminService] id, cmd, timeout in
                try await repeaterAdminService.sendCommand(sessionID: id, command: cmd, timeout: timeout)
            },
            sendRawCommand: { [repeaterAdminService] id, cmd, timeout in
                try await repeaterAdminService.sendRawCommand(sessionID: id, command: cmd, timeout: timeout)
            }
        )

        helper.name = session.name

        helper.onPreFetchNodeInfo = { [weak self] in
            await self?.fetchNodeInfo()
        }

        // Register CLI handler for late responses
        await repeaterAdminService.setCLIHandler { [weak self] message, _ in
            await MainActor.run {
                self?.handleLateResponse(message.text)
            }
        }

        // Detached so configure returns immediately and the node CLI send
        // closure wires without waiting on the owner-info round-trip (matches
        // RoomSettingsViewModel's detached device-info fetch).
        Task { await fetchNodeInfo() }
    }

    /// Builds the node-CLI send closure, pre-binding this session's id and
    /// capturing the private admin service (a thin pass-through to
    /// `RemoteNodeService.sendRawCLICommand`). Returns nil if not configured.
    func makeNodeCLISendClosure(
        session: RemoteNodeSessionDTO
    ) -> (@MainActor (_ command: String, _ timeout: Duration) async throws -> String)? {
        guard let repeaterAdminService else { return nil }
        return { [repeaterAdminService, sessionID = session.id] command, timeout in
            try await repeaterAdminService.sendRawCommand(
                sessionID: sessionID, command: command, timeout: timeout)
        }
    }

    private var isLoadingNodeInfo = false

    private func fetchNodeInfo() async {
        guard !isLoadingNodeInfo, let session = helper.session, let repeaterAdminService else { return }
        isLoadingNodeInfo = true
        defer { isLoadingNodeInfo = false }
        do {
            let response = try await repeaterAdminService.requestOwnerInfo(sessionID: session.id)
            helper.setNodeInfo(
                firmwareVersion: response.firmwareVersion,
                name: response.nodeName,
                ownerInfo: response.ownerInfo
            )
        } catch {
            logger.warning("Failed to fetch node info via binary: \(error)")
        }
    }

    // MARK: - Late Response Handling

    private func handleLateResponse(_ response: String) {
        // Try shared sections first
        if helper.handleCommonLateResponse(response) { return }

        // Behavior settings
        if !isLoadingBehavior && behaviorError {
            if originalRepeaterEnabled == nil {
                if case .repeatMode(let enabled) = CLIResponse.parse(response, forQuery: "get repeat") {
                    self.repeaterEnabled = enabled
                    self.originalRepeaterEnabled = enabled
                    self.behaviorError = false
                    logger.info("Late response: received repeat mode")
                    return
                }
            }

            if let result = NodeSettingsResponseParser.behaviorLateResponse(
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

        // Regions
        if !isLoadingRegions && regionsError {
            if originalRegions == nil {
                let parsed = Self.parseRegionTree(response)
                if !parsed.isEmpty {
                    self.regions = parsed
                    self.originalRegions = parsed
                    self.regionsError = false
                    logger.info("Late response: received region tree (\(parsed.count) regions)")
                    return
                }
            }
        }
    }

    // MARK: - Behavior Fetch/Apply

    func fetchBehaviorSettings() async {
        isLoadingBehavior = true
        behaviorError = false
        var hadTimeout = false

        do {
            let response = try await helper.sendAndWait("get repeat")
            if case .repeatMode(let enabled) = CLIResponse.parse(response, forQuery: "get repeat") {
                self.repeaterEnabled = enabled
                self.originalRepeaterEnabled = enabled
            }
        } catch {
            if case RemoteNodeError.timeout = error { hadTimeout = true }
            logger.warning("Failed to get repeat mode: \(error)")
        }

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
        let validation = NodeSettingsViewModel.validateBehaviorFields(
            advertInterval: advertIntervalMinutes,
            floodInterval: floodAdvertIntervalHours,
            floodMaxHops: floodMaxHops
        )
        advertIntervalError = validation.advertInterval
        floodAdvertIntervalError = validation.floodInterval
        floodMaxHopsError = validation.floodMaxHops

        if validation.hasErrors { return }

        helper.isApplying = true
        helper.errorMessage = nil

        do {
            var allSucceeded = true

            if let repeaterEnabled, repeaterEnabled != originalRepeaterEnabled {
                let response = try await helper.sendAndWait("set repeat \(repeaterEnabled ? "on" : "off")")
                if case .ok = CLIResponse.parse(response) {
                    originalRepeaterEnabled = repeaterEnabled
                } else {
                    allSucceeded = false
                }
            }

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
                    setApplying: { helper.isApplying = $0 },
                    setSuccess: { behaviorApplySuccess = $0 }
                )
                return
            } else {
                helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.someSettingsFailedToApply
            }
        } catch {
            helper.errorMessage = error.localizedDescription
        }

        helper.isApplying = false
    }

    // MARK: - Region Methods

    func fetchRegions() async {
        isLoadingRegions = true
        regionsError = false

        do {
            let treeResponse = try await helper.sendAndWait("region", timeout: .seconds(10), rawMatching: true)
            let parsed = Self.parseRegionTree(treeResponse)
            self.regions = parsed
            self.originalRegions = parsed
        } catch {
            if case RemoteNodeError.timeout = error {
                regionsError = true
            }
            logger.warning("Failed to fetch regions: \(error)")
        }

        isLoadingRegions = false
    }

    static func parseRegionTree(_ response: String) -> [RepeaterRegionEntry] {
        var entries: [RepeaterRegionEntry] = []
        let lines = response.split(separator: "\n", omittingEmptySubsequences: true)

        for line in lines {
            var text = String(line)
            text = String(text.drop(while: { $0 == " " }))
            guard !text.isEmpty else { continue }

            let floodAllowed: Bool
            if text.hasSuffix(" F") {
                floodAllowed = true
                text = String(text.dropLast(2))
            } else {
                floodAllowed = false
            }

            let isHome: Bool
            if text.hasSuffix("^") {
                isHome = true
                text = String(text.dropLast(1))
            } else {
                isHome = false
            }

            guard !text.isEmpty else { continue }

            entries.append(RepeaterRegionEntry(
                name: text,
                floodAllowed: floodAllowed,
                isHome: isHome
            ))
        }

        return entries
    }

    func toggleRegionFlood(name: String) async {
        guard let index = regions.firstIndex(where: { $0.name == name }) else { return }
        let currentlyAllowed = regions[index].floodAllowed
        let command = currentlyAllowed ? "region denyf \(name)" : "region allowf \(name)"

        helper.isApplying = true
        helper.errorMessage = nil

        do {
            let response = try await helper.sendAndWait(command)
            if case .ok = CLIResponse.parse(response) {
                regions[index].floodAllowed = !currentlyAllowed
                hasUnsavedRegionChanges = true
            } else {
                helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.unknownRegion
            }
        } catch {
            helper.errorMessage = error.localizedDescription
        }

        helper.isApplying = false
    }

    func setHomeRegion(name: String) async {
        let command = "region home \(name)"

        helper.isApplying = true
        helper.errorMessage = nil

        do {
            let response = try await helper.sendAndWait(command, rawMatching: true)
            if response.contains("home is now") {
                for i in regions.indices {
                    regions[i].isHome = (regions[i].name == name)
                }
                hasUnsavedRegionChanges = true
            } else {
                helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.unknownRegion
            }
        } catch {
            helper.errorMessage = error.localizedDescription
        }

        helper.isApplying = false
    }

    func addRegion(name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if let validationError = RegionNameValidator.validate(trimmed, existingRegions: regions.map(\.name)) {
            switch validationError {
            case .empty: return
            case .invalidCharacters, .tooLong, .duplicate:
                helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.addFailed
            }
            return
        }

        helper.isApplying = true
        helper.errorMessage = nil

        do {
            let response = try await helper.sendAndWait("region put \(trimmed)")
            if case .ok = CLIResponse.parse(response) {
                regions.append(RepeaterRegionEntry(
                    name: trimmed,
                    floodAllowed: false,
                    isHome: false
                ))
                hasUnsavedRegionChanges = true
                newRegionName = ""
            } else {
                helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.addFailed
            }
        } catch {
            helper.errorMessage = error.localizedDescription
        }

        helper.isApplying = false
    }

    func removeRegion(name: String) async {
        helper.isApplying = true
        helper.errorMessage = nil

        do {
            let response = try await helper.sendAndWait("region remove \(name)")
            if case .ok = CLIResponse.parse(response) {
                regions.removeAll { $0.name == name }
                hasUnsavedRegionChanges = true
            } else if response.contains("not empty") {
                helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.notEmpty
            } else {
                helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.removeFailed
            }
        } catch {
            helper.errorMessage = error.localizedDescription
        }

        helper.isApplying = false
    }

    func saveRegions() async {
        helper.isApplying = true
        helper.errorMessage = nil

        do {
            let response = try await helper.sendAndWait("region save")
            if case .ok = CLIResponse.parse(response) {
                hasUnsavedRegionChanges = false
                await helper.flashSuccess(
                    setApplying: { helper.isApplying = $0 },
                    setSuccess: { regionsSaveSuccess = $0 }
                )
                return
            } else {
                helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.saveFailed
            }
        } catch {
            helper.errorMessage = error.localizedDescription
        }

        helper.isApplying = false
    }
}

// MARK: - Region Entry

struct RepeaterRegionEntry: Identifiable, Equatable {
    var id: String { name }
    let name: String
    var floodAllowed: Bool
    var isHome: Bool
    var isWildcard: Bool { name == RepeaterSettingsViewModel.wildcardName }
}
