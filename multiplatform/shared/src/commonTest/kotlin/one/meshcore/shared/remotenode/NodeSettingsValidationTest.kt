package one.meshcore.shared.remotenode

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Identity and behavior field validation (structured, reject-not-clamp). */
class NodeSettingsValidationTest {
    @Test
    fun identityAcceptsValidFields() {
        val result = NodeSettingsValidation.validateIdentityFields("Repeater", 47.5, -122.3)
        assertFalse(result.hasErrors)
    }

    @Test
    fun identityRejectsLongNameAndOutOfRangeCoordinates() {
        val longName = "a".repeat(40)
        val result = NodeSettingsValidation.validateIdentityFields(longName, 91.0, -181.0)
        assertTrue(result.nameTooLong)
        assertTrue(result.latitudeOutOfRange)
        assertTrue(result.longitudeOutOfRange)
    }

    @Test
    fun identityRejectsNonFiniteCoordinate() {
        val result = NodeSettingsValidation.validateIdentityFields(null, Double.NaN, null)
        assertTrue(result.latitudeOutOfRange)
        assertFalse(result.longitudeOutOfRange)
    }

    @Test
    fun behaviorTreatsZeroIntervalsAsDisabled() {
        // advertInterval, floodInterval, floodMaxHops; 0 disables the two intervals.
        assertFalse(NodeSettingsValidation.validateBehaviorFields(0, 0, 0).hasErrors)
    }

    @Test
    fun behaviorAcceptsInRangeValues() {
        assertFalse(NodeSettingsValidation.validateBehaviorFields(120, 24, 8).hasErrors)
    }

    @Test
    fun behaviorRejectsOutOfRangeValues() {
        val result = NodeSettingsValidation.validateBehaviorFields(30, 200, 65)
        assertTrue(result.advertIntervalInvalid)
        assertTrue(result.floodIntervalInvalid)
        assertTrue(result.floodMaxHopsInvalid)
    }
}
