import SwiftUI

/// Hero icon for the Pair onboarding step. Pulses by default; honors
/// `accessibilityReduceMotion` by rendering statically.
struct PulsingAntenna: View {
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  @ScaledMetric(relativeTo: .body) private var heroSize = OnboardingMetrics.heroSize

  var body: some View {
    Image(systemName: "antenna.radiowaves.left.and.right")
      .font(.system(size: heroSize / 2))
      .foregroundStyle(.tint)
      .frame(height: heroSize)
      .symbolEffect(.pulse, isActive: !reduceMotion)
      .accessibilityHidden(true)
  }
}
