package one.meshcore.shared.settings.ui

import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_path_hash_mode_label
import kotlin.test.Test
import kotlin.test.assertEquals

class PathHashModePickerLabelTest {
    @Test
    fun pickerUsesHashSizeLabel() {
        assertEquals(Res.string.settings_path_hash_mode_label, pathHashModePickerLabel())
    }
}
