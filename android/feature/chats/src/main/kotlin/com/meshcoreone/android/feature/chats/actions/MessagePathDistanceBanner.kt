// PortedFrom: MC1/Views/Chats/Components/MessagePathPreviewMap.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Map/PathDistanceBanner.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.actions

import android.icu.number.NumberFormatter
import android.icu.number.Precision
import android.icu.util.LocaleData
import android.icu.util.MeasureUnit
import android.icu.util.ULocale
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.ui.sharedTouchTarget
import java.util.Locale

@Composable
fun MessagePathDistanceBanner(
    totalDistanceMeters: Double?,
    isDistanceIncomplete: Boolean,
    modifier: Modifier = Modifier,
) {
    if (totalDistanceMeters == null && !isDistanceIncomplete) return
    val locale = LocalResources.current.configuration.locales[0]
    val distance = remember(totalDistanceMeters, locale) {
        totalDistanceMeters?.let {
            require(it.isFinite() && it >= 0) { "Path distance must be finite and nonnegative" }
            if (Build.VERSION.SDK_INT >= 33) formatNativeRoadDistance(it, locale)
            else formatLegacyRoadDistance(it, locale)
        }

        @RequiresApi(33)
        private fun formatNativeRoadDistance(meters: Double, locale: Locale): String =
            NumberFormatter.withLocale(locale).unit(MeasureUnit.METER).usage("road")
                .unitWidth(NumberFormatter.UnitWidth.SHORT).format(meters).toString()

        private fun formatLegacyRoadDistance(meters: Double, locale: Locale): String {
            val system = LocaleData.getMeasurementSystem(ULocale.forLocale(locale))
            val imperial = system == LocaleData.MeasurementSystem.US || system == LocaleData.MeasurementSystem.UK
            // API 31–32 has no public road-usage formatter. Keep conventional whole-unit transitions,
            // with ICU's regional measurement system, localized units and two-significant-digit precision.
            val (unit, metersPerUnit) = when {
                !imperial && meters >= 1_000 -> MeasureUnit.KILOMETER to 1_000.0
                !imperial -> MeasureUnit.METER to 1.0
                meters >= 1_609.344 -> MeasureUnit.MILE to 1_609.344
                system == LocaleData.MeasurementSystem.UK -> MeasureUnit.YARD to 0.9144
                else -> MeasureUnit.FOOT to 0.3048
            }
            return NumberFormatter.withLocale(locale).unit(unit).unitWidth(NumberFormatter.UnitWidth.SHORT)
                .precision(Precision.maxSignificantDigits(2)).format(meters / metersPerUnit).toString()
        }
    }
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        distance?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (isDistanceIncomplete) {
            var showExplanation by rememberSaveable { mutableStateOf(false) }
            val label = stringResource(AppChatsStrings.chatsPathDistanceIncomplete)
            val explanation = stringResource(AppChatsStrings.chatsPathDistanceIncompleteExplanation)
            TextButton(
                onClick = { showExplanation = true },
                modifier = Modifier.sharedTouchTarget().semantics {
                    contentDescription = label
                    onClick(label = explanation, action = null)
                },
            ) {
                Text(label)
            }
            if (showExplanation) {
                AlertDialog(
                    onDismissRequest = { showExplanation = false },
                    title = { Text(stringResource(AppChatsStrings.chatsPathDistanceIncompleteTitle)) },
                    text = { Text(explanation, Modifier.verticalScroll(rememberScrollState())) },
                    confirmButton = {
                        TextButton(onClick = { showExplanation = false }, modifier = Modifier.sharedTouchTarget()) {
                            Text(stringResource(AppLocalizableStrings.commonDone))
                        }
                    },
                )
            }
        }
    }
}
