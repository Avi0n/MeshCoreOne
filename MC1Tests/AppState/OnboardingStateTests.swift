import Testing
import Foundation
@testable import MC1
@testable import MC1Services

@Suite("Onboarding State Tests")
@MainActor
struct OnboardingStateTests {

    private let defaults: UserDefaults

    init() {
        defaults = UserDefaults(suiteName: "test.\(UUID().uuidString)")!
    }

    // MARK: - completeOnboarding

    @Test("completeOnboarding sets flag to true")
    func completeOnboardingSetsFlag() {
        let onboarding = OnboardingState(defaults: defaults)
        onboarding.hasCompletedOnboarding = false

        onboarding.completeOnboarding()

        #expect(onboarding.hasCompletedOnboarding == true)
    }

    @Test("completeOnboarding persists to UserDefaults")
    func completeOnboardingPersists() {
        let onboarding = OnboardingState(defaults: defaults)
        onboarding.hasCompletedOnboarding = false

        onboarding.completeOnboarding()

        #expect(defaults.bool(forKey: "hasCompletedOnboarding") == true)
    }

    // MARK: - resetOnboarding

    @Test("resetOnboarding clears flag")
    func resetOnboardingClearsFlag() {
        let onboarding = OnboardingState(defaults: defaults)
        onboarding.hasCompletedOnboarding = true

        onboarding.resetOnboarding()

        #expect(onboarding.hasCompletedOnboarding == false)
    }

    @Test("resetOnboarding clears onboarding path")
    func resetOnboardingClearsPath() {
        let onboarding = OnboardingState(defaults: defaults)
        onboarding.onboardingPath = [.welcome, .permissions]

        onboarding.resetOnboarding()

        #expect(onboarding.onboardingPath.isEmpty)
    }

    @Test("resetOnboarding persists false to UserDefaults")
    func resetOnboardingPersists() {
        let onboarding = OnboardingState(defaults: defaults)
        onboarding.hasCompletedOnboarding = true
        onboarding.resetOnboarding()

        #expect(defaults.bool(forKey: "hasCompletedOnboarding") == false)
    }

    // MARK: - onboardingPath

    @Test("onboardingPath starts empty")
    func onboardingPathDefault() {
        let appState = AppState()
        #expect(appState.onboarding.onboardingPath.isEmpty)
    }

    @Test("onboardingPath can be appended to")
    func onboardingPathAppend() {
        let appState = AppState()

        appState.onboarding.onboardingPath.append(.welcome)
        appState.onboarding.onboardingPath.append(.permissions)

        #expect(appState.onboarding.onboardingPath == [.welcome, .permissions])
    }

    // MARK: - donateDeviceMenuTipIfOnValidTab

    @Test("donateDeviceMenuTipIfOnValidTab on Chats tab clears pending")
    func donateOnChatsTab() async {
        let appState = AppState()
        appState.navigation.selectedTab = 0
        appState.navigation.pendingDeviceMenuTipDonation = true

        await appState.donateDeviceMenuTipIfOnValidTab()

        #expect(appState.navigation.pendingDeviceMenuTipDonation == false)
    }

    @Test("donateDeviceMenuTipIfOnValidTab on Contacts tab clears pending")
    func donateOnContactsTab() async {
        let appState = AppState()
        appState.navigation.selectedTab = 1
        appState.navigation.pendingDeviceMenuTipDonation = true

        await appState.donateDeviceMenuTipIfOnValidTab()

        #expect(appState.navigation.pendingDeviceMenuTipDonation == false)
    }

    @Test("donateDeviceMenuTipIfOnValidTab on Map tab clears pending")
    func donateOnMapTab() async {
        let appState = AppState()
        appState.navigation.selectedTab = 2
        appState.navigation.pendingDeviceMenuTipDonation = true

        await appState.donateDeviceMenuTipIfOnValidTab()

        #expect(appState.navigation.pendingDeviceMenuTipDonation == false)
    }

    @Test("donateDeviceMenuTipIfOnValidTab on Settings tab sets pending")
    func donateOnSettingsTab() async {
        let appState = AppState()
        appState.navigation.selectedTab = 3
        appState.navigation.pendingDeviceMenuTipDonation = false

        await appState.donateDeviceMenuTipIfOnValidTab()

        #expect(appState.navigation.pendingDeviceMenuTipDonation == true)
    }

    @Test("donateDeviceMenuTipIfOnValidTab on Tools tab sets pending")
    func donateOnToolsTab() async {
        let appState = AppState()
        appState.navigation.selectedTab = 4
        appState.navigation.pendingDeviceMenuTipDonation = false

        await appState.donateDeviceMenuTipIfOnValidTab()

        #expect(appState.navigation.pendingDeviceMenuTipDonation == true)
    }

