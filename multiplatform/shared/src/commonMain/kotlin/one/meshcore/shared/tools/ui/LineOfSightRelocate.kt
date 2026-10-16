package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_line_of_sight_point_a
import one.meshcore.shared.resources.tools_line_of_sight_point_b
import one.meshcore.shared.resources.tools_line_of_sight_relocating
import one.meshcore.shared.resources.tools_line_of_sight_repeater
import one.meshcore.shared.resources.tools_line_of_sight_tap_map_instruction
import one.meshcore.shared.tools.LosPointId
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The banner shown while a point is being moved. Naming the point is the load-bearing part: three of them
 * can be relocated, and "tap the map" alone leaves the user guessing which one is about to move.
 */
@Composable
internal fun RelocatingBanner(pointName: String, modifier: Modifier = Modifier) {
    val relocating = formattedStringResource(Res.string.tools_line_of_sight_relocating, pointName)
    val instruction = stringResource(Res.string.tools_line_of_sight_tap_map_instruction)
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$relocating $instruction"
        },
        horizontalAlignment = Alignment.Start,
    ) {
        Text(relocating, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            text = instruction,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The shipped name for a line-of-sight point, shared by the place button and the relocating banner. */
internal fun LosPointId.labelResource() = when (this) {
    LosPointId.POINT_A -> Res.string.tools_line_of_sight_point_a
    LosPointId.POINT_B -> Res.string.tools_line_of_sight_point_b
    LosPointId.REPEATER -> Res.string.tools_line_of_sight_repeater
}
