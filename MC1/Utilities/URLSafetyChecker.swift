import Foundation
import OSLog

/// Validates URLs before fetching to prevent SSRF attacks via private network access.
/// Rejects non-HTTP(S) schemes and private/reserved IPs.
enum URLSafetyChecker {
    private static let logger = Logger(subsystem: "com.mc1", category: "URLSafetyChecker")

    /// Hosts that bypass safety checks (known-safe CDN domains)
    private static let allowedHosts: Set<String> = [
        "media.giphy.com",
        "i.giphy.com"
    ]

    private static let dnsTimeoutSeconds: Duration = .seconds(5)

    // MARK: - Public API

    /// Returns `true` if the URL is safe to fetch, `false` otherwise.
    /// Fails closed: DNS failure or timeout returns `false`.
    static func isSafe(_ url: URL) async -> Bool {
        // Only allow HTTP(S)
        guard let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else {
            logger.warning("Rejected non-HTTP(S) scheme: \(url.scheme ?? "nil")")
            return false
        }

        // Must have a host
        guard let host = url.host() else {
            logger.warning("Rejected URL with no host")
            return false
        }

        // Allow-listed hosts bypass SSRF checks (known-safe CDN domains)
        if allowedHosts.contains(host) {
            return true
        }

        // If host is an IP literal, check directly
        if isPrivateOrReserved(host) {
            logger.warning("Rejected private/reserved IP: \(host)")
            return false
        }

        // Resolve DNS and check all addresses
        return await resolveAndCheck(host: host)
    }

    /// Checks whether an IP address string falls within private or reserved ranges.
    static func isPrivateOrReserved(_ address: String) -> Bool {
        var addr4 = in_addr()
        if inet_pton(AF_INET, address, &addr4) == 1 {
            return isPrivateIPv4(UInt32(bigEndian: addr4.s_addr))
        }

        var addr6 = in6_addr()
        if inet_pton(AF_INET6, address, &addr6) == 1 {
            let bytes = withUnsafeBytes(of: &addr6.__u6_addr.__u6_addr8) { Array($0) }
            return isPrivateIPv6(bytes)
        }

        // Not a valid IP literal — not private (will be resolved via DNS)
        return false
    }

    // MARK: - Private IP Range Checks

    /// Checks whether a parsed IPv4 address falls within private or reserved ranges.
    private static func isPrivateIPv4(_ ip: UInt32) -> Bool {
        if ip == 0 { return true }                    // 0.0.0.0
        if ip >> 24 == 127 { return true }            // 127.0.0.0/8
        if ip >> 24 == 10 { return true }             // 10.0.0.0/8
        if ip >> 20 == 0xAC1 { return true }          // 172.16.0.0/12
        if ip >> 16 == 0xC0A8 { return true }         // 192.168.0.0/16
        if ip >> 16 == 0xA9FE { return true }         // 169.254.0.0/16 (link-local)
        if ip >> 28 == 0xE { return true }            // 224.0.0.0/4 (multicast)
        if ip >> 28 == 0xF { return true }            // 240.0.0.0/4 (reserved)
        return false
    }

    /// Checks whether a parsed IPv6 address falls within private or reserved ranges.
    private static func isPrivateIPv6(_ bytes: [UInt8]) -> Bool {
        if bytes.allSatisfy({ $0 == 0 }) { return true }                           // ::
        if bytes.dropLast().allSatisfy({ $0 == 0 }) && bytes.last == 1 { return true } // ::1
        if bytes[0] == 0xFE && (bytes[1] & 0xC0) == 0x80 { return true }           // fe80::/10
        if (bytes[0] & 0xFE) == 0xFC { return true }                               // fc00::/7
        // IPv4-mapped: check the embedded IPv4 address
        if bytes[0...9].allSatisfy({ $0 == 0 }) && bytes[10] == 0xFF && bytes[11] == 0xFF {
            let mapped = String(format: "%d.%d.%d.%d", bytes[12], bytes[13], bytes[14], bytes[15])
            return isPrivateOrReserved(mapped)
        }
        if bytes[0] == 0xFF { return true }                                         // ff00::/8
        return false
    }

    // MARK: - DNS Resolution

    /// Resolves the host via DNS and checks all returned addresses.
    /// Uses a 5-second timeout to prevent thread pool starvation.
    /// Returns `true` if all resolved addresses are public, `false` otherwise.
    private static func resolveAndCheck(host: String) async -> Bool {
        do {
            return try await withThrowingTaskGroup(of: Bool.self) { group in
                group.addTask {
                    await Task.detached {
                        resolveDNS(host: host)
                    }.value
                }
                group.addTask {
                    try await Task.sleep(for: dnsTimeoutSeconds)
                    throw CancellationError()
                }
                let result = try await group.next() ?? false
                group.cancelAll()
                return result
            }
        } catch {
            logger.warning("DNS resolution timed out or failed for \(host)")
            return false
        }
    }

    /// Synchronous DNS resolution + private IP check (runs on detached task).
    private static func resolveDNS(host: String) -> Bool {
        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM

        var result: UnsafeMutablePointer<addrinfo>?
        let status = getaddrinfo(host, nil, &hints, &result)
        guard status == 0, let addrList = result else {
            return false
        }
        defer { freeaddrinfo(addrList) }

        var current: UnsafeMutablePointer<addrinfo>? = addrList
        while let info = current {
            let address = extractAddress(from: info.pointee)
            if let address, isPrivateOrReserved(address) {
                return false
            }
            current = info.pointee.ai_next
        }

        return true
    }

    /// Extracts a human-readable IP address string from an addrinfo struct.
    private static func extractAddress(from info: addrinfo) -> String? {
        var buffer = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))

        switch info.ai_family {
        case AF_INET:
            var addr = info.ai_addr!.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
            inet_ntop(AF_INET, &addr.sin_addr, &buffer, socklen_t(INET_ADDRSTRLEN))
            return buffer.withUnsafeBufferPointer { String(cString: $0.baseAddress!) }

        case AF_INET6:
            var addr = info.ai_addr!.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { $0.pointee }
            inet_ntop(AF_INET6, &addr.sin6_addr, &buffer, socklen_t(INET6_ADDRSTRLEN))
            return buffer.withUnsafeBufferPointer { String(cString: $0.baseAddress!) }

        default:
            return nil
        }
    }
}
