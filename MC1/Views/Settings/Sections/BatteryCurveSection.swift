import SwiftUI
import MC1Services

/// Battery curve configuration section.
/// Pure UI component — caller provides data and save callback.
///
/// The preset picker and a discharge-curve chart sit inline. The caller is
/// responsible for showing the curve editor (e.g. as a sheet); tapping the
/// "Edit curve" row calls `onEditCurve`. Keeping sheet/navigation state in
/// the caller avoids the "invalid reuse after initialization failure" crash
/// that fires when .sheet is attached to any view inside a List cell on iOS.
struct BatteryCurveSection: View {
    @Environment(\.appTheme) private var theme
    let availablePresets: [OCVPreset]
    let headerText: String
    let footerText: String

    @Binding var selectedPreset: OCVPreset
    @Binding var voltageValues: [Int]

    let onSave: (OCVPreset, [Int]) async -> Void
    var isDisabled: Bool = false
    /// Called when the user taps "Edit curve". The caller presents the editor.
    var onEditCurve: (() -> Void)?

    var body: some View {
        Section {
            Picker(L10n.Settings.BatteryCurve.preset, selection: $selectedPreset) {
                ForEach(availablePresets, id: \.self) { preset in
                    Text(preset.displayName).tag(preset)
                }
                if selectedPreset == .custom && !availablePresets.contains(.custom) {
                    Text(L10n.Settings.BatteryCurve.custom).tag(OCVPreset.custom)
                }
            }
            .onChange(of: selectedPreset) { _, newValue in
                if newValue != .custom {
                    voltageValues = newValue.ocvArray
                    Task { await onSave(newValue, newValue.ocvArray) }
                }
            }
            .disabled(isDisabled)

            BatteryCurveChart(ocvArray: voltageValues)

            if let onEditCurve {
                Button {
                    onEditCurve()
                } label: {
                    Label(L10n.Settings.BatteryCurve.editCurve, systemImage: "slider.horizontal.3")
                }
                .disabled(isDisabled)
            }
        } header: {
            if !headerText.isEmpty { Text(headerText) }
        } footer: {
            if !footerText.isEmpty { Text(footerText) }
        }
        .themedRowBackground(theme)
    }

    /// Call when the editor commits new values.
    func handleEditorDone(_ newValues: [Int]) {
        voltageValues = newValues
        if selectedPreset != .custom && newValues == selectedPreset.ocvArray { return }
        selectedPreset = .custom
        Task { await onSave(.custom, newValues) }
    }
}

#Preview {
    @Previewable @State var preset: OCVPreset = .liIon
    @Previewable @State var values: [Int] = OCVPreset.liIon.ocvArray

    NavigationStack {
        List {
            BatteryCurveSection(
                availablePresets: OCVPreset.selectablePresets,
                headerText: "Battery Curve",
                footerText: "Configure the voltage-to-percentage curve.",
                selectedPreset: $preset,
                voltageValues: $values,
                onSave: { _, _ in },
                onEditCurve: {}
            )
        }
    }
}
