package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_close
import one.meshcore.shared.resources.settings_support_thank_you_body
import one.meshcore.shared.resources.settings_support_thank_you_title
import one.meshcore.shared.ui.ExpressiveSheetContent
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.modalSheetExpandedValues
import org.jetbrains.compose.resources.stringResource

private val HEART_SIZE = 44.dp

/**
 * Warm thank-you after a verified tip or All Themes purchase, matching iOS `PurchaseThankYouSheet`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseThankYouSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = modalSheetExpandedValues,
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        ExpressiveSheetContent(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(HEART_SIZE),
                )
                Text(
                    text = stringResource(Res.string.settings_support_thank_you_title),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(Res.string.settings_support_thank_you_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(stringResource(Res.string.localizable_common_close))
                }
            }
        }
    }
}
