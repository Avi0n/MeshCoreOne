import MC1Services
import SwiftUI

struct RepeaterAddRegionSheet: View {
  @Environment(\.appTheme) private var theme
  @Environment(\.dismiss) private var dismiss

  let existingRegions: [RepeaterRegionEntry]
  let onAdd: @MainActor (_ name: String, _ parent: RepeaterRegionEntry.Parent) async throws -> Void

  @State private var regionName = ""
  @State private var selectedParent: RepeaterRegionEntry.Parent
  @State private var isSubmitting = false
  @State private var errorMessage: String?
  @FocusState private var nameFieldFocused: Bool

  init(
    parent: RepeaterRegionEntry.Parent,
    existingRegions: [RepeaterRegionEntry],
    onAdd: @escaping @MainActor (_ name: String, _ parent: RepeaterRegionEntry.Parent) async throws -> Void
  ) {
    _selectedParent = State(initialValue: parent)
    self.existingRegions = existingRegions
    self.onAdd = onAdd
  }

  private var trimmedName: String {
    regionName.trimmingCharacters(in: .whitespaces)
  }

  private var validationError: RegionNameValidator.ValidationError? {
    RegionNameValidator.validate(trimmedName, existingRegions: existingRegions.map(\.name))
  }

  private var validationErrorText: String? {
    switch validationError {
    case .invalidCharacters:
      L10n.RemoteNodes.RemoteNodes.Settings.Regions.invalidName
    case let .tooLong(maxBytes):
      L10n.RemoteNodes.RemoteNodes.Settings.Regions.nameTooLong(maxBytes)
    case .duplicate:
      L10n.RemoteNodes.RemoteNodes.Settings.Regions.duplicate
    case .empty, nil:
      nil
    }
  }

  private var canAdd: Bool {
    !trimmedName.isEmpty && validationError == nil && !isSubmitting
  }

  var body: some View {
    NavigationStack {
      Form {
        Section {
          TextField(L10n.RemoteNodes.RemoteNodes.Settings.Regions.regionName, text: $regionName)
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            .focused($nameFieldFocused)

          Picker(L10n.RemoteNodes.RemoteNodes.Settings.Regions.parent, selection: $selectedParent) {
            Text(L10n.RemoteNodes.RemoteNodes.Settings.Regions.allTrafficWildcard)
              .tag(RepeaterRegionEntry.Parent.unscoped)
              .accessibilityLabel(L10n.RemoteNodes.RemoteNodes.Settings.Regions.allTraffic)
            ForEach(existingRegions.filter { !$0.isUnscoped }, id: \.name) { region in
              Text(region.name)
                .tag(RepeaterRegionEntry.Parent.named(region.name))
            }
          }
          .pickerStyle(.menu)
        }
        .themedRowBackground(theme)

        if let errorText = errorMessage ?? validationErrorText {
          Section {
            Text(errorText)
              .foregroundStyle(.red)
              .font(.caption)
          }
          .themedRowBackground(theme)
        }
      }
      .themedCanvas(theme)
      .navigationTitle(L10n.RemoteNodes.RemoteNodes.Settings.Regions.addRegionTitle)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.RemoteNodes.RemoteNodes.cancel) {
            dismiss()
          }
          .disabled(isSubmitting)
        }

        ToolbarItem(placement: .confirmationAction) {
          Button {
            isSubmitting = true
            errorMessage = nil
            let nameToAdd = trimmedName
            Task {
              defer { isSubmitting = false }
              do {
                try await onAdd(nameToAdd, selectedParent)
                dismiss()
              } catch is RepeaterSettingsViewModel.AddRegionError {
                errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.addFailed
              } catch {
                errorMessage = error.userFacingMessage
              }
            }
          } label: {
            if isSubmitting {
              ProgressView()
            } else {
              Text(L10n.RemoteNodes.RemoteNodes.Settings.Regions.addRegion)
            }
          }
          .accessibilityLabel(L10n.RemoteNodes.RemoteNodes.Settings.Regions.addRegion)
          .disabled(!canAdd)
        }
      }
      .interactiveDismissDisabled(isSubmitting)
      .disabled(isSubmitting)
      .onAppear { nameFieldFocused = true }
      .onChange(of: regionName) { _, _ in errorMessage = nil }
      .onChange(of: selectedParent) { _, _ in errorMessage = nil }
    }
  }
}
