import SwiftUI

/// Chips summarizing each active advanced filter over the map. Tapping a chip opens the sheet; its ✕ clears that filter.
struct MapActiveFiltersBar: View {
  let host: MapFilterHost
  @Binding var state: MapFilterState
  let onEdit: () -> Void

  var body: some View {
    let dimensions = state.activeAdvancedDimensions(for: host)
    if !dimensions.isEmpty {
      // Size to the chips so empty space beside them keeps passing gestures to the map;
      // scroll only when they overflow (large Dynamic Type, long translations).
      ViewThatFits(in: .horizontal) {
        chipRow(dimensions)
        ScrollView(.horizontal) {
          chipRow(dimensions)
        }
        .scrollIndicators(.hidden)
        .scrollClipDisabled()
      }
    }
  }

  private func chipRow(_ dimensions: [MapAdvancedFilterDimension]) -> some View {
    HStack(spacing: 8) {
      ForEach(dimensions, id: \.self) { dimension in
        chip(for: dimension)
      }
    }
    .padding(.horizontal)
  }

  private func chip(for dimension: MapAdvancedFilterDimension) -> some View {
    let text = state.chipText(for: dimension)
    return HStack(spacing: 6) {
      Button(action: onEdit) {
        Label(text, systemImage: dimension.systemImage)
          .labelStyle(.titleAndIcon)
      }
      .accessibilityHint(L10n.Map.Map.Filters.editHint)

      Button {
        state.clear(dimension)
      } label: {
        Image(systemName: "xmark.circle.fill")
          .foregroundStyle(.secondary)
      }
      .accessibilityLabel(L10n.Map.Map.Filters.remove(text))
    }
    .buttonStyle(.plain)
    .font(.subheadline.weight(.medium))
    .padding(.leading, 12)
    .padding(.trailing, 8)
    .padding(.vertical, 8)
    .liquidGlass(in: .capsule)
    .shadow(color: .black.opacity(0.15), radius: 4, y: 2)
  }
}

private extension MapAdvancedFilterDimension {
  var systemImage: String {
    switch self {
    case .lastHeard: "clock"
    case .hops: "point.3.connected.trianglepath.dotted"
    }
  }
}

#Preview {
  @Previewable @State var state = MapFilterState(
    lastHeard: .within(MapDuration(2, .hours)),
    hops: .direct
  )
  MapActiveFiltersBar(host: .mainMap, state: $state, onEdit: {})
    .frame(maxHeight: .infinity, alignment: .top)
    .background(.gray.opacity(0.3))
}
