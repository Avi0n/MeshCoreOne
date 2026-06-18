import SwiftUI

struct RFSettingsSectionView: View {
    @Bindable var viewModel: LineOfSightViewModel
    @Binding var isRFSettingsExpanded: Bool

    var body: some View {
        DisclosureGroup(isExpanded: $isRFSettingsExpanded) {
            VStack(spacing: 12) {
                // Frequency input - extracted to separate view for @FocusState to work in sheet
                FrequencyInputRow(viewModel: viewModel)

                Divider()

                // Refraction k-factor picker
                HStack {
                    Label(L10n.Tools.Tools.LineOfSight.refraction, systemImage: "globe")
                        .foregroundStyle(.secondary)
                    Spacer()
                    Picker("", selection: Binding(
                        get: { viewModel.refractionK },
                        set: { viewModel.refractionK = $0 }
                    )) {
                        Text(L10n.Tools.Tools.LineOfSight.Refraction.none).tag(1.0)
                        Text(L10n.Tools.Tools.LineOfSight.Refraction.standard).tag(4.0 / 3.0)
                        Text(L10n.Tools.Tools.LineOfSight.Refraction.ducting).tag(4.0)
                    }
                    .pickerStyle(.menu)
                }
            }
            .padding(.top, 8)
        } label: {
            Label(L10n.Tools.Tools.LineOfSight.rfSettings, systemImage: "antenna.radiowaves.left.and.right")
                .font(.headline)
        }
        .tint(.primary)
    }
}
