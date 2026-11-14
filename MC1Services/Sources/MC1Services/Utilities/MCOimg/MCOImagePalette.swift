public enum MCOImagePalette {
  public static let profileCount = 16
  public static let firstDynamicProfile = 9
  public static let dynamic512Profile = 15

  public static func count(profile: Int) -> Int {
    guard (0..<profileCount).contains(profile) else { return 0 }
    return profileReferences(profile).count
  }

  public static func colors(profile: Int) -> [UInt32] {
    profileReferences(profile).map { color($0, profile: profile) }
  }

  public static func color(_ reference: Int, profile: Int) -> UInt32 {
    if profile >= firstDynamicProfile {
      return global512.indices.contains(reference) ? global512[reference] : 0
    }
    guard fixed.indices.contains(profile), fixed[profile].indices.contains(reference) else { return 0 }
    return fixed[profile][reference]
  }

  public static func white(profile: Int) -> Int { 0 }

  public static func black(profile: Int) -> Int {
    guard (0..<profileCount).contains(profile) else { return 0 }
    return profile >= firstDynamicProfile ? 63 : [1, 3, 2, 2, 3, 7, 15, 31, 7][profile]
  }

  public static func ref(fromStored value: Int, profile: Int) throws -> Int {
    let refs = profileReferences(profile)
    guard refs.indices.contains(value) else { throw MCOImageCodecError.damaged }
    return refs[value]
  }

  public static func stored(fromRef value: Int, profile: Int) throws -> Int {
    guard let index = profileReferences(profile).firstIndex(of: value) else { throw MCOImageCodecError.damaged }
    return index
  }

  static func profileReferences(_ profile: Int) -> [Int] {
    guard (0..<profileCount).contains(profile) else { return [] }
    return profile >= firstDynamicProfile ? dynamicReferences[profile - firstDynamicProfile] : Array(fixed[profile].indices)
  }

  static func bits(_ profile: Int) -> Int { MCOImageBitReader.width(count(profile: profile) - 1) }
}
