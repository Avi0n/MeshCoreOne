import SwiftUI

extension View {
  /// Paint the receiver's scroll/content background with the theme's canvas color, hiding the
  /// SwiftUI default. No-op for themes without surfaces — preserves system rendering, including
  /// iOS 26 Form Liquid Glass treatments that a manual `Color(.systemGroupedBackground)` would
  /// override.
  func themedCanvas(_ theme: Theme) -> some View {
    modifier(ThemedCanvasModifier(theme: theme))
  }

  /// Paint a `Section`'s rows with the theme's card color. No-op for themes without a card tier
  /// (Default, Ember) — preserves system `.secondarySystemGroupedBackground` rendering.
  /// Apply per Section in `.insetGrouped` lists; use `themedPlainRowBackground` on `.plain` lists
  /// instead. For a `.sidebar`-styled list (the iPad Settings split-view column) pass
  /// `flatten: true`, so rows stay transparent and read flush with the canvas, not as cards.
  func themedRowBackground(_ theme: Theme, flatten: Bool = false) -> some View {
    modifier(ThemedRowBackgroundModifier(theme: theme, flatten: flatten))
  }

  /// Paint a `.plain` list's rows with the theme canvas so the themed background shows through.
  /// Plain rows draw an opaque `systemBackground` by default, which otherwise hides the canvas
  /// painted by `themedCanvas`. No-op for themes without surfaces — preserves system rendering
  /// on the default theme.
  func themedPlainRowBackground(_ theme: Theme) -> some View {
    modifier(ThemedPlainRowBackgroundModifier(theme: theme))
  }

  /// Match the navigation bar and tab bar backgrounds to the theme canvas so chrome blends
  /// with themed content instead of reading as a mismatched neutral material. No-op for themes
  /// without surfaces — preserves the system Liquid Glass bars on the default theme.
  func themedChrome(_ theme: Theme) -> some View {
    modifier(ThemedChromeModifier(theme: theme))
  }
}

private struct ThemedCanvasModifier: ViewModifier {
  let theme: Theme
  func body(content: Content) -> some View {
    if let canvas = theme.surfaces?.canvas {
      content
        .scrollContentBackground(.hidden)
        .background(canvas)
    } else {
      content
    }
  }
}

private struct ThemedRowBackgroundModifier: ViewModifier {
  let theme: Theme
  let flatten: Bool
  func body(content: Content) -> some View {
    if let rowFill = theme.surfaces?.rowFill(flatten: flatten) {
      content.listRowBackground(rowFill)
    } else {
      content
    }
  }
}

private struct ThemedPlainRowBackgroundModifier: ViewModifier {
  let theme: Theme

  func body(content: Content) -> some View {
    if let canvas = theme.surfaces?.canvas {
      content.listRowBackground(canvas)
    } else {
      content
    }
  }
}

private struct ThemedChromeModifier: ViewModifier {
  let theme: Theme

  /// Always applies the same modifier chain, varying only the values. Resolving the surface
  /// branch into computed values instead of a `ViewBuilder` `if`/`else` keeps this view's
  /// structural identity stable across theme changes. Because `themedChrome` wraps the TabView,
  /// a branch flip here would re-identify every tab and reset their NavigationStacks, popping any
  /// pushed screen — which is exactly what switching to or from the surface-less Default theme did.
  func body(content: Content) -> some View {
    content
      .toolbarBackground(barStyle, for: .navigationBar, .tabBar)
      .toolbarBackgroundVisibility(barVisibility, for: .navigationBar, .tabBar)
  }

  private var barStyle: AnyShapeStyle {
    if let canvas = theme.surfaces?.canvas {
      AnyShapeStyle(canvas)
    } else {
      AnyShapeStyle(Material.bar)
    }
  }

  private var barVisibility: Visibility {
    theme.surfaces?.canvas == nil ? .automatic : .visible
  }
}
