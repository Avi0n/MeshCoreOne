import SwiftUI

/// Shown when the user taps "I don't have a device yet" on the Pair screen.
/// Confirms exiting onboarding into the empty main app — does NOT unlock demo
/// mode and does NOT route through `.region`/`.preset`.
struct NoDeviceSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appState) private var appState
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var dismissTrigger = false

    var body: some View {
        VStack(spacing: OnboardingMetrics.largeSpacing) {
            Text(L10n.Onboarding.NoDevice.Sheet.title)
                .font(.title2)
                .bold()
                .accessibilityHeading(.h1)
                .padding(.top, OnboardingMetrics.sheetTopPadding)

            Text(L10n.Onboarding.NoDevice.Sheet.body)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)

            Spacer()

            VStack(spacing: OnboardingMetrics.mediumSpacing) {
                Button {
                    dismissTrigger.toggle()
                    appState.completeOnboarding()
                    dismiss()
                } label: {
                    Text(L10n.Onboarding.NoDevice.Sheet.confirm)
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding()
                }
                .liquidGlassProminentButtonStyle()
                .frame(minHeight: OnboardingMetrics.minHitTarget)

                Button(L10n.Onboarding.NoDevice.Sheet.cancel) {
                    dismiss()
                }
                .buttonStyle(.bordered)
                .tint(.secondary)
                .frame(minHeight: OnboardingMetrics.minHitTarget)
            }
            .padding(.horizontal)
            .padding(.bottom, OnboardingMetrics.largeSpacing)
        }
        .sensoryFeedback(.selection, trigger: dismissTrigger)
        .presentationDetents(dynamicTypeSize.isAccessibilitySize ? [.large] : [.medium])
        .presentationDragIndicator(.visible)
    }
}

#Preview {
    Color.clear.sheet(isPresented: .constant(true)) { NoDeviceSheet() }
        .environment(\.appState, AppState())
}
