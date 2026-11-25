import MC1Services
import SwiftUI
import UIKit

struct RxLogEntryDetailView: View {
  private static let rawPayloadByteSeparator = " "

  let entry: RxLogEntryDTO
  let viewModel: RxLogViewModel
  let labels: RxLogHopLabels

  @Environment(\.dismiss) private var dismiss
  @Environment(\.appState) private var appState

  @State private var pathViewModel = MessagePathViewModel()
  @State private var selectedID: UUID?
  @State private var showFullMap = false
  @State private var rowWidth: CGFloat = 0
  /// This packet's log entries, refreshed only when their IDs change so other
  /// packets arriving in the live log leave the path section alone.
  @State private var arrivalEntries: [RxLogEntryDTO] = []
  @State private var copyCount = 0

  init(entry: RxLogEntryDTO, viewModel: RxLogViewModel, labels: RxLogHopLabels) {
    self.entry = entry
    self.viewModel = viewModel
    self.labels = labels
    _selectedID = State(initialValue: entry.id)
  }

  var body: some View {
    NavigationStack {
      List {
        RxLogEntryHeaderSection(
          entry: displayedEntry,
          framing: framing,
          labels: labels,
          onWidthChange: { rowWidth = $0 }
        )
        RxLogEntryPathSection(
          framing: framing,
          sender: sender,
          arrivals: arrivals,
          traceTargets: entry.traceTargetHashes ?? [],
          selectedID: selectedBinding,
          pathViewModel: pathViewModel,
          previewWidth: rowWidth,
          onShowFullMap: { showFullMap = true }
        )
        .equatable()
        RxLogEntryPacketSection(entry: displayedEntry)
      }
      .messagePathMapPreviews(
        sender: sender,
        arrivals: framing == .traversed ? arrivals : [],
        selectedID: selectedBinding,
        showFullMap: $showFullMap,
        pathViewModel: pathViewModel,
        previewWidth: rowWidth
      )
      .navigationTitle(L10n.Tools.Tools.RxLog.packetDetails)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .primaryAction) {
          copyMenu
        }
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Localizable.Common.done) {
            dismiss()
          }
        }
      }
    }
    .sensoryFeedback(.success, trigger: copyCount)
    .presentationDetents([.large])
    .presentationDragIndicator(.visible)
    .onChange(of: viewModel.arrivals(of: entry).map(\.id), initial: true) {
      arrivalEntries = viewModel.arrivals(of: entry)
    }
    .task {
      await pathViewModel.loadContacts(dataStore: appState.offlineDataStore, radioID: entry.radioID)
    }
  }

  private var framing: RxLogPathFraming {
    RxLogPathFraming(entry: entry)
  }

  private var sender: MessagePathSender {
    MessagePathSender(entry: entry, localPublicKey: appState.connectedDevice?.publicKey)
  }

  private var arrivals: [MessagePathArrival] {
    MessagePathArrivals.assemble(entry: entry, arrivals: arrivalEntries)
  }

  private var selectedBinding: Binding<UUID?> {
    Binding(
      get: { MessagePathArrivals.resolvedSelection(preferred: selectedID, arrivals: arrivals) },
      set: { selectedID = $0 }
    )
  }

  /// The arrival picked in the capsules; its signal, bytes, and time differ per reception.
  private var displayedEntry: RxLogEntryDTO {
    let selected = selectedBinding.wrappedValue
    return arrivalEntries.first { $0.id == selected } ?? entry
  }

  private var copyMenu: some View {
    Menu(L10n.Tools.Tools.RxLog.copy, systemImage: "doc.on.doc") {
      Button(L10n.Tools.Tools.RxLog.copyHash) {
        copy(displayedEntry.packetHash)
      }
      if let path = framing.clipboardPath(for: displayedEntry) {
        Button(L10n.Chats.Chats.Path.copyButton) {
          copy(path)
        }
      }
      Button(L10n.Tools.Tools.RxLog.copyRawPayload) {
        copy(displayedEntry.rawPayload.map { String(format: "%02X", $0) }
          .joined(separator: Self.rawPayloadByteSeparator))
      }
    }
  }

  private func copy(_ text: String) {
    UIPasteboard.general.string = text
    copyCount += 1
  }
}
