package one.meshcore.shared.ui.menu

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McMenuRoleTest {
    private val onSurface = Color(0xFF1A1C1E)
    private val onSurfaceVariant = Color(0xFF44474E)
    private val error = Color(0xFFB3261E)

    @Test
    fun destructiveUsesErrorForLabelAndIcon() {
        assertEquals(error, McMenuColors.labelColor(McMenuRole.Destructive, onSurface, error))
        assertEquals(error, McMenuColors.iconColor(McMenuRole.Destructive, onSurfaceVariant, error))
    }

    @Test
    fun defaultAndSelectedUseSurfaceRoles() {
        assertEquals(onSurface, McMenuColors.labelColor(McMenuRole.Default, onSurface, error))
        assertEquals(onSurface, McMenuColors.labelColor(McMenuRole.Selected, onSurface, error))
        assertEquals(
            onSurfaceVariant,
            McMenuColors.iconColor(McMenuRole.Default, onSurfaceVariant, error),
        )
        assertEquals(
            onSurfaceVariant,
            McMenuColors.iconColor(McMenuRole.Selected, onSurfaceVariant, error),
        )
    }

    @Test
    fun destructiveStaysDestructiveAndStillShowsTheCheck() {
        assertEquals(
            McMenuRole.Destructive,
            mcMenuEffectiveRole(McMenuRole.Destructive, selected = true),
        )
        assertTrue(mcMenuShowsCheck(McMenuRole.Destructive, selected = true))
        assertEquals(
            McMenuRole.Destructive,
            mcMenuEffectiveRole(McMenuRole.Destructive, selected = false),
        )
        assertFalse(mcMenuShowsCheck(McMenuRole.Destructive, selected = false))
    }

    @Test
    fun selectionUsesTheSelectedRoleAndACheck() {
        assertEquals(McMenuRole.Selected, mcMenuEffectiveRole(McMenuRole.Default, selected = true))
        assertTrue(mcMenuShowsCheck(McMenuRole.Default, selected = true))
        assertEquals(McMenuRole.Selected, mcMenuEffectiveRole(McMenuRole.Selected, selected = false))
        assertTrue(mcMenuShowsCheck(McMenuRole.Selected, selected = false))
    }

    @Test
    fun plainRowsStayDefaultWithoutACheck() {
        assertEquals(McMenuRole.Default, mcMenuEffectiveRole(McMenuRole.Default, selected = false))
        assertFalse(mcMenuShowsCheck(McMenuRole.Default, selected = false))
    }

    @Test
    fun disabledColorFadesAndEnabledColorStays() {
        assertEquals(onSurface, onSurface.mcMenuEnabledColor(enabled = true))
        assertEquals(
            onSurface.copy(alpha = MC_MENU_DISABLED_ALPHA),
            onSurface.mcMenuEnabledColor(enabled = false),
        )
    }

    @Test
    fun fabMenuItemCountBounds() {
        assertFalse(McFabMenuRules.isValidItemCount(0))
        assertFalse(McFabMenuRules.isValidItemCount(1))
        assertTrue(McFabMenuRules.isValidItemCount(2))
        assertTrue(McFabMenuRules.isValidItemCount(6))
        assertFalse(McFabMenuRules.isValidItemCount(7))
    }
}
