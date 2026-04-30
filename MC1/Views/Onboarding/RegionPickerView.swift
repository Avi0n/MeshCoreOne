import SwiftUI
import MC1Services

/// Country + state/province picker used in onboarding step 4 and Settings → Region.
/// Hides the State/Province row for countries with no sub-region presets.
///
/// The picker writes to its `selection` binding inline on every change so the
/// host doesn't need a Continue button to persist state. Onboarding adds its
/// own "Continue" CTA below the picker for navigation; Settings relies on the
/// system back chevron and the inline write.
struct RegionPickerView: View {
    @Binding var selection: RegionSelection?

    @State private var showingCountrySheet = false
    @State private var showingSubdivisionSheet = false

    private static let emptyValue = "—"

    private var country: String? { selection?.countryCode }
    private var subdivision: String? { selection?.administrativeAreaCode }

    private var availableSubdivisions: [RegionalAreas.Subdivision] {
        guard let country,
              let entry = RegionalAreas.countries.first(where: { $0.id == country }) else { return [] }
        return entry.subdivisions ?? []
    }

    var body: some View {
        Form {
            Section {
                Button {
                    showingCountrySheet = true
                } label: {
                    LabeledContent(L10n.Onboarding.Region.country) {
                        Text(countryDisplay)
                    }
                }
                if !availableSubdivisions.isEmpty {
                    Button {
                        showingSubdivisionSheet = true
                    } label: {
                        LabeledContent(L10n.Onboarding.Region.administrativeArea) {
                            Text(subdivisionDisplay)
                        }
                    }
                }
            }
        }
        .sheet(isPresented: $showingCountrySheet) {
            CountryPickerSheet(selectedCountry: country) { newCountry in
                selectCountry(newCountry)
            }
        }
        .sheet(isPresented: $showingSubdivisionSheet) {
            SubdivisionPickerSheet(country: country, selectedSubdivision: subdivision) { newSubdivision in
                selectSubdivision(newSubdivision)
            }
        }
    }

    private var countryDisplay: String {
        guard let country else { return Self.emptyValue }
        return Locale.current.localizedString(forRegionCode: country) ?? country
    }

    private var subdivisionDisplay: String {
        guard let subdivision else { return Self.emptyValue }
        return RegionalAreas.subdivisionDisplayName(subdivision) ?? subdivision
    }

    private func selectCountry(_ newCountry: String) {
        // Drop subdivision when country changes so a stale id (e.g. "US-CA")
        // can't ride on a new country (e.g. "CA") into the persisted selection.
        selection = RegionSelection(
            countryCode: newCountry,
            administrativeAreaCode: nil,
            countyKey: nil,
            source: .manual
        )
    }

    private func selectSubdivision(_ newSubdivision: String?) {
        guard let country else { return }
        selection = RegionSelection(
            countryCode: country,
            administrativeAreaCode: newSubdivision,
            countyKey: nil,
            source: .manual
        )
    }
}

private struct CountryPickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let selectedCountry: String?
    let onSelect: (String) -> Void

    @State private var search = ""

    private var filtered: [RegionalAreas.Country] {
        let all = RegionalAreas.countries.sorted { $0.localizedName < $1.localizedName }
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
        }
    }
}

private struct SubdivisionPickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let country: String?
    let selectedSubdivision: String?
    let onSelect: (String) -> Void

    private var rows: [RegionalAreas.Subdivision] {
        guard let country,
              let entry = RegionalAreas.countries.first(where: { $0.id == country }) else { return [] }
        return entry.subdivisions ?? []
    }

    var body: some View {
        NavigationStack {
            List(rows) { row in
                Button {
                    onSelect(row.id)
                    dismiss()
                } label: {
                    HStack {
                        Text(RegionalAreas.subdivisionDisplayName(row.id) ?? row.id)
                        Spacer()
                        if row.id == selectedSubdivision { Image(systemName: "checkmark") }
                    }
                }
            }
            .navigationTitle(L10n.Onboarding.Region.administrativeArea)
        }
    }
}
