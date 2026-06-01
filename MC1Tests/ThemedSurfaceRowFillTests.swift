import Testing
import SwiftUI
@testable import MC1

/// Guards the elevation-aware row fill that keeps themed Settings rows flush with the canvas on
/// iPad (elevated trait) while preserving the distinct card tier on iPhone (base trait). This is
/// logic-only coverage; the rendered result is verified visually on device/simulator.
@Suite("Themed surface row fill")
struct ThemedSurfaceRowFillTests {

    @Test("card theme paints rows with the card tier in a base (iPhone) context")
    func cardThemeBaseUsesCard() {
        let surfaces = Theme.marine.surfaces!
        #expect(surfaces.rowFill(isElevated: false) == surfaces.card)
    }

    @Test("card theme paints rows with the canvas in an elevated (iPad) context")
    func cardThemeElevatedUsesCanvas() {
        let surfaces = Theme.marine.surfaces!
        #expect(surfaces.rowFill(isElevated: true) == surfaces.canvas)
        #expect(surfaces.rowFill(isElevated: true) != surfaces.card)
    }

    @Test("canvas-only theme (Ember) leaves rows on the system tier regardless of elevation")
    func cardlessSurfaceIsAlwaysNil() {
        let surfaces = Theme.ember.surfaces!
        #expect(surfaces.card == nil)
        #expect(surfaces.rowFill(isElevated: false) == nil)
        #expect(surfaces.rowFill(isElevated: true) == nil)
    }

    @Test("default theme has no surfaces, so themed row backgrounds are a no-op")
    func defaultThemeHasNoSurfaces() {
        #expect(Theme.default.surfaces == nil)
    }
}
