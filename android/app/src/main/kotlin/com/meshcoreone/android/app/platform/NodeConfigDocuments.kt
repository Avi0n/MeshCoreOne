// AndroidOnly: WP-318 JSON SAF consumer reusing the established picker fencing and incomplete-write cleanup.
package com.meshcoreone.android.app.platform

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.meshcoreone.android.feature.settings.app.backup.BackupPickOutcome
import com.meshcoreone.android.feature.settings.app.backup.BackupSaveOutcome
import com.meshcoreone.android.feature.settings.app.nodeconfig.NodeConfigDocuments

internal fun nodeConfigDocuments(documents: BackupDocuments): NodeConfigDocuments = object : NodeConfigDocuments {
    override suspend fun pick(): ByteArray? = when (val picked = documents.pick()) {
        BackupPickOutcome.Cancelled -> null
        is BackupPickOutcome.Picked -> picked.source.readBytes()
    }
    override suspend fun save(name: String, bytes: ByteArray): Boolean =
        documents.save(name, bytes) is BackupSaveOutcome.Saved
}

@Composable
internal fun rememberNodeConfigDocuments(): NodeConfigDocuments {
    val context = LocalContext.current
    var receiver by remember { mutableStateOf<BackupDocuments?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { receiver?.result(it) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { receiver?.result(it) }
    val documents = remember(context, create, open) {
        // Node configuration is plain JSON, not the size-limited compressed backup envelope.
        BackupDocuments(context, create::launch, { open.launch(arrayOf("application/json")) }, validateSize = {})
    }
    SideEffect { receiver = documents }
    DisposableEffect(documents) { onDispose { documents.close() } }
    return remember(documents) { nodeConfigDocuments(documents) }
}
