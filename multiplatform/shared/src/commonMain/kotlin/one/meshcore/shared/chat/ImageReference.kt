package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid

/**
 * Value-typed handle to an image that lives on the view model, not the bytes themselves: the bubble
 * resolves it to a concrete bitmap at render time via an image-resolver callback. Structural equality over
 * [cacheKey] + [role] keeps the enclosing fragment value-equatable so list diffing sees a real change when
 * the referenced image changes. Mirrors the Swift `ImageReference`.
 */
data class ImageReference(val cacheKey: Uuid, val role: Role) {
    enum class Role {
        INLINE,
        LINK_PREVIEW_IMAGE,
        LINK_PREVIEW_ICON,
    }
}
