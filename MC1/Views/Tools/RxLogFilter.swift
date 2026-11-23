import Foundation
import MC1Services

/// Persisted RX Log filter settings, stored as one JSON string under
/// `AppStorageKey.rxLogFilter` and carried through app backup.
struct RxLogFilter: Sendable, Equatable, Codable {
  // Raw values are the backup JSON. A case rename must not change them.
  // swiftformat:disable redundantRawValues
  // swiftlint:disable redundant_string_enum_value
  enum RouteFilter: String, CaseIterable, Sendable, Codable {
    case all = "all"
    case floodOnly = "floodOnly"
    case directOnly = "directOnly"

    var displayName: String {
      switch self {
      case .all: L10n.Tools.Tools.RxLog.Filter.all
      case .floodOnly: L10n.Tools.Tools.RxLog.Filter.floodOnly
      case .directOnly: L10n.Tools.Tools.RxLog.Filter.directOnly
      }
    }

    func matches(_ entry: RxLogEntryDTO) -> Bool {
      switch self {
      case .all: true
      case .floodOnly: entry.isFlood
      case .directOnly: !entry.isFlood
      }
    }
  }

  enum DecryptFilter: String, CaseIterable, Sendable, Codable {
    case all = "all"
    case decrypted = "decrypted"
    case failed = "failed"

    var displayName: String {
      switch self {
      case .all: L10n.Tools.Tools.RxLog.Filter.all
      case .decrypted: L10n.Tools.Tools.RxLog.Filter.decrypted
      case .failed: L10n.Tools.Tools.RxLog.Filter.failed
      }
    }

    func matches(_ entry: RxLogEntryDTO) -> Bool {
      switch self {
      case .all:
        true
      case .decrypted:
        entry.decryptStatus == .success
      case .failed:
        entry.decryptStatus == .hmacFailed
          || entry.decryptStatus == .decryptFailed
          || entry.decryptStatus == .noMatchingKey
          || entry.decryptStatus == .dmNoMatchingKey
      }
    }
  }

  // swiftformat:enable redundantRawValues
  // swiftlint:enable redundant_string_enum_value

  var route: RouteFilter
  var decrypt: DecryptFilter
  /// Stored as wire values so an unknown type from a newer build or a backup
  /// decodes instead of failing the whole filter.
  var hiddenPayloadTypeRawValues: Set<UInt8>
  var groupsArrivals: Bool

  init(
    route: RouteFilter = .all,
    decrypt: DecryptFilter = .all,
    hiddenPayloadTypeRawValues: Set<UInt8> = [],
    groupsArrivals: Bool = false
  ) {
    self.route = route
    self.decrypt = decrypt
    self.hiddenPayloadTypeRawValues = hiddenPayloadTypeRawValues
    self.groupsArrivals = groupsArrivals
  }

  /// Every key is optional so values written by older builds or backups decode
  /// with defaults instead of being treated as corrupt and reset.
  init(from decoder: Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    let defaults = RxLogFilter()
    self.init(
      route: (try? container.decodeIfPresent(RouteFilter.self, forKey: .route)) ?? defaults.route,
      decrypt: (try? container.decodeIfPresent(DecryptFilter.self, forKey: .decrypt)) ?? defaults.decrypt,
      hiddenPayloadTypeRawValues: (try? container.decodeIfPresent(Set<UInt8>.self, forKey: .hiddenPayloadTypeRawValues))
        ?? defaults.hiddenPayloadTypeRawValues,
      groupsArrivals: (try? container.decodeIfPresent(Bool.self, forKey: .groupsArrivals)) ?? defaults.groupsArrivals
    )
  }

  /// True when any filter hides packets. Grouping only collapses arrivals, so it does not count.
  var hidesPackets: Bool {
    route != .all || decrypt != .all || !hiddenPayloadTypeRawValues.isEmpty
  }

  func isHidden(_ type: PayloadType) -> Bool {
    hiddenPayloadTypeRawValues.contains(type.rawValue)
  }

  /// Known types only. Unrecognized raw values stay stored for a newer build.
  var hiddenPayloadTypes: Set<PayloadType> {
    Set(hiddenPayloadTypeRawValues.compactMap(PayloadType.init(rawValue:)))
  }

  mutating func setHidden(_ hidden: Bool, for type: PayloadType) {
    if hidden {
      hiddenPayloadTypeRawValues.insert(type.rawValue)
    } else {
      hiddenPayloadTypeRawValues.remove(type.rawValue)
    }
  }

  func matches(_ entry: RxLogEntryDTO) -> Bool {
    route.matches(entry)
      && decrypt.matches(entry)
      && !hiddenPayloadTypeRawValues.contains(entry.payloadType.rawValue)
  }

  // MARK: Storage

  var storageString: String {
    guard let data = try? JSONEncoder().encode(self),
          let string = String(data: data, encoding: .utf8) else {
      return ""
    }
    return string
  }

  init?(storageString: String) {
    guard !storageString.isEmpty,
          let data = storageString.data(using: .utf8),
          let decoded = try? JSONDecoder().decode(RxLogFilter.self, from: data) else {
      return nil
    }
    self = decoded
  }
}
