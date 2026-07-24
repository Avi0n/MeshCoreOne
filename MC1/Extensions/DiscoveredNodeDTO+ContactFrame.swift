import Foundation
import MC1Services

extension DiscoveredNodeDTO {
  func makeContactFrame(lastModified: UInt32 = UInt32(Date().timeIntervalSince1970)) -> ContactFrame {
    ContactFrame(
      publicKey: publicKey,
      type: nodeType,
      typeRawValue: typeRawValue,
      flags: 0,
      outPathLength: outPathLength,
      outPath: outPath,
      name: name,
      lastAdvertTimestamp: lastAdvertTimestamp,
      latitude: latitude,
      longitude: longitude,
      lastModified: lastModified
    )
  }
}
