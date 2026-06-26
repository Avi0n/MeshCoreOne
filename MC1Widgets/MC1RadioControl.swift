import AppIntents
import SwiftUI
import WidgetKit

/// A Control Center / Action Button button that opens MeshCore One. A control
/// runs in the widget process with no radio access, so this only foregrounds
/// the app; the live status glance and any send happen once the app is up.
struct MC1RadioControl: ControlWidget {
    static let kind = "io.pocketmesh.app.RadioControl"

    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: Self.kind) {
            ControlWidgetButton(action: OpenRadioStatusIntent()) {
                Label("Open MeshCore One", systemImage: "antenna.radiowaves.left.and.right")
            }
        }
        .displayName("MeshCore One")
        .description("Open MeshCore One")
    }
}
