import MC1Services
import SwiftUI

/// Equatable so a live packet with another hash skips this body and its hop
/// resolution. Keep `==` in sync with the value inputs.
struct RxLogEntryPathSection: View, Equatable {
  @Environment(\.appState) private var appState
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  let framing: RxLogPathFraming
  let sender: MessagePathSender
  let arrivals: [MessagePathArrival]
  let traceTargets: [Data]
  @Binding var selectedID: UUID?
  let pathViewModel: MessagePathViewModel
  let previewWidth: CGFloat
  let onShowFullMap: () -> Void

  nonisolated static func == (lhs: Self, rhs: Self) -> Bool {
    MainActor.assumeIsolated {
      lhs.framing == rhs.framing
        && lhs.sender == rhs.sender
        && lhs.arrivals == rhs.arrivals
        && lhs.traceTargets == rhs.traceTargets
        && lhs.selectedID == rhs.selectedID
        && lhs.previewWidth == rhs.previewWidth
        && lhs.pathViewModel === rhs.pathViewModel
    }
  }

  var body: some View {
    switch framing {
    case .traversed:
      pathSection {
        MessagePathArrivalDetail(
          sender: sender,
          arrivals: arrivals,
          selectedID: $selectedID,
          pathViewModel: pathViewModel,
          previewWidth: previewWidth,
          onShowFullMap: onShowFullMap
        )
      }
    case .remaining:
      remainingSections
    case .trace:
      pathSection {
        capsules
        hopRows(traceTargets)
        receiverRow
      }
    }
  }

  @ViewBuilder
  private var remainingSections: some View {
    pathSection {
      capsules
      receiverRow
    }
    if !remainingHashes.isEmpty {
      Section {
        hopRows(remainingHashes)
      } header: {
        Text(L10n.Tools.Tools.RxLog.stillToRelay)
      }
    }
  }

  private var remainingHashes: [Data] {
    selectedArrival?.pathHops.map(\.data) ?? []
  }

  private func pathSection(@ViewBuilder content: () -> some View) -> some View {
    Section {
      content()
    } header: {
      Text(L10n.Tools.Tools.RxLog.pathSection)
    }
  }

  private var selectedArrival: MessagePathArrival? {
    let selected = MessagePathArrivals.resolvedSelection(preferred: selectedID, arrivals: arrivals)
    return arrivals.first { $0.id == selected } ?? arrivals.first
  }

  @ViewBuilder
  private var capsules: some View {
    if arrivals.count > 1 {
      MessagePathArrivalCapsules(
        arrivals: arrivals,
        selectedID: $selectedID,
        reduceMotion: reduceMotion
      )
      .listRowSeparator(.hidden)
    }
  }

  private var receiverRow: some View {
    PathHopRowView(
      hopType: .receiver,
      nodeName: appState.connectedDevice?.nodeName ?? L10n.Chats.Chats.Path.Receiver.you,
      nodeID: nil,
      snr: selectedArrival?.snr,
      rssi: selectedArrival?.rssi
    )
  }

  @ViewBuilder
  private func hopRows(_ hashes: [Data]) -> some View {
    if pathViewModel.isLoading {
      ProgressView()
        .frame(maxWidth: .infinity, alignment: .center)
    } else {
      ForEach(Array(hashes.enumerated()), id: \.offset) { index, hash in
        let resolution = pathViewModel.repeaterResolution(
          for: hash,
          userLocation: appState.bestAvailableLocation
        )
        PathHopRowView(
          hopType: .intermediate(index + 1),
          nodeName: resolution.displayName,
          nodeID: hash.uppercaseHexString(),
          snr: nil,
          matchKind: resolution.matchKind
        )
      }
    }
  }
}
