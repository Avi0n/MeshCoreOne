package one.meshcore.shared.whatsnew

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhatsNewCatalogTest {
    @Test
    fun versionEncodeDecodeRoundTrip() {
        val v = WhatsNewVersion(1, 1)
        assertEquals("1.1", v.encode())
        assertEquals(v, WhatsNewVersion.decode("1.1"))
        assertEquals(WhatsNewVersion(2, 0), WhatsNewVersion.decode("2.0"))
        assertNull(WhatsNewVersion.decode("bad"))
    }

    @Test
    fun firstInstallStillOnboardingDoesNotShow() {
        assertNull(
            WhatsNewCatalog.resolve(
                current = WhatsNewCatalog.currentVersion,
                baselineString = null,
                isOnboarded = false,
            ),
        )
    }

    @Test
    fun aPendingReleaseWaitsWhileAConnectionAlertIsUp() {
        val pending = WhatsNewCatalog.resolve(
            current = WhatsNewCatalog.currentVersion,
            baselineString = null,
            isOnboarded = true,
        )
        assertNotNull(pending)
        assertTrue(shouldPresentWhatsNew(pending, connectionAlertPresent = false))
        assertTrue(!shouldPresentWhatsNew(pending, connectionAlertPresent = true))
        assertTrue(!shouldPresentWhatsNew(null, connectionAlertPresent = false))
    }

    @Test
    fun onboardedUpgraderWithNoBaselineSeesCurrentNotes() {
        val pending = WhatsNewCatalog.resolve(
            current = WhatsNewCatalog.currentVersion,
            baselineString = null,
            isOnboarded = true,
        )
        assertNotNull(pending)
        assertEquals(WhatsNewCatalog.currentVersion, pending.version)
        assertTrue(pending.items.isNotEmpty())
    }

    @Test
    fun currentBaselineSuppresses() {
        assertNull(
            WhatsNewCatalog.resolve(
                current = WhatsNewCatalog.currentVersion,
                baselineString = WhatsNewCatalog.currentVersion.encode(),
                isOnboarded = true,
            ),
        )
    }

    @Test
    fun olderBaselineShowsCurrentRelease() {
        val pending = WhatsNewCatalog.resolve(
            current = WhatsNewCatalog.currentVersion,
            baselineString = "1.0",
            isOnboarded = true,
        )
        assertNotNull(pending)
        assertEquals(WhatsNewCatalog.currentVersion, pending.version)
    }

    @Test
    fun versionOrdering() {
        assertTrue(WhatsNewVersion(1, 2) > WhatsNewVersion(1, 1))
        assertTrue(WhatsNewVersion(2, 0) > WhatsNewVersion(1, 99))
    }

    @Test
    fun androidCatalogDropsIosOnlySiriShortcuts() {
        val ids = WhatsNewCatalog.releases.flatMap { it.items }.map { it.id }
        assertTrue(ids.none { it == "siri-shortcuts" }, "Siri Shortcuts is iOS-only and must not appear on Android")
        assertTrue(ids.none { it.contains("siri") })
        assertEquals(listOf("message-translation", "sender-avatars", "failed-sends"), ids)
        assertEquals("translate", WhatsNewSymbols.TRANSLATE)
        assertEquals("person.crop.circle", WhatsNewSymbols.PERSON)
        assertEquals("arrow.clockwise", WhatsNewSymbols.REFRESH)
        assertEquals(
            listOf(WhatsNewSymbols.TRANSLATE, WhatsNewSymbols.PERSON, WhatsNewSymbols.REFRESH),
            WhatsNewCatalog.releases.single().items.map { it.symbol },
        )
        assertEquals(WhatsNewVersion(1, 4), WhatsNewCatalog.currentVersion)
        assertEquals(
            "https://github.com/Avi0n/MeshCoreOne/releases/tag/v1.4.0",
            WhatsNewCatalog.releases.single().releaseNotesUrl,
        )
    }
}
