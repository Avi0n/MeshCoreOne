import CoreLocation
import SwiftUI

struct PermissionsView: View {
  @Environment(\.appState) private var appState
  @Environment(\.scenePhase) private var scenePhase
  @State private var coordinator = PermissionsCoordinator()
  @State private var permissionGrantTrigger = false

  var body: some View {
    VStack(spacing: OnboardingMetrics.sheetTopPadding) {
      VStack(spacing: OnboardingMetrics.mediumSpacing) {
        Image(systemName: "checkmark.shield.fill")
          .font(.system(size: OnboardingMetrics.iconSize))
          .foregroundStyle(.tint)

        Text(L10n.Onboarding.Permissions.title)
          .font(.largeTitle)
          .bold()
          .accessibilityHeading(.h1)

        Text(L10n.Onboarding.Permissions.subtitle)
          .font(.body)
          .foregroundStyle(.secondary)
          .multilineTextAlignment(.center)
          .padding(.horizontal)
      }
      .padding(.top, OnboardingMetrics.headerTopPadding)

      GeometryReader { proxy in
        ScrollView {
          VStack(spacing: 0) {
            Spacer(minLength: 0)

            LiquidGlassContainer(spacing: OnboardingMetrics.contentPadding) {
              VStack(spacing: OnboardingMetrics.cardSpacing) {
                PermissionCard(
                  icon: "bell.fill",
                  title: L10n.Onboarding.Permissions.Notifications.title,
                  description: L10n.Onboarding.Permissions.Notifications.description,
                  isGranted: coordinator.notificationAuthorization == .authorized,
                  isDenied: coordinator.notificationAuthorization == .denied,
                  action: coordinator.requestNotifications
                )

                PermissionCard(
                  icon: "location.fill",
                  title: L10n.Onboarding.Permissions.Location.title,
                  description: L10n.Onboarding.Permissions.Location.description,
                  isGranted: coordinator.locationAuthorization == .authorizedWhenInUse
                    || coordinator.locationAuthorization == .authorizedAlways,
                  isDenied: coordinator.locationAuthorization == .denied,
                  action: coordinator.requestLocation
                )
              }
            }
            .padding(.horizontal)

            Spacer(minLength: 0)
          }
          .frame(minHeight: proxy.size.height)
        }
        .scrollBounceBehavior(.basedOnSize)
      }

      Button {
        appState.onboarding.onboardingPath.append(.pair)
      } label: {
        Text(L10n.Onboarding.Permissions.continue)
          .font(.headline)
          .frame(maxWidth: .infinity)
          .padding()
      }
      .liquidGlassProminentButtonStyle()
      .padding(.horizontal)
      .padding(.bottom)
    }
    .sensoryFeedback(.success, trigger: permissionGrantTrigger)
    .onChange(of: coordinator.locationAuthorization) { _, new in
      if new == .authorizedWhenInUse || new == .authorizedAlways {
        permissionGrantTrigger.toggle()
      }
    }
    .onChange(of: coordinator.notificationAuthorization) { _, new in
      if new == .authorized {
        permissionGrantTrigger.toggle()
      }
    }
    .onChange(of: scenePhase) {
      if scenePhase == .active {
        coordinator.checkPermissions()
      }
    }
  }
}

#Preview {
  PermissionsView()
    .environment(\.appState, AppState())
}
