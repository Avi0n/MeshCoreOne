// PortedFrom: MC1/Intents/SendMessageIntent.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Intents/SendMessageIntent+Routing.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Intents/MessageTargetResolution.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.platform.shortcuts

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.model.ContactType

sealed interface SendResult {
    data object Queued : SendResult
    /** The user must finish in the foreground; the message was NOT queued. */
    data object NeedsForeground : SendResult
    /** The user declined; nothing was queued. */
    data object Declined : SendResult
}

object SendRouting {
    /** CONNECTED is deliberately not ready: services are built after the rung flips. */
    fun route(state: DeviceConnectionState): SendRoute = when (state) {
        DeviceConnectionState.READY -> SendRoute.HEADLESS_QUEUE
        DeviceConnectionState.SYNCING -> SendRoute.QUEUE_AFTER_SYNC
        DeviceConnectionState.CONNECTED, DeviceConnectionState.CONNECTING -> SendRoute.FOREGROUND_ESCALATE
        DeviceConnectionState.DISCONNECTED -> SendRoute.NOT_CONNECTED
    }

    fun disconnectedRoute(hasRestorableRadio: Boolean): SendRoute =
        if (hasRestorableRadio) SendRoute.FOREGROUND_ESCALATE else SendRoute.NOT_CONNECTED

    /** Only chat contacts can receive a DM; channel text is capped at the node-name-adjusted budget. */
    fun validate(message: String, recipient: ShortcutRecipient, nodeNameByteCount: Int) {
        val bytes = message.toByteArray(Charsets.UTF_8).size
        when (recipient) {
            is ShortcutRecipient.Contact -> {
                if (recipient.dto.type != ContactType.CHAT) throw ShortcutException(ShortcutError.INVALID_RECIPIENT)
                if (bytes > ProtocolLimits.MAX_DIRECT_MESSAGE_LENGTH) throw ShortcutException(ShortcutError.MESSAGE_TOO_LONG)
            }
            is ShortcutRecipient.Channel -> {
                if (bytes > ProtocolLimits.maxChannelMessageLength(nodeNameByteCount.toLong())) {
                    throw ShortcutException(ShortcutError.MESSAGE_TOO_LONG)
                }
            }
        }
    }

    /** Chat contacts only: a DM target must be a person, never a repeater or room. */
    fun chatContacts(contacts: List<ContactDTO>): List<ContactDTO> = contacts.filter { it.type == ContactType.CHAT }

    /** Zero or duplicate digest matches fail safe to no match so a relocated channel cannot mis-route. */
    fun resolveUniqueChannel(id: String, channels: List<ChannelDTO>): ChannelDTO? =
        channels.filter { TargetIdentity.channelId(it) == id }.singleOrNull()

    /** Contacts then channels, each sorted by display name; ambiguous channels are omitted. */
    fun buildTargets(contacts: List<ContactDTO>, channels: List<ChannelDTO>, channelSubtitle: String): List<ShortcutTarget> {
        val byName = compareBy<ShortcutTarget, String>(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        val contactTargets = contacts.map { TargetIdentity.target(it) }.sortedWith(byName)
        val channelTargets = channels
            .filter { it.name.isNotEmpty() && resolveUniqueChannel(TargetIdentity.channelId(it), channels) != null }
            .map { TargetIdentity.target(it, channelSubtitle) }
            .sortedWith(byName)
        return contactTargets + channelTargets
    }
}

/**
 * Send-message flow. Authorization is explicit: nothing is queued unless the user confirms the
 * named recipient, and every precondition is re-checked after the unbounded confirmation await.
 */
class SendMessageFlow(
    private val radio: ShortcutRadioPort,
    private val confirmation: ShortcutConfirmationPort,
    private val text: ShortcutText,
) {
    suspend fun execute(targetId: String, message: String): SendResult {
        when (SendRouting.route(radio.connectionState)) {
            SendRoute.FOREGROUND_ESCALATE -> return SendResult.NeedsForeground
            SendRoute.NOT_CONNECTED -> return when (SendRouting.disconnectedRoute(radio.hasRestorableRadio)) {
                SendRoute.FOREGROUND_ESCALATE -> SendResult.NeedsForeground
                else -> throw ShortcutException(ShortcutError.NOT_CONNECTED)
            }
            SendRoute.HEADLESS_QUEUE, SendRoute.QUEUE_AFTER_SYNC -> Unit
        }
        val recipient = resolveRecipient(targetId)
        SendRouting.validate(message, recipient, radio.connectedNodeNameByteCount)
        val request = ConfirmationRequest.SendMessage(recipientName(recipient), message, text.sendConfirm(recipientName(recipient)))
        if (!confirmation.confirm(request)) return SendResult.Declined
        return enqueueConfirmed(message, recipient)
    }

    /** Re-resolves against the live radio; stale, other-radio or unparseable ids are invalid. */
    internal suspend fun resolveRecipient(targetId: String): ShortcutRecipient {
        val parsed = TargetIdentity.parse(targetId) ?: throw ShortcutException(ShortcutError.INVALID_RECIPIENT)
        val live = radio.currentRadioId ?: throw ShortcutException(ShortcutError.INVALID_RECIPIENT)
        if (parsed.radioId != live) throw ShortcutException(ShortcutError.INVALID_RECIPIENT)
        return when (parsed.kind) {
            TargetKind.CONTACT -> radio.contacts(live).firstOrNull { TargetIdentity.contactId(it) == targetId }
                ?.let { ShortcutRecipient.Contact(it) }
            TargetKind.CHANNEL -> SendRouting.resolveUniqueChannel(targetId, radio.channels(live))
                ?.let { ShortcutRecipient.Channel(it) }
        } ?: throw ShortcutException(ShortcutError.INVALID_RECIPIENT)
    }

    private suspend fun enqueueConfirmed(message: String, recipient: ShortcutRecipient): SendResult {
        if (!radio.connectionState.isOperational) return SendResult.NeedsForeground
        if (radio.currentRadioId != recipient.radioId) return SendResult.NeedsForeground
        SendRouting.validate(message, recipient, radio.connectedNodeNameByteCount)
        try {
            radio.queueMessage(recipient, message)
        } catch (error: ShortcutServicesUnavailableException) {
            return SendResult.NeedsForeground
        } catch (error: ShortcutException) {
            throw error
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ShortcutException(ShortcutError.SEND_FAILED, error)
        }
        return SendResult.Queued
    }

    private fun recipientName(recipient: ShortcutRecipient): String = when (recipient) {
        is ShortcutRecipient.Contact -> recipient.dto.displayName
        is ShortcutRecipient.Channel -> recipient.dto.name
    }
}
