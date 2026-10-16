import SwiftUI

/// Advanced map filters that need more than a toggle: a rolling last-heard window and a hop-count range.
/// Edits apply live to the bound filter so the map updates behind the sheet.
struct MapAdvancedFiltersSheet: View {
  @Environment(\.dismiss) private var dismiss
  let host: MapFilterHost
  @Binding var state: MapFilterState

  @FocusState private var focusedField: MapFilterField?

  var body: some View {
    NavigationStack {
      Form {
        if host.capabilities.contains(.lastHeard) {
          lastHeardSection
        }
        if host.capabilities.contains(.hops) {
          hopsSection
        }
      }
      .scrollDismissesKeyboard(.interactively)
      .navigationTitle(L10n.Map.Map.Filters.title)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.Map.Map.Filters.reset) {
            state.resetAdvanced()
          }
          .disabled(!state.hasActiveAdvancedFilters(for: host))
        }
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Map.Map.Common.done) {
            focusedField = nil
            dismiss()
          }
        }
        ToolbarItemGroup(placement: .keyboard) {
          Spacer()
          Button(L10n.Map.Map.Common.done) {
            focusedField = nil
          }
        }
      }
    }
  }

  // MARK: - Last Heard

  private var lastHeardSection: some View {
    Section {
      MapFilterPresetRow(
        presets: MapLastHeardRange.presets,
        selection: state.lastHeard,
        label: \.presetLabel
      ) { state.setLastHeard($0) }

      MapDurationField(
        title: L10n.Map.Map.Filters.minimum,
        duration: state.lastHeard.minAge,
        field: .lastHeardMin,
        focusedField: $focusedField
      ) { state.setLastHeard(state.lastHeard.withMinAge($0)) }

      MapDurationField(
        title: L10n.Map.Map.Filters.maximum,
        duration: state.lastHeard.maxAge,
        field: .lastHeardMax,
        focusedField: $focusedField
      ) { state.setLastHeard(state.lastHeard.withMaxAge($0)) }
    } header: {
      Text(L10n.Map.Map.Filters.LastHeard.header)
    } footer: {
      VStack(alignment: .leading, spacing: 4) {
        Text(state.lastHeard.summaryText)
        Text(L10n.Map.Map.Filters.LastHeard.explanation)
      }
    }
  }

  // MARK: - Hops

  private var hopsSection: some View {
    Section {
      MapFilterPresetRow(
        presets: MapHopRange.presets,
        selection: state.hops,
        label: \.presetLabel
      ) { state.setHops($0) }

      MapHopBoundField(
        title: L10n.Map.Map.Filters.minimum,
        value: state.hops.min,
        allowsNoLimit: false,
        field: .hopsMin,
        focusedField: $focusedField
      ) { state.setHops(state.hops.withMin($0 ?? 0)) }

      MapHopBoundField(
        title: L10n.Map.Map.Filters.maximum,
        value: state.hops.max,
        allowsNoLimit: true,
        field: .hopsMax,
        focusedField: $focusedField
      ) { state.setHops(state.hops.withMax($0)) }
    } header: {
      Text(L10n.Map.Map.Filters.Hops.header)
    } footer: {
      VStack(alignment: .leading, spacing: 4) {
        Text(state.hops.summaryText)
        Text(L10n.Map.Map.Filters.Hops.explanation)
      }
    }
  }
}

/// Identifies the typed fields so the keyboard Done button and scrolling can end editing.
private enum MapFilterField: Hashable {
  case lastHeardMin
  case lastHeardMax
  case hopsMin
  case hopsMax
}

// MARK: - Preset Row

/// Horizontally scrolling capsules; the one equal to the current value is highlighted.
private struct MapFilterPresetRow<Value: Hashable>: View {
  let presets: [Value]
  let selection: Value
  let label: KeyPath<Value, String>
  let onSelect: (Value) -> Void

  var body: some View {
    ScrollView(.horizontal) {
      HStack(spacing: 8) {
        ForEach(presets, id: \.self) { preset in
          let isSelected = preset == selection
          Button(preset[keyPath: label]) {
            onSelect(preset)
          }
          .buttonStyle(.bordered)
          .buttonBorderShape(.capsule)
          .tint(isSelected ? .accentColor : .secondary)
          .accessibilityAddTraits(isSelected ? .isSelected : [])
        }
      }
      .padding(.vertical, 4)
    }
    .scrollIndicators(.hidden)
    .accessibilityElement(children: .contain)
    .accessibilityLabel(L10n.Map.Map.Filters.presets)
  }
}

// MARK: - Duration Field

