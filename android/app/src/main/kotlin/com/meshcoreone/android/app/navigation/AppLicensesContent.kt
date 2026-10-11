// AndroidOnly: WP-407 Read actual packaged notices, including generated theme/data notices, without inventing license text.
package com.meshcoreone.android.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import kotlinx.coroutines.*

@Composable
internal fun AppLicensesContent(onDone: () -> Unit, onFailure: (Throwable) -> Unit) {
    val assets = LocalContext.current.assets
    var paths by remember { mutableStateOf<List<String>?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    BackHandler(selected != null) { selected = null }
    LaunchedEffect(assets, selected) {
        failed = false
        text = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                fun notices(path: String): List<String> = requireNotNull(assets.list(path)).flatMap {
                    val child = "$path/$it"
                    if (it.endsWith(".txt")) listOf(child) else notices(child)
                }
                val discovered = paths ?: notices("licenses").sorted().also {
                    check(it.isNotEmpty()) { "No packaged license notices" }
                }
                val content = selected?.let { path ->
                    assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }.also {
                        check(it.isNotBlank()) { "Empty packaged license: $path" }
                    }
                }
                discovered to content
            }
            paths = loaded.first
            text = loaded.second
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { failed = true; onFailure(failure) }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = { if (selected != null) selected = null else onDone() }) { Text(stringResource(L.commonDone)) }
        when {
            failed -> Text(stringResource(L.commonErrorFailedToLoad))
            paths == null || (selected != null && text == null) -> CircularProgressIndicator()
            selected != null -> LazyColumn { item { Text(requireNotNull(text), fontFamily = FontFamily.Monospace) } }
            else -> LazyColumn { items(paths.orEmpty(), key = { it }) { path ->
                TextButton(onClick = { selected = path }, modifier = Modifier.fillMaxWidth()) {
                    Text(path.removePrefix("licenses/").removeSuffix(".txt"))
                }
            } }
        }
    }
}
