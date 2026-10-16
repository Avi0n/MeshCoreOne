package one.meshcore.shared.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LineOfSightRepeaterOwnershipTest {
    @Test
    fun emptyThenPopulatedFitsOnceAndFurtherReloadDoesNotMoveCamera() = runTest {
        val radio = Uuid.random()
        var contacts = emptyList<ContactDto>()
        val model = LineOfSightViewModel(backgroundScope, fetchContacts = { contacts })
        model.configure({ radio })
        model.loadRepeaters()
        runCurrent()
        assertFalse(model.hasAppliedInitialCameraFit)
        contacts = listOf(contact(radio))
        model.loadRepeaters()
        runCurrent()
        val region = model.cameraRegion
        assertTrue(model.hasAppliedInitialCameraFit)
        assertEquals(1, model.cameraTargetVersion)
        contacts = listOf(contact(radio).copy(latitude = 40.0))
        model.loadRepeaters()
        runCurrent()
        assertEquals(region, model.cameraRegion)
        assertEquals(1, model.cameraTargetVersion)
    }

    @Test
    fun populatedLateLoadAfterUserPanCannotRefit() = runTest {
        val radio = Uuid.random()
        val pending = CompletableDeferred<List<ContactDto>>()
        val model = LineOfSightViewModel(backgroundScope, fetchContacts = { pending.await() })
        model.configure({ radio })
        model.loadRepeaters()
        runCurrent()
        val region = one.meshcore.shared.map.MapCameraRegion(LatLng(41.0, -120.0), 0.4, 0.6)
        model.acceptUserCameraRegion(region)
        pending.complete(listOf(contact(radio)))
        runCurrent()
        assertEquals(region, model.cameraRegion)
        assertNull(model.cameraTarget)
        assertEquals(0, model.cameraTargetVersion)
    }

    @Test
    fun radioSwitchClearsCatalogBeforeNewReadButRetainsUserAnalysis() = runTest {
        val first = Uuid.random()
        var radio: Uuid? = first
        val pending = CompletableDeferred<List<ContactDto>>()
        val model = LineOfSightViewModel(backgroundScope, fetchContacts = { id ->
            if (id == first) listOf(contact(first)) else pending.await()
        })
        model.configure({ radio })
        model.loadRepeaters()
        runCurrent()
        model.pointA = LosSelectedPoint(LatLng(42.0, -119.0), groundElevation = 10.0, additionalHeight = 25.0)
        model.setFrequencyMHz(915.0)
        val region = model.cameraRegion
        radio = null
        model.loadRepeaters()
        assertEquals(1, model.repeatersWithLocation.size)
        radio = Uuid.random()
        model.loadRepeaters()
        runCurrent()
        assertEquals(0, model.repeatersWithLocation.size)
        assertEquals(25.0, model.pointA?.additionalHeight)
        assertEquals(915.0, model.frequencyMHz)
        assertEquals(region, model.cameraRegion)
        pending.completeExceptionally(IllegalStateException("new catalog unavailable"))
        runCurrent()
        assertEquals(0, model.repeatersWithLocation.size)
    }

    @Test
    fun canceledOldLoadCannotOverwriteReplacementCatalog() = runTest {
        val first = Uuid.random()
        val second = Uuid.random()
        var radio = first
        val late = CompletableDeferred<Unit>()
        val replacement = contact(second)
        val model = LineOfSightViewModel(backgroundScope, fetchContacts = { id ->
            if (id == first) {
                withContext(NonCancellable) { late.await() }
                listOf(contact(first))
            } else {
                listOf(replacement)
            }
        })
        model.configure({ radio })
        model.loadRepeaters()
        runCurrent()
        radio = second
        model.loadRepeaters()
        runCurrent()
        late.complete(Unit)
        runCurrent()
        assertEquals(replacement.id, model.repeatersWithLocation.single().id)
    }

    private fun contact(radio: Uuid) = ContactDto(
        id = Uuid.random(), radioID = radio, publicKey = ByteArray(32), name = "Repeater",
        typeRawValue = ContactType.REPEATER.raw, flags = 0, outPathLength = 0, outPath = byteArrayOf(),
        lastAdvertTimestamp = 0, latitude = 38.0, longitude = -121.0, lastModified = 0,
        isBlocked = false, isMuted = false, isFavorite = false, unreadCount = 0,
    )
}
