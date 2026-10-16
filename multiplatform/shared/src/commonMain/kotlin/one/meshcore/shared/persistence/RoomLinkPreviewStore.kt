package one.meshcore.shared.persistence

import one.meshcore.shared.chat.linkpreview.LinkPreviewData
import one.meshcore.shared.chat.linkpreview.LinkPreviewStore

/** Room-backed [LinkPreviewStore] (tier 2 of the link-preview cache), mapping the DAO row to the cache model. */
class RoomLinkPreviewStore(private val dao: LinkPreviewDao) : LinkPreviewStore {
    override suspend fun fetchLinkPreview(url: String): LinkPreviewData? = dao.fetch(url)?.toData()

    override suspend fun saveLinkPreview(data: LinkPreviewData) = dao.upsert(data.toEntity())
}

private fun LinkPreviewEntity.toData(): LinkPreviewData = LinkPreviewData(
    url = url,
    title = title,
    imageBytes = imageBytes,
    iconBytes = iconBytes,
    imageWidth = imageWidth,
    imageHeight = imageHeight,
    fetchedAtEpochSeconds = fetchedAtEpochSeconds,
)

private fun LinkPreviewData.toEntity(): LinkPreviewEntity = LinkPreviewEntity(
    url = url,
    title = title,
    imageBytes = imageBytes,
    iconBytes = iconBytes,
    imageWidth = imageWidth,
    imageHeight = imageHeight,
    fetchedAtEpochSeconds = fetchedAtEpochSeconds,
)
