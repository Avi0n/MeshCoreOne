// AndroidOnly: WP-407 Resumed-activity shortcut/share UI; cancellation never authorizes a radio command.
package com.meshcoreone.android.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.meshcoreone.android.app.container.AppContainer
import com.meshcoreone.android.app.container.AppShortcutRadio
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.platform.shortcuts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

private var activeShortcutHandler: ShortcutRequestHandler? = null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppShortcutContent(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val radio = remember(container) { AppShortcutRadio(container) }
    val text = remember(context) { AndroidShortcutText(context) }
    var confirmation by remember { mutableStateOf<Pair<ConfirmationRequest, CompletableDeferred<Boolean>>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var send by remember { mutableStateOf<ShortcutRequest.Send?>(null) }
    var targets by remember { mutableStateOf(emptyList<ShortcutTarget>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var resumedScope by remember { mutableStateOf<CoroutineScope?>(null) }
    val executor = remember(container, radio, text) {
        ShortcutRequestExecutor(radio, object : ShortcutConfirmationPort {
            override suspend fun confirm(request: ConfirmationRequest): Boolean {
                confirmation?.second?.complete(false)
                val answer = CompletableDeferred<Boolean>()
                confirmation = request to answer
                return try { answer.await() } finally { if (confirmation?.second === answer) confirmation = null }
            }
        }, text, object : ShortcutPresenter {
            override fun show(messageText: String) { message = messageText }
            override fun openApp(request: ShortcutRequest) {
                container.navigation.selectTab(AppTab.CHATS)
                if (request is ShortcutRequest.Send) {
                    send = request
                    selected = request.targetId
                    draft = request.message.orEmpty()
                }
            }
        })
    }
    val handler = remember(executor) { ShortcutRequestHandler { request ->
        checkNotNull(resumedScope) { "Shortcut handler requires a resumed host" }.launch { executor.run(request) }
    } }
    LifecycleResumeEffect(handler) {
        val lifetime = SupervisorJob(scope.coroutineContext[Job])
        resumedScope = CoroutineScope(scope.coroutineContext + lifetime)
        activeShortcutHandler = handler
        ShortcutHost.dispatcher.install(handler)
        onPauseOrDispose {
            if (activeShortcutHandler === handler) {
                ShortcutHost.dispatcher.uninstall()
                activeShortcutHandler = null
            }
            lifetime.cancel()
            resumedScope = null
            confirmation?.second?.complete(false)
            confirmation = null
            send = null
        }
    }
    LaunchedEffect(container) {
        combine(container.appState.contactsVersion, container.appState.conversationsVersion,
            container.appState.servicesVersion, container.appState.sessionStateChangeCount) { _, _, _, _ -> Unit }.collect {
            try {
                targets = radio.targets(text.channelSubtitle)
                if (!ShortcutPublisher(context, text).publish(targets)) {
                    java.util.logging.Logger.getLogger("com.mc1.Shortcuts").warning("System shortcut publication denied or rate limited")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                java.util.logging.Logger.getLogger("com.mc1.Shortcuts").log(java.util.logging.Level.WARNING, "Shortcut target loading failed", failure)
                message = text.error(ShortcutError.INVALID_RECIPIENT)
            }
        }
    }
    confirmation?.let { (request, answer) ->
        AlertDialog(onDismissRequest = { answer.complete(false) },
            title = { Text(request.prompt) },
            text = { if (request is ConfirmationRequest.SendMessage) Text(request.message) },
            confirmButton = { TextButton(onClick = { answer.complete(true) }) { Text(stringResource(L.commonOk)) } },
            dismissButton = { TextButton(onClick = { answer.complete(false) }) { Text(stringResource(L.commonCancel)) } })
    }
    message?.let { value -> AlertDialog(onDismissRequest = { message = null }, text = { Text(value) },
        confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(L.commonOk)) } }) }
    if (send != null) ModalBottomSheet(onDismissRequest = { send = null }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            OutlinedTextField(draft, { draft = it }, label = { Text(text.sendShortTitle) })
            targets.forEach { target ->
                Row {
                    RadioButton(selected == target.id, onClick = { selected = target.id })
                    Text(target.displayName)
                }
            }
            Button(enabled = selected != null && draft.isNotBlank(), onClick = {
                val request = ShortcutRequest.Send(selected, draft)
                send = null
                checkNotNull(resumedScope) { "Sending a shortcut requires a resumed host" }.launch { executor.run(request) }
            }) { Text(text.sendShortTitle) }
        }

    }
}
