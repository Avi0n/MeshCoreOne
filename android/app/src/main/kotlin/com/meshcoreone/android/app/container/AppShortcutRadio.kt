// AndroidOnly: WP-407 Shortcuts use the same process store and connection owner; only confirmed operations enqueue.
// ReferenceInput: MC1/Intents/SendMessageIntent.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Intents/SendMessageIntent+Routing.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Intents/SendAdvertIntent.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceError
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.services.contacts.AdvertisementError
import com.meshcoreone.android.core.services.contacts.ChannelServiceError
import com.meshcoreone.android.core.ui.isBatteryPresent
import com.meshcoreone.android.core.ui.percentage
import com.meshcoreone.android.platform.shortcuts.*
import kotlinx.coroutines.CancellationException
import java.util.logging.Level
import java.util.logging.Logger

internal class AppShortcutRadio(private val container: AppContainer) : ShortcutRadioPort {
    private val app get() = container.appState
    override val connectionState get() = app.connectionState
    override val currentRadioId get() = app.currentRadioId
    override val hasRestorableRadio get() = container.connectionPort.lastConnectedDeviceId != null
    override val connectedNodeNameByteCount get() = app.connectedDevice?.nodeName?.toByteArray(Charsets.UTF_8)?.size ?: 0
    override fun status() = RadioStatusSnapshot(connectionState, app.connectedDevice?.nodeName,
        container.connectionPort.lastConnectedDeviceName,
        app.batteryMonitor.deviceBattery?.takeIf { it.isBatteryPresent }
            ?.percentage(app.batteryMonitor.activeBatteryOcvArray(app.connectedDevice)))
    override suspend fun contacts(radioId: RadioId) = container.processStore.fetchContacts(radioId)
    override suspend fun channels(radioId: RadioId) = container.processStore.fetchChannels(radioId)
    internal suspend fun targets(channelSubtitle: String): List<ShortcutTarget> {
        val radioId = currentRadioId ?: return emptyList()
        return SendRouting.buildTargets(SendRouting.chatContacts(contacts(radioId)), channels(radioId), channelSubtitle)
    }
    private fun live(radioId: RadioId?) = container.sessions.current?.takeIf {
        it.token.radioId == radioId && connectionState == DeviceConnectionState.READY
    } ?: throw ShortcutException(ShortcutError.NOT_CONNECTED)
    override suspend fun queueMessage(recipient: ShortcutRecipient, text: String) {
        if (!connectionState.isOperational || currentRadioId != recipient.radioId) {
            throw ShortcutException(ShortcutError.NOT_CONNECTED)
        }
        val session = container.sessions.current ?: throw ShortcutServicesUnavailableException()
        if (session.token.radioId != recipient.radioId) throw ShortcutException(ShortcutError.NOT_CONNECTED)
        val localNodeName = app.connectedDevice?.takeIf { it.radioId == recipient.radioId }?.nodeName
        SendRouting.validate(text, recipient, localNodeName?.toByteArray(Charsets.UTF_8)?.size ?: 0)
        val pending = try {
            when (recipient) {
                is ShortcutRecipient.Contact -> session.messageService.createPendingMessage(text, recipient.dto, TextType.PLAIN, null)
                is ShortcutRecipient.Channel -> session.messageService.createPendingChannelMessage(text, recipient.dto.index, recipient.radioId, TextType.PLAIN)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            throw sendFailure(failure)
        }
        app.messageEventStream.send(when (recipient) {
            is ShortcutRecipient.Contact -> MessageEvent.DirectMessageReceived(pending, recipient.dto)
            is ShortcutRecipient.Channel -> MessageEvent.ChannelMessageReceived(pending, recipient.dto.index)
        })
        try {
            when (recipient) {
                is ShortcutRecipient.Contact -> session.chatSendQueueService.enqueueDM(
                    DirectMessageEnvelope(pending.id, recipient.dto.id),
                )
                is ShortcutRecipient.Channel -> session.chatSendQueueService.enqueueChannel(
                    ChannelMessageEnvelope(
                        pending.id, recipient.dto.index, false, pending.text, pending.timestamp, localNodeName,
                    ),
                )
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            try {
                container.chatsFeature.failPending(pending)
            } catch (cleanup: PersistenceStoreException) {
                Logger.getLogger("com.mc1.Shortcuts").log(Level.WARNING, "Mark shortcut pending send failed", cleanup)
            }
            throw sendFailure(failure)
        }
        app.refreshConversations()
    }
    internal fun sendFailure(failure: Exception): ShortcutException {
        if (failure is ShortcutException) return failure
        Logger.getLogger("com.mc1.Shortcuts").log(Level.WARNING, "Shortcut enqueue failed", failure)
        val error = when (failure) {
            is MessageServiceException -> when (val serviceError = failure.error) {
                MessageServiceError.NotConnected -> ShortcutError.NOT_CONNECTED
                MessageServiceError.ContactNotFound, MessageServiceError.ChannelNotFound,
                MessageServiceError.InvalidRecipient -> ShortcutError.INVALID_RECIPIENT
                MessageServiceError.MessageTooLong -> ShortcutError.MESSAGE_TOO_LONG
                is MessageServiceError.SessionError -> ShortcutError.SessionError(serviceError.underlying)
                else -> ShortcutError.SEND_FAILED
            }
            is ChannelServiceError -> when (failure) {
                is ChannelServiceError.NotConnected -> ShortcutError.NOT_CONNECTED
                is ChannelServiceError.ChannelNotFound, is ChannelServiceError.InvalidChannelIndex -> ShortcutError.INVALID_RECIPIENT
                is ChannelServiceError.SessionError -> ShortcutError.SessionError(failure.error)
                else -> ShortcutError.SEND_FAILED
            }
            is ChatSendQueueServiceException -> when (failure.error) {
                ChatSendQueueServiceError.NotConnected -> ShortcutError.NOT_CONNECTED
                is ChatSendQueueServiceError.PersistFailed -> ShortcutError.SEND_FAILED
            }
            else -> ShortcutError.SEND_FAILED
        }
        return ShortcutException(error, failure)
    }
    override suspend fun sendAdvert(flood: Boolean) {
        try { live(currentRadioId).advertisementService.sendSelfAdvertisement(flood) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ShortcutException) { throw failure }
        catch (failure: Exception) {
            throw advertFailure(failure)
        }
    }
    internal fun advertFailure(failure: Exception): ShortcutException {
        if (failure is ShortcutException) return failure
        Logger.getLogger("com.mc1.Shortcuts").log(Level.WARNING, "Shortcut advertisement failed", failure)
        val error = when (failure) {
            is AdvertisementError -> when (failure) {
                is AdvertisementError.NotConnected -> ShortcutError.NOT_CONNECTED
                is AdvertisementError.SessionError -> ShortcutError.SessionError(failure.error)
                is AdvertisementError.SendFailed, is AdvertisementError.InvalidResponse -> ShortcutError.ADVERT_FAILED
            }
            is MeshCoreException -> ShortcutError.SessionError(failure)
            else -> ShortcutError.ADVERT_FAILED
        }
        return ShortcutException(error, failure)
    }
}
