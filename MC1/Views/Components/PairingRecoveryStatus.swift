import SwiftUI

struct PairingRecoveryStatus: View {
  var body: some View {
    HStack {
      ProgressView().controlSize(.small)
      Text(L10n.Settings.DeviceSelection.rePairing)
    }
    .accessibilityElement(children: .ignore)
    .accessibilityLabel(L10n.Settings.DeviceSelection.rePairing)
  }
}
