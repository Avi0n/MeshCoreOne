package one.meshcore.shared.backup

/**
 * Returns a copy with both cached link-preview blobs dropped and [MessageDto.linkPreviewFetched]
 * cleared, so the restored row re-resolves its preview instead of trusting a stale image. Applied on
 * backup export and on import insert. The blobs are bytes fetched from an arbitrary remote host
 * named in a mesh message; a shared `.mc1backup` must not persist them, and they inflate the
 * envelope toward the 50 MiB compressed cap. The preview URL and title stay: they are the
 * message's own text, and re-fetching needs the URL. Mirrors Swift
 * `MessageDTO(from:includeLinkPreviewBlobs: false)` and `Message(dto:)` (blobs nil, fetched false).
 */
fun MessageDto.redactedForBackup(): MessageDto = copy(
    linkPreviewImageData = null,
    linkPreviewIconData = null,
    linkPreviewFetched = false,
)
