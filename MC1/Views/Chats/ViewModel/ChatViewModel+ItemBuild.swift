import CoreLocation
import Foundation
import MC1Services
import SwiftUI

extension ChatViewModel {
  // MARK: - Item Build

  /// Assemble `MessageBuildInputs` from current bake and env state.
  func makeBuildInputs(
    for message: MessageDTO,
    previous: MessageDTO?,
    next: MessageDTO?
  ) -> MessageBuildInputs {
    bake.makeBuildInputs(
      for: message,
      previous: previous,
      next: next,
      envInputs: envInputs,
      senderTables: currentSenderTables()
    )
  }
}
