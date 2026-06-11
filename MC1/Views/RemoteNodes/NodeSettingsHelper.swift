import SwiftUI
import MC1Services
import OSLog

private let logger = Logger(subsystem: "com.mc1", category: "NodeSettingsHelper")

/// Shared logic for repeater and room settings view models.
/// Owns CLI transport, device info, radio, identity, contact info,
/// security, and device action methods.
@Observable
@MainActor
final class NodeSettingsHelper {

    // MARK: - Session

    var session: RemoteNodeSessionDTO?

    // MARK: - Device Info

    var firmwareVersion: String?
    private var deviceTimeUTC: String?
    var isLoadingDeviceInfo = false
    var deviceInfoError = false
    var deviceInfoLoaded: Bool { deviceTimeUTC != nil }

    var deviceTime: String? {
        guard let utcString = deviceTimeUTC else { return nil }
        return Self.convertUTCToLocal(utcString)
    }

    // swiftlint:disable:next force_try
    private static let utcDateRegex = try! Regex(#"(\d{1,2}:\d{2}) - (\d{1,2}/\d{1,2}/\d{4}) UTC"#)

    private static let utcInputFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm d/M/yyyy"
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter
    }()

    static func convertUTCToLocal(_ utcString: String) -> String {
        guard let match = utcString.firstMatch(of: utcDateRegex),
              match.count >= 3 else {
            return utcString
        }

        let timeStr = String(match[1].substring ?? "")
        let dateStr = String(match[2].substring ?? "")

        guard let date = utcInputFormatter.date(from: "\(timeStr) \(dateStr)") else {
            return utcString
        }

        let timeString = date.formatted(date: .omitted, time: .shortened)
        let dateString = date.formatted(.dateTime.year(.twoDigits).month(.twoDigits).day(.twoDigits))
        return "\(timeString) - \(dateString)"
    }

    // MARK: - Identity

    var name: String?
    var latitude: Double?
    var longitude: Double?
    private(set) var originalName: String?
    private(set) var originalLatitude: Double?
    private(set) var originalLongitude: Double?
    var isLoadingIdentity = false
    var identityError = false
    var identityLoaded: Bool { originalLatitude != nil || originalLongitude != nil }

    var identitySettingsModified: Bool {
        (name != nil && name != originalName) ||
        (latitude != nil && latitude != originalLatitude) ||
        (longitude != nil && longitude != originalLongitude)
    }

    // MARK: - Radio

    var frequency: Double?
    var bandwidth: Double?
    var spreadingFactor: Int?
    var codingRate: Int?
    var txPower: Int?
    var isLoadingRadio = false
    var radioError = false
    var radioLoaded: Bool { frequency != nil || txPower != nil }
    var radioSettingsModified = false

    // MARK: - Contact Info

    /// Firmware limit on the `set owner.info` value length.
    static let ownerInfoMaxLength = 119

    var ownerInfo: String?
    private(set) var originalOwnerInfo: String?
    var isLoadingContactInfo = false
    var contactInfoError = false
    var contactInfoLoaded: Bool { originalOwnerInfo != nil }

    /// Gated on `contactInfoLoaded` so the text field's empty pre-fetch value can't
    /// enable Apply and wipe the node's owner info before the current value arrives.
    var contactInfoSettingsModified: Bool {
        contactInfoLoaded && ownerInfo != originalOwnerInfo
    }

    var ownerInfoCharCount: Int {
        (ownerInfo ?? "").count
    }

    var isOwnerInfoTooLong: Bool {
        ownerInfoCharCount > Self.ownerInfoMaxLength
    }

    // MARK: - Security

    var newPassword: String = ""
    var confirmPassword: String = ""

    // MARK: - Expansion State

    var isDeviceInfoExpanded = false
    var isRadioExpanded = false
    var isIdentityExpanded = false
    var isContactInfoExpanded = false
    var isSecurityExpanded = false

    // MARK: - Global State

    var isApplying = false
    var isRebooting = false
    var errorMessage: String?
    var successMessage: String?
    var showSuccessAlert = false
    var identityApplySuccess = false
    var contactInfoApplySuccess = false
    var changePasswordSuccess = false
    var isSendingAdvert = false

    // MARK: - Service Closures

    private var sendCommandClosure: ((UUID, String, Duration) async throws -> String)?
    private var sendRawCommandClosure: ((UUID, String, Duration) async throws -> String)?

