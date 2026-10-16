@file:OptIn(ExperimentalMaterial3Api::class)

package one.meshcore.shared.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue

internal val modalSheetPartialValues: Set<SheetValue> = setOf(
    SheetValue.Hidden,
    SheetValue.PartiallyExpanded,
    SheetValue.Expanded,
)

internal val modalSheetExpandedValues: Set<SheetValue> = setOf(
    SheetValue.Hidden,
    SheetValue.Expanded,
)
