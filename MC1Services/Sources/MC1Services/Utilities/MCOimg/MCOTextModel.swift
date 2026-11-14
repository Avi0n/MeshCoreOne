struct MCOTextModel: Sendable {
  let primary: [UInt32]
  let extensionSymbols: [UInt32]
  let start: [Int]
  let punctuationStart: [Int]
  let top4: [Int]
  let uppercase: [UInt32: UInt32]

  var symbols: [UInt32] { primary + extensionSymbols }
}
