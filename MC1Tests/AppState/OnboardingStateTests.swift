import Testing
import Foundation
@testable import MC1

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
        func emptyWithNoPairing() async {
            let testDefaults = UserDefaults(suiteName: "test.\(UUID().uuidString)")!
            let onboarding = OnboardingState(defaults: testDefaults)
            let appState = AppState()
            let path = await onboarding.suggestedStartingPath(
                connectionManager: appState.connectionManager,
                locationAuthorizationStatus: .notDetermined,
                regionAlreadySet: false
            )
            #expect(path.isEmpty)
        }
    }
}
