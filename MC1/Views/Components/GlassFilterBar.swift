import SwiftUI

private let pillSpacing: CGFloat = 8
private let barHorizontalPadding: CGFloat = 16
private let barTopPadding: CGFloat = -4
private let barBottomPadding: CGFloat = 8
private let dimmedOpacity: Double = 0.5

/// Pinned filter bar that renders as Liquid Glass capsule pills on iOS 26 and
/// falls back to a standard segmented `Picker` on iOS 18.
///
/// Designed to be hosted via `.safeAreaInset(edge: .top)` so list content
/// scrolls behind the glass on iOS 26.
struct GlassFilterBar<Filter: Hashable & CaseIterable>: View
where Filter.AllCases: RandomAccessCollection {

    @Binding var selection: Filter
    let isSearching: Bool
    let pickerLabel: String
    let title: (Filter) -> String

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
        Button {
            selection = filter
        } label: {
            Text(title(filter))
                .lineLimit(1)
                .font(.subheadline)
        }
        .controlSize(.small)
        .pillButtonStyle(isSelected: selection == filter)
    }

    private var segmentedFallback: some View {
        Picker(pickerLabel, selection: $selection) {
            ForEach(Filter.allCases, id: \.self) { filter in
                Text(title(filter)).tag(filter)
            }
        }
        .pickerStyle(.segmented)
        .padding(.horizontal, barHorizontalPadding)
        .padding(.top, barTopPadding)
        .padding(.bottom, barBottomPadding)
    }
}

private extension View {
    @ViewBuilder
    func pillButtonStyle(isSelected: Bool) -> some View {
        if isSelected {
            self.liquidGlassProminentButtonStyle()
        } else {
            self.liquidGlassSecondaryButtonStyle()
        }
    }
}
