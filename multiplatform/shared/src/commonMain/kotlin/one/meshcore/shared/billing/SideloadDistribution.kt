package one.meshcore.shared.billing

/**
 * The first release channel is a sideload APK. Play is a later channel.
 * Billing stays off and every named theme is unlocked, matching the iOS `SIDELOAD` build.
 * Flip [BILLING_ENABLED] only for a Play build.
 */
object SideloadDistribution {
    const val BILLING_ENABLED: Boolean = false

    /** iOS sideload Support row: `https://github.com/sponsors/Avi0n`. */
    const val SPONSORS_URL: String = "https://github.com/sponsors/Avi0n"

    val donationUrls: List<String> = listOf(SPONSORS_URL)
}
