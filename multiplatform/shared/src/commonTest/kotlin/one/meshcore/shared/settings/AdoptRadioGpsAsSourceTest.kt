package one.meshcore.shared.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first connection to a radio whose GPS is already on adopts that GPS as the source.
 */
class AdoptRadioGpsAsSourceTest {
    @Test
    fun anEnabledRadioGpsBecomesTheSourceWhenTheUserHasNeverChosen() {
        assertTrue(adoptRadioGpsAsSource(deviceGpsEnabled = true, hasSetGpsSource = false))
    }

    @Test
    fun aStoredSourceWinsOverAnEnabledRadioGps() {
        assertFalse(adoptRadioGpsAsSource(deviceGpsEnabled = true, hasSetGpsSource = true))
    }

    @Test
    fun aDisabledRadioGpsDoesNotBecomeTheSource() {
        assertFalse(adoptRadioGpsAsSource(deviceGpsEnabled = false, hasSetGpsSource = false))
    }
}
