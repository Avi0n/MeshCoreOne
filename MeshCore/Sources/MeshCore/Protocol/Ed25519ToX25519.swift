import Foundation

/// Converts Ed25519 public keys (used by MeshCore firmware for node identity) to
/// Curve25519/X25519 public keys (used by ``DirectMessageCrypto`` for ECDH).
///
/// Contact public keys stored in the database are 32-byte Ed25519 public keys.
/// ``DirectMessageCrypto`` expects 32-byte Curve25519 public keys for key agreement.
///
/// Equivalent to libsodium's `crypto_sign_ed25519_pk_to_curve25519`.
public enum Ed25519ToX25519 {
  /// Convert an Ed25519 public key to a Curve25519 public key.
  ///
  /// Uses the birational map: `u = (1 + y) / (1 - y) mod p` where `p = 2^255 - 19`.
  /// - Parameter ed25519PublicKey: 32-byte Ed25519 compressed public key.
  /// - Returns: 32-byte Curve25519 public key, or nil if input is invalid.
  public static func convertPublicKey(_ ed25519PublicKey: Data) -> Data? {
    guard ed25519PublicKey.count == 32 else { return nil }

    var yBytes = [UInt8](ed25519PublicKey)
    yBytes[31] &= 0x7F // Clear sign bit to get y-coordinate

    let y = FieldElement.fromBytes(yBytes)
    let one = FieldElement.one

    let numerator = FieldElement.add(one, y)
    let denominator = FieldElement.sub(one, y)
    let denominatorInv = FieldElement.inverse(denominator)
    let u = FieldElement.mul(numerator, denominatorInv)

    return Data(FieldElement.toBytes(u))
  }
}

// MARK: - Field Arithmetic over GF(2^255 - 19)

/// Minimal field arithmetic for the Ed25519→Curve25519 public key conversion.
/// Represents elements as 4 UInt64 limbs (256 bits, little-endian limb order).
private enum FieldElement {
  typealias Element = [UInt64]

  /// p = 2^255 - 19
  static let p: Element = [
    0xFFFF_FFFF_FFFF_FFED,
    0xFFFF_FFFF_FFFF_FFFF,
    0xFFFF_FFFF_FFFF_FFFF,
    0x7FFF_FFFF_FFFF_FFFF
  ]

  static let one: Element = [1, 0, 0, 0]

  static func fromBytes(_ bytes: [UInt8]) -> Element {
    var limbs: Element = [0, 0, 0, 0]
    for i in 0..<4 {
      for j in 0..<8 where i * 8 + j < bytes.count {
        limbs[i] |= UInt64(bytes[i * 8 + j]) << UInt64(j * 8)
      }
    }
    return limbs
  }

  static func toBytes(_ a: Element) -> [UInt8] {
    let r = conditionalSubtractP(a)
    var bytes = [UInt8](repeating: 0, count: 32)
    for i in 0..<4 {
      for j in 0..<8 {
        bytes[i * 8 + j] = UInt8((r[i] >> UInt64(j * 8)) & 0xFF)
      }
    }
    return bytes
  }

  static func add(_ a: Element, _ b: Element) -> Element {
    var r: Element = [0, 0, 0, 0]
    var carry: UInt64 = 0
    for i in 0..<4 {
      let (s1, c1) = a[i].addingReportingOverflow(b[i])
      let (s2, c2) = s1.addingReportingOverflow(carry)
      r[i] = s2
      carry = (c1 ? 1 : 0) + (c2 ? 1 : 0)
    }
    if carry > 0 {
      // 2^256 ≡ 38 (mod p), so carry * 2^256 ≡ carry * 38
      let (s, c) = r[0].addingReportingOverflow(carry &* 38)
      r[0] = s
      if c {
        for i in 1..<4 {
          let (s2, c2) = r[i].addingReportingOverflow(1)
          r[i] = s2
          if !c2 { break }
        }
      }
    }
    return conditionalSubtractP(r)
  }

  static func sub(_ a: Element, _ b: Element) -> Element {
    var r: Element = [0, 0, 0, 0]
    var borrow: UInt64 = 0
    for i in 0..<4 {
      let (s1, b1) = a[i].subtractingReportingOverflow(b[i])
      let (s2, b2) = s1.subtractingReportingOverflow(borrow)
      r[i] = s2
      borrow = (b1 ? 1 : 0) + (b2 ? 1 : 0)
    }
    if borrow > 0 {
      var carry: UInt64 = 0
      for i in 0..<4 {
        let (s, c1) = r[i].addingReportingOverflow(p[i])
        let (s2, c2) = s.addingReportingOverflow(carry)
        r[i] = s2
        carry = (c1 ? 1 : 0) + (c2 ? 1 : 0)
      }
    }
    return r
  }

