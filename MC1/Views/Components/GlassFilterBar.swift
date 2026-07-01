import SwiftUI

private let pillSpacing: CGFloat = 8
private let barHorizontalPadding: CGFloat = 16
private let barTopPadding: CGFloat = -4
private let barBottomPadding: CGFloat = 8
/// The segmented `Picker` has no intrinsic vertical padding, so the glass pills' negative top
/// inset would clip its top edge. It gets a positive inset matching the bottom for even spacing.
private let segmentedTopPadding: CGFloat = 8
private let regularPillHorizontalPadding: CGFloat = 14
private let regularPillVerticalPadding: CGFloat = 6
private let largePillHorizontalPadding: CGFloat = 18
private let largePillVerticalPadding: CGFloat = 9
private let dimmedOpacity: Double = 0.5
/// Hueless tint marking the selected pill, flipped by color scheme (darker in light, lighter in
/// dark) so the selection holds contrast on every theme canvas without a hue that clashes with one.
private let selectedSegmentTintLight = Color.black.opacity(0.12)
private let selectedSegmentTintDark = Color.white.opacity(0.16)

/// Pinned filter bar that renders as Liquid Glass capsule pills on iOS 26 and
/// falls back to a standard segmented `Picker` on iOS 18.
///
/// Designed to be hosted via `.safeAreaInset(edge: .top)` so list content
/// scrolls behind the glass on iOS 26.
struct GlassFilterBar<Filter: Hashable & CaseIterable & Sendable>: View
  where Filter.AllCases: RandomAccessCollection {
  /// Pill sizing. `regular` is the compact default for the chat/contact filters;
  /// `large` gives the roomier pills used by the node management sheet.
  enum Size {
    case regular
    case large

    var horizontalPadding: CGFloat {
      switch self {
      case .regular: regularPillHorizontalPadding
      case .large: largePillHorizontalPadding
      }
    }

    var verticalPadding: CGFloat {
      switch self {
      case .regular: regularPillVerticalPadding
      case .large: largePillVerticalPadding
      }
    }

    var font: Font {
      switch self {
      case .regular: .subheadline
      case .large: .callout
      }
    }

    var controlSize: ControlSize {
      switch self {
      case .regular: .regular
      case .large: .large
      }
    }
  }

  @Binding var selection: Filter
  let isSearching: Bool
  let pickerLabel: String
  let title: @Sendable (Filter) -> String
  var size: Size = .regular

  @Namespace private var glassNamespace
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @Environment(\.colorScheme) private var colorScheme

  private var selectedSegmentTint: Color {
    colorScheme == .dark ? selectedSegmentTintDark : selectedSegmentTintLight
  }

  var body: some View {
    Group {
      if #available(iOS 26.0, *) {
        glassPills
      } else {
        segmentedFallback
      }
    }
    .opacity(isSearching ? dimmedOpacity : 1.0)
    .disabled(isSearching)
  }

  @available(iOS 26.0, *)
  private var glassPills: some View {
    ViewThatFits(in: .horizontal) {
      pillsRow
      ScrollView(.horizontal, showsIndicators: false) {
        pillsRow
      }
      .scrollClipDisabled()
    }
    .frame(maxWidth: .infinity)
    // Scope the pill morph to the bar so it doesn't leak a transaction into consumer
    // content (a list's row transitions would otherwise animate on every filter tap).
    .animation(reduceMotion ? nil : .smooth, value: selection)
  }

  @available(iOS 26.0, *)
  private var pillsRow: some View {
    GlassEffectContainer(spacing: pillSpacing) {
      HStack(spacing: pillSpacing) {
        ForEach(Filter.allCases, id: \.self) { filter in
          pill(for: filter)
        }
      }
      .padding(.horizontal, barHorizontalPadding)
      .padding(.top, barTopPadding)
      .padding(.bottom, barBottomPadding)
    }
  }

  @available(iOS 26.0, *)
  @ViewBuilder
  private func pill(for filter: Filter) -> some View {
    let isSelected = selection == filter
    Button {
      selection = filter
    } label: {
      Text(title(filter))
        .lineLimit(1)
        .font(size.font)
        .foregroundStyle(.primary)
        .padding(.horizontal, size.horizontalPadding)
        .padding(.vertical, size.verticalPadding)
        .contentShape(.capsule)
    }
    .buttonStyle(.plain)
    .glassEffect(
      isSelected ? .regular.tint(selectedSegmentTint).interactive() : .regular.interactive(),
      in: .capsule
    )
    .glassEffectID(filter, in: glassNamespace)
  }

  private var segmentedFallback: some View {
    Picker(pickerLabel, selection: $selection) {
      ForEach(Filter.allCases, id: \.self) { filter in
        Text(title(filter)).tag(filter)
      }
    }
    .pickerStyle(.segmented)
    .controlSize(size.controlSize)
    .padding(.horizontal, barHorizontalPadding)
    .padding(.top, segmentedTopPadding)
    .padding(.bottom, barBottomPadding)
  }
}

extension View {
  /// Backs the pinned filter header with the themed canvas on iOS 18, where the fallback
  /// segmented `Picker` is transparent and would let scrolling rows show through. iOS 26 glass
  /// pills carry their own material and float over the content, so no backing is applied.
  @ViewBuilder
  func pinnedFilterHeaderBackground(_ theme: Theme) -> some View {
    if #available(iOS 26.0, *) {
      self
    } else {
      background(theme.surfaces?.canvas ?? Color(.systemBackground))
    }
  }
}

private enum FilterBarPreviewFilter: String, CaseIterable {
  case all, unread, dms, channels, rooms

  var label: String {
    switch self {
    case .all: "All"
    case .unread: "Unread"
    case .dms: "DMs"
    case .channels: "Channels"
    case .rooms: "Rooms"
    }
  }
}

#Preview {
  @Previewable @State var selection = FilterBarPreviewFilter.all

  List(0..<20) { row in
    Text("Row \(row)")
  }
  .safeAreaInset(edge: .top, spacing: 0) {
    GlassFilterBar(
      selection: $selection,
      isSearching: false,
      pickerLabel: "View",
      title: { $0.label }
    )
    .frame(maxWidth: .infinity)
  }
}
