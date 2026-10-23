import MC1Services
import SwiftUI

struct NodeActionRows: View {
  let contact: ContactDTO
  let pingLabel: String
  let isPinging: Bool
  let pingResult: PingResult?
  let connectionState: DeviceConnectionState
  let onShowTelemetry: () -> Void
  let onShowAdminAccess: () -> Void
  let onPing: () -> Void
  let historyRoute: ((ContactRoute.TelemetryHistory) -> NodesRoute)?
  let onRowPosition: ((ContactDetailRowPosition.Measurement) -> Void)?

  var body: some View {
    Button(action: onShowTelemetry) {
      Label(L10n.Contacts.Contacts.Detail.telemetry, systemImage: "chart.line.uptrend.xyaxis")
    }
    .radioDisabled(for: connectionState)
    .modifier(ContactDetailRowPosition(section: .actions, onPosition: onRowPosition))

    ContactSavedHistoryLink(
      route: ContactRoute.TelemetryHistory(publicKey: contact.publicKey, radioID: contact.radioID),
      nodesRoute: historyRoute
    )

    Button(action: onShowAdminAccess) {
      Label(L10n.Contacts.Contacts.Detail.management, systemImage: "gearshape.2")
    }
    .radioDisabled(for: connectionState)

    Button(action: onPing) {
      HStack {
        Label(pingLabel, systemImage: "wave.3.right")
        if isPinging {
          Spacer()
          ProgressView()
        }
      }
    }
    .disabled(isPinging)
    .radioDisabled(for: connectionState)

    if let result = pingResult {
      PingResultRow(result: result)
    }
  }
}
