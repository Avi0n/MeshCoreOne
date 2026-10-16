package one.meshcore.shared.tools

/**
 * Draft text for a Line-of-Sight numeric field, paired with the value that draft last committed.
 *
 * These fields commit on every keystroke, so the committed value must never be allowed to re-seed the
 * draft. Re-seeding re-initializes the box after the first digit — a typed "10" becomes "1.0" and the
 * clearance verdict is computed for a 1 m mast, a typed "915" becomes "9.015" and the Fresnel radius is
 * computed for the wrong band. Only a change that did not come from this field (relocating a point, an
 * elevation fetch, the radio's seeded frequency) re-seeds it.
 */
data class LosNumericDraft(val text: String, val committed: Double) {
    /**
     * The draft after the user types [newText]. The value is committed only when it parses and
     * [isAcceptable] admits it; otherwise the text is kept as typed and the last commit stands, so a
     * half-typed "1." or "-" never reaches the analysis.
     */
    fun typed(newText: String, isAcceptable: (Double) -> Boolean = { true }): LosNumericDraft {
        val parsed = parseLosNumber(newText)?.takeIf(isAcceptable) ?: return copy(text = newText)
        return LosNumericDraft(text = newText, committed = parsed)
    }

    /** The draft re-seeded from [value], unless [value] is what this field itself last committed. */
    fun synced(value: Double): LosNumericDraft = if (value == committed) this else forValue(value)

    companion object {
        fun forValue(value: Double): LosNumericDraft = LosNumericDraft(text = value.toString(), committed = value)
    }
}

/**
 * Parses a user-entered decimal, accepting "," as the decimal separator so comma-decimal locales
 * round-trip. Mirrors the iOS `parseFrequency` normalization. Non-finite input is rejected, since
 * "Infinity" and "NaN" both parse and would poison every downstream RF figure.
 */
fun parseLosNumber(text: String): Double? = text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
