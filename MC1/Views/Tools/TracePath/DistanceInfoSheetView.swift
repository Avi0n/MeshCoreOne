import MC1Services
import SwiftUI

/// Sheet explaining distance calculation details and limitations
struct DistanceInfoSheetView: View {
  @Environment(\.appTheme) private var theme
  let result: TraceResult
  @Bindable var viewModel: TracePathViewModel
  @Binding var showingDistanceInfo: Bool

  var body: some View {
    NavigationStack {
      List {
        if viewModel.isDistanceUsingFallback {
          Section {
            Text(L10n.Contacts.Contacts.Results.partialDistanceExplanation)
          } header: {
            Label(L10n.Contacts.Contacts.Results.partialDistanceHeader, systemImage: "location.slash")
          }
          .themedRowBackground(theme)
          Section {
            Text(L10n.Contacts.Contacts.Results.fullPathTip)
          } header: {
            Label(L10n.Contacts.Contacts.Results.fullPathHeader, systemImage: "lightbulb")
          }
          .themedRowBackground(theme)
        } else if result.hops.count(where: { !$0.isStartNode && !$0.isEndNode }) < 2 {
          Section {
            Text(L10n.Contacts.Contacts.Results.needsRepeaters)
          }
          .themedRowBackground(theme)
        } else if viewModel.repeatersWithoutLocation.isEmpty {
          Section {
            Text(L10n.Contacts.Contacts.Results.distanceError)
          }
          .themedRowBackground(theme)
        } else {
          Section {
            Text(L10n.Contacts.Contacts.Results.missingLocations)
          }
          .themedRowBackground(theme)
          Section(L10n.Contacts.Contacts.Results.repeatersWithoutLocations) {
            ForEach(viewModel.repeatersWithoutLocation, id: \.self) { name in
              Text(name)
            }
          }
          .themedRowBackground(theme)
        }
      }
      .themedCanvas(theme)
      .navigationTitle(viewModel.isDistanceUsingFallback ? L10n.Contacts.Contacts.Results.distanceInfoTitlePartial : L10n.Contacts.Contacts.Results.distanceInfoTitle)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .topBarTrailing) {
          Button(L10n.Contacts.Contacts.Common.done) {
            showingDistanceInfo = false
          }
        }
      }
    }
  }
}
