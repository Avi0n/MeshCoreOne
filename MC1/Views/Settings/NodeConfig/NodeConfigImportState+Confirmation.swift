import Foundation
import MC1Services

extension NodeConfigImportState {
  enum Confirmation {
    case config(Preview)
    case recovery(IdentityImportRecovery, input: Input, privateKey: Data)

    var input: Input {
      switch self {
      case let .config(preview): preview.input
      case let .recovery(_, input, _): input
      }
    }

    var recovery: IdentityImportRecovery? {
      guard case let .recovery(summary, _, _) = self else { return nil }
      return summary
    }
  }
}
