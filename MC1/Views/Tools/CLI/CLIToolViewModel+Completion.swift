import Foundation
import OSLog

// MARK: - Ghost Text Completion

extension CLIToolViewModel {
  /// Updates ghost text based on current input.
  /// - Parameter cursorAtEnd: Whether cursor is at end of input. Ghost text only shows when true.
  func updateGhostText(cursorAtEnd: Bool) {
    // No ghost text during password entry
    guard pendingLoginContact == nil else {
      ghostText = ""
      return
    }

    // No ghost text for empty input or cursor not at end
    guard !currentInput.isEmpty, cursorAtEnd else {
      ghostText = ""
      return
    }

    let isLocal = activeSession?.isLocal ?? true
    let suggestions = completionEngine.completions(for: currentInput, isLocal: isLocal)

    guard let first = suggestions.first else {
      ghostText = ""
      return
    }

    // Extract suffix: if input is "hel" and match is "help", ghost is "p"
    // Handle argument completion: "session li" -> first might be "list"
    let parts = currentInput.split(separator: " ", omittingEmptySubsequences: false)
    let lastPart = parts.last.map(String.init) ?? ""

    if first.lowercased().hasPrefix(lastPart.lowercased()) {
      ghostText = String(first.dropFirst(lastPart.count))
    } else {
      ghostText = ""
    }
  }

  /// Accepts the current ghost text, appending it to input.
  func acceptGhostText() {
    guard !ghostText.isEmpty else { return }
    currentInput += ghostText
    ghostText = ""
  }

  /// Handles tab press for completion.
  /// - Returns: Array of suggestions if multiple matches, nil otherwise
  @discardableResult
  func tabComplete() -> [String]? {
    guard pendingLoginContact == nil else { return nil }

    // If already showing suggestions, cycle selection
    if let suggestions = tabSuggestions, !suggestions.isEmpty {
      if let currentIndex = tabSelectionIndex {
        tabSelectionIndex = (currentIndex + 1) % suggestions.count
      } else {
        tabSelectionIndex = 0
      }
      return suggestions
    }

    // Generate new suggestions
    let isLocal = activeSession?.isLocal ?? true
    let suggestions = completionEngine.completions(for: currentInput, isLocal: isLocal)

    guard !suggestions.isEmpty else {
      tabSuggestions = nil
      tabSelectionIndex = nil
      return nil
    }

    if suggestions.count == 1 {
      applyCompletion(suggestions[0])
      return nil
    }

    tabSuggestions = suggestions
    tabSelectionIndex = nil
    return suggestions
  }

  /// Applies the selected suggestion if in selection mode.
  /// - Returns: true if suggestion was applied, false if not in selection mode
  func applySelectedSuggestion() -> Bool {
    guard let suggestions = tabSuggestions,
          let index = tabSelectionIndex,
          index < suggestions.count else {
      return false
    }
    applyCompletion(suggestions[index])
    clearTabState()
    return true
  }

  /// Clears tab completion state (suggestions and selection).
  func clearTabState() {
    tabSuggestions = nil
    tabSelectionIndex = nil
  }

  private func applyCompletion(_ suggestion: String) {
    let parts = currentInput.split(separator: " ", omittingEmptySubsequences: false)

    if parts.count <= 1 {
      currentInput = suggestion + " "
    } else {
      var newParts = parts.dropLast().map(String.init)
      newParts.append(suggestion)
      currentInput = newParts.joined(separator: " ") + " "
    }
    ghostText = ""
  }

  /// Clears ghost text and tab state when switching sessions.
  func clearCompletionState() {
    ghostText = ""
    clearTabState()
  }

  func updateNodeNamesForCompletion() async {
    guard let dataStore, let radioID else { return }

    do {
      let contacts = try await dataStore.fetchContacts(radioID: radioID)
      let names = contacts
        .filter { $0.type == .repeater || $0.type == .room }
        .map(\.name)
      completionEngine.updateNodeNames(names)
    } catch {
      Self.logger.error("Failed to fetch contacts for completion: \(error)")
    }
  }
}
