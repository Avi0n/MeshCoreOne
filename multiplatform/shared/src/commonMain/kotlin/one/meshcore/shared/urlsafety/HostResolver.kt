package one.meshcore.shared.urlsafety

/**
 * DNS seam for [UrlSafetyChecker]. Resolves a hostname to its IP-literal strings so the checker can
 * verify every A/AAAA record is public before a fetch. Kept as an interface so [commonTest] can
 * drive the SSRF control flow with a fake, and each platform supplies a real implementation (the
 * JVM one uses `java.net.InetAddress`).
 *
 * Fail-closed contract: return `null` on any resolution failure. The checker also treats an empty
 * list as failure, so an implementation may return either for "no usable address".
 */
fun interface HostResolver {
    /** Resolves [host] to IP-literal strings, or `null` if resolution failed. */
    suspend fun resolve(host: String): List<String>?
}