    /// Called when firmware version or node info needs pre-fetching.
    /// Repeater sets this to binary requestOwnerInfo; Room sets this to CLI `ver`.
    var onPreFetchNodeInfo: (() async -> Void)?

    // MARK: - Configuration

    func configure(
        session: RemoteNodeSessionDTO,
        sendCommand: @escaping (UUID, String, Duration) async throws -> String,
        sendRawCommand: @escaping (UUID, String, Duration) async throws -> String
    ) {
        self.session = session
        self.sendCommandClosure = sendCommand
        self.sendRawCommandClosure = sendRawCommand
    }

    /// Set name and owner info from an external source (e.g., binary protocol pre-fetch)
    func setNodeInfo(firmwareVersion: String?, name: String?, ownerInfo: String?) {
        if let firmwareVersion { self.firmwareVersion = firmwareVersion }
        if let name {
            self.name = name
            self.originalName = name
        }
        if let ownerInfo {
            self.ownerInfo = ownerInfo
            self.originalOwnerInfo = ownerInfo
        }
    }

    func cleanup() {
        sendCommandClosure = nil
        sendRawCommandClosure = nil
        onPreFetchNodeInfo = nil
    }

    // MARK: - CLI Transport

    func sendAndWait(
        _ command: String,
        timeout: Duration = .seconds(5),
        rawMatching: Bool = false
    ) async throws -> String {
        guard let session, let sendCmd = rawMatching ? sendRawCommandClosure : sendCommandClosure else {
            throw NodeSettingsError.noService
        }

        let response = try await sendCmd(session.id, command, timeout)
        logger.debug("Command '\(command)' response: \(response.prefix(50))")
        return response
    }

    // MARK: - Fetch Methods

    func fetchDeviceInfo() async {
        isLoadingDeviceInfo = true
        deviceInfoError = false

        if firmwareVersion == nil {
            await onPreFetchNodeInfo?()
        }

        if firmwareVersion == nil {
            do {
                let response = try await sendAndWait("ver")
                if case .version(let version) = CLIResponse.parse(response, forQuery: "ver") {
                    self.firmwareVersion = version
                }
            } catch {
                if case RemoteNodeError.timeout = error {
                    deviceInfoError = true
                }
                logger.warning("Failed to get firmware version: \(error)")
            }
        }

        do {
            let response = try await sendAndWait("clock")
            if case .deviceTime(let time) = CLIResponse.parse(response, forQuery: "clock") {
                self.deviceTimeUTC = time
            }
        } catch {
            if case RemoteNodeError.timeout = error {
                deviceInfoError = true
            }
            logger.warning("Failed to get device time: \(error)")
        }

        isLoadingDeviceInfo = false
    }

    func fetchIdentity() async {
        isLoadingIdentity = true
        identityError = false
        var hadTimeout = false

        if originalName == nil {
            await onPreFetchNodeInfo?()
        }

        if originalName == nil {
            do {
                let response = try await sendAndWait("get name")
                if case .name(let n) = CLIResponse.parse(response, forQuery: "get name") {
                    self.name = n
                    self.originalName = n
                }
            } catch {
                if case RemoteNodeError.timeout = error { hadTimeout = true }
                logger.warning("Failed to get name: \(error)")
            }
        }

        do {
            let response = try await sendAndWait("get lat")
            if case .latitude(let lat) = CLIResponse.parse(response, forQuery: "get lat") {
                self.latitude = lat
                self.originalLatitude = lat
            }
        } catch {
            if case RemoteNodeError.timeout = error { hadTimeout = true }
            logger.warning("Failed to get latitude: \(error)")
        }

        do {
            let response = try await sendAndWait("get lon")
            if case .longitude(let lon) = CLIResponse.parse(response, forQuery: "get lon") {
                self.longitude = lon
                self.originalLongitude = lon
            }
        } catch {
            if case RemoteNodeError.timeout = error { hadTimeout = true }
            logger.warning("Failed to get longitude: \(error)")
        }

        if hadTimeout {
            identityError = true
        }

        isLoadingIdentity = false
    }

