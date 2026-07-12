import MC1Services
import SwiftUI

/// List-based view for building trace paths. Hops are added through the shared
/// `AddHopPickerView`, pushed onto the Tools navigation stack via the Add Hop CTA.
struct TracePathListView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Bindable var viewModel: TracePathViewModel

  @Binding var dragHapticTrigger: Int
  @Binding var copyHapticTrigger: Int
  @Binding var showingClearConfirmation: Bool
  @Binding var presentedResult: TraceResult?
  @Binding var showJumpToPath: Bool

  /// Drives the pushed Add Hop picker via `.navigationDestination(item:)`.
  @State private var insertionIntent: AddHopIntent?

  var body: some View {
    List {
      if viewModel.outboundPath.isEmpty {
        emptyStateSection
      } else {
        outboundPathSection
          .themedRowBackground(theme)
        addHopCtaSection
      }
      PathActionsSectionView(
        viewModel: viewModel,
        showingClearConfirmation: $showingClearConfirmation,
        copyHapticTrigger: $copyHapticTrigger
      )
      RunTraceSectionView(
        viewModel: viewModel,
        showJumpToPath: $showJumpToPath
      )

      Color.clear
        .frame(height: 1)
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets())
        .id("bottom")
    }
    .themedCanvas(theme)
    .environment(\.editMode, .constant(.active))
    .addHopPicker(for: $insertionIntent, source: viewModel, inDetailColumn: true)
  }

  // MARK: - Empty state

  @ViewBuilder
  private var emptyStateSection: some View {
    Section {
      ContentUnavailableView {
        Label(
          L10n.Contacts.Contacts.PathEdit.Empty.title,
          systemImage: "antenna.radiowaves.left.and.right.slash"
        )
      } description: {
        Text(L10n.Contacts.Contacts.Trace.List.emptyPath)
      }
      .listRowBackground(Color.clear)
      .listRowInsets(EdgeInsets())
      .listRowSeparator(.hidden)
    }

    Section {
      addHopButton
        .listRowInsets(PathEditMetrics.ctaRowInsets)
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
    }
  }

  // MARK: - Add Hop CTA

  private var addHopCtaSection: some View {
    Section {
      addHopButton
        .listRowInsets(PathEditMetrics.ctaRowInsets)
        .listRowBackground(Color.clear)
    }
  }

  private var addHopButton: some View {
    Button {
      insertionIntent = .append
    } label: {
      PathEditCTALabel(
        title: L10n.Contacts.Contacts.PathEdit.addHop,
        systemImage: "plus.circle.fill"
      )
    }
    .buttonStyle(.borderedProminent)
    .controlSize(.large)
  }

  // MARK: - Outbound Path Section

  private var outboundPathSection: some View {
    Section {
      ForEach(Array(viewModel.outboundPath.enumerated()), id: \.element.id) { index, hop in
        TracePathHopRow(hop: hop, hopNumber: index + 1)
      }
      .onMove { source, destination in
        dragHapticTrigger += 1
        viewModel.moveRepeater(from: source, to: destination)
      }
      .onDelete { indexSet in
        for index in indexSet.sorted().reversed() {
          viewModel.removeRepeater(at: index)
        }
      }
    } header: {
      Text(L10n.Contacts.Contacts.Trace.List.roundTripPath)
    }
  }
}
