import Foundation
import MC1Services

enum NodeConfigImportState {
  case selectingFile
  case preview(Input)
  case recovery(IdentityImportRecovery, input: Input?)
  case preparing(Preview)
  case confirming(Confirmation)
  case applying(Application)
  case completed(Application)
  case failed(Application)
  case interrupted(Application, IdentityImportRecovery)

  var input: Input? {
    switch self {
    case .selectingFile: nil
    case let .preview(input), let .recovery(_, input?): input
    case .recovery(_, nil): nil
    case let .preparing(preview): preview.input
    case let .confirming(confirmation): confirmation.input
    case let .applying(application), let .completed(application), let .failed(application), let .interrupted(application, _):
      application.confirmation.input
    }
  }

  var recovery: IdentityImportRecovery? {
    switch self {
    case let .recovery(summary, _), let .interrupted(_, summary): summary
    case let .confirming(confirmation): confirmation.recovery
    case let .applying(application), let .completed(application), let .failed(application):
      application.confirmation.recovery
    case .selectingFile, .preview, .preparing: nil
    }
  }

  var application: Application? {
    switch self {
    case let .applying(application), let .completed(application), let .failed(application), let .interrupted(application, _):
      application
    default: nil
    }
  }

  var editable: Self {
    if let recovery { return .recovery(recovery, input: input) }
    if let input { return .preview(input) }
    return .selectingFile
  }
}
