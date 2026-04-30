import SwiftUI
import MC1Services

/// Onboarding step 5. Lands on the region's recommended preset when one
/// exists; falls back to locale-sorted alternatives when `regionSelection` is nil.
struct PresetStepView: View {
    @Environment(\.appState) private var appState

    @State private var selectedID: String?
    @State private var isApplying = false
    @State private var errorMessage: String?
    @State private var retryAlert = RetryAlertState()
    @State private var commitTrigger = false
    @State private var forceShowPicker = false

    private var region: RegionSelection? { appState.regionSelection }

    private var recommended: RadioPreset? {
        guard let region else { return nil }
        return RadioPresets.recommended(for: region)
    }

    private var alternatives: [RadioPreset] {
        guard let region else { return RadioPresets.presetsForLocale() }
        let presets = RadioPresets.presets(for: region)
        guard !presets.isEmpty else { return RadioPresets.presetsForLocale() }
        return presets.sorted { $0.name < $1.name }
    }

    private var currentDevicePreset: RadioPreset? {
        guard let device = appState.connectedDevice else { return nil }
        return RadioPresets.matchingPreset(
            frequencyKHz: device.frequency,
            bandwidthKHz: device.bandwidth,
            spreadingFactor: device.spreadingFactor,
            codingRate: device.codingRate
        )
    }

    private var alreadyConfigured: Bool {
        guard !forceShowPicker, let recommended, let currentDevicePreset else { return false }
        return recommended.id == currentDevicePreset.id
    }

    private var canApply: Bool {
        appState.services?.settingsService != nil
    }

    var body: some View {
        Group {
            if alreadyConfigured, let recommended {
                alreadyConfiguredState(preset: recommended)
            } else {
                pickerState
            }
        }
        .sensoryFeedback(.success, trigger: commitTrigger)
        .errorAlert($errorMessage)
        .retryAlert(retryAlert)
        .onAppear { selectedID = recommended?.id ?? alternatives.first?.id }
    }

    private func alreadyConfiguredState(preset: RadioPreset) -> some View {
        VStack(spacing: OnboardingMetrics.cardSpacing) {
            Spacer()
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: OnboardingMetrics.iconSize))
                .foregroundStyle(.tint)
            Text(L10n.Onboarding.Preset.AlreadyConfigured.title)
                .font(.largeTitle)
                .bold()
                .accessibilityHeading(.h1)
            Text(L10n.Onboarding.Preset.AlreadyConfigured.subtitle(
                preset.name,
                region.map { RegionalAreas.displayName(for: $0) } ?? ""
            ))
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)

            Spacer()

            VStack(spacing: OnboardingMetrics.mediumSpacing) {
                Button {
                    commitTrigger.toggle()
                    appState.completeOnboarding()
                } label: {
                    Text(L10n.Onboarding.Preset.AlreadyConfigured.done)
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding()
                }
                .liquidGlassProminentButtonStyle()

                Button(L10n.Onboarding.Preset.AlreadyConfigured.choose) {
                    forceShowPicker = true
                }
                .buttonStyle(.bordered)
                .tint(.accentColor)
                .frame(minHeight: OnboardingMetrics.minHitTarget)
            }
            .padding(.horizontal)
            .padding(.bottom)
        }
    }

    private var pickerState: some View {
        VStack(spacing: OnboardingMetrics.cardSpacing) {
            VStack(spacing: OnboardingMetrics.titleStackSpacing) {
                Text(L10n.Onboarding.Preset.title)
                    .font(.largeTitle)
                    .bold()
                    .accessibilityHeading(.h1)
                if let region {
                    Text(L10n.Onboarding.Preset.Subtitle.recommended(RegionalAreas.displayName(for: region)))
                        .font(.body)
                        .foregroundStyle(.secondary)
                } else {
                    Text(L10n.Onboarding.Preset.Subtitle.locale)
                        .font(.body)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.top, OnboardingMetrics.headerTopPadding)

            ScrollView {
                VStack(spacing: OnboardingMetrics.mediumSpacing) {
                    if let recommended {
                        prominentCard(recommended)
                    }
                    ForEach(alternatives.filter { $0.id != recommended?.id }) { preset in
                        rowCard(preset)
                    }
                }
                .padding(.horizontal)
            }

            Spacer()

            Button {
                if let id = selectedID {
                    apply(id: id)
                }
            } label: {
                Text(applyCTAText)
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding()
            }
            .liquidGlassProminentButtonStyle()
            .disabled(isApplying || selectedID == nil || !canApply)
            .padding(.horizontal)
            .padding(.bottom)
        }
    }

    private var applyCTAText: String {
        guard let preset = alternatives.first(where: { $0.id == selectedID }) ?? recommended else {
            return L10n.Onboarding.Preset.continue
        }
        return L10n.Onboarding.Preset.use(preset.name)
    }

    private func prominentCard(_ preset: RadioPreset) -> some View {
        Button {
            selectedID = preset.id
        } label: {
            VStack(alignment: .leading, spacing: OnboardingMetrics.titleStackSpacing) {
                HStack {
                    Text(L10n.Onboarding.Preset.recommendedTag)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.tint)
                    Spacer()
                    if selectedID == preset.id {
                        Image(systemName: "checkmark")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.tint)
                    }
                }
                Text(preset.name)
                    .font(.title3.weight(.semibold))
                Text("\(preset.frequencyMHz, format: .number.precision(.fractionLength(3))) MHz · SF\(preset.spreadingFactor)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .padding(OnboardingMetrics.contentPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .liquidGlass(in: .rect(cornerRadius: OnboardingMetrics.cardCornerRadius))
            .overlay(
                RoundedRectangle(cornerRadius: OnboardingMetrics.cardCornerRadius)
                    .stroke(selectedID == preset.id ? Color.accentColor : .clear, lineWidth: 2)
            )
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityHint(L10n.Onboarding.Preset.Row.accessibilityHint)
    }

    private func rowCard(_ preset: RadioPreset) -> some View {
        Button {
            selectedID = preset.id
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: OnboardingMetrics.compactSpacing) {
                    Text(preset.name)
                        .font(.body)
                    Text("\(preset.frequencyMHz, format: .number.precision(.fractionLength(3))) MHz")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if selectedID == preset.id {
                    Image(systemName: "checkmark")
                        .foregroundStyle(.tint)
                }
            }
            .padding()
            .frame(minHeight: OnboardingMetrics.minHitTarget)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityHint(L10n.Onboarding.Preset.Row.accessibilityHint)
    }

    private func apply(id: String) {
        guard let preset = alternatives.first(where: { $0.id == id }) ?? recommended else { return }
        guard let settingsService = appState.services?.settingsService else {
            // Defensive: CTA is disabled when services is nil, but if reconnect ends mid-tap
            // we surface the error rather than swallowing it silently.
            errorMessage = L10n.Onboarding.Preset.Error.notConnected
            return
        }
        isApplying = true
        Task {
            do {
                _ = try await settingsService.applyRadioPresetVerified(preset)
                retryAlert.reset()
                commitTrigger.toggle()
                appState.completeOnboarding()
            } catch let error as SettingsServiceError where error.isRetryable {
                retryAlert.show(
                    message: error.errorDescription ?? L10n.Settings.Alert.Retry.fallbackMessage,
                    onRetry: { apply(id: id) },
                    onMaxRetriesExceeded: {
                        errorMessage = L10n.Settings.Alert.Retry.fallbackMessage
                    }
                )
            } catch {
                errorMessage = error.localizedDescription
            }
            isApplying = false
        }
    }
}

#Preview {
    PresetStepView()
        .environment(\.appState, AppState())
}
