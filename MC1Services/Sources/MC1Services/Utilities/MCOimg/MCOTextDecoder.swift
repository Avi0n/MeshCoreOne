import Foundation

struct MCOTextDecoder {
  private enum Field {
    static let version = 1
    static let languageBits = 3
    static let languageEscape = 7
    static let globalLanguageNone = 255
    static let symbolBits = 5
    static let extendedBits = 3
    static let byteBits = 8
    static let rawPaddingBits = 4
    static let predictionCount = 4
    static let space: UInt32 = 0x20
    static let lineFeed: UInt32 = 0x0a
  }

  private enum Context { case start, punctuation, symbol }
  private var reader: MCOTextBitReader
  private var languageA = 0
  private var languageB: Int?
  private var language = 0
  private var context = Context.start
  private var previous: UInt32?
  private var shift = false
  private var capitals = false
  private var output = String.UnicodeScalarView()

  init(_ data: Data, bitLength: Int) throws { reader = try MCOTextBitReader(data, bitLength: bitLength) }

  mutating func decode() throws -> String {
    guard try reader.headerField() == Field.version else { throw MCOImageCodecError.unsupported }
    let generation = try reader.headerField()
    let first = try reader.read(Field.languageBits)
    let second = try reader.read(Field.languageBits)
    if first == Field.languageEscape {
      if second == 1 {
        guard try reader.read(Field.rawPaddingBits) == 0, reader.remaining % Field.byteBits == 0 else {
          throw MCOImageCodecError.damaged
        }
        return try utf8(count: reader.remaining / Field.byteBits)
      }
      guard second == 0 else { throw MCOImageCodecError.damaged }
      languageA = try reader.read(Field.byteBits)
      let partner = try reader.read(Field.byteBits)
      languageB = partner == Field.globalLanguageNone ? nil : partner
    } else {
      languageA = first
      languageB = second == Field.languageEscape ? nil : second
    }
    guard MCOTextModels.all.indices.contains(languageA),
          languageB == nil || MCOTextModels.all.indices.contains(languageB!) else { throw MCOImageCodecError.damaged }
    guard generation == MCOTextModels.generation else { throw MCOImageCodecError.unsupported }
    language = languageA
    while reader.remaining > 0 { try token() }
    guard !shift else { throw MCOImageCodecError.damaged }
    return String(output)
  }

  private mutating func token() throws {
    let model = MCOTextModels.all[language]
    switch try tokenPrefix() {
    case 0:
      let rank = try top4Rank()
      let predictions: [Int]
      switch context {
      case .start: predictions = model.start
      case .punctuation: predictions = model.punctuationStart
      case .symbol:
        guard let previous, let index = model.symbols.firstIndex(of: previous) else { throw MCOImageCodecError.damaged }
        let start = index * Field.predictionCount
        predictions = Array(model.top4[start..<start + Field.predictionCount])
      }
      try emit(model.symbols[predictions[rank]], model: model)
    case 1:
      let index = try reader.read(Field.symbolBits)
      guard model.primary.indices.contains(index) else { throw MCOImageCodecError.damaged }
      try emit(model.primary[index], model: model)
    case 2:
      try requireNoShift()
      let rune = MCOTextModels.punctuation[try reader.read(Field.symbolBits)]
      try append(rune)
      if rune == Field.lineFeed { reset() } else if rune == Field.space {
        if context != .symbol { reset() }
      } else { context = .punctuation; previous = nil }
    case 3:
      let index = try reader.read(Field.symbolBits)
      guard model.extensionSymbols.indices.contains(index) else { throw MCOImageCodecError.damaged }
      try emit(model.extensionSymbols[index], model: model)
    case 4:
      guard !shift else { throw MCOImageCodecError.damaged }
      shift = true
    case 5:
      try requireNoShift()
      guard let languageB, language == languageA || language == languageB else { throw MCOImageCodecError.damaged }
      language = language == languageA ? languageB : languageA
      reset()
    default:
      try extended()
    }
  }

  private mutating func tokenPrefix() throws -> Int {
    let maximumPrefixLength = 6
    for count in 0..<maximumPrefixLength where try !reader.bit() { return count }
    return maximumPrefixLength
  }

  private mutating func top4Rank() throws -> Int {
    if try !reader.bit() { return 0 }
    if try !reader.bit() { return 1 }
    return try reader.bit() ? 3 : 2
  }

  private mutating func extended() throws {
    try requireNoShift()
    switch try reader.read(Field.extendedBits) {
    case 0:
      let next = try reader.read(Field.byteBits)
      guard MCOTextModels.all.indices.contains(next) else { throw MCOImageCodecError.damaged }
      language = next
      reset()
    case 1: reset()
    case 2:
      let count = try reader.read(Field.symbolBits) + 1
      output.append(contentsOf: try utf8(count: count).unicodeScalars)
      reset()
    case 3: capitals.toggle()
    default: throw MCOImageCodecError.damaged
    }
  }

  private mutating func emit(_ rune: UInt32, model: MCOTextModel) throws {
    let uppercase = model.uppercase[rune]
    guard !shift || uppercase != nil else { throw MCOImageCodecError.damaged }
    try append(uppercase != nil && capitals != shift ? uppercase! : rune)
    context = .symbol
    previous = rune
    shift = false
  }

  private mutating func append(_ rune: UInt32) throws {
    guard let scalar = UnicodeScalar(rune) else { throw MCOImageCodecError.damaged }
    output.append(scalar)
  }

  private mutating func utf8(count: Int) throws -> String {
    guard count <= reader.remaining / Field.byteBits else { throw MCOImageCodecError.damaged }
    let bytes = try (0..<count).map { _ in UInt8(try reader.read(Field.byteBits)) }
    guard let text = String(bytes: bytes, encoding: .utf8) else { throw MCOImageCodecError.damaged }
    return text
  }

  private func requireNoShift() throws {
    if shift { throw MCOImageCodecError.damaged }
  }

  private mutating func reset() { context = .start; previous = nil }
}
