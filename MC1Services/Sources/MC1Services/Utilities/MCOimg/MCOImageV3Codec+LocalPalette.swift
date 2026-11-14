extension MCOImageV3Codec.Decoder {
  mutating func localPalette() throws -> [Int] {
    let size = MCOImagePalette.count(profile: profile)
    let count: Int
    var descriptor: Int?
    if size <= 64 {
      if try reader.read(1) != 0 { descriptor = try reader.read(2); count = 0 } else {
        count = try reader.read(MCOImagePalette.bits(profile)) + 1
      }
    } else {
      switch try reader.prefix(limit: 4) {
      case 0: count = try reader.read(6) + 1
      case 1: count = try reader.read(6) + 65
      case 2: count = try reader.read(8) + 129
      case 3: count = try reader.read(7) + 385
      default: descriptor = try reader.read(2); count = 0
      }
    }
    let refs: [Int]
    if let descriptor {
      refs = try paletteDescriptor(descriptor, size: size)
    } else {
      guard count <= size else { throw MCOImageCodecError.damaged }
      refs = try (0..<count).map { _ in try reader.read(MCOImagePalette.bits(profile)) }
    }
    guard !refs.isEmpty, refs.count <= size, Set(refs).count == refs.count else { throw MCOImageCodecError.damaged }
    return try refs.map { try MCOImagePalette.ref(fromStored: $0, profile: profile) }
  }

  mutating func paletteLength() throws -> Int {
    let size = MCOImagePalette.count(profile: profile)
    if size <= 64 { return try reader.read(MCOImagePalette.bits(profile)) + 1 }
    let prefix = try reader.prefix(limit: 2)
    if prefix == 0 { return try reader.read(6) + 1 }
    if prefix == 1 { return try reader.read(6) + 65 }
    guard size > 128 else { throw MCOImageCodecError.damaged }
    let count = try reader.read(MCOImageBitReader.width(size - 129)) + 129
    guard count <= size else { throw MCOImageCodecError.damaged }
    return count
  }

  mutating func paletteDescriptor(_ descriptor: Int, size: Int) throws -> [Int] {
    var refs: [Int] = []
    switch descriptor {
    case 0:
      for ref in 0..<size { if try reader.read(1) != 0 { refs.append(ref) } }
    case 1:
      let count = try paletteLength()
      refs.append(try reader.read(MCOImagePalette.bits(profile)))
      while refs.count < count { refs.append(try refs[refs.count - 1] + reader.compact() + 1) }
    case 2:
      let runs = try reader.range(size - 1) + 1
      var previousEnd = -1
      for _ in 0..<runs {
        let start = try reader.read(MCOImagePalette.bits(profile))
        let length = try reader.compact() + 1
        let end = start + length - 1
        guard start > previousEnd, end < size, refs.count + length <= size else { throw MCOImageCodecError.damaged }
        refs.append(contentsOf: start...end)
        previousEnd = end
      }
    default:
      guard profile == MCOImagePalette.dynamic512Profile else { throw MCOImageCodecError.damaged }
      return try reader.read(1) == 0 ? bankBitmap() : orderedBanks()
    }
    return refs
  }

  mutating func bankBitmap() throws -> [Int] {
    let bankMask = try reader.read(8)
    guard bankMask != 0 else { throw MCOImageCodecError.damaged }
    var refs: [Int] = []
    for bank in 0..<8 where bankMask & (1 << bank) != 0 {
      let before = refs.count
      for offset in 0..<64 { if try reader.read(1) != 0 { refs.append((bank << 6) | offset) } }
      guard refs.count > before else { throw MCOImageCodecError.damaged }
    }
    return refs
  }

  mutating func orderedBanks() throws -> [Int] {
    let count = try paletteLength()
    if try reader.read(1) == 0 {
      guard count <= 64 else { throw MCOImageCodecError.damaged }
      let bank = try reader.read(3)
      return try (0..<count).map { _ in try (bank << 6) | reader.read(6) }
    }
    let mask = try reader.read(8)
    let banks = (0..<8).filter { mask & (1 << $0) != 0 }
    guard banks.count > 1, count <= banks.count * 64 else { throw MCOImageCodecError.damaged }
    var used: Set<Int> = []
    var refs: [Int] = []
    for _ in 0..<count {
      let index = try reader.read(MCOImageBitReader.width(banks.count - 1))
      guard banks.indices.contains(index) else { throw MCOImageCodecError.damaged }
      used.insert(index)
      refs.append(try (banks[index] << 6) | reader.read(6))
    }
    guard used.count == banks.count else { throw MCOImageCodecError.damaged }
    return refs
  }
}
