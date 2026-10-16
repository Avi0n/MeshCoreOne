package one.meshcore.shared.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.meshcore.shared.chat.reactions.EmojiCatalog
import one.meshcore.shared.chat.reactions.EmojiItem
import one.meshcore.shared.chat.reactions.emojiAccessibilityName
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_chats_search_no_results_title
import one.meshcore.shared.resources.chats_reactions_emoji_category_activity
import one.meshcore.shared.resources.chats_reactions_emoji_category_flags
import one.meshcore.shared.resources.chats_reactions_emoji_category_foods
import one.meshcore.shared.resources.chats_reactions_emoji_category_frequent
import one.meshcore.shared.resources.chats_reactions_emoji_category_nature
import one.meshcore.shared.resources.chats_reactions_emoji_category_objects
import one.meshcore.shared.resources.chats_reactions_emoji_category_people
import one.meshcore.shared.resources.chats_reactions_emoji_category_places
import one.meshcore.shared.resources.chats_reactions_emoji_category_symbols
import one.meshcore.shared.resources.chats_reactions_emoji_search_placeholder
import one.meshcore.shared.resources.chats_reactions_title
import one.meshcore.shared.ui.AppSearchField
import one.meshcore.shared.ui.modalSheetExpandedValues
import one.meshcore.shared.ui.pickerSheetSizeModifier
import org.jetbrains.compose.resources.stringResource

/**
 * Full emoji picker: Emojibase categories, search over label/shortcodes/tags, and frequently used
 * (hidden while searching). Mirrors iOS `EmojiPickerSheet`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerSheet(
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    frequent: List<String> = emptyList(),
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = modalSheetExpandedValues,
    )
    var searchQuery by remember { mutableStateOf("") }
    var catalog by remember { mutableStateOf<EmojiCatalog?>(null) }
    LaunchedEffect(Unit) {
        catalog = EmojiCatalog.load()
    }
    val sections = remember(catalog, searchQuery, frequent) {
        catalog?.categories(searchQuery, frequent).orEmpty()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(pickerSheetSizeModifier(EMOJI_SHEET_HEIGHT_FRACTION).padding(bottom = 24.dp)) {
            Text(
                text = stringResource(Res.string.chats_reactions_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            AppSearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = stringResource(Res.string.chats_reactions_emoji_search_placeholder),
            )
            when {
                catalog == null -> Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                sections.isEmpty() -> Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(Res.string.android_chats_search_no_results_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(sections, key = { it.id }) { category ->
                        CategoryHeader(categoryTitle(category.id))
                        EmojiRows(emojis = category.emojis, onSelect = onSelect, onDismiss = onDismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

@Composable
private fun EmojiRows(emojis: List<EmojiItem>, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val rows = emojis.chunked(EMOJI_GRID_COLUMNS)
    Column(Modifier.padding(horizontal = 12.dp)) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth()) {
                for (emoji in row) {
                    val spoken = emoji.label.ifEmpty { emojiAccessibilityName(emoji.unicode) }
                    Text(
                        text = emoji.unicode,
                        fontSize = EMOJI_SIZE_SP.sp,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = spoken }
                            .clickable {
                                onSelect(emoji.unicode)
                                onDismiss()
                            }
                            .padding(10.dp),
                    )
                }
                repeat(EMOJI_GRID_COLUMNS - row.size) {
                    Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun categoryTitle(id: String): String = when (id) {
    "frequent" -> stringResource(Res.string.chats_reactions_emoji_category_frequent)
    "people" -> stringResource(Res.string.chats_reactions_emoji_category_people)
    "nature" -> stringResource(Res.string.chats_reactions_emoji_category_nature)
    "foods" -> stringResource(Res.string.chats_reactions_emoji_category_foods)
    "activity" -> stringResource(Res.string.chats_reactions_emoji_category_activity)
    "places" -> stringResource(Res.string.chats_reactions_emoji_category_places)
    "objects" -> stringResource(Res.string.chats_reactions_emoji_category_objects)
    "symbols" -> stringResource(Res.string.chats_reactions_emoji_category_symbols)
    "flags" -> stringResource(Res.string.chats_reactions_emoji_category_flags)
    else -> id
}

private const val EMOJI_GRID_COLUMNS = 6
private const val EMOJI_SIZE_SP = 28
private const val EMOJI_SHEET_HEIGHT_FRACTION = 0.65f