/// A whole-number field plus unit menu. An empty field means no bound.
private struct MapDurationField: View {
  let title: String
  let duration: MapDuration?
  let field: MapFilterField
  var focusedField: FocusState<MapFilterField?>.Binding
  let onChange: (MapDuration?) -> Void

  @State private var unit: MapDurationUnit = .hours

  var body: some View {
    LabeledContent(title) {
      HStack(spacing: 8) {
        MapIntegerField(
          value: duration?.value,
          range: MapDuration.valueRange,
          field: field,
          focusedField: focusedField
        ) { newValue in
          onChange(newValue.map { MapDuration($0, unit) })
        }

        Picker(L10n.Map.Map.Filters.LastHeard.Unit.label, selection: unitBinding) {
          ForEach(MapDurationUnit.allCases, id: \.self) { unit in
            Text(unit.localizedName).tag(unit)
          }
        }
        .labelsHidden()
        .pickerStyle(.menu)
        .fixedSize()
      }
    }
    .onAppear {
      if let duration { unit = duration.unit }
    }
    .onChange(of: duration?.unit) { _, newUnit in
      if let newUnit { unit = newUnit }
    }
  }

  private var unitBinding: Binding<MapDurationUnit> {
    Binding(
      get: { unit },
      set: { newUnit in
        unit = newUnit
        if let duration {
          onChange(MapDuration(duration.value, newUnit))
        }
      }
    )
  }
}

// MARK: - Hop Bound Field

/// Typed entry plus a stepper over the protocol hop domain. When `allowsNoLimit`, empty (or the ceiling) means no bound.
private struct MapHopBoundField: View {
  let title: String
  let value: Int?
  let allowsNoLimit: Bool
  let field: MapFilterField
  var focusedField: FocusState<MapFilterField?>.Binding
  let onChange: (Int?) -> Void

  private var domain: ClosedRange<Int> {
    MapHopRange.hopDomain
  }

  var body: some View {
    LabeledContent(title) {
      HStack(spacing: 8) {
        MapIntegerField(
          value: value,
          range: domain,
          placeholder: allowsNoLimit ? L10n.Map.Map.Filters.noLimit : "0",
          field: field,
          focusedField: focusedField
        ) { newValue in
          onChange(allowsNoLimit ? newValue : (newValue ?? 0))
        }

        Stepper(title, value: stepperBinding, in: domain)
          .labelsHidden()
      }
    }
  }

  /// No limit behaves as the domain ceiling so stepping down from it starts at 62.
  private var stepperBinding: Binding<Int> {
    Binding(
      get: { value ?? domain.upperBound },
      set: { onChange($0) }
    )
  }
}

// MARK: - Integer Field

/// Number-pad text field bound to an optional integer. The value commits when editing ends,
/// so partially typed numbers never trigger crossed-bound repair; out-of-range input is clamped.
private struct MapIntegerField: View {
  let value: Int?
  let range: ClosedRange<Int>
  var placeholder: String = L10n.Map.Map.Filters.noLimit
  let field: MapFilterField
  var focusedField: FocusState<MapFilterField?>.Binding
  let onCommit: (Int?) -> Void

  @State private var text = ""

  private var isFocused: Bool {
    focusedField.wrappedValue == field
  }

  var body: some View {
    TextField(placeholder, text: $text)
      .keyboardType(.numberPad)
      .multilineTextAlignment(.trailing)
      .monospacedDigit()
      .frame(minWidth: 44, maxWidth: 96)
      .focused(focusedField, equals: field)
      .onAppear { text = Self.format(value) }
      // Value only changes while editing via presets, the stepper, or bound repair, so always mirror it.
      .onChange(of: value) { _, newValue in
        text = Self.format(newValue)
      }
      .onChange(of: text) { _, newText in
        // Longer input than the upper bound can only clamp to it, and would overflow `Int`.
        let digits = String(newText.filter(\.isASCII).filter(\.isNumber).prefix(maxDigits))
        if digits != newText { text = digits }
      }
      .onChange(of: isFocused) { _, focused in
        if !focused { commit() }
      }
      .onSubmit(commit)
      .onDisappear {
        if isFocused { commit() }
      }
  }

  private var maxDigits: Int {
    String(range.upperBound).count
  }

  private func commit() {
    let parsed = Int(text).map { min(max($0, range.lowerBound), range.upperBound) }
    if parsed != value { onCommit(parsed) }
    text = Self.format(parsed)
  }

  private static func format(_ value: Int?) -> String {
    value.map(String.init) ?? ""
  }
}

#Preview {
  @Previewable @State var state = MapFilterState(
    lastHeard: .within(MapDuration(2, .hours)),
    hops: MapHopRange(min: 1, max: 3)
  )
  MapAdvancedFiltersSheet(host: .mainMap, state: $state)
}
