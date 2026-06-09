import SwiftUI

struct WelcomeView: View {
    @Environment(\.appState) private var appState

    var body: some View {
        VStack(spacing: OnboardingMetrics.cardSpacing * 2) {
            Spacer()

            MeshAnimationView()
                .frame(height: OnboardingMetrics.heroSize)
                .padding(.horizontal)

            VStack(spacing: 12) {
                Text(L10n.Onboarding.Welcome.title)
                    .font(.largeTitle)
                    .bold()
                    .accessibilityHeading(.h1)

                Text(L10n.Onboarding.Welcome.subtitle)
                    .font(.title3)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
            }

            Spacer()

            Button {
                appState.onboarding.onboardingPath.append(.permissions)
            } label: {
                Text(L10n.Onboarding.Welcome.getStarted)
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding()
            }
            .liquidGlassProminentButtonStyle()
            .padding(.horizontal)
            .padding(.bottom)
        }
    }
}

#Preview {
    WelcomeView()
        .environment(\.appState, AppState())
}
