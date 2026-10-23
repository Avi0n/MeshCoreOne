import Foundation

/// Focus flag and first-appear bit for one `CLITerminalView` identity.
struct CLIKeyboardFocus: Equatable {
  var isFocused = false
  private(set) var hasAppeared = false
  private var isProgrammaticBegin = false

  mutating func onAppear() {
    if !hasAppeared {
      isFocused = true
    }
    hasAppeared = true
  }

  mutating func onTap() {
    isFocused = true
  }

  mutating func clearFocus() {
    isFocused = false
  }

  mutating func noteBeginEditing() {
    if !isProgrammaticBegin {
      isFocused = true
    }
  }

  mutating func beginProgrammaticEditing() {
    isProgrammaticBegin = true
  }

  mutating func endProgrammaticEditing() {
    isProgrammaticBegin = false
  }
}
