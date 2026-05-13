import SwiftUI
import UIKit
import MC1Services

extension ChatViewModel {

    // MARK: - Preview State Management

    /// Request preview fetch for a message (called when cell becomes visible)
    func requestPreviewFetch(for messageID: UUID) {
        // Ignore if already fetched or in progress
        guard previewStates[messageID] == nil || previewStates[messageID] == .idle else { return }

        // Get the display item to check for detected URL
        guard let displayItem = displayItems.first(where: { $0.messageID == messageID }),
              let url = displayItem.detectedURL else { return }

        // Check if channel message
        let isChannel = currentChannel != nil

        // Start fetch task
        previewFetchTasks[messageID] = Task {
            await fetchPreview(for: messageID, url: url, isChannelMessage: isChannel)
        }
    }

    /// Fetch preview for a message and update state
    private func fetchPreview(for messageID: UUID, url: URL, isChannelMessage: Bool) async {
        guard let dataStore, let linkPreviewCache else { return }

        // Check malware domain blocklist before fetching
        if let host = url.host(), await MalwareDomainFilter.shared.isBlocked(host) {
            previewStates[messageID] = .malwareWarning
            rebuildDisplayItem(for: messageID)
            return
        }

        // Update to loading state
        previewStates[messageID] = .loading
        rebuildDisplayItem(for: messageID)

        // Get preview from cache (handles all tiers: memory, database, network)
        let result = await linkPreviewCache.preview(
            for: url,
            using: dataStore,
            isChannelMessage: isChannelMessage
        )

        // Check if task was cancelled (message scrolled away or conversation changed)
        guard !Task.isCancelled else {
            previewFetchTasks.removeValue(forKey: messageID)
            return
        }

        // Update state based on result
        switch result {
        case .loaded(let dto):
            await decodeAndStorePreviewImages(from: dto, for: messageID)
            previewStates[messageID] = .loaded
            loadedPreviews[messageID] = dto
            // VoiceOver announcement for dynamic content
            if let title = dto.title {
                AccessibilityNotification.Announcement("Preview loaded: \(title)")
                    .post()
            }

        case .loading:
            // Still loading (duplicate request), keep current state
            break

        case .noPreviewAvailable, .failed:
            previewStates[messageID] = .noPreview

        case .disabled:
            previewStates[messageID] = .disabled
        }

        previewFetchTasks.removeValue(forKey: messageID)
        rebuildDisplayItem(for: messageID)
    }

    /// Manually fetch preview (for tap-to-load when previews disabled)
    func manualFetchPreview(for messageID: UUID) async {
        guard let displayItem = displayItems.first(where: { $0.messageID == messageID }),
              let url = displayItem.detectedURL,
              let dataStore,
              let linkPreviewCache else { return }

        previewStates[messageID] = .loading
        rebuildDisplayItem(for: messageID)

        let result = await linkPreviewCache.manualFetch(for: url, using: dataStore)

        switch result {
        case .loaded(let dto):
            await decodeAndStorePreviewImages(from: dto, for: messageID)
            previewStates[messageID] = .loaded
            loadedPreviews[messageID] = dto
            // VoiceOver announcement for dynamic content
            if let title = dto.title {
                AccessibilityNotification.Announcement("Preview loaded: \(title)")
                    .post()
            }
        case .loading:
            break
        case .noPreviewAvailable, .failed, .disabled:
            previewStates[messageID] = .noPreview
        }

        rebuildDisplayItem(for: messageID)
    }

    /// Decode preview hero image and icon off the main thread and store results
    private func decodeAndStorePreviewImages(from dto: LinkPreviewDataDTO, for messageID: UUID) async {
        async let heroResult: UIImage? = {
            guard let data = dto.imageData else { return nil }
            return await Task.detached { ImageURLDetector.downsampledImage(from: data) }.value
        }()
        async let iconResult: UIImage? = {
            guard let data = dto.iconData else { return nil }
            return await Task.detached { ImageURLDetector.downsampledImage(from: data) }.value
        }()
        let (hero, icon) = await (heroResult, iconResult)
        if hero != nil || icon != nil {
            decodedPreviewAssets[messageID] = DecodedPreviewAssets(image: hero, icon: icon)
        }
    }

