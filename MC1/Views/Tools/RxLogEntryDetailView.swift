import MC1Services
import SwiftUI
import UIKit

struct RxLogEntryDetailView: View {
  private static let rawPayloadByteSeparator = " "

  let entry: RxLogEntryDTO
  let arrivalEntries: [RxLogEntryDTO]
  let labels: RxLogHopLabels

  @Environment(\.dismiss) private var dismiss
  @Environment(\.appState) private var appState

  @State private var pathViewModel = MessagePathViewModel()
  @State private var selectedID: UUID?
  @State private var showFullMap = false
  @State private var rowWidth: CGFloat = 0
  @State private var copyCount = 0

  init(entry: RxLogEntryDTO, arrivalEntries: [RxLogEntryDTO] = [], labels: RxLogHopLabels) {
    self.entry = entry
    self.arrivalEntries = arrivalEntries
    self.labels = labels
    selectedID = entry.id
  }

  var body: some View {
    NavigationStack {
      List {
        Section {
          RxLogEntryHeaderSection(
            entry: displayedEntry,
            framing: framing,
            labels: labels
          )
          .onGeometryChange(for: CGFloat.self) { proxy in
            proxy.size.width
          } action: { width in
            rowWidth = width
          }
        }
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
        copy(displayedEntry.rawPayload.uppercaseHexString(separator: Self.rawPayloadByteSeparator))
      }
    }
  }

  private func copy(_ text: String) {
    UIPasteboard.general.string = text
    copyCount += 1
  }
}
