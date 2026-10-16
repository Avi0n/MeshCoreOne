import SwiftUI

/// Hero icon for the Pair onboarding step. Pulses by default; honors
/// `accessibilityReduceMotion` by rendering statically.
struct PulsingAntenna: View {
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  var body: some View {
    Image(systemName: "antenna.radiowaves.left.and.right")
      .font(.system(size: OnboardingMetrics.heroSize / 2))
      .foregroundStyle(.tint)
      .frame(height: OnboardingMetrics.heroSize)
      .symbolEffect(.pulse, isActive: !reduceMotion)
      .accessibilityHidden(true)
  }
}
