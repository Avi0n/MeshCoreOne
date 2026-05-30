import SwiftUI
import MC1Services

/// Battery curve configuration section
/// Pure UI component - caller provides data and save callback
struct BatteryCurveSection: View {
    @Environment(\.appTheme) private var theme
    let availablePresets: [OCVPreset]
    let headerText: String
    let footerText: String

    @Binding var selectedPreset: OCVPreset
    @Binding var voltageValues: [Int]

    let onSave: (OCVPreset, [Int]) async -> Void
    var isDisabled: Bool = false

    @State private var isEditingValues = false
    @State private var validationError: String?
    @State private var isUpdatingFromPreset = false

    var body: some View {
        Section {
            // Preset picker
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
                    isUpdatingFromPreset = true
                    voltageValues = newValue.ocvArray
                    Task { @MainActor in
                        isUpdatingFromPreset = false
                    }
                    Task {
                        await onSave(newValue, newValue.ocvArray)
                    }
                }
            }
            .disabled(isDisabled)

            BatteryCurveChart(ocvArray: voltageValues)

            // Edit values disclosure
            DisclosureGroup(L10n.Settings.BatteryCurve.editValues, isExpanded: $isEditingValues) {
                VoltageFieldsGrid(
                    voltageValues: $voltageValues,
                    validationError: $validationError,
                    onValueChanged: handleValueChanged
                )
            }
            .disabled(isDisabled)

            // Validation error
            if let error = validationError {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        } header: {
            if !headerText.isEmpty {
                Text(headerText)
            }
        } footer: {
            if !footerText.isEmpty {
                Text(footerText)
            }
        }
        .themedRowBackground(theme)
    }

    private func handleValueChanged() {
        guard !isUpdatingFromPreset else { return }

        if let error = validateVoltageValues() {
            validationError = error
            return
        }
        validationError = nil

        selectedPreset = .custom
        Task {
            await onSave(.custom, voltageValues)
        }
    }

    private func validateVoltageValues() -> String? {
        for (index, value) in voltageValues.enumerated() {
            if !OCVPreset.validMillivoltRange.contains(value) {
                return L10n.Settings.BatteryCurve.Validation.outOfRange((10 - index) * 10)
            }
        }

        for (current, next) in zip(voltageValues, voltageValues.dropFirst()) where current <= next {
            return L10n.Settings.BatteryCurve.Validation.notDescending
        }

        return nil
    }
}

/// Two-column grid of voltage input fields
struct VoltageFieldsGrid: View {
    @Binding var voltageValues: [Int]
    @Binding var validationError: String?
    let onValueChanged: () -> Void

    private let columns = [
        GridItem(.flexible()),
        GridItem(.flexible())
    ]

    var body: some View {
        LazyVGrid(columns: columns, spacing: 12) {
            ForEach(0..<11, id: \.self) { index in
                VoltageField(
                    percent: (10 - index) * 10,
                    value: $voltageValues[index],
                    hasError: fieldHasError(at: index),
                    onValueChanged: onValueChanged
                )
            }
        }
        .padding(.vertical, 8)
    }

    private func fieldHasError(at index: Int) -> Bool {
        let value = voltageValues[index]
        if !OCVPreset.validMillivoltRange.contains(value) { return true }
        if index > 0 && voltageValues[index - 1] <= value { return true }
        if index < 10 && value <= voltageValues[index + 1] { return true }
        return false
    }
}

/// Individual voltage input field
struct VoltageField: View {
    let percent: Int
    @Binding var value: Int
    let hasError: Bool
    let onValueChanged: () -> Void

    var body: some View {
        HStack {
            Text("\(percent)%")
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(width: 40, alignment: .trailing)

            TextField("", value: $value, format: .number)
                .keyboardType(.numberPad)
                .textFieldStyle(.roundedBorder)
                .overlay(
                    RoundedRectangle(cornerRadius: 6)
                        .stroke(hasError ? .red : .clear, lineWidth: 1)
                )
                .onChange(of: value) { _, _ in
                    onValueChanged()
                }
                .accessibilityLabel(L10n.Settings.BatteryCurve.Accessibility.voltageLabel(percent))
                .accessibilityValue(L10n.Settings.BatteryCurve.Accessibility.voltageValue(value))
                .accessibilityHint(L10n.Settings.BatteryCurve.Accessibility.voltageHint)

            Text(L10n.Settings.BatteryCurve.mv)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
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
                footerText: "Configure the voltage-to-percentage curve for your device's battery.",
                selectedPreset: $preset,
                voltageValues: $values,
                onSave: { _, _ in }
            )
        }
    }
}