  static func mul(_ a: Element, _ b: Element) -> Element {
    // Schoolbook 4×4 → 8 limbs, then reduce mod p
    var r = [UInt64](repeating: 0, count: 8)

    for i in 0..<4 {
      var carry: UInt64 = 0
      for j in 0..<4 {
        let (hi, lo) = a[i].multipliedFullWidth(by: b[j])
        let (s1, c1) = r[i + j].addingReportingOverflow(lo)
        let (s2, c2) = s1.addingReportingOverflow(carry)
        r[i + j] = s2
        carry = hi &+ (c1 ? 1 : 0) &+ (c2 ? 1 : 0)
      }
      if carry > 0 {
        var k = i + 4
        while k < 8 {
          let (s, c) = r[k].addingReportingOverflow(carry)
          r[k] = s
          if !c { break }
          carry = 1
          k += 1
        }
      }
    }

    return reduce512(r)
  }

  /// Modular inverse via Fermat's little theorem: a^(p-2) mod p.
  static func inverse(_ a: Element) -> Element {
    // p - 2 = 2^255 - 21
    let pMinus2: Element = [
      0xFFFF_FFFF_FFFF_FFEB,
      0xFFFF_FFFF_FFFF_FFFF,
      0xFFFF_FFFF_FFFF_FFFF,
      0x7FFF_FFFF_FFFF_FFFF
    ]
    return pow(a, pMinus2)
  }

  // MARK: - Private Helpers

  /// Reduce a 512-bit product mod p using 2^256 ≡ 38 (mod p).
  private static func reduce512(_ r: [UInt64]) -> Element {
    var result: Element = [r[0], r[1], r[2], r[3]]
    let high: Element = [r[4], r[5], r[6], r[7]]

    // result += high * 38
    var carry: UInt64 = 0
    for i in 0..<4 {
      let (hi, lo) = high[i].multipliedFullWidth(by: 38)
      let (s1, c1) = result[i].addingReportingOverflow(lo)
      let (s2, c2) = s1.addingReportingOverflow(carry)
      result[i] = s2
      carry = hi &+ (c1 ? 1 : 0) &+ (c2 ? 1 : 0)
    }

    // Remaining carry: carry * 2^256 ≡ carry * 38 (mod p)
    while carry > 0 {
      let addend = carry &* 38
      carry = 0
      let (s, c) = result[0].addingReportingOverflow(addend)
      result[0] = s
      if c {
        for i in 1..<4 {
          let (s2, c2) = result[i].addingReportingOverflow(1)
          result[i] = s2
          if !c2 { carry = 0; break }
          if i == 3 { carry = 1 }
        }
      }
    }

    return conditionalSubtractP(result)
  }

  /// Square-and-multiply modular exponentiation.
  private static func pow(_ base: Element, _ exp: Element) -> Element {
    var result = one
    var b = base

    for i in 0..<4 {
      var bits = exp[i]
      let numBits = (i == 3) ? 63 : 64
      for _ in 0..<numBits {
        if bits & 1 == 1 {
          result = mul(result, b)
        }
        b = mul(b, b)
        bits >>= 1
      }
    }

    return result
  }

  /// If a >= p, return a - p; otherwise return a.
  private static func conditionalSubtractP(_ a: Element) -> Element {
    for i in stride(from: 3, through: 0, by: -1) {
      if a[i] > p[i] { return subtractNoUnderflow(a, p) }
      if a[i] < p[i] { return a }
    }
    // a == p → return 0
    return [0, 0, 0, 0]
  }

  private static func subtractNoUnderflow(_ a: Element, _ b: Element) -> Element {
    var r: Element = [0, 0, 0, 0]
    var borrow: UInt64 = 0
    for i in 0..<4 {
      let (s1, b1) = a[i].subtractingReportingOverflow(b[i])
      let (s2, b2) = s1.subtractingReportingOverflow(borrow)
      r[i] = s2
      borrow = (b1 ? 1 : 0) + (b2 ? 1 : 0)
    }
    return r
  }
}
