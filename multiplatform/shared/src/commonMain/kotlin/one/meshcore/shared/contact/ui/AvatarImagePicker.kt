package one.meshcore.shared.contact.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A platform photo-picker handle for choosing a contact avatar. The picker cannot live in shared code
 * because the Android system photo picker needs `androidx.activity` compose APIs that only the app
 * module depends on, so the platform provides its implementation through [LocalAvatarImagePicker].
 * [isSupported] gates the choose-photo affordance; a platform without a picker (desktop) hides it.
 */
interface AvatarImagePicker {
    val isSupported: Boolean

    /** Opens the platform photo picker; the picked image's raw (un-processed) bytes flow to [onPicked]. */
    fun launch(onPicked: (ByteArray) -> Unit)

    /**
     * Opens a generic image-file picker (iOS Files / Android Storage Access Framework). Photo-picker
     * platforms that also expose a document picker override this; others hide the Choose File row.
     */
    val isFilePickerSupported: Boolean get() = false

    fun launchFromFiles(onPicked: (ByteArray) -> Unit) = Unit
}

/** The picker used when a platform provides none: reports unsupported and never launches. */
object UnsupportedAvatarImagePicker : AvatarImagePicker {
    override val isSupported: Boolean = false

    override fun launch(onPicked: (ByteArray) -> Unit) = Unit
}

/** The active [AvatarImagePicker], supplied by the host platform; defaults to [UnsupportedAvatarImagePicker]. */
val LocalAvatarImagePicker = staticCompositionLocalOf<AvatarImagePicker> { UnsupportedAvatarImagePicker }
