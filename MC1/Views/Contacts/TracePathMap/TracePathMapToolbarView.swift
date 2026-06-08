import MapKit
import SwiftUI
import MC1Services

/// Map controls toolbar for trace path map view (location, labels, layers)
struct TracePathMapToolbarView: View {
    @Environment(\.appState) private var appState
    @Bindable var mapViewModel: TracePathMapViewModel
    @Binding var mapStyleSelection: MapStyleSelection
    @Binding var showLabels: Bool

    var body: some View {
        VStack {
            Spacer()
            HStack {
                Spacer()
                MapControlsToolbar(
                    onLocationTap: {
                        if let location = appState.bestAvailableLocation {
                            mapViewModel.setCameraRegion(MKCoordinateRegion(
                                center: location.coordinate,
                                span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)
                            ))
                        } else {
                            appState.locationService.requestLocation()
                        }
                    },
                    showingLayersMenu: $mapViewModel.showingLayersMenu,
                    topContent: {
                        NorthLockButton(isNorthLocked: $mapViewModel.isNorthLocked)
                    }
                ) {
                    LabelsToggleButton(showLabels: $showLabels)

                    // Center on path
                    if mapViewModel.hasPath {
                        Button(L10n.Contacts.Contacts.Trace.Map.centerOnPath, systemImage: "arrow.up.left.and.arrow.down.right") {
                            mapViewModel.centerOnPath()
                        }
                        .font(.body.weight(.medium))
                        .foregroundStyle(.primary)
                        .frame(width: 44, height: 44)
                        .contentShape(.rect)
                        .buttonStyle(.plain)
                        .labelStyle(.iconOnly)
                    }
                }
            }
        }
        .overlay(alignment: .bottomTrailing) {
            if mapViewModel.showingLayersMenu {
                LayersMenu(
                    selection: $mapStyleSelection,
                    isPresented: $mapViewModel.showingLayersMenu
                )
                .padding(.trailing, 16)
                .padding(.bottom, 160)
                .transition(.scale.combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.3), value: mapViewModel.showingLayersMenu)
    }
}
