import SwiftUI

struct ContactSavedHistoryLink: View {
  let route: ContactRoute.TelemetryHistory
  let nodesRoute: ((ContactRoute.TelemetryHistory) -> NodesRoute)?

  var body: some View {
    if let nodesRoute {
      NavigationLink(value: nodesRoute(route)) { label }
    } else {
      NavigationLink(value: route) { label }
    }
  }

  private var label: some View {
    Label(L10n.Contacts.Contacts.Detail.savedHistory, systemImage: "clock.arrow.trianglehead.counterclockwise.rotate.90")
      .foregroundStyle(.tint)
  }
}
