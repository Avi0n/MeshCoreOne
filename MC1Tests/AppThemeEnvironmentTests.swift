import Testing
import SwiftUI
@testable import MC1

@MainActor
@Suite("AppTheme environment")
struct AppThemeEnvironmentTests {

    @Test("the default appTheme environment value is Theme.default")
    func defaultValueIsDefaultTheme() {
        let values = EnvironmentValues()
        #expect(values.appTheme.id == Theme.default.id)
    }
}