    /// Update a message in place and rebuild its display item.
    func updateMessage(id: UUID, mutation: (inout MessageDTO) -> Void) {
        guard let index = messages.firstIndex(where: { $0.id == id }),
              let existing = messagesByID[id] else { return }
        let updated = existing.copy(mutation)
        messages[index] = updated
        messagesByID[id] = updated
        rebuildDisplayItem(for: id)
    }

    /// Rebuild a single display item with current preview state (O(1) lookup)
    func rebuildDisplayItem(for messageID: UUID) {
        guard let index = renderState.itemIndexByID[messageID] else { return }
        let item = renderState.items[index]
        let message = messagesByID[messageID]

        var items = renderState.items
        items[index] = MessageDisplayItem(
            messageID: item.messageID,
            showTimestamp: item.showTimestamp,
            showDirectionGap: item.showDirectionGap,
            showSenderName: item.showSenderName,
            showNewMessagesDivider: item.showNewMessagesDivider,
            detectedURL: item.detectedURL,
            isImageURL: item.isImageURL,
            isOutgoing: item.isOutgoing,
            status: item.status,
            containsSelfMention: item.containsSelfMention,
            mentionSeen: item.mentionSeen,
            heardRepeats: message?.heardRepeats ?? item.heardRepeats,
            retryAttempt: item.retryAttempt,
            maxRetryAttempts: item.maxRetryAttempts,
            reactionSummary: message?.reactionSummary,
            previewState: previewStates[messageID] ?? .idle,
            loadedPreview: loadedPreviews[messageID]
        )
        renderState = renderState.with(items: items)
    }

    /// Cancel preview fetch for a message (called when cell scrolls away)
    func cancelPreviewFetch(for messageID: UUID) {
        previewFetchTasks[messageID]?.cancel()
        previewFetchTasks.removeValue(forKey: messageID)
    }

    /// Clear all preview state (called on conversation switch)
    func clearPreviewState() {
        previewFetchTasks.values.forEach { $0.cancel() }
        previewFetchTasks.removeAll()
        previewStates.removeAll()
        loadedPreviews.removeAll()
        decodedPreviewAssets.removeAll()
        legacyPreviewDecodeInFlight.removeAll()
        cachedURLs.removeAll()
        formattedTexts.removeAll()
        clearImageState()
    }

    /// Clean up preview state for a specific message (called on message deletion)
    func cleanupPreviewState(for messageID: UUID) {
        previewStates.removeValue(forKey: messageID)
        loadedPreviews.removeValue(forKey: messageID)
        decodedPreviewAssets.removeValue(forKey: messageID)
        formattedTexts.removeValue(forKey: messageID)
        previewFetchTasks[messageID]?.cancel()
        previewFetchTasks.removeValue(forKey: messageID)
        cleanupImageState(for: messageID)
    }

    // MARK: - Inline Image State Management

    /// Returns the pre-decoded UIImage for a message, if available
    func decodedImage(for messageID: UUID) -> UIImage? {
        decodedImages[messageID]
    }

    /// Returns the pre-decoded link preview hero image for a message
    func decodedPreviewImage(for messageID: UUID) -> UIImage? {
        decodedPreviewAssets[messageID]?.image
    }

    /// Returns the pre-decoded link preview icon for a message
    func decodedPreviewIcon(for messageID: UUID) -> UIImage? {
        decodedPreviewAssets[messageID]?.icon
    }

    /// Pre-decode images for legacy messages with embedded preview data
    func decodeLegacyPreviewImages() {
        for message in messages where message.linkPreviewURL != nil {
            let id = message.id
            let existing = decodedPreviewAssets[id]
            let needsImageDecode = message.linkPreviewImageData != nil && existing?.image == nil
            let needsIconDecode = message.linkPreviewIconData != nil && existing?.icon == nil
            guard needsImageDecode || needsIconDecode,
                  !legacyPreviewDecodeInFlight.contains(id) else { continue }

            let imageData = message.linkPreviewImageData
            let iconData = message.linkPreviewIconData

            legacyPreviewDecodeInFlight.insert(id)
            Task {
                async let heroResult: UIImage? = if needsImageDecode, let imageData {
                    await Task.detached { ImageURLDetector.downsampledImage(from: imageData) }.value
                } else {
                    existing?.image
                }
                async let iconResult: UIImage? = if needsIconDecode, let iconData {
                    await Task.detached { ImageURLDetector.downsampledImage(from: iconData) }.value
                } else {
                    existing?.icon
                }
                let (hero, icon) = await (heroResult, iconResult)
                if hero != nil || icon != nil {
                    self.decodedPreviewAssets[id] = DecodedPreviewAssets(image: hero, icon: icon)
                }
                self.legacyPreviewDecodeInFlight.remove(id)
            }
        }
    }

