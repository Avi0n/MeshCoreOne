import Foundation
import MC1Services

extension NodeConfigImportState {
  struct Preview {
    let id = UUID()
    var input: Input
    let source: NodeConfigImportViewModel.ImportSource
    let sections: ConfigSections
  }
}
