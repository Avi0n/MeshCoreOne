import Testing
import SwiftUI
@testable import MC1

/// Guards the row fill that flattens themed rows to the canvas in the iPad Settings sidebar
/// (`flatten: true`) while preserving the distinct card tier everywhere else (`flatten: false`).
/// This is logic-only coverage; the rendered result is verified visually on device/simulator.
@Suite("Themed surface row fill")
struct ThemedSurfaceRowFillTests {

    @Test("card theme paints rows with the card tier in a normal (non-flattened) context")
    func cardThemeUsesCardByDefault() {
        let surfaces = Theme.marine.surfaces!
        #expect(surfaces.rowFill(flatten: false) == surfaces.card)
    }

    @Test("card theme paints rows with the canvas when flattened (iPad Settings sidebar)")
    func cardThemeFlattenedUsesCanvas() {
        let surfaces = Theme.marine.surfaces!
        #expect(surfaces.rowFill(flatten: true) == surfaces.canvas)
        #expect(surfaces.rowFill(flatten: true) != surfaces.card)
    }

    @Test("canvas-only theme (Ember) leaves rows on the system tier regardless of flattening")
    func cardlessSurfaceIsAlwaysNil() {
        let surfaces = Theme.ember.surfaces!
        #expect(surfaces.card == nil)
        #expect(surfaces.rowFill(flatten: false) == nil)
        #expect(surfaces.rowFill(flatten: true) == nil)
    }

    @Test("default theme has no surfaces, so themed row backgrounds are a no-op")
    func defaultThemeHasNoSurfaces() {
        #expect(Theme.default.surfaces == nil)
    }
}