    /// Returns whether the image for a message is a GIF
    func isGIFImage(for messageID: UUID) -> Bool {
        imageIsGIF[messageID] ?? false
    }

    /// Returns the raw image data for a message, if available
    func imageData(for messageID: UUID) -> Data? {
        loadedImageData[messageID]
    }

    /// Clears the negative cache entry for a failed image and re-triggers the fetch.
    func retryImageFetch(for messageID: UUID) async {
        guard previewStates[messageID] != .malwareWarning else { return }
        guard let displayItem = displayItems.first(where: { $0.messageID == messageID }),
              let url = displayItem.detectedURL else { return }

        let directURL = ImageURLDetector.directImageURL(for: url)
        await InlineImageCache.shared.clearFailure(for: directURL)

        previewStates[messageID] = .idle
        rebuildDisplayItem(for: messageID)
        requestImageFetch(for: messageID, showInlineImages: true)
    }

    /// Request inline image fetch for a message (called when cell becomes visible)
    func requestImageFetch(for messageID: UUID, showInlineImages: Bool) {
        guard showInlineImages else { return }
        guard previewStates[messageID] == nil || previewStates[messageID] == .idle else { return }

        guard let displayItem = displayItems.first(where: { $0.messageID == messageID }),
              displayItem.isImageURL,
              let url = displayItem.detectedURL else { return }

        imageFetchTasks[messageID] = Task {
            await fetchInlineImage(for: messageID, url: url)
        }
    }

    /// Fetch inline image data and update state
    private func fetchInlineImage(for messageID: UUID, url: URL) async {
        let directURL = ImageURLDetector.directImageURL(for: url)

        // Check malware domain blocklist before fetching
        if let host = directURL.host(), await MalwareDomainFilter.shared.isBlocked(host) {
            previewStates[messageID] = .malwareWarning
            rebuildDisplayItem(for: messageID)
            return
        }

        previewStates[messageID] = .loading
        rebuildDisplayItem(for: messageID)
        let result = await InlineImageCache.shared.fetchImageData(for: directURL)

        guard !Task.isCancelled else {
            imageFetchTasks.removeValue(forKey: messageID)
            return
        }
        guard displayItemIndexByID[messageID] != nil else {
            imageFetchTasks.removeValue(forKey: messageID)
            return
        }

        switch result {
        case .loaded(let data):
            let isGIF = ImageURLDetector.isGIFData(data)
            imageIsGIF[messageID] = isGIF
            if !isGIF {
                loadedImageData[messageID] = data
            }
            let decoded: UIImage? = await Task.detached {
                if isGIF {
                    return ImageURLDetector.decodeGIFImage(from: data)
                } else {
                    return ImageURLDetector.downsampledImage(from: data)
                }
            }.value
            guard !Task.isCancelled, let decoded else {
                imageFetchTasks.removeValue(forKey: messageID)
                return
            }
            guard displayItemIndexByID[messageID] != nil else {
                imageFetchTasks.removeValue(forKey: messageID)
                return
            }
            decodedImages[messageID] = decoded
            previewStates[messageID] = .loaded

        case .loading:
            break

        case .failed:
            previewStates[messageID] = .noPreview
        }

        imageFetchTasks.removeValue(forKey: messageID)
        rebuildDisplayItem(for: messageID)
    }

    /// Cancel image fetch for a message
    func cancelImageFetch(for messageID: UUID) {
        imageFetchTasks[messageID]?.cancel()
        imageFetchTasks.removeValue(forKey: messageID)
    }

    /// Clean up image state for a specific message
    private func cleanupImageState(for messageID: UUID) {
        loadedImageData.removeValue(forKey: messageID)
        decodedImages.removeValue(forKey: messageID)
        imageIsGIF.removeValue(forKey: messageID)
        imageFetchTasks[messageID]?.cancel()
        imageFetchTasks.removeValue(forKey: messageID)
    }

    /// Clear all image state (called on conversation switch)
    private func clearImageState() {
        imageFetchTasks.values.forEach { $0.cancel() }
        imageFetchTasks.removeAll()
        loadedImageData.removeAll()
        decodedImages.removeAll()
        imageIsGIF.removeAll()
    }
}
