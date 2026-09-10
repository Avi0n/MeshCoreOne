import MC1Services
import SwiftUI

/// Onboarding step 5. Lists presets for the selected place; falls back to
/// locale-sorted alternatives when `regionSelection` is nil.
struct PresetStepView: View {
  @Environment(\.appState) private var appState

  @State private var selectedID: String?
  @State private var isApplying = false
  @State private var errorMessage: String?
  @State private var retryAlert = RetryAlertState()
  @State private var commitTrigger = false

  private var region: RegionSelection? {
    appState.regionSelection
  }

  private var recommended: RadioPreset? {
    guard let region else { return nil }
    return RadioPresets.recommended(for: region)
  }

  private var alternatives: [RadioPreset] {
    let base: [RadioPreset] = if let region, !RadioPresets.presets(for: region).isEmpty {
      RadioPresets.presets(for: region)
    } else {
      RadioPresets.presetsForLocale()
    }
    return base
      .filter { RadioPresets.isSelectable($0, in: region) }
      .sorted { $0.name < $1.name }
  }

  private var visiblePresets: [RadioPreset] {
    var result: [RadioPreset] = []
    if let recommended {
      result.append(recommended)
    }
    result.append(contentsOf: alternatives.filter { $0.id != recommended?.id })
    return result
  }

  private var canApply: Bool {
    appState.services?.settingsService != nil
  }

  var body: some View {
    pickerState
      .sensoryFeedback(.success, trigger: commitTrigger)
      .errorAlert($errorMessage)
      .retryAlert(retryAlert)
      .onAppear { selectedID = recommended?.id ?? alternatives.first?.id }
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
        Text(L10n.Settings.Radio.regulationsFooter)
          .font(.body)
          .foregroundStyle(.secondary)
          .multilineTextAlignment(.center)
          .padding(.horizontal)
      }
      .padding(.top, OnboardingMetrics.headerTopPadding)

      ScrollView {
        VStack(spacing: OnboardingMetrics.mediumSpacing) {
          ForEach(visiblePresets) { preset in
            rowCard(preset)
          }
          if visiblePresets.count > 1 {
            Text(
              (try? AttributedString(markdown: L10n.Onboarding.Preset.discordHelp))
                ?? AttributedString(L10n.Onboarding.Preset.discordHelp)
            )
            .font(.footnote)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding(.top, OnboardingMetrics.mediumSpacing)
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

  private func rowCard(_ preset: RadioPreset) -> some View {
    Button {
      selectedID = preset.id
    } label: {
      HStack {
        VStack(alignment: .leading, spacing: OnboardingMetrics.compactSpacing) {
          Text(preset.name)
            .font(.body)
          Text("\(preset.frequencyMHz, format: .number.precision(.fractionLength(3)).locale(.posix)) MHz")
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
      .frame(maxWidth: .infinity, minHeight: OnboardingMetrics.minHitTarget)
      .contentShape(.rect)
    }
    .buttonStyle(.plain)
    .accessibilityElement(children: .combine)
    .accessibilityHint(L10n.Onboarding.Preset.Row.accessibilityHint)
  }

  private func apply(id: String) {
    guard let preset = alternatives.first(where: { $0.id == id }) ?? recommended else { return }
    // Mock device has no radio to configure.
    if appState.connectedDevice?.id == MockDataProvider.simulatorDeviceID {
      commitTrigger.toggle()
      appState.completeOnboarding()
      return
    }
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
          message: error.userFacingMessage,
          onRetry: { apply(id: id) },
          onMaxRetriesExceeded: {
            errorMessage = L10n.Settings.Alert.Retry.fallbackMessage
          }
        )
      } catch {
        errorMessage = error.userFacingMessage
      }
      isApplying = false
    }
  }
}

#Preview {
  PresetStepView()
    .environment(\.appState, AppState())
}
