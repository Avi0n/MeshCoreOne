import Foundation

/// Snapshot of user preferences stored in UserDefaults, used for backup/restore.
/// Each property is optional — `nil` means the key was not set at export time.
public struct BackupUserDefaults: Codable, Sendable, Equatable {
  // MARK: - App preferences

  public var hasCompletedOnboarding: Bool?
  public var liveActivityEnabled: Bool?
  public var mapStyleSelection: String?
  public var selectedThemeID: String?
  public var appColorSchemePreference: String?
  public var mapShowLabels: Bool?
  public var mapNorthLocked: Bool?
  public var showDiscoveredNodesOnMap: Bool?
  public var mapColorSchemePreference: String?
  public var replyWithQuote: Bool?
  public var showInlineImages: Bool?
  public var autoPlayGIFs: Bool?
  public var showIncomingPath: Bool?
  public var showIncomingHopCount: Bool?
  public var showIncomingRegion: Bool?
  public var showIncomingSendTime: Bool?
  public var autoDeleteStaleNodesDays: Int?
  public var discoverySortOrder: String?
  public var nodesSortOrder: String?
  public var tracePathViewMode: String?
  public var linkPreviewsEnabled: Bool?
  public var linkPreviewsAutoResolveDM: Bool?
  public var linkPreviewsAutoResolveChannels: Bool?
  public var showMapPreviewThumbnails: Bool?
  public var frequentEmojis: [String]?
  public var recentEmojis: [String]?
  public var hasSeenRepeaterDragHint: Bool?
  public var regionSelection: RegionSelection?

  // MARK: - Notification preferences

  public var notifyContactMessages: Bool?
  public var notifyChannelMessages: Bool?
  public var notifyRoomMessages: Bool?
  public var notifyNewContacts: Bool?
  public var notifyNewContactsContact: Bool?
  public var notifyNewContactsRepeater: Bool?
  public var notifyNewContactsRoom: Bool?
  public var notifyReactions: Bool?
  public var notificationSoundEnabled: Bool?
  public var notificationBadgeEnabled: Bool?
  public var notifyLowBattery: Bool?

  public init() {}

  // MARK: - UserDefaults keys for special-cased (non-Bool/String) properties

  private static let autoDeleteStaleNodesDaysKey = AppStorageKey.autoDeleteStaleNodesDays.rawValue
  private static let frequentEmojisKey = AppStorageKey.frequentEmojis.rawValue
  private static let recentReactionEmojisKey = AppStorageKey.recentReactionEmojis.rawValue
  /// Public so `AppState` (and tests) can persist via the same key without a duplicated literal.
  public static let regionSelectionKey = "userPrefs.region"

  /// Property names handled by hand-rolled branches in `snapshot`/`restore`
  /// rather than the bool/string mappings. Internal solely for testability —
  /// do not consume from non-test code.
  static let specialCasedPropertyNames: Set<String> = [
    "autoDeleteStaleNodesDays",
    "frequentEmojis",
    "recentEmojis",
    "regionSelection"
  ]

  // MARK: - Region selection persistence

  /// Single source of truth for the encoder/decoder used by `regionSelection`.
  /// Both `BackupUserDefaults.snapshot`/`restore` and `AppState`'s live persistence
  /// path go through these helpers, so the on-disk format cannot drift.
  public static func loadRegionSelection(from defaults: UserDefaults = .standard) -> RegionSelection? {
    guard let data = defaults.data(forKey: regionSelectionKey) else { return nil }
    return try? JSONDecoder().decode(RegionSelection.self, from: data)
  }

  public static func persistRegionSelection(
    _ region: RegionSelection?,
    to defaults: UserDefaults = .standard
  ) {
    if let region, let data = try? JSONEncoder().encode(region) {
      defaults.set(data, forKey: regionSelectionKey)
    } else {
      defaults.removeObject(forKey: regionSelectionKey)
    }
  }

  // MARK: - UserDefaults key mapping

