package one.meshcore.shared.connection

import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore

/** Single-slot bond verification, separate from the last-connection record a Wi-Fi link overwrites. */
internal interface LastConnectionBond {
    /** The handle holding the bond-verification slot, or null when no bond has verified. Distinct from
     *  [LastConnectionStore.handle]: a WiFi connection overwrites the connection slot without touching this. */
    suspend fun bondVerifiedHandle(): String?

    /** When [handle]'s bond last completed a verified encrypted session (epoch seconds), or null if the
     *  bond slot is empty or belongs to a different handle. Single-slot, mirroring Swift's keyed
     *  `bondVerificationDate(for:)`. */
    suspend fun bondVerificationEpochSeconds(handle: String): Double?

    /** Records that [handle]'s bond completed a verified encrypted session at [epochSeconds]. Single-slot
     *  and keyed to the handle, mirroring Swift `persistBondVerification`. Stamped on every successful BLE
     *  handshake; never on the WiFi path, which carries no bond. */
    suspend fun persistBondVerification(handle: String, epochSeconds: Double)

    /** Clears the bond-verification slot only when [handle] holds it (holder-match), so forgetting a
     *  non-holder radio cannot destroy another radio's cross-launch shield. Mirrors Swift's holder-matched
     *  bond clear in `clear(for:)`. */
    suspend fun clearBondVerification(handle: String)
}

internal class LastConnectionBondStore(private val prefs: PreferencesStore) : LastConnectionBond {
    override suspend fun bondVerifiedHandle(): String? = prefs.getString(PersistenceKeys.LAST_BOND_VERIFIED_HANDLE)

    override suspend fun bondVerificationEpochSeconds(handle: String): Double? {
        if (prefs.getString(PersistenceKeys.LAST_BOND_VERIFIED_HANDLE) != handle) return null
        return prefs.getDouble(PersistenceKeys.LAST_BOND_VERIFIED_EPOCH_SECONDS)
    }

    override suspend fun persistBondVerification(handle: String, epochSeconds: Double) {
        prefs.putString(PersistenceKeys.LAST_BOND_VERIFIED_HANDLE, handle)
        prefs.putDouble(PersistenceKeys.LAST_BOND_VERIFIED_EPOCH_SECONDS, epochSeconds)
    }

    override suspend fun clearBondVerification(handle: String) {
        if (prefs.getString(PersistenceKeys.LAST_BOND_VERIFIED_HANDLE) == handle) {
            prefs.remove(PersistenceKeys.LAST_BOND_VERIFIED_HANDLE)
            prefs.remove(PersistenceKeys.LAST_BOND_VERIFIED_EPOCH_SECONDS)
        }
    }
}
