import SwiftUI

/// Explains that ASK-authorized radios must be forgotten before they can be added.
struct SystemPairingSetupSheet: View {
  let prompt: SystemPairingSetupPrompt
  let onForget: () -> Void
  let onCancel: () -> Void

  @Environment(\.dynamicTypeSize) private var dynamicTypeSize

  private var isPlural: Bool {
    prompt.accessories.count > 1
  }

  var body: some View {
    NavigationStack {
      VStack(alignment: .leading, spacing: OnboardingMetrics.largeSpacing) {
        Text(title)
          .font(.title2)
          .bold()
          .frame(maxWidth: .infinity)
          .multilineTextAlignment(.center)
          .accessibilityHeading(.h1)
          .padding(.top, OnboardingMetrics.sheetTopPadding)

        Text(isPlural
          ? L10n.Settings.DeviceSelection.setupMessagePlural
          : L10n.Settings.DeviceSelection.setupMessage)
          .font(.body)
          .foregroundStyle(.secondary)
          .multilineTextAlignment(.center)
          .frame(maxWidth: .infinity)

        if isPlural {
          VStack(alignment: .leading, spacing: 8) {
            ForEach(prompt.accessories) { accessory in
              Text(accessory.name)
                .font(.body)
            }
          }
          .frame(maxWidth: .infinity, alignment: .leading)
          .padding(.horizontal)
        }

        Text(L10n.Settings.DeviceSelection.setupConversations)
          .font(.body)
          .foregroundStyle(.secondary)
          .multilineTextAlignment(.center)
          .frame(maxWidth: .infinity)

        Spacer()

        Button(forgetTitle, role: .destructive, action: onForget)
          .font(.headline)
          .frame(maxWidth: .infinity, minHeight: OnboardingMetrics.minHitTarget)
          .padding(.horizontal)
          .padding(.bottom, OnboardingMetrics.largeSpacing)
      }
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.Localizable.Common.cancel, action: onCancel)
        }
      }
    }
    .presentationDetents(dynamicTypeSize.isAccessibilitySize ? [.large] : [.medium])
    .presentationDragIndicator(.visible)
  }

  private var title: String {
    if prompt.accessories.count == 1, let name = prompt.accessories.first?.name {
      return L10n.Settings.DeviceSelection.setupTitle(name)
    }
    return L10n.Settings.DeviceSelection.setupTitleGeneric
  }

  private var forgetTitle: String {
    isPlural
      ? L10n.Settings.DeviceSelection.forgetTheseDevices
      : L10n.Settings.DeviceSelection.forgetThisDevice
  }
}

#Preview {
  Color.clear.sheet(isPresented: .constant(true)) {
    SystemPairingSetupSheet(
      prompt: SystemPairingSetupPrompt(
        accessories: [SystemPairedAccessory(id: UUID(), name: "Radio")]
      ),
      onForget: {},
      onCancel: {}
    )
  }
}
