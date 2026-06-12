import Foundation

extension Parsers {

    // MARK: - LoginSuccess

    /// Parser for successful login responses (push opcode 0x85).
    ///
    /// After the opcode byte is stripped, two payload formats are seen in the wild:
    ///
    /// **Legacy format (7 bytes):**
    /// - byte 0: admin indicator (companion radio hardcodes `0` for legacy "OK" replies).
    /// - bytes 1–6: pubkey prefix.
    ///
    /// **v7+ extended format (13 bytes):**
    /// - byte 0: admin indicator. Populated by the server and forwarded verbatim by the
    ///           companion radio. `1` means admin; any other value (including `0` and `2`)
    ///           means non-admin. The official C++ room server uses a tri-state encoding
    ///           (`0` / `1` / `2`) where `2` signals guest for downstream role awareness;
    ///           clients must treat `2` as non-admin. Only `== 1` is a safe admin test.
    /// - bytes 1–6: pubkey prefix.
    /// - bytes 7–10: server timestamp (not parsed).
    /// - byte 11: full ACL permissions byte. Encoding is firmware-specific:
    ///            - Official C++ firmware: `0 = guest`, `1 = read-only`, `2 = read-write`,
    ///              `3 = admin`.
    ///            - pyMC: `0x01 = non-admin (guest)`, `0x02 = admin`.
    ///            This parser uses byte 0 for the admin gate. For non-admin sessions, only byte 11
    ///            = 2 maps to `.readWrite`; every other value (including C++ `1 = read-only`)
    ///            falls back to `.guest` so a non-posting client cannot acquire `canPost`.
    /// - byte 12: firmware version level (not parsed).
    ///
    /// The byte-0 admin indicator is preferred over re-deriving admin status from the
    /// permissions byte because the latter is encoding-specific and has produced
    /// cross-implementation bugs.
    enum LoginSuccess {
        /// Parses permissions and admin status.
        static func parse(_ data: Data) -> MeshEvent {
            guard data.count >= PacketSize.loginSuccessMinimum else {
                return .loginSuccess(LoginInfo(permissions: 0, isAdmin: false, publicKeyPrefix: Data()))
            }

            let pubkeyPrefix = Data(data[1..<7])

            // See type-level doc for byte-0 admin gate and byte-11 coalescing rationale.
            if data.count >= PacketSize.loginSuccessExtended {
                let isAdmin = data[0] == 1
                let firmwarePermissions = data[11]

                let normalizedPermissions: UInt8
                if isAdmin {
                    normalizedPermissions = 0x02  // RoomPermissionLevel.admin
                } else if firmwarePermissions == 0x02 {
                    normalizedPermissions = 0x01  // RoomPermissionLevel.readWrite
                } else {
                    normalizedPermissions = 0x00  // RoomPermissionLevel.guest (read-only / unknowns)
                }

                return .loginSuccess(LoginInfo(
                    permissions: normalizedPermissions,
                    isAdmin: isAdmin,
                    publicKeyPrefix: pubkeyPrefix
                ))
            }

            // Legacy format: convert legacy indicator to RoomPermissionLevel values
            // Legacy byte 0: 0=member/readWrite, 1=admin, 2=guest
            // RoomPermissionLevel: 0x00=guest, 0x01=readWrite, 0x02=admin
            let legacyIndicator = data[0]
            let permissions: UInt8
            let isAdmin: Bool

            switch legacyIndicator {
            case 1:
                // Admin
                permissions = 0x02  // RoomPermissionLevel.admin
                isAdmin = true
            case 2:
                // Guest/readonly
                permissions = 0x00  // RoomPermissionLevel.guest
                isAdmin = false
            default:
                // Member/readWrite (legacy 0 or unknown values)
                permissions = 0x01  // RoomPermissionLevel.readWrite
                isAdmin = false
            }

            return .loginSuccess(LoginInfo(
                permissions: permissions,
                isAdmin: isAdmin,
                publicKeyPrefix: pubkeyPrefix
            ))
        }
    }
}
