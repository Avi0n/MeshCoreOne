import Charts
import MC1Services
import SwiftUI

/// Full-screen editor for a battery discharge curve, presented as a sheet.
///
/// Works in terms of `BatteryAnchor`s — arbitrary (voltage, percent) points the
/// user can add, remove, and nudge with steppers — and resamples them into the
/// device's fixed 11-point OCV array when the user taps Done. Steppers clamp each
/// anchor against its neighbours so the curve stays valid (strictly increasing
/// voltage, non-decreasing percent) at every step.
struct BatteryCurveEditorView: View {
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss

    /// Reference chemistry curve drawn as a dashed line, if the active preset has one.
    let referenceOCVArray: [Int]?
    /// Receives the resampled 11-point OCV array when the user commits.
    let onDone: ([Int]) -> Void

    @State private var anchors: [BatteryAnchor]
    @State private var editMode = EditMode.inactive

    /// Millivolt granularity of a single stepper tap.
    private let millivoltStep = 10

    private var curveValidationError: String? {
        switch BatteryCurve.validationError(for: anchors) {
        case nil: return nil
        case .tooFewAnchors: return "At least \(BatteryCurve.minAnchors) anchors are required."
        case .millivoltsOutOfRange:
            let r = BatteryCurve.validMillivoltRange
            return "All voltages must be between \(r.lowerBound) mV and \(r.upperBound) mV."
        case .percentOutOfRange: return "All percentages must be between 0% and 100%."
        case .voltagesNotIncreasing: return "Voltages must strictly increase — reorder or adjust anchors."
        case .percentsDecreasing: return "Percentages must not decrease — reorder or adjust anchors."
        }
    }

    init(ocvArray: [Int], referenceOCVArray: [Int]?, onDone: @escaping ([Int]) -> Void) {
        self.referenceOCVArray = referenceOCVArray
        self.onDone = onDone
        _anchors = State(initialValue: BatteryCurve.anchors(fromOCVArray: ocvArray))
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    chart
                        .listRowInsets(EdgeInsets(top: 12, leading: 16, bottom: 4, trailing: 16))
                } footer: {
                    Text(L10n.Settings.BatteryCurve.referenceCaption)
                }
                .themedRowBackground(theme)

                Section {
                    ForEach(Array(anchors.enumerated()), id: \.element.id) { index, anchor in
                        AnchorRow(
                            anchor: anchor,
                            millivoltLabel: L10n.Settings.BatteryCurve.mv,
                            percentLabel: L10n.Settings.BatteryCurve.percent,
                            onMillivoltsChange: { adjustMillivolts(at: index, by: $0) },
                            onPercentChange: { adjustPercent(at: index, by: $0) },
                            onMillivoltsSet: { setMillivolts(at: index, to: $0) },
                            onPercentSet: { setPercent(at: index, to: $0) }
                        )
                    }
                    .onDelete(perform: deleteAnchors)
                    .onMove { anchors.move(fromOffsets: $0, toOffset: $1) }

                    Button {
                        addAnchor()
                    } label: {
                        Label(L10n.Settings.BatteryCurve.addAnchor, systemImage: "plus.circle.fill")
                    }
                    .disabled(anchors.count >= BatteryCurve.maxAnchors)
                } header: {
                    Text(L10n.Settings.BatteryCurve.anchors)
                } footer: {
                    if let error = curveValidationError {
                        Text(error).foregroundStyle(.red)
                    } else {
                        Text(L10n.Settings.BatteryCurve.editorFooter)
                    }
                }
                .themedRowBackground(theme)
            }
            .themedCanvas(theme)
            .navigationTitle(L10n.Settings.BatteryCurve.Editor.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    EditButton()
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.Localizable.Common.done) {
                        onDone(BatteryCurve.ocvArray(fromAnchors: anchors))
                        dismiss()
                    }
                    .fontWeight(.semibold)
                    .disabled(curveValidationError != nil)
                }
            }
            .environment(\.editMode, $editMode)
        }
    }

    // MARK: - Chart

    private func yAxisMin(for arrays: [[Int]]) -> Double {
        let allMV = arrays.flatMap { $0 }
        guard let minMV = allMV.min() else { return 1.0 }
        let multiplier = pow(10.0, 1.0)
        return (Double(minMV - 100) / 1000.0 * multiplier).rounded(.down) / multiplier
    }

    private func yAxisMax(for arrays: [[Int]]) -> Double {
        let allMV = arrays.flatMap { $0 }
        guard let maxMV = allMV.max() else { return 4.5 }
        let multiplier = pow(10.0, 1.0)
        return (Double(maxMV + 100) / 1000.0 * multiplier).rounded(.up) / multiplier
    }

    private var chart: some View {
        let userArray = BatteryCurve.ocvArray(fromAnchors: anchors)
        let allArrays = [userArray] + (referenceOCVArray.map { [$0] } ?? [])
        let yMin = yAxisMin(for: allArrays)
        let yMax = yAxisMax(for: allArrays)
        return Chart {
            if let reference = referenceOCVArray {
                ForEach(curvePoints(reference), id: \.percent) { point in
                    LineMark(
                        x: .value(L10n.Settings.Chart.percent, point.percent),
                        y: .value(L10n.Settings.Chart.voltage, point.voltage),
                        series: .value("series", "reference")
                    )
                    .foregroundStyle(.secondary)
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
                    .interpolationMethod(.monotone)
                }
            }

            ForEach(curvePoints(userArray), id: \.percent) { point in
                LineMark(
                    x: .value(L10n.Settings.Chart.percent, point.percent),
                    y: .value(L10n.Settings.Chart.voltage, point.voltage),
                    series: .value("series", "user")
                )
                .foregroundStyle(theme.accentColor)
                .interpolationMethod(.monotone)
            }
        }
        .chartXScale(domain: 0...100)
        .chartYScale(domain: yMin...yMax)
        .chartXAxis { AxisMarks(values: [0, 25, 50, 75, 100]) }
        .chartXAxisLabel(L10n.Settings.Chart.percent)
        .chartYAxisLabel(L10n.Settings.Chart.voltage)
        .accessibilityLabel(L10n.Settings.Chart.accessibility)
        .frame(height: 160)
    }

    private func curvePoints(_ ocvArray: [Int]) -> [CurvePoint] {
        ocvArray.enumerated().map { index, millivolts in
            CurvePoint(percent: (10 - index) * 10, voltage: Double(millivolts) / 1000.0)
        }
    }

    // MARK: - Anchor mutation

    private func adjustMillivolts(at index: Int, by direction: Int) {
        guard anchors.indices.contains(index) else { return }
        let r = BatteryCurve.validMillivoltRange
        let proposed = anchors[index].millivolts + direction * millivoltStep
        anchors[index].millivolts = min(max(proposed, r.lowerBound), r.upperBound)
    }

    private func adjustPercent(at index: Int, by direction: Int) {
        guard anchors.indices.contains(index) else { return }
        anchors[index].percent = min(max(anchors[index].percent + direction, 0), 100)
    }

    private func setMillivolts(at index: Int, to value: Int) {
        guard anchors.indices.contains(index) else { return }
        let r = BatteryCurve.validMillivoltRange
        anchors[index].millivolts = min(max(value, r.lowerBound), r.upperBound)
    }

    private func setPercent(at index: Int, to value: Int) {
        guard anchors.indices.contains(index) else { return }
        anchors[index].percent = min(max(value, 0), 100)
    }

    private func addAnchor() {
        let newMV: Int
        let newPct: Int
        if let last = anchors.last {
            newMV = min(last.millivolts + 100, BatteryCurve.validMillivoltRange.upperBound)
            newPct = min(last.percent + 10, 100)
        } else {
            newMV = BatteryCurve.validMillivoltRange.lowerBound
            newPct = 0
        }
        withAnimation {
            anchors.append(BatteryAnchor(millivolts: newMV, percent: newPct))
        }
    }

    private func deleteAnchors(at offsets: IndexSet) {
        guard anchors.count - offsets.count >= BatteryCurve.minAnchors else { return }
        anchors.remove(atOffsets: offsets)
    }
}