  /// Mapping from struct keyPaths to their UserDefaults key strings.
  /// `frequentEmojis` is stored as encoded `Data` in the app (via @AppStorage),
  /// but we export/import the decoded `[String]` array directly.
  ///
  /// Marked `nonisolated(unsafe)` because `WritableKeyPath` is not `Sendable`.
  /// Safe here: the array is a `let` initialised once at module load and only
  /// read afterwards (never mutated); no cross-actor write race can occur.
  private nonisolated(unsafe) static let boolMappings: [(WritableKeyPath<BackupUserDefaults, Bool?>, String)] = [
    (\.hasCompletedOnboarding, AppStorageKey.hasCompletedOnboarding.rawValue),
    (\.liveActivityEnabled, AppStorageKey.liveActivityEnabled.rawValue),
    (\.mapShowLabels, AppStorageKey.mapShowLabels.rawValue),
    (\.mapNorthLocked, AppStorageKey.mapNorthLocked.rawValue),
    (\.showDiscoveredNodesOnMap, AppStorageKey.showDiscoveredNodesOnMap.rawValue),
    (\.replyWithQuote, AppStorageKey.replyWithQuote.rawValue),
    (\.showInlineImages, AppStorageKey.showInlineImages.rawValue),
    (\.autoPlayGIFs, AppStorageKey.autoPlayGIFs.rawValue),
    (\.showIncomingPath, AppStorageKey.showIncomingPath.rawValue),
    (\.showIncomingHopCount, AppStorageKey.showIncomingHopCount.rawValue),
    (\.showIncomingRegion, AppStorageKey.showIncomingRegion.rawValue),
    (\.showIncomingSendTime, AppStorageKey.showIncomingSendTime.rawValue),
    (\.linkPreviewsEnabled, AppStorageKey.linkPreviewsEnabled.rawValue),
    (\.linkPreviewsAutoResolveDM, AppStorageKey.linkPreviewsAutoResolveDM.rawValue),
    (\.linkPreviewsAutoResolveChannels, AppStorageKey.linkPreviewsAutoResolveChannels.rawValue),
    (\.showMapPreviewThumbnails, AppStorageKey.showMapPreviewThumbnails.rawValue),
    (\.hasSeenRepeaterDragHint, AppStorageKey.hasSeenRepeaterDragHint.rawValue),
    (\.notifyContactMessages, AppStorageKey.notifyContactMessages.rawValue),
    (\.notifyChannelMessages, AppStorageKey.notifyChannelMessages.rawValue),
    (\.notifyRoomMessages, AppStorageKey.notifyRoomMessages.rawValue),
    (\.notifyNewContacts, AppStorageKey.notifyNewContacts.rawValue),
    (\.notifyNewContactsContact, AppStorageKey.notifyNewContactsContact.rawValue),
    (\.notifyNewContactsRepeater, AppStorageKey.notifyNewContactsRepeater.rawValue),
    (\.notifyNewContactsRoom, AppStorageKey.notifyNewContactsRoom.rawValue),
    (\.notifyReactions, AppStorageKey.notifyReactions.rawValue),
    (\.notificationSoundEnabled, AppStorageKey.notificationSoundEnabled.rawValue),
    (\.notificationBadgeEnabled, AppStorageKey.notificationBadgeEnabled.rawValue),
    (\.notifyLowBattery, AppStorageKey.notifyLowBattery.rawValue),
  ]

  /// Key strings used by `boolMappings`. Internal solely for testability —
  /// do not consume from non-test code.
  static var boolMappingKeys: Set<String> {
    Set(boolMappings.map(\.1))
  }

  /// See `boolMappings` for the `nonisolated(unsafe)` rationale.
  private nonisolated(unsafe) static let stringMappings: [(WritableKeyPath<BackupUserDefaults, String?>, String)] = [
    (\.mapStyleSelection, AppStorageKey.mapStyleSelection.rawValue),
    (\.mapColorSchemePreference, AppStorageKey.mapColorSchemePreference.rawValue),
    (\.discoverySortOrder, AppStorageKey.discoverySortOrder.rawValue),
    (\.nodesSortOrder, AppStorageKey.nodesSortOrder.rawValue),
    (\.tracePathViewMode, AppStorageKey.tracePathViewMode.rawValue),
    (\.selectedThemeID, PersistenceKeys.selectedThemeID),
    (\.appColorSchemePreference, PersistenceKeys.appColorSchemePreference),
  ]

