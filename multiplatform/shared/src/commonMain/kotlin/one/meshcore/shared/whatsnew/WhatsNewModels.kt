package one.meshcore.shared.whatsnew

/**
 * major.minor release key for What's New presentation. Mirrors iOS `WhatsNewVersion`.
 * Encoding is `"major.minor"` for DataStore persistence.
 */
data class WhatsNewVersion(val major: Int, val minor: Int) : Comparable<WhatsNewVersion> {
    fun encode(): String = "$major.$minor"

    override fun compareTo(other: WhatsNewVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor })

    companion object {
        fun decode(raw: String): WhatsNewVersion? {
            val parts = raw.trim().split('.')
            val maj = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val min = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return WhatsNewVersion(maj, min)
        }
    }
}

/** iOS SF Symbol names from `WhatsNewCatalog`. Compose maps each to a Material icon. */
object WhatsNewSymbols {
    const val TRANSLATE = "translate"
    const val PERSON = "person.crop.circle"
    const val REFRESH = "arrow.clockwise"
}

/**
 * One feature bullet in a release sheet. [symbol] is the iOS SF Symbol name
 * (`WhatsNewItem.symbol`); Compose maps it to a Material icon in the sheet.
 */
data class WhatsNewItem(val id: String, val symbol: String, val titleRes: String, val descriptionRes: String)

/** Items for a single major.minor release. */
data class WhatsNewRelease(val version: WhatsNewVersion, val items: List<WhatsNewItem>, val releaseNotesUrl: String)

/**
 * Catalog of releases that can present once on upgrade. Keep in lockstep with iOS
 * `WhatsNewCatalog` + Compose string keys under `whatsnew_*`.
 */
object WhatsNewCatalog {
    /** Current app What's New baseline (bump when shipping a new release sheet). */
    val currentVersion: WhatsNewVersion = WhatsNewVersion(major = 1, minor = 4)

    val releases: List<WhatsNewRelease> = listOf(
        WhatsNewRelease(
            version = WhatsNewVersion(1, 4),
            items = listOf(
                WhatsNewItem(
                    id = "message-translation",
                    symbol = WhatsNewSymbols.TRANSLATE,
                    titleRes = "whatsnew_whats_new_message_translation_title",
                    descriptionRes = "whatsnew_whats_new_message_translation_description",
                ),
                WhatsNewItem(
                    id = "sender-avatars",
                    symbol = WhatsNewSymbols.PERSON,
                    titleRes = "whatsnew_whats_new_sender_avatars_title",
                    descriptionRes = "whatsnew_whats_new_sender_avatars_description",
                ),
                WhatsNewItem(
                    id = "failed-sends",
                    symbol = WhatsNewSymbols.REFRESH,
                    titleRes = "whatsnew_whats_new_failed_sends_title",
                    descriptionRes = "whatsnew_whats_new_failed_sends_description",
                ),
            ),
            releaseNotesUrl = "https://github.com/Avi0n/MeshCoreOne/releases/tag/v1.4.0",
        ),
    )

    /**
     * Launch-time show/suppress, mirroring iOS `WhatsNewState.resolve`.
     * No baseline: an upgrader ([isOnboarded] true) sees the current notes; a first
     * install still in onboarding does not. A recorded baseline only shows when
     * [current] is newer.
     */
    fun resolve(
        current: WhatsNewVersion,
        baselineString: String?,
        isOnboarded: Boolean,
        catalog: List<WhatsNewRelease> = releases,
    ): WhatsNewRelease? {
        val release = catalog.firstOrNull { it.version == current } ?: return null
        if (release.items.isEmpty()) return null
        if (baselineString == null) return if (isOnboarded) release else null
        val baseline = WhatsNewVersion.decode(baselineString) ?: return null
        return if (current > baseline) release else null
    }
}
