package one.meshcore.shared.remotenode

/** A fresh reading replaces only a field unchanged since its last accepted reading. */
internal inline fun <T> adoptSettingsReading(current: T?, original: T?, reading: T, adopt: (T) -> Unit) {
    if (current == original) adopt(reading)
}
