import SwiftUI
import MC1Services

/// Onboarding step 4. Resolves region from location when authorized; falls
/// silently to the manual picker on any failure (denied, timeout, no network).
struct RegionStepView: View {
    @Environment(\.appState) private var appState
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var resolved: RegionSelection?
    @State private var isResolving = true
    @State private var showManualPicker = false
    @State private var manualSelection: RegionSelection?
    @State private var commitTrigger = false

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
        .task(id: locationGranted) {
            guard locationGranted, !showManualPicker else {
                isResolving = false
                return
            }
            isResolving = true
            resolved = await appState.regionResolver.resolve()
            isResolving = false
            if resolved == nil {
                showManualPicker = true
            }
        }
    }

    private var resolvingState: some View {
        VStack(spacing: OnboardingMetrics.cardSpacing) {
            Spacer()
            if !reduceMotion {
                ProgressView().controlSize(.large)
            }
            Text(L10n.Onboarding.Region.resolving)
                .font(.body)
                .foregroundStyle(.secondary)
            Spacer()
        }
    }

    private func detectedState(region: RegionSelection) -> some View {
        VStack(spacing: OnboardingMetrics.cardSpacing) {
            VStack(spacing: 8) {
                Text(L10n.Onboarding.Region.title)
                    .font(.largeTitle)
                    .bold()
                    .accessibilityHeading(.h1)
                Text(L10n.Onboarding.Region.Subtitle.detected)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.top, 40)

            Spacer()

            VStack(spacing: 8) {
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

            Button(L10n.Onboarding.Region.chooseAnother) {
                showManualPicker = true
            }
            .font(.subheadline)
            .foregroundStyle(.secondary)
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
            VStack(spacing: 8) {
                Text(L10n.Onboarding.Region.title)
                    .font(.largeTitle)
                    .bold()
                    .accessibilityHeading(.h1)
                Text(L10n.Onboarding.Region.Subtitle.manual)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.top, 40)

            RegionPickerView(
                selection: $manualSelection,
                onCommit: {
                    if let manualSelection {
                        appState.regionSelection = manualSelection
                        commitTrigger.toggle()
                        appState.onboarding.onboardingPath.append(.preset)
                    }
                }
            )

            if locationGranted {
                Button(L10n.Onboarding.Region.useMyLocation) {
                    showManualPicker = false
                }
                .font(.subheadline)
                .foregroundStyle(.tint)
                .frame(minHeight: OnboardingMetrics.minHitTarget)
            }
        }
    }
}

#Preview {
    RegionStepView()
        .environment(\.appState, AppState())
}
