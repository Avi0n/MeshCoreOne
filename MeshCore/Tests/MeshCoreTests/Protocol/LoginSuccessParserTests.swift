import Foundation
@testable import MeshCore
import Testing

@Suite("LoginSuccessParser")
struct LoginSuccessParserTests {
  /// Builds the 13-byte payload as `Parsers.LoginSuccess.parse` sees it (after
  /// `PacketParser` strips the 0x85 opcode).
  private static func extendedPayload(
    isAdminByte: UInt8,
    pubkeyPrefix: Data = Data([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]),
    timestamp: UInt32 = 0,
    aclPermissions: UInt8,
    firmwareVersion: UInt8 = 2
  ) -> Data {
    var data = Data()
    data.append(isAdminByte)
    data.append(pubkeyPrefix)
    var ts = timestamp.littleEndian
    withUnsafeBytes(of: &ts) { data.append(contentsOf: $0) }
    data.append(aclPermissions)
    data.append(firmwareVersion)
    return data
  }

  private static func extractLogin(_ event: MeshEvent) -> LoginInfo? {
    if case let .loginSuccess(info) = event { return info }
    return nil
  }

  // MARK: - Official C++ admin encoding

  // Both repeater and room-server emit byte0=1 with ACL=0x03 for admin, so this
  // test covers both at once. Non-admin encodings differ and are covered below.

  @Test
  func `C++ admin login (byte0=1, ACL=0x03) parses as admin on both repeater and room-server`() {
    let payload = Self.extendedPayload(isAdminByte: 1, aclPermissions: 0x03)
    let info = Self.extractLogin(Parsers.LoginSuccess.parse(payload))
    #expect(info?.isAdmin == true)
    #expect(info?.permissions == 0x02, "Normalizes to RoomPermissionLevel.admin")
  }

  // MARK: - Official C++ repeater non-admin encodings

  // Byte 0 is a clean boolean: 1 for admin, 0 otherwise.

  @Test
  func `C++ repeater guest login (byte0=0, ACL=0x00) parses as guest`() {
    let payload = Self.extendedPayload(isAdminByte: 0, aclPermissions: 0x00)
    let info = Self.extractLogin(Parsers.LoginSuccess.parse(payload))
    #expect(info?.isAdmin == false)
    #expect(info?.permissions == 0x00, "Normalizes to RoomPermissionLevel.guest")
  }

  @Test
  func `C++ repeater read-write login (byte0=0, ACL=0x02) parses as read-write, not admin`() {
    let payload = Self.extendedPayload(isAdminByte: 0, aclPermissions: 0x02)
    let info = Self.extractLogin(Parsers.LoginSuccess.parse(payload))
    #expect(info?.isAdmin == false)
    #expect(info?.permissions == 0x01, "Normalizes to RoomPermissionLevel.readWrite")
  }

  // MARK: - Official C++ room-server non-admin encodings

  // Byte 0 is tri-state: 1 = admin, 2 = guest (permissions == 0), 0 = other.
  // A `data[0] != 0` check would incorrectly promote the guest case to admin.

  @Test
  func `C++ room-server guest login (byte0=2, ACL=0x00) parses as non-admin guest`() {
    let payload = Self.extendedPayload(isAdminByte: 2, aclPermissions: 0x00)
    let info = Self.extractLogin(Parsers.LoginSuccess.parse(payload))
    #expect(info?.isAdmin == false, "Room-server guest must never be read as admin")
    #expect(info?.permissions == 0x00, "Normalizes to RoomPermissionLevel.guest")
  }

  @Test
  func `C++ room-server read-only login (byte0=0, ACL=0x01) parses as non-posting guest`() {
    // Room-server firmware emits byte 0 = 0 and byte 11 = 1 for read-only
    // clients (not admin, not the server's guest tier). RoomPermissionLevel
    // has no read-only level, so we coalesce to `.guest` to withhold canPost.
    let payload = Self.extendedPayload(isAdminByte: 0, aclPermissions: 0x01)
    let info = Self.extractLogin(Parsers.LoginSuccess.parse(payload))
    #expect(info?.isAdmin == false)
    #expect(info?.permissions == 0x00, "Read-only must not inherit .readWrite (and its canPost)")
  }

  // MARK: - pyMC encodings

  // pyMC's PERM_ACL_ADMIN = 0x02 has bit 0 clear, and PERM_ACL_GUEST = 0x01 has
  // bit 0 set, so a bit-0 test on byte 11 is inverted against pyMC. Byte 0 is
  // set authoritatively by `reply_data[6] = 1 if permissions & 0x02`.

  @Test
  func `pyMC admin login (ACL=0x02) parses as admin`() {
    let payload = Self.extendedPayload(isAdminByte: 1, aclPermissions: 0x02)
    let info = Self.extractLogin(Parsers.LoginSuccess.parse(payload))
    #expect(info?.isAdmin == true)
    #expect(info?.permissions == 0x02, "Normalizes to RoomPermissionLevel.admin")
  }

  @Test
  func `pyMC guest login (ACL=0x01) parses as non-admin guest`() {
    let payload = Self.extendedPayload(isAdminByte: 0, aclPermissions: 0x01)
    let info = Self.extractLogin(Parsers.LoginSuccess.parse(payload))
    #expect(info?.isAdmin == false)
    #expect(info?.permissions == 0x00, "pyMC labels non-admin as guest; normalizes to RoomPermissionLevel.guest")
  }

  // MARK: - Legacy 7-byte path

  // Companion radio hardcodes byte 0 = 0 in the legacy "OK" path.

  @Test
  func `Legacy 7-byte payload with companion-radio hardcoded 0 parses as non-admin`() {
    let payload = Data([0x00, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF])
    let info = Self.extractLogin(Parsers.LoginSuccess.parse(payload))
    #expect(info?.isAdmin == false)
    #expect(info?.permissions == 0x01, "Legacy byte 0 = 0 falls through to .readWrite")
  }

  // MARK: - Short-payload guard

  @Test
  func `Payload shorter than 7 bytes returns zero LoginInfo fallback`() {
    let payload = Data([0x01, 0x02])
    let info = Self.extractLogin(Parsers.LoginSuccess.parse(payload))
    #expect(info?.isAdmin == false)
    #expect(info?.permissions == 0)
    #expect(info?.publicKeyPrefix.isEmpty == true)
  }

  // MARK: - Pubkey prefix propagation

  @Test
  func `Extended-format pubkey prefix is extracted from bytes 1..<7`() {
    let prefix = Data([0x11, 0x22, 0x33, 0x44, 0x55, 0x66])
    let payload = Self.extendedPayload(
      isAdminByte: 1,
      pubkeyPrefix: prefix,
      aclPermissions: 0x03
    )
    let info = Self.extractLogin(Parsers.LoginSuccess.parse(payload))
    #expect(info?.publicKeyPrefix == prefix)
  }
}