    func fetchRadioSettings() async {
        isLoadingRadio = true
        radioError = false
        var hadTimeout = false

        do {
            let response = try await sendAndWait("get tx")
            if case .txPower(let power) = CLIResponse.parse(response, forQuery: "get tx") {
                self.txPower = power
            }
        } catch {
            if case RemoteNodeError.timeout = error { hadTimeout = true }
            logger.warning("Failed to get TX power: \(error)")
        }

        do {
            let response = try await sendAndWait("get radio")
            if case .radio(let freq, let bw, let sf, let cr) = CLIResponse.parse(response, forQuery: "get radio") {
                self.frequency = freq
                self.bandwidth = bw
                self.spreadingFactor = sf
                self.codingRate = cr
            }
        } catch {
            if case RemoteNodeError.timeout = error { hadTimeout = true }
            logger.warning("Failed to get radio settings: \(error)")
        }

        if hadTimeout {
            radioError = true
        }

        isLoadingRadio = false
    }

    func fetchContactInfo() async {
        if originalOwnerInfo == nil {
            await onPreFetchNodeInfo?()
        }
        if originalOwnerInfo != nil { return }

        isLoadingContactInfo = true
        contactInfoError = false

        do {
            let response = try await sendAndWait("get owner.info")
            if case .ownerInfo(let info) = CLIResponse.parse(response, forQuery: "get owner.info") {
                let displayText = info.replacing("|", with: "\n")
                self.ownerInfo = displayText
                self.originalOwnerInfo = displayText
            }
        } catch {
            if case RemoteNodeError.timeout = error {
                contactInfoError = true
            }
            logger.warning("Failed to get owner info: \(error)")
        }

        isLoadingContactInfo = false
    }

    // MARK: - Success Flash

    /// How long an Apply button shows its success state before returning to idle.
    static let successFlashDuration: Duration = .seconds(1.5)

    /// Drop the section's applying flag and flash its success indicator for
    /// `successFlashDuration`. The closures target the section's own state, which
    /// may live on this helper or on the owning view model.
    func flashSuccess(setApplying: (Bool) -> Void, setSuccess: (Bool) -> Void) async {
        withAnimation {
            setApplying(false)
            setSuccess(true)
        }
        try? await Task.sleep(for: Self.successFlashDuration)
        withAnimation { setSuccess(false) }
    }

    // MARK: - Apply Methods

    func applyRadioSettings() async {
        guard let frequency, let bandwidth, let spreadingFactor, let codingRate, let txPower else {
            errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.radioNotLoaded
            return
        }

        isApplying = true
        errorMessage = nil

        do {
            var allSucceeded = true

            let radioCommand = "set radio \(frequency),\(bandwidth),\(spreadingFactor),\(codingRate)"
            let radioResponse = try await sendAndWait(radioCommand)
            if case .ok = CLIResponse.parse(radioResponse) {
            } else {
                allSucceeded = false
            }

            let txCommand = "set tx \(txPower)"
            let txResponse = try await sendAndWait(txCommand)
            if case .ok = CLIResponse.parse(txResponse) {
            } else {
                allSucceeded = false
            }

            if allSucceeded {
                radioSettingsModified = false
                successMessage = L10n.RemoteNodes.RemoteNodes.Settings.radioAppliedSuccess
                showSuccessAlert = true
            } else {
                errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.radioApplyFailed
            }
        } catch {
            errorMessage = error.localizedDescription
        }

        isApplying = false
    }

    func applyIdentitySettings() async {
        isApplying = true
        errorMessage = nil

        do {
            var allSucceeded = true

            if let name, name != originalName {
                let response = try await sendAndWait("set name \(name)")
                if case .ok = CLIResponse.parse(response) {
                    originalName = name
                } else {
                    allSucceeded = false
                }
            }

            if let latitude, latitude != originalLatitude {
                let response = try await sendAndWait("set lat \(latitude)")
                if case .ok = CLIResponse.parse(response) {
                    originalLatitude = latitude
                } else {
                    allSucceeded = false
                }
            }

            if let longitude, longitude != originalLongitude {
                let response = try await sendAndWait("set lon \(longitude)")
                if case .ok = CLIResponse.parse(response) {
                    originalLongitude = longitude
                } else {
                    allSucceeded = false
                }
            }

            if allSucceeded {
                await flashSuccess(
                    setApplying: { isApplying = $0 },
                    setSuccess: { identityApplySuccess = $0 }
                )
                return
            } else {
                errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.someSettingsFailedToApply
            }
        } catch {
            errorMessage = error.localizedDescription
        }

        isApplying = false
    }