  /// Key strings used by `stringMappings`. Internal solely for testability —
  /// do not consume from non-test code.
  static var stringMappingKeys: Set<String> {
    Set(stringMappings.map(\.1))
  }

  // MARK: - Read from UserDefaults

  /// Creates a snapshot by reading all known keys from UserDefaults.
  /// - Parameter defaults: The UserDefaults instance to read from.
  public static func snapshot(from defaults: UserDefaults = .standard) -> BackupUserDefaults {
    var result = BackupUserDefaults()

    for (keyPath, key) in boolMappings {
      if defaults.object(forKey: key) != nil {
        result[keyPath: keyPath] = defaults.bool(forKey: key)
      }
    }

    for (keyPath, key) in stringMappings {
      if defaults.object(forKey: key) != nil {
        result[keyPath: keyPath] = defaults.string(forKey: key)
      }
    }

    if defaults.object(forKey: Self.autoDeleteStaleNodesDaysKey) != nil {
      result.autoDeleteStaleNodesDays = defaults.integer(forKey: Self.autoDeleteStaleNodesDaysKey)
    }

    // frequentEmojis is stored as JSON-encoded [String] via @AppStorage Data binding
    if let data = defaults.data(forKey: Self.frequentEmojisKey),
       let decoded = try? JSONDecoder().decode([String].self, from: data) {
      result.frequentEmojis = decoded
    }

    result.recentEmojis = defaults.stringArray(forKey: Self.recentReactionEmojisKey)

    result.regionSelection = Self.loadRegionSelection(from: defaults)

    return result
  }

  // MARK: - Write to UserDefaults (write-if-missing)

  /// Restores preferences to UserDefaults, only writing keys that are not already set.
  /// - Parameter defaults: The UserDefaults instance to write to.
  /// - Returns: Keys that were newly set, in insertion order. Callers can undo a
  ///   partial restore by passing this list to `removeKeys(_:from:)`.
  @discardableResult
  public func restore(to defaults: UserDefaults = .standard) -> [String] {
    var setKeys: [String] = []

    for (keyPath, key) in Self.boolMappings {
      if let value = self[keyPath: keyPath], defaults.object(forKey: key) == nil {
        defaults.set(value, forKey: key)
        setKeys.append(key)
      }
    }

    for (keyPath, key) in Self.stringMappings {
      if let value = self[keyPath: keyPath], defaults.object(forKey: key) == nil {
        defaults.set(value, forKey: key)
        setKeys.append(key)
      }
    }

    if let value = autoDeleteStaleNodesDays,
       defaults.object(forKey: Self.autoDeleteStaleNodesDaysKey) == nil {
      defaults.set(value, forKey: Self.autoDeleteStaleNodesDaysKey)
      setKeys.append(Self.autoDeleteStaleNodesDaysKey)
    }

    if let emojis = frequentEmojis, defaults.object(forKey: Self.frequentEmojisKey) == nil {
      if let data = try? JSONEncoder().encode(emojis) {
        defaults.set(data, forKey: Self.frequentEmojisKey)
        setKeys.append(Self.frequentEmojisKey)
      }
    }

    if let emojis = recentEmojis, defaults.object(forKey: Self.recentReactionEmojisKey) == nil {
      defaults.set(emojis, forKey: Self.recentReactionEmojisKey)
      setKeys.append(Self.recentReactionEmojisKey)
    }

    if let region = regionSelection,
       defaults.object(forKey: Self.regionSelectionKey) == nil {
      Self.persistRegionSelection(region, to: defaults)
      setKeys.append(Self.regionSelectionKey)
    }

    return setKeys
  }

  /// Undoes a `restore(to:)` partial write by removing the specified keys.
  public static func removeKeys(_ keys: [String], from defaults: UserDefaults) {
    for key in keys {
      defaults.removeObject(forKey: key)
    }
  }
}
