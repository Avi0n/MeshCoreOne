import Foundation
import MC1Services

extension NodeConfigImportState {
  struct Input {
    let id: UUID
    let config: MeshCoreNodeConfig
    var source: NodeConfigImportViewModel.ImportSource?
    var channelsWouldOverwrite = false
  }
}
