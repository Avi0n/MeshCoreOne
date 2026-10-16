package one.meshcore.shared.chat.linkpreview

/**
 * The production [PreviewImageProcessor] for the running JVM target. Android decodes through
 * `android.graphics.BitmapFactory` (which exposes a bounds-only pre-pass so the decode bomb is refused
 * before allocation); desktop decodes through `javax.imageio` with source subsampling for the same bound.
 */
expect fun defaultPreviewImageProcessor(): PreviewImageProcessor
