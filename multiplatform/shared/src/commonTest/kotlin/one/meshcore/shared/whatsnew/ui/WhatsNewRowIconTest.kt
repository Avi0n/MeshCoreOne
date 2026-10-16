package one.meshcore.shared.whatsnew.ui

import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.whatsnew.WhatsNewCatalog
import kotlin.test.Test
import kotlin.test.assertEquals

class WhatsNewRowIconTest {
    @Test
    fun catalogSymbolsMapToIosMatchingIcons() {
        val items = WhatsNewCatalog.releases.single().items
        assertEquals(AppIcons.Translate, whatsNewRowIcon(items[0].symbol))
        assertEquals(AppIcons.Person, whatsNewRowIcon(items[1].symbol))
        assertEquals(AppIcons.Refresh, whatsNewRowIcon(items[2].symbol))
    }
}
