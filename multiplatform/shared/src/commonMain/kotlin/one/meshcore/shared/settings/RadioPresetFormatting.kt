package one.meshcore.shared.settings

/** Thousandths display that drops a trailing fraction when the scaled value is whole. */
internal fun formatThousandths(value: Double): String {
    val rounded = (value * THOUSANDTHS_SCALE).toLong() / THOUSANDTHS_SCALE
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

/** Preset subtitle: frequency, spreading factor, and coding rate. */
internal fun RadioPreset.detailLine(): String =
    "${formatThousandths(frequencyMHz)} MHz, SF$spreadingFactor, CR$codingRate"

private const val THOUSANDTHS_SCALE = 1000.0
