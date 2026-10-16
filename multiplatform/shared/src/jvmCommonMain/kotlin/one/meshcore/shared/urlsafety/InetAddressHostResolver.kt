package one.meshcore.shared.urlsafety

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * JVM [HostResolver] backed by `java.net.InetAddress`, shared by the Android and desktop targets.
 * Resolves all A/AAAA records and returns their numeric literals so [UrlSafetyChecker] can reject a
 * host that resolves to any private/reserved address. The blocking lookup runs on [Dispatchers.IO]
 * via [runInterruptible] so the checker's timeout can cancel it. Fails closed: any failure or empty
 * result yields `null`.
 */
object InetAddressHostResolver : HostResolver {
    override suspend fun resolve(host: String): List<String>? = runInterruptible(Dispatchers.IO) {
        try {
            InetAddress.getAllByName(host)
                // Drop any IPv6 zone id ("fe80::1%eth0") so the classifier sees a clean literal;
                // it rejects '%' outright, which would otherwise let a scoped address slip through.
                .mapNotNull { it.hostAddress?.substringBefore('%') }
                .filter { it.isNotEmpty() }
                .ifEmpty { null }
        } catch (_: UnknownHostException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
