import CoreLocation
import MC1Services
import SwiftUI

struct PathRepeaterHopRows: View {
  let hashes: [Data]
  let viewModel: MessagePathViewModel
  let userLocation: CLLocation?

  var body: some View {
    ForEach(Array(hashes.enumerated()), id: \.offset) { index, hash in
      let resolution = viewModel.repeaterResolution(
        for: hash,
        userLocation: userLocation
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
