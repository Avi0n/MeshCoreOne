import Foundation
import MC1Services

extension NodeConfigImportState {
  struct Application {
    let id = UUID()
    let confirmation: Confirmation
    var progress: ImportProgress?
  }
}