    // MARK: - hasCompletedOnboarding didSet

    @Test("hasCompletedOnboarding syncs to UserDefaults on set")
    func hasCompletedOnboardingDidSet() {
        let onboarding = OnboardingState(defaults: defaults)

        onboarding.hasCompletedOnboarding = true
        #expect(defaults.bool(forKey: "hasCompletedOnboarding") == true)

        onboarding.hasCompletedOnboarding = false
        #expect(defaults.bool(forKey: "hasCompletedOnboarding") == false)
    }

    // MARK: - suggestedStartingPath

    @Suite("suggestedStartingPath")
    @MainActor
    struct SuggestedStartingPathTests {
        @Test("Returns empty when onboarding is already complete")
        func emptyWhenCompleted() async {
            let testDefaults = UserDefaults(suiteName: "test.\(UUID().uuidString)")!
            let onboarding = OnboardingState(defaults: testDefaults)
            onboarding.hasCompletedOnboarding = true
            let appState = AppState()
            let path = await onboarding.suggestedStartingPath(
                connectionManager: appState.connectionManager,
                locationAuthorizationStatus: .notDetermined,
                regionAlreadySet: false
            )
            #expect(path.isEmpty)
        }

        @Test("Returns empty when no paired device")
        func emptyWithNoPairing() async throws {
            let testDefaults = UserDefaults(suiteName: "test.\(UUID().uuidString)")!
            let onboarding = OnboardingState(defaults: testDefaults)

            // Build the ConnectionManager with a registry-less stub so pairedAccessoriesCount == 0
            // is a guaranteed precondition; a bare AppState() would inherit whatever the host's
            // pairing registry reports. The fresh defaults suite leaves lastConnectedDeviceID nil,
            // so neither half of the resume guard fires and onboarding must not resume.
            let container = try PersistenceStore.createContainer(inMemory: true)
            let connectionManager = ConnectionManager(
                modelContainer: container,
                defaults: testDefaults,
                pairing: StubDevicePairingService()
            )
            #expect(connectionManager.pairedAccessoriesCount == 0)
            #expect(connectionManager.lastConnectedDeviceID == nil)

            let path = await onboarding.suggestedStartingPath(
                connectionManager: connectionManager,
                locationAuthorizationStatus: .notDetermined,
                regionAlreadySet: false
            )
            #expect(path.isEmpty)
        }

        @Test("Resumes when a device was connected even with no system pairing registry (macOS)")
        func resumesViaLastConnectedDeviceWithoutRegistry() async throws {
            let testDefaults = UserDefaults(suiteName: "test.\(UUID().uuidString)")!
            let onboarding = OnboardingState(defaults: testDefaults)

            // Build a ConnectionManager with a registry-less stub so pairedAccessoriesCount == 0 is
            // a guaranteed precondition; a bare AppState() would inherit whatever the host's pairing
            // registry reports. A real connect happened, so lastConnectedDeviceID is set — onboarding
            // must still resume via that signal alone.
            let container = try PersistenceStore.createContainer(inMemory: true)
            let connectionManager = ConnectionManager(
                modelContainer: container,
                defaults: testDefaults,
                pairing: StubDevicePairingService()
            )
            connectionManager.testLastConnectedDeviceID = UUID()
            #expect(connectionManager.pairedAccessoriesCount == 0)

            let path = await onboarding.suggestedStartingPath(
                connectionManager: connectionManager,
                locationAuthorizationStatus: .notDetermined,
                regionAlreadySet: false
            )
            // Passed the resume guard; halts at the permissions step (location is .notDetermined).
            #expect(path == [.permissions])
        }
    }
}

/// Registry-less `DevicePairingService` stub mirroring macOS "Designed for iPad": its
/// `registeredDeviceCount` is 0, so a `ConnectionManager` built with it reports
/// `pairedAccessoriesCount == 0` regardless of the test host's real pairing registry.
@MainActor
private final class StubDevicePairingService: DevicePairingService {
    var delegate: (any DevicePairingDelegate)?
    var isSessionActive: Bool { false }
    var registeredDeviceCount: Int { 0 }
    var hasSystemPairingRegistry: Bool { false }
    var supportsSystemRename: Bool { false }

    func activate() async throws {}
    func discoverDevice() async throws -> UUID { throw CancellationError() }
    func isDeviceConnectable(_ id: UUID) -> Bool { true }
    func registeredDeviceInfos() -> [(id: UUID, name: String)] { [] }
    func removeDevice(_ id: UUID) async throws {}
    func renameDevice(_ id: UUID) async throws {}
    func clearStaleRegistrations() async {}
}
