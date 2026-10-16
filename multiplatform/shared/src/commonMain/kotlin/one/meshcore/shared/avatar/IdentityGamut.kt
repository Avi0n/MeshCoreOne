package one.meshcore.shared.avatar

/**
 * A theme's identity-color space: the [hueAnchors] that give the theme its character plus a
 * [saturation] band. [colorForName] maps any name to a deterministic color drawn from this gamut, with
 * brightness (and, when needed, saturation) solved so the result clears WCAG AA against the surfaces it
 * renders on. Hue and saturation for a name are stable across launches and appearances; only brightness
 * flips with light/dark so the color stays legible.
 *
 * Byte-for-byte port of Swift `IdentityGamut` (all pure `Double` work, no UI framework). The seed is an
 * FNV-1a hash of the name's UTF-8 bytes — stable across processes, unlike a per-process-seeded hash — so
 * a given name resolves to the identical color on iOS and Android.
 */
data class IdentityGamut(val hueAnchors: List<Double>, val saturation: ClosedFloatingPointRange<Double>) {
    /** Sorted hue anchors: the on-theme hues a name can land on before jitter. */
    val sortedAnchors: List<Double> get() = hueAnchors.sorted()

    /**
     * On-theme identity color for [name], guaranteed to clear the contrast floor against every surface
     * in [backgroundLuminances] (all expected to share the current appearance's polarity). [atHue] pins
     * the hue (category avatars); [atVariety] overrides the per-name brightness draw (0 = darkest legible).
     */
    fun colorForName(
        name: String,
        backgroundLuminances: List<Double>,
        highContrast: Boolean,
        atHue: Double? = null,
        atVariety: Double? = null,
    ): RgbColor {
        val resolved = resolve(name, backgroundLuminances, highContrast, atHue, atVariety)
        return hsbToRgb(resolved.hue, resolved.saturation, resolved.brightness)
    }

    /**
     * Assigns each name in [names] a distinct anchor hue (no jitter), bumping to the next anchor on
     * collision so a small fixed set — the channel / repeater / room category avatars — never share a
     * color. Earlier names win their preferred anchor; later ones step to the next free one.
     */
    fun distinctAnchorHues(names: List<String>): List<Double> {
        val anchors = sortedAnchors
        val taken = mutableSetOf<Int>()
        return names.map { name ->
            var index = (fnv1a(name) % anchors.size.toULong()).toInt()
            while (taken.contains(index)) {
                index = (index + 1) % anchors.size
            }
            taken.add(index)
            anchors[index]
        }
    }

    /**
     * Hue (degrees), saturation, and brightness a name resolves to, before constructing the color.
     * Exposed so tests resolve the same values the renderer will and assert AA on the real color.
     */
    fun resolve(
        name: String,
        backgroundLuminances: List<Double>,
        highContrast: Boolean,
        atHue: Double? = null,
        atVariety: Double? = null,
    ): Hsb {
        val seed = fnv1a(name)
        val hue = atHue ?: hueFor(seed)
        val baseSaturation = pickSaturation(seed)
        val varietyFraction = atVariety ?: fraction(seed, VARIETY_SHIFT)
        val baseFloor = if (highContrast) WcagContrast.INCREASED_CONTRAST_FLOOR else WcagContrast.AA_FLOOR
        val floor = baseFloor + CONTRAST_SAFETY_MARGIN

        val backgrounds = backgroundLuminances.ifEmpty { listOf(1.0) }
        val darkText = (backgrounds.minOrNull() ?: 1.0) >= APPEARANCE_MIDPOINT_LUMINANCE

        return if (darkText) {
            // Light surfaces -> dark text. Binding surface is the darkest (least headroom).
            val bindingBackground = backgrounds.minOrNull() ?: 1.0
            val maxLuminance = (bindingBackground + CONTRAST_FLARE) / floor - CONTRAST_FLARE
            val safeMax = maxOf(maxLuminance, 0.0)
            val targetLuminance = safeMax * (DARK_VARIETY_FLOOR + (1 - DARK_VARIETY_FLOOR) * varietyFraction)
            Hsb(hue, baseSaturation, brightnessFor(targetLuminance, hue, baseSaturation))
        } else {
            // Dark surfaces -> light text. Binding surface is the lightest (least headroom).
            val bindingBackground = backgrounds.maxOrNull() ?: 0.0
            val requiredLuminance = maxOf(floor * (bindingBackground + CONTRAST_FLARE) - CONTRAST_FLARE, 0.0)
            // A vivid hue caps the luminance it reaches at full brightness; desaturate until it can meet
            // the requirement. Legibility outranks saturation here; a gray reaches any luminance.
            var saturation = baseSaturation
            while (saturation > SATURATION_REACH_FLOOR &&
                luminance(hue, saturation, 1.0) < requiredLuminance
            ) {
                saturation = maxOf(SATURATION_REACH_FLOOR, saturation - SATURATION_RELAX_STEP)
            }
            val reachableMax = luminance(hue, saturation, 1.0)
            val ceiling = maxOf(requiredLuminance, minOf(reachableMax, LIGHT_LUMINANCE_CAP))
            val varied = requiredLuminance + (ceiling - requiredLuminance) * varietyFraction
            val targetLuminance = minOf(varied, reachableMax)
            Hsb(hue, saturation, brightnessFor(targetLuminance, hue, saturation))
        }
    }

    private fun hueFor(seed: ULong): Double {
        val sorted = hueAnchors.sorted()
        val index = (seed % sorted.size.toULong()).toInt()
        val anchor = sorted[index]
        val next = sorted[(index + 1) % sorted.size]
        val prev = sorted[(index - 1 + sorted.size) % sorted.size]
        val gapNext = circularGap(anchor, next)
        val gapPrev = circularGap(prev, anchor)
        val fraction = fraction(seed, HUE_JITTER_SHIFT) * 2 - 1
        val jitter = if (fraction >= 0) fraction * gapNext / 2 else fraction * gapPrev / 2
        val shifted = anchor + jitter
        return shifted % DEGREES_PER_CIRCLE + if (shifted < 0) DEGREES_PER_CIRCLE else 0.0
    }

