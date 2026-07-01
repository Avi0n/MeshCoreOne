@testable import MC1
import SwiftUI
import Testing

/// Guards the row fill that emits no background in the iPad Settings sidebar (`flatten: true`), so
/// sidebar rows stay transparent and keep the native `.sidebar` selection highlight, while
/// preserving the distinct card tier everywhere else (`flatten: false`).
/// This is logic-only coverage; the rendered result is verified visually on device/simulator.
@Suite("Themed surface row fill")
struct ThemedSurfaceRowFillTests {
  @Test
  func `card theme paints rows with the card tier in a normal (non-flattened) context`() throws {
    let surfaces = try #require(Theme.marine.surfaces)
    #expect(surfaces.rowFill(flatten: false) == surfaces.card)
  }

  @Test
  func `card theme emits no row fill when flattened, so the iPad Settings sidebar keeps native selection`() throws {
    let surfaces = try #require(Theme.marine.surfaces)
    #expect(surfaces.rowFill(flatten: true) == nil)
  }

  @Test
  func `canvas-only theme (Ember) leaves rows on the system tier regardless of flattening`() throws {
    let surfaces = try #require(Theme.ember.surfaces)
    #expect(surfaces.card == nil)
    #expect(surfaces.rowFill(flatten: false) == nil)
    #expect(surfaces.rowFill(flatten: true) == nil)
  }

  @Test
  func `default theme has no surfaces, so themed row backgrounds are a no-op`() {
    #expect(Theme.default.surfaces == nil)
  }
}
