import SwiftUI
import MC1Services

/// Onboarding step 4. Resolves region from location when authorized; falls
/// silently to the manual picker on any failure (denied, timeout, no network).
struct RegionStepView: View {
    @Environment(\.appState) private var appState

    @State private var resolved: RegionSelection?
    @State private var isResolving = true
    @State private var showManualPicker = false
    @State private var manualSelection: RegionSelection?
    @State private var commitTrigger = false
    @State private var resolveAttempt = 0
    @State private var pendingUserRetry = false
    @State private var errorMessage: String?

    private var locationGranted: Bool {
        appState.locationService.isAuthorized
    }

    var body: some View {
        Group {
            if showManualPicker || !locationGranted {
                manualPickerState
            } else if let resolved {
                detectedState(region: resolved)
            } else if isResolving {
                resolvingState
            } else {
                manualPickerState
            }
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .sensoryFeedback(.success, trigger: commitTrigger)
        .errorAlert($errorMessage)
        .task(id: resolveAttempt) {
            guard locationGranted, !showManualPicker else {
                isResolving = false
                return
            }
            isResolving = true
            resolved = await appState.regionResolver.resolve()
            isResolving = false
            let wasUserRetry = pendingUserRetry
            pendingUserRetry = false
            if resolved == nil {
                if wasUserRetry {
                    errorMessage = L10n.Onboarding.Region.UseMyLocation.failure
                }
                showManualPicker = true
            }
        }
        .onChange(of: locationGranted) { _, _ in
            // Permission flipped (user granted/revoked from Settings); re-fire the resolver.
            resolveAttempt += 1
        }
    }

    private var resolvingState: some View {
        VStack(spacing: OnboardingMetrics.cardSpacing) {
            Spacer()
            ProgressView().controlSize(.large)
            Text(L10n.Onboarding.Region.resolving)
                .font(.body)
                .foregroundStyle(.secondary)
            Spacer()
        }
    }

    private func detectedState(region: RegionSelection) -> some View {
        VStack(spacing: OnboardingMetrics.cardSpacing) {
            VStack(spacing: OnboardingMetrics.titleStackSpacing) {
                Text(L10n.Onboarding.Region.title)
                    .font(.largeTitle)
                    .bold()
                    .accessibilityHeading(.h1)
                Text(L10n.Onboarding.Region.Subtitle.detected)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.top, OnboardingMetrics.headerTopPadding)

            Spacer()

            VStack(spacing: OnboardingMetrics.titleStackSpacing) {
                Text(L10n.Onboarding.Region.Detected.tag)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.tint)

                Text(RegionalAreas.displayName(for: region))
                    .font(.title2.weight(.semibold))

                Text(L10n.Onboarding.Region.Detected.source)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(OnboardingMetrics.contentPadding)
            .frame(maxWidth: .infinity)
            .liquidGlass(in: .rect(cornerRadius: OnboardingMetrics.cardCornerRadius))
            .padding(.horizontal)
            .accessibilityElement(children: .combine)

            Button(L10n.Onboarding.Region.chooseAnother) {
                showManualPicker = true
            }
            .font(.subheadline)
            .buttonStyle(.bordered)
            .tint(.accentColor)
            .frame(minHeight: OnboardingMetrics.minHitTarget)

            Spacer()

            Button {
                appState.regionSelection = region
                commitTrigger.toggle()
                appState.onboarding.onboardingPath.append(.preset)
            } label: {
                Text(L10n.Onboarding.Region.useThisRegion)
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding()
            }
            .liquidGlassProminentButtonStyle()
            .padding(.horizontal)
            .padding(.bottom)
        }
    }

    private var manualPickerState: some View {
        VStack(spacing: OnboardingMetrics.cardSpacing) {
            VStack(spacing: OnboardingMetrics.titleStackSpacing) {
                Text(L10n.Onboarding.Region.title)
                    .font(.largeTitle)
                    .bold()
                    .accessibilityHeading(.h1)
                Text(L10n.Onboarding.Region.Subtitle.manual)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.top, OnboardingMetrics.headerTopPadding)

            RegionPickerView(selection: $manualSelection)

            if locationGranted {
                Button(L10n.Onboarding.Region.useMyLocation) {
                    // Clear any prior resolve so the view shows the spinner while the
                    // re-resolve runs, instead of flashing the previous detected region.
                    resolved = nil
                    showManualPicker = false
                    isResolving = true
                    pendingUserRetry = true
                    resolveAttempt += 1
                }
                .font(.subheadline)
                .foregroundStyle(.tint)
                .frame(minHeight: OnboardingMetrics.minHitTarget)
            }

            Button {
                guard let manualSelection else { return }
                appState.regionSelection = manualSelection
                commitTrigger.toggle()
                appState.onboarding.onboardingPath.append(.preset)
            } label: {
                Text(L10n.Onboarding.Region.continue)
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding()
            }
            .liquidGlassProminentButtonStyle()
            .disabled(manualSelection == nil)
            .padding(.horizontal)
            .padding(.bottom)
        }
    }

}

#Preview {
    RegionStepView()
        .environment(\.appState, AppState())
}
