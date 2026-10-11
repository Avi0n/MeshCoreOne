// AndroidOnly: WP-407 Shortcuts use the same process store and connection owner; only confirmed operations enqueue.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.ui.isBatteryPresent
import com.meshcoreone.android.core.ui.percentage
import com.meshcoreone.android.platform.shortcuts.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger

internal class AppShortcutRadio(private val container: AppContainer) : ShortcutRadioPort {
    private val app get() = container.appState
    override val connectionState get() = app.connectionState
    override val currentRadioId get() = app.currentRadioId
    override val hasRestorableRadio get() = container.connectionPort.lastConnectedDeviceId != null
    override val connectedNodeNameByteCount get() = app.connectedDevice?.nodeName?.toByteArray(Charsets.UTF_8)?.size ?: 0
    override fun status() = RadioStatusSnapshot(connectionState, app.connectedDevice?.nodeName, null,
        app.batteryMonitor.deviceBattery?.takeIf { it.isBatteryPresent }
            ?.percentage(app.batteryMonitor.activeBatteryOcvArray(app.connectedDevice)))
    override suspend fun contacts(radioId: RadioId) = container.processStore.fetchContacts(radioId)
    override suspend fun channels(radioId: RadioId) = container.processStore.fetchChannels(radioId)
    private fun live(radioId: RadioId?) = container.sessions.current?.takeIf {
        it.token.radioId == radioId && connectionState == DeviceConnectionState.READY
    } ?: throw ShortcutException(ShortcutError.NOT_CONNECTED)
    override suspend fun queueMessage(recipient: ShortcutRecipient, text: String) {
        val session = live(recipient.radioId)
        val sender = container.chatsFeature.sender(recipient.radioId)
        val pending = try {
            when (recipient) {
                is ShortcutRecipient.Contact -> sender.createPendingDirect(text, recipient.dto, TextType.PLAIN, null)
                is ShortcutRecipient.Channel -> sender.createPendingChannel(text, recipient.dto.index, recipient.radioId, TextType.PLAIN)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            Logger.getLogger("com.mc1.Shortcuts").log(Level.WARNING, "Shortcut enqueue failed", failure)
            throw ShortcutException(ShortcutError.SEND_FAILED, failure)
        }
        container.uiScope.launch {
            try {
                check(live(recipient.radioId) === session) { "Radio connection changed after enqueue" }
                when (recipient) {
                    is ShortcutRecipient.Contact -> session.messageService.sendPendingDirectMessage(pending.id, recipient.dto)
                    is ShortcutRecipient.Channel -> session.messageService.sendPendingChannelMessage(pending.id)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                Logger.getLogger("com.mc1.Shortcuts").log(Level.WARNING, "Queued shortcut send failed; pending row retained", failure)
                container.chatsFeature.failPending(pending)
            }
        }
    }
    override suspend fun sendAdvert(flood: Boolean) {
        try { live(currentRadioId).advertisementService.sendSelfAdvertisement(flood) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ShortcutException) { throw failure }
        catch (failure: Exception) {
            Logger.getLogger("com.mc1.Shortcuts").log(Level.WARNING, "Shortcut advertisement failed", failure)
            throw ShortcutException(ShortcutError.ADVERT_FAILED, failure)
        }
    }
}
