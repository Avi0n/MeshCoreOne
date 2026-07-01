import MapLibre
import SwiftUI

/// Dropdown menu for selecting map layers
struct LayersMenu: View {
  @Environment(\.appState) private var appState
  @Binding var selection: MapStyleSelection
  @Binding var isPresented: Bool
  var viewportBounds: MLNCoordinateBounds?

  var body: some View {
    VStack(spacing: 0) {
      ForEach(MapStyleSelection.allCases, id: \.self) { style in
        let isDisabled = !appState.offlineMapService.isNetworkAvailable
          && (style.requiresNetwork
            || !hasOfflineCoverage(for: style))

        Button {
          selection = style
          withAnimation {
            isPresented = false
          }
        } label: {
          HStack {
            Text(style.label)
              .foregroundStyle(isDisabled ? .secondary : .primary)
            Spacer()
            if selection == style {
              Image(systemName: "checkmark")
                .foregroundStyle(.tint)
            }
          }
          .padding(.horizontal, 16)
          .padding(.vertical, 12)
        }
        .disabled(isDisabled)
        .accessibilityHint(isDisabled ? disabledReason(for: style) : "")

        if style != MapStyleSelection.allCases.last {
          Divider()
        }
      }
    }
    .frame(width: 140)
    .liquidGlass(in: .rect(cornerRadius: 12))
    .shadow(color: .black.opacity(0.2), radius: 8, y: 4)
    .accessibilityElement(children: .contain)
    .accessibilityLabel(L10n.Map.Map.Style.accessibilityLabel)
  }

  private func hasOfflineCoverage(for style: MapStyleSelection) -> Bool {
    if let viewportBounds {
      appState.offlineMapService.hasCompletedPack(for: style.offlineMapLayer, overlapping: viewportBounds)
    } else {
      appState.offlineMapService.hasCompletedPack(for: style.offlineMapLayer)
    }
  }

  private func disabledReason(for style: MapStyleSelection) -> String {
    if style.requiresNetwork {
      L10n.Map.Map.Style.requiresNetwork
    } else {
      L10n.Map.Map.Style.noOfflineCoverage
    }
  }
}

#Preview {
  LayersMenu(
    selection: .constant(.standard),
    isPresented: .constant(true)
  )
  .padding()
  .environment(\.appState, AppState())
}
