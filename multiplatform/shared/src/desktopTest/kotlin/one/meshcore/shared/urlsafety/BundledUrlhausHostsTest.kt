package one.meshcore.shared.urlsafety

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves the URLhaus hosts snapshot is packaged on the JVM classpath and parses to a non-empty
 * domain set (production [createMalwareDomainFilter] baseline when disk cache is cold).
 */
class BundledUrlhausHostsTest {
    @Test
    fun bundledHostsResourceIsPresentAndParses() {
        val text = readBundledUrlhausHosts()
        assertNotNull(text, "urlhaus-filter-hosts-online.txt must be on the jvmCommon classpath")
        val domains = MalwareDomainFilter.parseHostsFile(text)
        assertTrue(domains.isNotEmpty(), "bundled hosts must yield at least one domain")
        // Snapshot includes known malicious sample hosts from the URLhaus export header era.
        assertFalse(domains.contains(""), "parser must not insert empty host strings")
    }
}
