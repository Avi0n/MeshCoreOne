import SwiftUI
import MC1Services

/// Country + state/province picker used in onboarding step 4 and Settings → Region.
/// Hides the State/Province row for countries with no sub-region presets.
struct RegionPickerView: View {
    @Binding var selection: RegionSelection?
    var onCommit: () -> Void

    @State private var country: String?
    @State private var subdivision: String?
    @State private var showingCountrySheet = false
    @State private var showingSubdivisionSheet = false

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

            Section {
                Button(L10n.Onboarding.Region.continue) { commit() }
                    .disabled(country == nil)
            }
        }
        .onAppear {
            country = selection?.countryCode
            subdivision = selection?.administrativeAreaCode
        }
        .sheet(isPresented: $showingCountrySheet) {
            CountryPickerSheet(country: $country)
        }
        .sheet(isPresented: $showingSubdivisionSheet) {
            SubdivisionPickerSheet(
                country: country,
                subdivision: $subdivision
            )
        }
    }

    private var countryDisplay: String {
        guard let country else { return "—" }
        return Locale.current.localizedString(forRegionCode: country) ?? country
    }

    private var subdivisionDisplay: String {
        guard let subdivision else { return "—" }
        return subdivision
    }

    private func commit() {
        guard let country else { return }
        selection = RegionSelection(
            countryCode: country,
            administrativeAreaCode: subdivision,
            countyKey: nil,
            source: .manual
        )
        onCommit()
    }
}

private struct CountryPickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Binding var country: String?
    @State private var search = ""

    var filtered: [RegionalAreas.Country] {
        let all = RegionalAreas.countries.sorted { $0.localizedName < $1.localizedName }
        guard !search.isEmpty else { return all }
        return all.filter { $0.localizedName.localizedCaseInsensitiveContains(search) }
    }

    var body: some View {
        NavigationStack {
            List(filtered) { entry in
                Button {
                    country = entry.id
                    dismiss()
                } label: {
                    HStack {
                        Text(entry.localizedName)
                        Spacer()
                        if entry.id == country { Image(systemName: "checkmark") }
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
    @Binding var subdivision: String?

    var rows: [RegionalAreas.Subdivision] {
        guard let country,
              let entry = RegionalAreas.countries.first(where: { $0.id == country }) else { return [] }
        return entry.subdivisions ?? []
    }

    var body: some View {
        NavigationStack {
            List(rows) { row in
                Button {
                    subdivision = row.id
                    dismiss()
                } label: {
                    HStack {
                        Text(row.id)
                        Spacer()
                        if row.id == subdivision { Image(systemName: "checkmark") }
                    }
                }
            }
            .navigationTitle(L10n.Onboarding.Region.administrativeArea)
        }
    }
}
