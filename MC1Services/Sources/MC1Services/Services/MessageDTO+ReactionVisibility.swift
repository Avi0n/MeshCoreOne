import Foundation

public extension MessageDTO {
  /// A sent outgoing reaction renders as a badge, so the timeline hides
  /// the row. Failed reactions stay visible so the user can retry them.
  func isHiddenOutgoingReaction(isDM: Bool) -> Bool {
    guard direction == .outgoing else { return false }
    let isReaction = isDM
      ? ReactionParser.parseDM(text) != nil
      : ReactionParser.parse(text) != nil
    guard isReaction else { return false }
    return status != .failed
  }
}
