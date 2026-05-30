import SwiftUI

extension EnvironmentValues {
    /// The active cosmetic theme. Set once at the `MC1App` scene root from
    /// `AppState.themeService.current`; read by themed surfaces (chat bubble fill,
    /// list/thread backgrounds). Defaults to `Theme.default` so previews and any
    /// subtree that is not under the scene-root injection still resolve a valid theme.
    @Entry var appTheme: Theme = .default
}
