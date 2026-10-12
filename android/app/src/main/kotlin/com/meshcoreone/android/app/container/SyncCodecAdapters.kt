// AndroidOnly: WP-303 Sync codec and reaction adapters.
// AndroidOnly: WP-216 Bind incoming reactions to the existing generation-owned service and parsers.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.model.DeduplicationKey
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.services.messaging.ChannelRXCorrelation
import com.meshcoreone.android.core.services.reactions.HeardRepeatsService
import com.meshcoreone.android.core.services.reactions.MeshCoreOpenReactionParser
import com.meshcoreone.android.core.services.reactions.ParsedDMReaction
import com.meshcoreone.android.core.services.reactions.ReactionParser
import com.meshcoreone.android.core.services.reactions.ReactionService
import com.meshcoreone.android.core.services.remote.CLIResponse
import com.meshcoreone.android.core.services.rendering.MentionUtilities
import com.meshcoreone.android.core.services.sync.SyncCLIEchoParsing
import com.meshcoreone.android.core.services.sync.SyncChannelRXCorrelating
import com.meshcoreone.android.core.services.sync.SyncDeduplicationKeying
import com.meshcoreone.android.core.services.sync.SyncHeardRepeatsServicing
import com.meshcoreone.android.core.services.sync.SyncMentionDetecting
import com.meshcoreone.android.core.services.sync.SyncMeshCoreOpenReactionParsing
import com.meshcoreone.android.core.services.sync.SyncMessageCodecs
import com.meshcoreone.android.core.services.sync.SyncParsedDMReaction
import com.meshcoreone.android.core.services.sync.SyncParsedMCOReaction
import com.meshcoreone.android.core.services.sync.SyncParsedMCOReactionV1
import com.meshcoreone.android.core.services.sync.SyncPendingDMReaction
import com.meshcoreone.android.core.services.sync.SyncPendingReaction
import com.meshcoreone.android.core.services.sync.SyncReactionParsing
import com.meshcoreone.android.core.services.sync.SyncReactionPersistResult
import com.meshcoreone.android.core.services.sync.SyncReactionServicing
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.contracts.domain.ReactionPersisting

/**
 * Historical diagnostic identities retained for existing acceptance receipts. These reaction capabilities
 * are now bound; this list is not the current graph's availability state.
 */
object ServiceVisibilityGaps {
    const val REACTION_TARGET_LOOKUP = "ReactionService.findTargetMessage/findDMTargetMessage are internal"
    const val REACTION_PENDING_QUEUE = "ReactionService.queuePendingReaction/queuePendingDMReaction are internal"
    const val MESHCORE_OPEN_REACTIONS = "MeshCoreOpenReactionParser (and its parsed types) is internal"

    val ALL: List<String> = listOf(
        REACTION_TARGET_LOOKUP, REACTION_PENDING_QUEUE, MESHCORE_OPEN_REACTIONS,
    )
}

/** Reports each degraded capability once; the sink receives a stable message, never message content. */
internal class GapReporter(private val sink: (String) -> Unit) {
    private val reported = ConcurrentHashMap.newKeySet<String>()

    val reportedGaps: Set<String> get() = reported.toSet()

    fun degraded(gap: String) {
        if (reported.add(gap)) {
            try {
                sink("Degraded capability: $gap")
            } catch (failure: Exception) {
                // A failing log sink must not change sync behavior.
            }
        }
    }
}