    private fun pickSaturation(seed: ULong): Double =
        saturation.start + (saturation.endInclusive - saturation.start) * fraction(seed, SATURATION_SHIFT)

    private fun brightnessFor(target: Double, hue: Double, saturation: Double): Double {
        var low = 0.0
        var high = 1.0
        repeat(BRIGHTNESS_SEARCH_ITERATIONS) {
            val mid = (low + high) / 2
            if (luminance(hue, saturation, mid) < target) low = mid else high = mid
        }
        return (low + high) / 2
    }

    private fun luminance(hue: Double, saturation: Double, brightness: Double): Double {
        val rgb = hsbToRgb(hue, saturation, brightness)
        return WcagContrast.relativeLuminance(rgb.red, rgb.green, rgb.blue)
    }

    companion object {
        private const val DEGREES_PER_CIRCLE = 360.0
        private const val DEGREES_PER_SECTOR = 60.0
        private const val HSB_SECTOR_COUNT = 6
        private const val HSB_SECTOR_RED = 0
        private const val HSB_SECTOR_YELLOW = 1
        private const val HSB_SECTOR_GREEN = 2
        private const val HSB_SECTOR_CYAN = 3
        private const val HSB_SECTOR_BLUE = 4

        // Tunables (values frozen against Swift; do not drift — the golden vectors pin them).
        private const val SATURATION_REACH_FLOOR = 0.0
        private const val SATURATION_RELAX_STEP = 0.04
        private const val DARK_VARIETY_FLOOR = 0.45
        private const val LIGHT_LUMINANCE_CAP = 0.86
        private const val CONTRAST_SAFETY_MARGIN = 0.35
        private const val BRIGHTNESS_SEARCH_ITERATIONS = 24
        private const val APPEARANCE_MIDPOINT_LUMINANCE = 0.3
        private const val CONTRAST_FLARE = 0.05

        /** Brightness draw category avatars resolve at: darkest legible, so they read as deep swatches. */
        const val CATEGORY_DARKEST_VARIETY = 0.0

        // FNV-1a 64-bit constants.
        private const val FNV_OFFSET_BASIS = 0xCBF29CE484222325uL
        private const val FNV_PRIME = 0x100000001B3uL

        // Bit offsets into the hash giving independent 0..1 draws for jitter, saturation, and variety.
        private const val HUE_JITTER_SHIFT = 16
        private const val SATURATION_SHIFT = 32
        private const val VARIETY_SHIFT = 48
        private const val FRACTION_BITS = 0xFFFFuL
        private const val BYTE_MASK = 0xFF

        /**
         * Whether the glyph (initials / icon) on a fill of the given luminance should be white rather
         * than near-black. One of the two always clears AA for any fill. Mirrors Swift `glyphColor`.
         */
        fun glyphIsWhite(fillLuminance: Double): Boolean {
            val whiteContrast = (1.0 + CONTRAST_FLARE) / (fillLuminance + CONTRAST_FLARE)
            val blackContrast = (fillLuminance + CONTRAST_FLARE) / CONTRAST_FLARE
            return whiteContrast >= blackContrast
        }

        /**
         * FNV-1a 64-bit over the UTF-8 bytes of [string]. Stable across launches and free of the
         * order-insensitivity that makes XOR-folding collide anagrams. Exposed for the golden tests.
         */
        fun fnv1a(string: String): ULong {
            var hash = FNV_OFFSET_BASIS
            for (byte in string.encodeToByteArray()) {
                hash = hash xor (byte.toInt() and BYTE_MASK).toULong()
                hash *= FNV_PRIME
            }
            return hash
        }

        private fun fraction(seed: ULong, shift: Int): Double =
            ((seed shr shift) and FRACTION_BITS).toDouble() / FRACTION_BITS.toDouble()

        private fun circularGap(from: Double, to: Double): Double {
            val raw = (to - from) % DEGREES_PER_CIRCLE
            return if (raw <= 0) raw + DEGREES_PER_CIRCLE else raw
        }

        /**
         * Standard sRGB HSB-to-RGB conversion ([hue] in degrees). Matches SwiftUI's
         * `Color(hue:saturation:brightness:)` closely enough that the solver's luminance prediction
         * tracks the rendered color; the contrast safety margin absorbs any residual drift.
         */
        fun hsbToRgb(hue: Double, saturation: Double, brightness: Double): RgbColor {
            val h = hue.mod(DEGREES_PER_CIRCLE) / DEGREES_PER_SECTOR
            val sector = h.toInt() % HSB_SECTOR_COUNT
            val fraction = h - h.toInt()
            val p = brightness * (1 - saturation)
            val q = brightness * (1 - fraction * saturation)
            val t = brightness * (1 - (1 - fraction) * saturation)
            return when (sector) {
                HSB_SECTOR_RED -> RgbColor(brightness, t, p)
                HSB_SECTOR_YELLOW -> RgbColor(q, brightness, p)
                HSB_SECTOR_GREEN -> RgbColor(p, brightness, t)
                HSB_SECTOR_CYAN -> RgbColor(p, q, brightness)
                HSB_SECTOR_BLUE -> RgbColor(t, p, brightness)
                else -> RgbColor(brightness, p, q)
            }
        }
    }
}

/** Hue (degrees), saturation, and brightness a name resolves to, before constructing the color. */
data class Hsb(val hue: Double, val saturation: Double, val brightness: Double)
