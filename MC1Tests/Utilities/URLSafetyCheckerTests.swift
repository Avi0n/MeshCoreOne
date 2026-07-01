import Foundation
@testable import MC1
import Testing

struct URLSafetyCheckerTests {
  // MARK: - Scheme Validation

  @Test
  func `Allows HTTPS URLs`() async throws {
    let url = try #require(URL(string: "https://example.com/page"))
    let result = await URLSafetyChecker.isSafe(url)
    #expect(result, "HTTPS should be allowed")
  }

  @Test
  func `Allows HTTP URLs`() async throws {
    let url = try #require(URL(string: "http://example.com/page"))
    let result = await URLSafetyChecker.isSafe(url)
    #expect(result, "HTTP should be allowed")
  }

  @Test
  func `Rejects FTP scheme`() async throws {
    let url = try #require(URL(string: "ftp://example.com/file"))
    let result = await URLSafetyChecker.isSafe(url)
    #expect(!result, "FTP should be rejected")
  }

  @Test
  func `Rejects file scheme`() async throws {
    let url = try #require(URL(string: "file:///etc/passwd"))
    let result = await URLSafetyChecker.isSafe(url)
    #expect(!result, "file:// should be rejected")
  }

  @Test
  func `Rejects javascript scheme`() async throws {
    let url = try #require(URL(string: "javascript:alert(1)"))
    let result = await URLSafetyChecker.isSafe(url)
    #expect(!result, "javascript: should be rejected")
  }

  // MARK: - Host Validation

  @Test
  func `Rejects URL with no host`() async throws {
    let url = try #require(URL(string: "https://"))
    let result = await URLSafetyChecker.isSafe(url)
    #expect(!result, "URL with no host should be rejected")
  }

  // MARK: - Allow-listed Hosts

  @Test
  func `Allows media.giphy.com`() async throws {
    let url = try #require(URL(string: "https://media.giphy.com/media/abc123/giphy.gif"))
    let result = await URLSafetyChecker.isSafe(url)
    #expect(result, "media.giphy.com should be allow-listed")
  }

  @Test
  func `Allows i.giphy.com`() async throws {
    let url = try #require(URL(string: "https://i.giphy.com/media/abc123/giphy.gif"))
    let result = await URLSafetyChecker.isSafe(url)
    #expect(result, "i.giphy.com should be allow-listed")
  }

  // MARK: - Private/Reserved IP Detection (IPv4)

  @Test
  func `Detects loopback 127.0.0.1`() {
    #expect(URLSafetyChecker.isPrivateOrReserved("127.0.0.1"))
  }

  @Test
  func `Detects loopback 127.255.255.255`() {
    #expect(URLSafetyChecker.isPrivateOrReserved("127.255.255.255"))
  }

  @Test
  func `Detects 10.0.0.0/8`() {
    #expect(URLSafetyChecker.isPrivateOrReserved("10.0.0.1"))
    #expect(URLSafetyChecker.isPrivateOrReserved("10.255.255.255"))
  }

  @Test
  func `Detects 172.16.0.0/12`() {
    #expect(URLSafetyChecker.isPrivateOrReserved("172.16.0.1"))
    #expect(URLSafetyChecker.isPrivateOrReserved("172.31.255.255"))
    #expect(!URLSafetyChecker.isPrivateOrReserved("172.32.0.1"), "172.32.x.x is public")
  }

  @Test
  func `Detects 192.168.0.0/16`() {
    #expect(URLSafetyChecker.isPrivateOrReserved("192.168.0.1"))
    #expect(URLSafetyChecker.isPrivateOrReserved("192.168.255.255"))
  }

  @Test
  func `Detects link-local 169.254.0.0/16`() {
    #expect(URLSafetyChecker.isPrivateOrReserved("169.254.1.1"))
    #expect(URLSafetyChecker.isPrivateOrReserved("169.254.169.254"), "AWS metadata endpoint")
  }

  @Test
  func `Detects 0.0.0.0`() {
    #expect(URLSafetyChecker.isPrivateOrReserved("0.0.0.0"))
  }

  @Test
  func `Detects multicast 224.0.0.0/4`() {
    #expect(URLSafetyChecker.isPrivateOrReserved("224.0.0.1"))
    #expect(URLSafetyChecker.isPrivateOrReserved("239.255.255.255"))
  }

