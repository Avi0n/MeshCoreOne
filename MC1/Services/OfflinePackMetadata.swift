import Foundation

struct OfflinePackMetadata: Codable {
  let name: String
  let createdAt: Date
  var layer: OfflineMapLayer

  init(name: String, createdAt: Date, layer: OfflineMapLayer = .base) {
    self.name = name
    self.createdAt = createdAt
    self.layer = layer
  }

  init(from decoder: any Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    name = try container.decode(String.self, forKey: .name)
    createdAt = try container.decode(Date.self, forKey: .createdAt)
    layer = try container.decodeIfPresent(OfflineMapLayer.self, forKey: .layer) ?? .base
  }
}
