import MC1Services
import SwiftUI

struct RxLogEntryPathSection: View {
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

  var body: some View {
    switch framing {
    case .traversed:
      pathSection {
        capsules(showsHopCount: true)
        MessagePathPreviewRow(
          sender: sender,
          arrival: selectedArrival,
          pathViewModel: pathViewModel,
          previewWidth: previewWidth,
          onShowFullMap: onShowFullMap
        )
        .listRowSeparator(.hidden)
        if let selectedArrival {
          MessagePathContent(
            sender: sender,
            arrival: selectedArrival,
            viewModel: pathViewModel,
            receiverName: receiverName,
            userLocation: appState.bestAvailableLocation,
            showsCopyBar: false
          )
        }
      }
    case .remaining:
      remainingSections
    case .trace:
      pathSection {
        capsules(showsHopCount: false)
        hopRows(traceTargets)
        receiverRow
      }
    }
  }

  @ViewBuilder
  private var remainingSections: some View {
    pathSection {
      capsules(showsHopCount: false)
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

  private var receiverName: String {
    appState.connectedDevice?.nodeName ?? L10n.Chats.Chats.Path.Receiver.you
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
    MessagePathArrivals.selectedArrival(preferred: selectedID, in: arrivals)
  }

  @ViewBuilder
  private func capsules(showsHopCount: Bool) -> some View {
    if arrivals.count > 1 {
      MessagePathArrivalCapsules(
        arrivals: arrivals,
        selectedID: $selectedID,
        reduceMotion: reduceMotion,
        showsHopCount: showsHopCount
      )
      .listRowSeparator(.hidden)
    }
  }

  private var receiverRow: some View {
    PathHopRowView(
      hopType: .receiver,
      nodeName: receiverName,
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
      PathRepeaterHopRows(
        hashes: hashes,
        viewModel: pathViewModel,
        userLocation: appState.bestAvailableLocation
      )
    }
  }
}