    func applyContactInfoSettings() async {
        isApplying = true
        errorMessage = nil

        do {
            let pipeText = (ownerInfo ?? "").replacing("\n", with: "|")
            let response = try await sendAndWait("set owner.info \(pipeText)")
            if case .ok = CLIResponse.parse(response) {
                originalOwnerInfo = ownerInfo
                await flashSuccess(
                    setApplying: { isApplying = $0 },
                    setSuccess: { contactInfoApplySuccess = $0 }
                )
                return
            } else {
                errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.someSettingsFailedToApply
            }
        } catch {
            errorMessage = error.localizedDescription
        }

        isApplying = false
    }

    // MARK: - Location Picker

    func setLocationFromPicker(latitude: Double, longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
    }

    // MARK: - Security

    func changePassword() async {
        guard !newPassword.isEmpty else {
            errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.passwordEmpty
            return
        }
        guard newPassword == confirmPassword else {
            errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.passwordMismatch
            return
        }

        isApplying = true
        errorMessage = nil

        do {
            let response = try await sendAndWait("password \(newPassword)", rawMatching: true)
            let parsed = CLIResponse.parse(response)
            // Firmware echoes "password now: {pw}" on success, not "OK"
            let isSuccess: Bool = switch parsed {
            case .ok: true
            case .raw(let text) where text.hasPrefix("password now:"): true
            default: false
            }
            if isSuccess {
                newPassword = ""
                confirmPassword = ""
                await flashSuccess(
                    setApplying: { isApplying = $0 },
                    setSuccess: { changePasswordSuccess = $0 }
                )
                return
            } else {
                errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.passwordChangeFailed
            }
        } catch {
            errorMessage = error.localizedDescription
        }

        isApplying = false
    }

    // MARK: - Device Actions

    func reboot() async {
        guard session != nil else { return }

        isRebooting = true
        errorMessage = nil

        do {
            _ = try await sendAndWait("reboot")
            successMessage = L10n.RemoteNodes.RemoteNodes.Settings.rebootSent
            showSuccessAlert = true
        } catch {
            errorMessage = error.localizedDescription
        }

        isRebooting = false
    }

