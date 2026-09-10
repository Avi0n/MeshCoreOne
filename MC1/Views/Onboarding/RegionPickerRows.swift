import MC1Services
import SwiftUI

/// Country + state rows. Hosts apply `regionPickerSheet` on the Form or List;
/// a sheet attached here is presented once per row.
struct RegionPickerRows: View {
  enum Sheet: Identifiable {
    case country
    case subdivision
    var id: Self {
      self
    }
  }

  @Binding var selection: RegionSelection?
  @Binding var activeSheet: Sheet?

  private var country: String? {
    selection?.countryCode
  }

  private var subdivision: String? {
    selection?.administrativeAreaCode
  }

  var body: some View {
    Button {
      activeSheet = .country
    } label: {
      LabeledContent(L10n.Onboarding.Region.country) {
        Text(countryDisplay)
      }
    }
    if RegionalAreas.showsSubdivisionPicker(for: country) {
      Button {
        activeSheet = .subdivision
      } label: {
        LabeledContent(administrativeAreaTitle(for: country)) {
          Text(subdivisionDisplay)
        }
      }
    }
  }

  private var countryDisplay: String {
    guard let country else { return L10n.Settings.Radio.PresetLocation.notSet }
    return Locale.current.localizedString(forRegionCode: country) ?? country
  }

  private var subdivisionDisplay: String {
    guard let subdivision else { return L10n.Settings.Radio.PresetLocation.notSet }
    return RegionalAreas.subdivisionDisplayName(subdivision) ?? subdivision
  }
}

extension View {
  func regionPickerSheet(
    _ activeSheet: Binding<RegionPickerRows.Sheet?>,
    selection: Binding<RegionSelection?>
  ) -> some View {
    sheet(item: activeSheet) { sheet in
      switch sheet {
      case .country:
        CountryPickerSheet(selectedCountry: selection.wrappedValue?.countryCode) { newCountry in
          if let next = RegionSelection.afterChoosingCountry(
            newCountry,
            current: selection.wrappedValue
          ) {
            selection.wrappedValue = next
          }
        }
      case .subdivision:
        SubdivisionPickerSheet(
          country: selection.wrappedValue?.countryCode,
          selectedSubdivision: selection.wrappedValue?.administrativeAreaCode
        ) { newSubdivision in
          if let next = RegionSelection.afterChoosingSubdivision(
            newSubdivision,
            current: selection.wrappedValue
          ) {
            selection.wrappedValue = next
          }
        }
      }
    }
  }
}

private struct CountryPickerSheet: View {
  @Environment(\.dismiss) private var dismiss
  let selectedCountry: String?
  let onSelect: (String) -> Void

  @State private var search = ""

  private var filtered: [RegionalAreas.Country] {
    let all = RegionalAreas.countriesSortedByLocalizedName
    guard !search.isEmpty else { return all }
    return all.filter { $0.localizedName.localizedStandardContains(search) }
  }

  var body: some View {
    NavigationStack {
      List(filtered) { entry in
        Button {
          onSelect(entry.id)
          dismiss()
        } label: {
          HStack {
            Text(entry.localizedName)
            Spacer()
            if entry.id == selectedCountry { Image(systemName: "checkmark") }
          }
        }
      }
      .searchable(text: $search)
      .navigationTitle(L10n.Onboarding.Region.country)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.Localizable.Common.cancel) {
            dismiss()
          }
        }
      }
    }
  }
}

private struct SubdivisionPickerSheet: View {
  @Environment(\.dismiss) private var dismiss
  let country: String?
  let selectedSubdivision: String?
  let onSelect: (String) -> Void

  @State private var search = ""

  private var rows: [RegionalAreas.Subdivision] {
    let all = RegionalAreas.subdivisions(for: country)
    guard !search.isEmpty else { return all }
    return all.filter {
      (RegionalAreas.subdivisionDisplayName($0.id) ?? $0.englishName)
        .localizedStandardContains(search)
    }
  }

  var body: some View {
    NavigationStack {
      List(rows) { row in
        Button {
          onSelect(row.id)
          dismiss()
        } label: {
          HStack {
            Text(RegionalAreas.subdivisionDisplayName(row.id) ?? row.englishName)
            Spacer()
            if row.id == selectedSubdivision { Image(systemName: "checkmark") }
          }
        }
      }
      .searchable(text: $search)
      .navigationTitle(administrativeAreaTitle(for: country))
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.Localizable.Common.cancel) {
            dismiss()
          }
        }
      }
    }
  }
}

private func administrativeAreaTitle(for country: String?) -> String {
  switch RegionalAreas.administrativeAreaKind(for: country) {
  case .province: L10n.Onboarding.Region.province
  case .state: L10n.Onboarding.Region.state
  }
}
