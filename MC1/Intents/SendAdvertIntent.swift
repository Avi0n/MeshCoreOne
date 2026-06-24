import AppIntents
import MC1Services

/// Voice/Shortcuts intent to broadcast a self-advertisement from the connected
/// radio so nearby mesh nodes can discover it. The reach picker defaults to
/// zero-hop (direct neighbors) so a bare invocation never floods the mesh; the
/// user can pick flood. Like the toolbar antenna action it optionally refreshes
/// GPS first, but passes `allowLocationPrompt: false` so it never blocks on a
/// permission dialog a background Siri context cannot present. "Sent" is honest:
/// an advert has no delivery ACK, so the radio queuing the command is the
/// terminal success state, the same as a channel broadcast.
struct SendAdvertIntent: AppIntent {
    static let title = LocalizedStringResource("intent.advert.title", table: "Tools")
    static let description = IntentDescription(
        LocalizedStringResource("intent.advert.description", table: "Tools")
    )
    static let openAppWhenRun = false

    /// The advert transmits the radio's live GPS, so it must run only when this
    /// iPhone is unlocked, not merely an authenticated companion device.
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: LocalizedStringResource("intent.advert.param.reach", table: "Tools"), default: .zeroHop)
    var reach: AdvertReach

    @Dependency var bridge: IntentBridge

    static var parameterSummary: some ParameterSummary {
        Summary("Send a \(\.$reach) advert", table: "Tools")
    }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        guard let appState = bridge.appState else { throw IntentError.notConnected }
        do {
            try await appState.sendSelfAdvert(flood: reach.sendsFlood, allowLocationPrompt: false)
        } catch {
            throw Self.mapToIntentError(error)
        }
        return .result(dialog: IntentDialog(stringLiteral: Self.successDialog(for: reach)))
    }

    /// The spoken confirmation, branched on reach. Pure so it is unit-assertable
    /// without the framework's perform machinery.
    static func successDialog(for reach: AdvertReach) -> String {
        switch reach {
        case .zeroHop: L10n.Tools.Intent.Advert.Dialog.sentZeroHop
        case .flood: L10n.Tools.Intent.Advert.Dialog.sentFlood
        }
    }

    /// Maps any error from the send onto the localized `IntentError` surface so
    /// Siri never speaks a raw error. The service layer rewraps only
    /// `MeshCoreError`, so a mid-advert radio drop can surface a raw transport
    /// error here; anything unrecognized maps to the generic advert failure.
    static func mapToIntentError(_ error: Error) -> IntentError {
        switch error {
        case let intentError as IntentError: intentError
        case let advertError as AdvertisementError: mapToIntentError(advertError)
        case let meshError as MeshCoreError: .sessionError(meshError)
        default: .advertFailed
        }
    }

    /// Maps the advert service's errors onto the localized `IntentError` surface.
    /// Typed and exhaustive so an unexpected case can't silently collapse to the
    /// wrong message; `.sendFailed` and `.invalidResponse` share one user line.
    static func mapToIntentError(_ error: AdvertisementError) -> IntentError {
        switch error {
        case .notConnected: .notConnected
        case .sessionError(let meshError): .sessionError(meshError)
        case .sendFailed, .invalidResponse: .advertFailed
        }
    }
}