    func forceAdvert() async {
        isSendingAdvert = true
        defer { isSendingAdvert = false }
        do {
            _ = try await sendAndWait("advert")
            successMessage = L10n.RemoteNodes.RemoteNodes.Settings.advertSent
            showSuccessAlert = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func syncTime() async {
        isApplying = true
        errorMessage = nil

        do {
            let response = try await sendAndWait("clock sync")
            switch CLIResponse.parse(response) {
            case .ok:
                successMessage = L10n.RemoteNodes.RemoteNodes.Settings.timeSynced
                showSuccessAlert = true
            case .error(let message):
                if message.contains("clock cannot go backwards") {
                    errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.clockAheadError
                } else {
                    let cleanMessage = message.replacing("ERR: ", with: "")
                    errorMessage = cleanMessage.isEmpty ? L10n.RemoteNodes.RemoteNodes.Settings.syncTimeFailed : cleanMessage
                }
            default:
                errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.unexpectedResponse(response)
            }
        } catch {
            errorMessage = error.localizedDescription
        }

        isApplying = false
    }

    // MARK: - Shared Validation

    /// Firmware-accepted ranges for the behavior fields; 0 means disabled for
    /// the two intervals and is validated separately.
    static let advertIntervalMinutesRange = 60...240
    static let floodIntervalHoursRange = 3...168
    static let floodMaxHopsRange = 0...64

    struct BehaviorValidationErrors {
        var advertInterval: String?
        var floodInterval: String?
        var floodMaxHops: String?
        var hasErrors: Bool { advertInterval != nil || floodInterval != nil || floodMaxHops != nil }
    }

    static func validateBehaviorFields(
        advertInterval: Int?,
        floodInterval: Int?,
        floodMaxHops: Int?
    ) -> BehaviorValidationErrors {
        var errors = BehaviorValidationErrors()
        if let interval = advertInterval, interval != 0 && !advertIntervalMinutesRange.contains(interval) {
            errors.advertInterval = L10n.RemoteNodes.RemoteNodes.Settings.advertIntervalValidation
        }
        if let interval = floodInterval, interval != 0 && !floodIntervalHoursRange.contains(interval) {
            errors.floodInterval = L10n.RemoteNodes.RemoteNodes.Settings.floodIntervalValidation
        }
        if let hops = floodMaxHops, !floodMaxHopsRange.contains(hops) {
            errors.floodMaxHops = L10n.RemoteNodes.RemoteNodes.Settings.floodMaxValidation
        }
        return errors
    }

    // MARK: - Shared Late Response Parsing

    enum BehaviorLateResponse {
        case advertInterval(Int)
        case floodAdvertInterval(Int)
        case floodMax(Int)
    }

    /// Try to parse a late response as one of the shared behavior fields.
    /// Returns `nil` if the response didn't match any field that's still missing.
    static func parseBehaviorLateResponse(
        _ response: String,
        hasAdvertInterval: Bool,
        hasFloodInterval: Bool,
        hasFloodMaxHops: Bool
    ) -> BehaviorLateResponse? {
        if !hasAdvertInterval {
            if case .advertInterval(let interval) = CLIResponse.parse(response, forQuery: "get advert.interval") {
                return .advertInterval(interval)
            }
        }
        if !hasFloodInterval {
            if case .floodAdvertInterval(let interval) = CLIResponse.parse(response, forQuery: "get flood.advert.interval") {
                return .floodAdvertInterval(interval)
            }
        }
        if !hasFloodMaxHops {
            if case .floodMax(let hops) = CLIResponse.parse(response, forQuery: "get flood.max") {
                return .floodMax(hops)
            }
        }
        return nil
    }

    // MARK: - Late Response Handling

    /// Handle late CLI responses for shared sections.
    /// Returns `true` if the response was consumed.
    func handleCommonLateResponse(_ response: String) -> Bool {
        // Radio settings
        if !isLoadingRadio && radioError {
            if frequency == nil {
                if case .radio(let freq, let bw, let sf, let cr) = CLIResponse.parse(response, forQuery: "get radio") {
                    self.frequency = freq
                    self.bandwidth = bw
                    self.spreadingFactor = sf
                    self.codingRate = cr
                    self.radioError = false
                    logger.info("Late response: received radio settings")
                    return true
                }
            }

            if txPower == nil {
                if case .txPower(let power) = CLIResponse.parse(response, forQuery: "get tx") {
                    self.txPower = power
                    self.radioError = false
                    logger.info("Late response: received TX power")
                    return true
                }
            }
        }

        // Device info
        if !isLoadingDeviceInfo && deviceInfoError {
            if firmwareVersion == nil {
                if case .version(let version) = CLIResponse.parse(response, forQuery: "ver") {
                    self.firmwareVersion = version
                    self.deviceInfoError = false
                    logger.info("Late response: received firmware version")
                    return true
                }
            }

            if deviceTimeUTC == nil {
                if case .deviceTime(let time) = CLIResponse.parse(response, forQuery: "clock") {
                    self.deviceTimeUTC = time
                    self.deviceInfoError = false
                    logger.info("Late response: received device time")
                    return true
                }
            }
        }

        // Identity settings (lat/lon before name to avoid numeric capture)
        if !isLoadingIdentity && identityError {
            if originalLatitude == nil {
                if case .latitude(let lat) = CLIResponse.parse(response, forQuery: "get lat") {
                    self.latitude = lat
                    self.originalLatitude = lat
                    self.identityError = false
                    logger.info("Late response: received latitude")
                    return true
                }
            }

            if originalLongitude == nil {
                if case .longitude(let lon) = CLIResponse.parse(response, forQuery: "get lon") {
                    self.longitude = lon
                    self.originalLongitude = lon
                    self.identityError = false
                    logger.info("Late response: received longitude")
                    return true
                }
            }

            if originalName == nil {
                if case .name(let n) = CLIResponse.parse(response, forQuery: "get name") {
                    self.name = n
                    self.originalName = n
                    self.identityError = false
                    logger.info("Late response: received name")
                    return true
                }
            }
        }

        // Contact info
        if !isLoadingContactInfo && contactInfoError {
            if originalOwnerInfo == nil {
                if case .ownerInfo(let info) = CLIResponse.parse(response, forQuery: "get owner.info") {
                    let displayText = info.replacing("|", with: "\n")
                    self.ownerInfo = displayText
                    self.originalOwnerInfo = displayText
                    self.contactInfoError = false
                    logger.info("Late response: received owner info")
                    return true
                }
            }
        }

        return false
    }
}

// MARK: - Shared Error Type

enum NodeSettingsError: LocalizedError {
    case noService

    var errorDescription: String? {
        switch self {
        case .noService: return L10n.RemoteNodes.RemoteNodes.Settings.noService
        }
    }
}
