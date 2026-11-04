import Foundation

// swiftformat:disable redundantRawValues
// swiftlint:disable redundant_string_enum_value

/// The installation-wide snapshot used by both receive and Settings.
public struct TextRulesPreference: Sendable, Codable, Equatable {
  public enum Kind: String, CaseIterable, Sendable, Codable {
    case publicChannel = "publicChannel"
    case hashtagChannel = "hashtagChannel"
    case privateChannel = "privateChannel"
    case directMessage = "directMessage"
    case room = "room"
  }

  public struct Key: Hashable, Sendable, Codable {
    public let radioID: UUID
    public let conversationID: UUID

    public init(radioID: UUID, conversationID: UUID) {
      self.radioID = radioID
      self.conversationID = conversationID
    }
  }

  public struct Conversation: Identifiable, Sendable, Equatable {
    public var id: Key {
      key
    }

    public let key: Key
    public let kind: Kind
    public let name: String
    public let radioName: String
    public let override: Override

    public init(key: Key, kind: Kind, name: String, radioName: String, override: Override) {
      self.key = key
      self.kind = kind
      self.name = name
      self.radioName = radioName
      self.override = override
    }
  }

  public enum Selection: Sendable, Codable, Equatable {
    case off
    case all
    case fixed(Set<Key>)

    private enum CodingKeys: String, CodingKey {
      case off = "off"
      case all = "all"
      case fixed = "fixed"
    }

    public var isOn: Bool {
      self != .off
    }

    public func includes(_ key: Key) -> Bool {
      switch self {
      case .off: false
      case .all: true
      case let .fixed(keys): keys.contains(key)
      }
    }
  }

  public enum Override: String, CaseIterable, Sendable, Codable {
    case inherit = "inherit"
    case on = "on"
    case off = "off"
  }

  public struct Effective: Sendable, Equatable {
    public enum Source: Sendable, Equatable {
      case type
      case fixedExclusion
      case explicit
    }

    public let isOn: Bool
    public let source: Source
  }

  public struct Rule: Identifiable, Sendable, Codable, Equatable {
    public enum LookIn: String, CaseIterable, Sendable, Codable {
      case message = "message"
      case sender = "sender"
      case messageOrSender = "messageOrSender"
    }

    public enum Match: String, CaseIterable, Sendable, Codable {
      case contains = "contains"
      case wholeField = "wholeField"
    }

    public let id: UUID
    public let phrase: String
    public let lookIn: LookIn
    public let match: Match

    public var isValid: Bool {
      !phrase.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    public init(id: UUID = UUID(), phrase: String, lookIn: LookIn, match: Match) {
      self.id = id
      self.phrase = phrase
      self.lookIn = lookIn
      self.match = match
    }
  }

  public struct RuleMatch: Sendable, Codable, Equatable {
    public struct Highlight: Sendable, Codable, Equatable {
      let location: Int
      let length: Int

      init(_ range: NSRange) {
        location = range.location
        length = range.length
      }

      public func range(in text: String) -> Range<String.Index>? {
        Range(NSRange(location: location, length: length), in: text)
      }
    }

    public let rule: Rule
    public let bodyHighlights: [Highlight]
    public let senderHighlights: [Highlight]
  }

  private static let comparisonLocale = Locale(identifier: "en_US_POSIX")

  public internal(set) var revision = UUID()
  public private(set) var rules: [Rule] = []
  private var selections: [Kind: Selection] = [
    .publicChannel: .all,
    .hashtagChannel: .all,
    .privateChannel: .off,
    .directMessage: .off,
    .room: .off
  ]

  public init() {}

  public mutating func append(_ rule: Rule) {
    rules.append(rule)
  }

  public mutating func removeRule(id: UUID) {
    rules.removeAll { $0.id == id }
  }

  public mutating func setSelection(_ selection: Selection, for kind: Kind) {
    selections[kind] = selection
  }

  public func selection(for kind: Kind) -> Selection {
    selections[kind] ?? .off
  }

  public func effective(for kind: Kind, key: Key?, override: Override) -> Effective {
    switch override {
    case .on:
      Effective(isOn: true, source: .explicit)
    case .off:
      Effective(isOn: false, source: .explicit)
    case .inherit:
      switch selection(for: kind) {
      case .off:
        Effective(isOn: false, source: .type)
      case .all:
        Effective(isOn: true, source: .type)
      case let .fixed(keys):
        if let key, keys.contains(key) {
          Effective(isOn: true, source: .type)
        } else {
          Effective(isOn: false, source: .fixedExclusion)
        }
      }
    }
  }

  public func firstMatch(body: String?, sender: String?) -> RuleMatch? {
    for rule in rules {
      let bodyHighlights = rule.lookIn == .sender ? [] : Self.highlights(in: body, for: rule)
      let senderHighlights = rule.lookIn == .message ? [] : Self.highlights(in: sender, for: rule)
      if !bodyHighlights.isEmpty || !senderHighlights.isEmpty {
        return RuleMatch(rule: rule, bodyHighlights: bodyHighlights, senderHighlights: senderHighlights)
      }
    }
    return nil
  }

  var isValid: Bool {
    selections.count == Kind.allCases.count &&
      rules.allSatisfy(\.isValid) &&
      Set(rules.map(\.id)).count == rules.count
  }

  private static func highlights(in field: String?, for rule: Rule) -> [RuleMatch.Highlight] {
    guard let field, !field.isEmpty, !rule.phrase.isEmpty else { return [] }
    let text = field as NSString
    let entireField = NSRange(location: 0, length: text.length)
    let options: String.CompareOptions = [.literal, .caseInsensitive]
    if rule.match == .wholeField {
      let equal = text.compare(rule.phrase, options: options, range: entireField, locale: comparisonLocale)
      return equal == .orderedSame ? [RuleMatch.Highlight(entireField)] : []
    }

    var highlights: [RuleMatch.Highlight] = []
    var start = 0
    while start < text.length {
      let searchRange = NSRange(location: start, length: text.length - start)
      let match = text.range(of: rule.phrase, options: options, range: searchRange, locale: comparisonLocale)
      guard match.location != NSNotFound else { break }
      let highlight = RuleMatch.Highlight(text.rangeOfComposedCharacterSequences(for: match))
      if highlights.last != highlight {
        highlights.append(highlight)
      }
      start = NSMaxRange(match)
    }
    return highlights
  }
}

// swiftlint:enable redundant_string_enum_value
// swiftformat:enable redundantRawValues

public extension TextRulesPreference {
  static func channelKind(isPublic: Bool, name: String) -> Kind {
    if isPublic { return .publicChannel }
    return name.hasPrefix("#") ? .hashtagChannel : .privateChannel
  }
}
