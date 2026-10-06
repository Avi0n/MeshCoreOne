import Foundation
@testable import MC1
import Testing

@Suite("CLI keyboard focus")
struct CLIKeyboardFocusTests {
  @Test
  func `first appear sets focus and later appears do not`() {
    var focus = CLIKeyboardFocus()
    focus.onAppear()
    #expect(focus.isFocused)
    #expect(focus.hasAppeared)

    focus.clearFocus()
    focus.onAppear()
    #expect(focus.isFocused == false)
    #expect(focus.hasAppeared)
  }

  @Test
  func `tap after clearFocus sets focus`() {
    var focus = CLIKeyboardFocus()
    focus.onAppear()
    focus.clearFocus()
    focus.onTap()
    #expect(focus.isFocused)
    #expect(focus.hasAppeared)
  }

  @Test
  func `external begin sets focus and programmatic begin does not`() {
    var focus = CLIKeyboardFocus()
    focus.onAppear()
    focus.clearFocus()

    focus.noteBeginEditing()
    #expect(focus.isFocused)
    #expect(focus.hasAppeared)

    focus.clearFocus()
    focus.beginProgrammaticEditing()
    focus.noteBeginEditing()
    focus.endProgrammaticEditing()
    #expect(focus.isFocused == false)
    #expect(focus.hasAppeared)
  }
}
