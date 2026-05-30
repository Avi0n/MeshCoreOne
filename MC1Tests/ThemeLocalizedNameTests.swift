import Testing
@testable import MC1

@Suite("Theme.localizedName")
struct ThemeLocalizedNameTests {

    @Test("every registry theme has a non-empty localized name")
    func everyThemeNamed() {
        for theme in ThemeRegistry.allThemes {
            #expect(theme.localizedName.isEmpty == false)
        }
    }

    @Test("every registry theme resolves to copy distinct from its raw display-name key")
    func everyThemeResolvesToLocalizedCopy() {
        // A theme added to the registry without a `localizedName` case would fall back to the raw
        // dotted key. Asserting resolution differs from `displayNameKey` catches that regression
        // (and the missing case also traps via assertionFailure on this debug test run).
        for theme in ThemeRegistry.allThemes {
            #expect(theme.localizedName != theme.displayNameKey)
        }
    }

    @Test("theme names are distinct across the registry")
    func namesAreDistinct() {
        let names = ThemeRegistry.allThemes.map(\.localizedName)
        #expect(Set(names).count == names.count)
    }

    @Test("default and ember resolve to their expected English copy")
    func knownNames() {
        #expect(Theme.default.localizedName == L10n.Settings.Support.Theme.default)
        #expect(Theme.ember.localizedName == L10n.Settings.Support.Theme.ember)
    }
}
