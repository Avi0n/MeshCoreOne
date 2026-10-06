import SwiftUI

/// `@FocusState` stays on this row. A focus state declared on the parent does not work in sheet content.
struct FrequencyInputRow: View {
  @Bindable var viewModel: LineOfSightViewModel
  @FocusState private var isFocused: Bool
  @State private var text: String = ""

  var body: some View {
    HStack {
      Label(L10n.Tools.Tools.LineOfSight.frequency, systemImage: "antenna.radiowaves.left.and.right")
        .foregroundStyle(.secondary)
      Spacer()
      TextField(L10n.Tools.Tools.LineOfSight.mhz, text: $text)
        .keyboardType(.decimalPad)
        .multilineTextAlignment(.trailing)
        .frame(width: 80)
        .focused($isFocused)
        .onChange(of: text) { _, newValue in
          let replaced = newValue.replacing(",", with: ".")
          if replaced != newValue {
            text = replaced
          }
        }
        .onChange(of: isFocused) { _, focused in
          if focused {
            text = viewModel.formatFrequencyForEditing(viewModel.frequencyMHz)
          } else {
            commitEdit()
          }
        }

      Text(L10n.Tools.Tools.LineOfSight.mhz)
        .foregroundStyle(.secondary)

      if isFocused {
        Button {
          commitEdit()
          isFocused = false
        } label: {
          Image(systemName: "checkmark.circle.fill")
            .foregroundStyle(.green)
            .font(.title2)
        }
        .buttonStyle(.plain)
      }
    }
    .onAppear {
      text = viewModel.formatFrequencyForEditing(viewModel.frequencyMHz)
    }
  }

  private func commitEdit() {
    guard let parsed = viewModel.parseFrequency(text) else {
      text = viewModel.formatFrequencyForEditing(viewModel.frequencyMHz)
      return
    }
    viewModel.frequencyMHz = parsed
    viewModel.commitFrequencyChange()
  }
}