// MARK: - Anchor Row

/// A single anchor: a millivolt stepper column and a percent stepper column.
private struct AnchorRow: View {
    let anchor: BatteryAnchor
    let millivoltLabel: String
    let percentLabel: String
    let onMillivoltsChange: (Int) -> Void
    let onPercentChange: (Int) -> Void
    let onMillivoltsSet: (Int) -> Void
    let onPercentSet: (Int) -> Void

    var body: some View {
        HStack(spacing: 16) {
            StepperColumn(
                label: millivoltLabel,
                numericValue: anchor.millivolts,
                suffix: "",
                onIncrement: { onMillivoltsChange(1) },
                onDecrement: { onMillivoltsChange(-1) },
                onDirectEdit: onMillivoltsSet
            )

            Divider().frame(height: 36)

            StepperColumn(
                label: percentLabel,
                numericValue: anchor.percent,
                suffix: "%",
                onIncrement: { onPercentChange(1) },
                onDecrement: { onPercentChange(-1) },
                onDirectEdit: onPercentSet
            )
        }
        .padding(.vertical, 2)
    }
}

// MARK: - Stepper Column

/// One column inside an AnchorRow: label, tappable/editable value, and +/− stepper.
private struct StepperColumn: View {
    let label: String
    let numericValue: Int
    let suffix: String
    let onIncrement: () -> Void
    let onDecrement: () -> Void
    let onDirectEdit: (Int) -> Void

    @State private var editText = ""
    @FocusState private var focused: Bool

    private var displayText: String { "\(numericValue)\(suffix)" }

    var body: some View {
        Stepper(onIncrement: onIncrement, onDecrement: onDecrement) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                // ZStack keeps TextField in the hierarchy at all times so programmatic
                // focus works immediately; Text shows the live value when not editing.
                ZStack(alignment: .leading) {
                    Text(displayText)
                        .opacity(focused ? 0 : 1)
                    TextField("", text: $editText)
                        .keyboardType(.numberPad)
                        .opacity(focused ? 1 : 0)
                        .allowsHitTesting(focused)
                        .focused($focused)
                        .onSubmit { commit() }
                }
                .font(.body)
                .monospacedDigit()
                .lineLimit(1)
                .contentShape(Rectangle())
                .onTapGesture {
                    editText = "\(numericValue)"
                    focused = true
                }
            }
        }
        .frame(maxWidth: .infinity)
        .onChange(of: focused) { _, isFocused in
            if !isFocused { commit() }
        }
        .accessibilityValue(displayText)
    }

    private func commit() {
        if let parsed = Int(editText) {
            onDirectEdit(parsed)
        }
    }
}

private struct CurvePoint {
    let percent: Int
    let voltage: Double
}

#Preview {
    BatteryCurveEditorView(
        ocvArray: OCVPreset.liIon.ocvArray,
        referenceOCVArray: OCVPreset.liFePO4.ocvArray,
        onDone: { _ in }
    )
}
