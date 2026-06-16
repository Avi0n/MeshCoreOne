import SwiftUI
import MC1Services

struct BatterySettingsView: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme

    @State private var preferences = NotificationPreferencesStore()
    @State private var selectedOCVPreset: OCVPreset = .liIon
    @State private var ocvValues: [Int] = OCVPreset.liIon.ocvArray
    @State private var showingCurveEditor = false

    var body: some View {
        @Bindable var preferences = preferences

        List {
            Section {
                Stepper(
                    value: $preferences.lowBatteryWarningThreshold,
                    in: AppStorageKey.lowBatteryWarningThresholdRange,
                    step: AppStorageKey.lowBatteryWarningThresholdStep
                ) {
                    HStack {
                        TintedLabel(L10n.Settings.Battery.LowWarning.title, systemImage: "battery.25")
                        Spacer()
                        Text("\(preferences.lowBatteryWarningThreshold)%")
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                    }
                }
            } header: {
                Text(L10n.Settings.Battery.LowWarning.header)
            } footer: {
                Text(L10n.Settings.Battery.LowWarning.footer)
            }
            .themedRowBackground(theme)

            BatteryCurveSection(
                availablePresets: OCVPreset.selectablePresets,
                headerText: L10n.Settings.BatteryCurve.header,
                footerText: L10n.Settings.BatteryCurve.footer,
                selectedPreset: $selectedOCVPreset,
                voltageValues: $ocvValues,
                onSave: saveOCVToDevice,
                isDisabled: appState.connectionState != .ready,
                onEditCurve: { showingCurveEditor = true }
            )
        }
        // Sheet is on the List (page level), not inside any cell.
        .sheet(isPresented: $showingCurveEditor) {
            BatteryCurveEditorView(
                ocvArray: ocvValues,
                referenceOCVArray: selectedOCVPreset == .custom ? nil : selectedOCVPreset.ocvArray,
                onDone: { newValues in
                    ocvValues = newValues
                    selectedOCVPreset = .custom
                    Task { await saveOCVToDevice(preset: .custom, values: newValues) }
                }
            )
        }
        .themedCanvas(theme)
        .navigationTitle(L10n.Settings.Battery.title)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: appState.connectedDevice?.id) {
            loadOCVFromDevice()
        }
    }

    private func loadOCVFromDevice() {
        guard let device = appState.connectedDevice else { return }

        if let presetName = device.ocvPreset {
            if presetName == OCVPreset.custom.rawValue, let customString = device.customOCVArrayString {
                let parsed = customString.split(separator: ",")
                    .compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
                if parsed.count == 11 {
                    ocvValues = parsed
                    selectedOCVPreset = .custom
                    return
                }
            }
            if let preset = OCVPreset(rawValue: presetName) {
                selectedOCVPreset = preset
                ocvValues = preset.ocvArray
                return
            }
        }

        selectedOCVPreset = .liIon
        ocvValues = OCVPreset.liIon.ocvArray
    }

    private func saveOCVToDevice(preset: OCVPreset, values: [Int]) async {
        guard let deviceService = appState.services?.deviceService,
              let deviceID = appState.connectedDevice?.id else { return }

        if preset == .custom {
            let customString = values.map(String.init).joined(separator: ",")
            try? await deviceService.updateOCVSettings(
                deviceID: deviceID,
                preset: OCVPreset.custom.rawValue,
                customArray: customString
            )
        } else {
            try? await deviceService.updateOCVSettings(
                deviceID: deviceID,
                preset: preset.rawValue,
                customArray: nil
            )
        }
    }
}