internal class SyncReactionAdapter(
    private val reactions: ReactionService,
) : SyncReactionServicing {
    override suspend fun indexDMMessage(id: UUID, contactID: UUID, text: String, timestamp: UInt): List<SyncPendingDMReaction> =
        reactions.indexDMMessage(id, contactID, text, timestamp).map {
            SyncPendingDMReaction(
                SyncParsedDMReaction(it.parsed.emoji, it.parsed.messageHash), it.contactID, it.senderName, it.rawText, it.radioId,
            )
        }

    override suspend fun indexMessage(
        id: UUID, channelIndex: UByte, senderName: String, text: String, timestamp: UInt,
    ): List<SyncPendingReaction> = reactions.indexMessage(id, channelIndex, senderName, text, timestamp).map {
        SyncPendingReaction(it.parsed, it.channelIndex, it.senderNodeName, it.rawText, it.radioId)
    }

    override suspend fun persistReactionAndUpdateSummary(
        reaction: ReactionDTO, dataStore: ReactionPersisting,
    ): SyncReactionPersistResult? = reactions.persistReactionAndUpdateSummary(reaction, dataStore)?.let {
        SyncReactionPersistResult(it.messageID, it.summary)
    }

    override suspend fun findDMTargetMessage(messageHash: String, contactID: UUID): UUID? =
        reactions.findDMTargetMessage(messageHash, contactID)

    override suspend fun queuePendingDMReaction(
        parsed: SyncParsedDMReaction, contactID: UUID, senderName: String, rawText: String, radioId: RadioId,
    ) = reactions.queuePendingDMReaction(
        ParsedDMReaction(parsed.emoji, parsed.messageHash), contactID, senderName, rawText, radioId,
    )

    override fun tryProcessAsReaction(text: String): ParsedReaction? = ReactionParser.parse(text)

    override suspend fun findTargetMessage(parsed: ParsedReaction, channelIndex: UByte): UUID? =
        reactions.findTargetMessage(parsed, channelIndex)

    override suspend fun queuePendingReaction(
        parsed: ParsedReaction, channelIndex: UByte, senderNodeName: String, rawText: String, radioId: RadioId,
    ) = reactions.queuePendingReaction(parsed, channelIndex, senderNodeName, rawText, radioId)
}

internal class SyncHeardRepeatsAdapter(
    private val heardRepeats: HeardRepeatsService,
) : SyncHeardRepeatsServicing {
    override suspend fun harvestIncomingPaths(message: MessageDTO, decodedCandidates: List<RxLogEntryDTO>) =
        heardRepeats.harvestIncomingPaths(message, decodedCandidates)

    override suspend fun recordDistinctPathIfNeeded(
        message: MessageDTO, pathNodes: Bytes, pathLength: UByte, snr: Double?, rssi: Long?,
        receivedAt: Instant, rxLogEntryID: UUID?,
    ): Long? = heardRepeats.recordDistinctPathIfNeeded(
        message, pathNodes, pathLength, snr, rssi, receivedAt, rxLogEntryID,
    )
}

internal object ReactionTextParsing : SyncReactionParsing {
    override fun parseDM(text: String): SyncParsedDMReaction? =
        ReactionParser.parseDM(text)?.let { SyncParsedDMReaction(it.emoji, it.messageHash) }

    override fun isReactionText(text: String, isDM: Boolean): Boolean =
        ReactionParser.isReactionText(text, isDM)
}

internal object MeshCoreOpenParsing : SyncMeshCoreOpenReactionParsing {
    override fun parse(text: String): SyncParsedMCOReaction? =
        MeshCoreOpenReactionParser.parse(text)?.let { SyncParsedMCOReaction(it.emoji, it.dartHash) }

    override fun parseV1(text: String): SyncParsedMCOReactionV1? =
        MeshCoreOpenReactionParser.parseV1(text)?.let {
            SyncParsedMCOReactionV1(it.emoji, it.timestampSeconds, it.senderNameHash, it.textHash)
        }

    override fun computeReactionHash(timestamp: UInt, senderName: String?, text: String): String =
        MeshCoreOpenReactionParser.computeReactionHash(timestamp, senderName, text)

    override fun dartStringHash(string: String): UInt = MeshCoreOpenReactionParser.dartStringHash(string)
}

internal fun syncMessageCodecs(): SyncMessageCodecs = SyncMessageCodecs(
    reactionParser = ReactionTextParsing,
    meshCoreOpenParser = MeshCoreOpenParsing,
    deduplicationKey = SyncDeduplicationKeying { contactID, channelIndex, senderNodeName, timestamp, content ->
        DeduplicationKey.contentBased(contactID, channelIndex, senderNodeName, timestamp, content)
    },
    channelRXCorrelation = SyncChannelRXCorrelating { entries, key -> ChannelRXCorrelation.matching(entries, key).toList() },
    mentions = SyncMentionDetecting { text, selfName -> MentionUtilities.containsSelfMention(text, selfName) },
    cliEcho = SyncCLIEchoParsing { text -> CLIResponse.splitEchoedPrefix(text)?.body },
)
