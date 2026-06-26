import AppIntents

/// Foregrounds MeshCore One from a Control Center / Action Button control.
///
/// This type compiles into both the app and the widget extension, but a
/// control's `perform()` runs in the widget process where there is no live
/// session, no CoreBluetooth, and no persistence store. So it does the one
/// thing that is safe from there: ask the system to bring the app to the
/// foreground. It performs no radio send or read. The authentication gate is a
/// belt-and-suspenders UX choice; the real send gate lives on
/// `SendMessageIntent` in the app process.
struct OpenRadioStatusIntent: AppIntent {
    static let title = LocalizedStringResource("Open MeshCore One")
    static let openAppWhenRun = true
    static let isDiscoverable = false

    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

    func perform() async throws -> some IntentResult {
        .result()
    }
}
