package one.meshcore.shared.billing

/**
 * Product ids for Play Billing / Support Development. Pinned to iOS StoreCatalog identifiers so
 * entitlement restore can align when a cross-platform account layer exists.
 */
object StoreCatalog {
    const val THEME_BUNDLE = "theme_bundle"

    object Theme {
        const val EMBER = "theme_ember"
        const val FERN = "theme_fern"
        const val MARINE = "theme_marine"
        const val OLIVE = "theme_olive"
        const val LAVENDER = "theme_lavender"
        const val SAKURA = "theme_sakura"
        const val SOLARIZED = "theme_solarized"
        const val NORD = "theme_nord"
        const val CATPPUCCIN = "theme_catppuccin"

        val all: List<String> = listOf(
            EMBER, FERN, MARINE, OLIVE, LAVENDER, SAKURA, SOLARIZED, NORD, CATPPUCCIN,
        )
    }

    object Tip {
        const val TIP_1 = "tip_1"
        const val TIP_2 = "tip_2"
        const val TIP_3 = "tip_3"
        const val TIP_4 = "tip_4"
        const val TIP_5 = "tip_5"
        const val TIP_6 = "tip_6"

        val all: List<String> = listOf(TIP_1, TIP_2, TIP_3, TIP_4, TIP_5, TIP_6)
    }

    val allProductIds: List<String> = listOf(THEME_BUNDLE) + Theme.all + Tip.all
}
