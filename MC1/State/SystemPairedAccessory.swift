import Foundation

/// An AccessorySetupKit accessory that has no MeshCore One `Device` row.
struct SystemPairedAccessory: Identifiable, Equatable, Hashable {
  let id: UUID
  let name: String
}