  @Test
  func `Detects reserved 240.0.0.0/4`() {
    #expect(URLSafetyChecker.isPrivateOrReserved("240.0.0.1"))
    #expect(URLSafetyChecker.isPrivateOrReserved("255.255.255.254"))
  }

  @Test
  func `Allows public IPv4 addresses`() {
    #expect(!URLSafetyChecker.isPrivateOrReserved("8.8.8.8"), "Google DNS is public")
    #expect(!URLSafetyChecker.isPrivateOrReserved("1.1.1.1"), "Cloudflare DNS is public")
    #expect(!URLSafetyChecker.isPrivateOrReserved("93.184.216.34"), "example.com is public")
  }

  // MARK: - Private/Reserved IP Detection (IPv6)

  @Test
  func `Detects IPv6 loopback ::1`() {
    #expect(URLSafetyChecker.isPrivateOrReserved("::1"))
  }

  @Test
  func `Detects IPv6 unspecified ::`() {
    #expect(URLSafetyChecker.isPrivateOrReserved("::"))
  }

  @Test
  func `Detects IPv6 link-local fe80::`() {
    #expect(URLSafetyChecker.isPrivateOrReserved("fe80::1"))
    #expect(URLSafetyChecker.isPrivateOrReserved("fe80::abcd:ef01:2345:6789"))
  }

  @Test
  func `Detects IPv6 unique local fc00::/7`() {
    #expect(URLSafetyChecker.isPrivateOrReserved("fc00::1"))
    #expect(URLSafetyChecker.isPrivateOrReserved("fd00::1"))
  }

  @Test
  func `Detects IPv4-mapped IPv6 addresses`() {
    #expect(URLSafetyChecker.isPrivateOrReserved("::ffff:127.0.0.1"), "Mapped loopback")
    #expect(URLSafetyChecker.isPrivateOrReserved("::ffff:192.168.1.1"), "Mapped private")
    #expect(URLSafetyChecker.isPrivateOrReserved("::ffff:10.0.0.1"), "Mapped 10.x private")
    #expect(!URLSafetyChecker.isPrivateOrReserved("::ffff:8.8.8.8"), "Mapped public should pass")
  }

  @Test
  func `Detects IPv6 multicast ff00::/8`() {
    #expect(URLSafetyChecker.isPrivateOrReserved("ff02::1"))
    #expect(URLSafetyChecker.isPrivateOrReserved("ff05::2"))
  }

  @Test
  func `Allows public IPv6 addresses`() {
    #expect(!URLSafetyChecker.isPrivateOrReserved("2001:4860:4860::8888"), "Google DNS v6")
    #expect(!URLSafetyChecker.isPrivateOrReserved("2606:4700:4700::1111"), "Cloudflare v6")
  }

  // MARK: - Non-IP Hostnames

  @Test
  func `Non-IP strings are not private`() {
    #expect(!URLSafetyChecker.isPrivateOrReserved("example.com"))
    #expect(!URLSafetyChecker.isPrivateOrReserved("localhost"))
  }

  // MARK: - IP Literal URLs

  @Test
  func `Rejects URL with private IP literal`() async throws {
    let url = try #require(URL(string: "http://192.168.1.1/admin"))
    let result = await URLSafetyChecker.isSafe(url)
    #expect(!result, "Private IP URL should be rejected")
  }

  @Test
  func `Rejects URL with loopback IP literal`() async throws {
    let url = try #require(URL(string: "http://127.0.0.1:8080/api"))
    let result = await URLSafetyChecker.isSafe(url)
    #expect(!result, "Loopback IP URL should be rejected")
  }

  @Test
  func `Rejects metadata endpoint`() async throws {
    let url = try #require(URL(string: "http://169.254.169.254/latest/meta-data/"))
    let result = await URLSafetyChecker.isSafe(url)
    #expect(!result, "AWS metadata endpoint should be rejected")
  }

  @Test
  func `Rejects private IP with port`() async throws {
    let url = try #require(URL(string: "http://10.0.0.1:3000/api"))
    let result = await URLSafetyChecker.isSafe(url)
    #expect(!result, "Private IP with port should be rejected")
  }
}
