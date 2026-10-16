package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.settings.KeyGenerationService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Regenerating a device identity. A failed generate or replace still reports the mapped text and
 * clears the spinner. Cancellation and an error propagate and do not record a failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdentityRegenerationLaunchTest {
    @Test
    fun anEmptyPrefixGeneratesWithoutAVanityFilter() = runTest {
        val harness = IdentityHarness()
        launchIdentityGeneration(identityScope(), harness.generation())
        advanceUntilIdle()

        assertEquals(listOf<String?>(null), harness.prefixes)
        assertEquals(1, harness.generated)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(listOf<String?>(null), harness.prefixErrors)
        assertEquals(listOf(true, false), harness.generating)
        assertEquals(listOf(true, false), harness.busy)
    }

    @Test
    fun aValidPrefixIsPassedToGeneration() = runTest {
        val harness = IdentityHarness()
        launchIdentityGeneration(identityScope(), harness.generation(prefix = "ab"))
        advanceUntilIdle()

        assertEquals(listOf<String?>("ab"), harness.prefixes)
        assertEquals(1, harness.generated)
    }

    @Test
    fun anInvalidPrefixReportsTheLengthErrorAndDoesNotGenerate() = runTest {
        val harness = IdentityHarness()
        launchIdentityGeneration(identityScope(), harness.generation(prefix = "GG"))
        advanceUntilIdle()

        assertEquals(listOf<String?>(LENGTH), harness.prefixErrors)
        assertTrue(harness.prefixes.isEmpty())
        assertEquals(0, harness.generated)
        assertTrue(harness.generating.isEmpty())
        assertTrue(harness.busy.isEmpty())
    }

    @Test
    fun aReservedPrefixReportsTheReservedErrorAndDoesNotGenerate() = runTest {
        val harness = IdentityHarness()
        launchIdentityGeneration(identityScope(), harness.generation(prefix = "00"))
        advanceUntilIdle()

        assertEquals(listOf<String?>(RESERVED), harness.prefixErrors)
        assertTrue(harness.prefixes.isEmpty())
        assertEquals(0, harness.generated)
    }

    @Test
    fun maxAttemptsReportsTheGenerateDialog() = runTest {
        val harness = IdentityHarness()
        launchIdentityGeneration(
            identityScope(),
            harness.generation {
                throw KeyGenerationService.Error.MaxAttemptsExceeded
            },
        )
        advanceUntilIdle()

        assertEquals(listOf(null, MAX), harness.errors)
        assertEquals(listOf<String?>(null), harness.prefixErrors)
        assertEquals(0, harness.generated)
        assertEquals(listOf(true, false), harness.generating)
        assertEquals(listOf(true, false), harness.busy)
    }

    @Test
    fun aReservedPrefixFromGenerationStaysOnThePrefixField() = runTest {
        val harness = IdentityHarness()
        launchIdentityGeneration(
            identityScope(),
            harness.generation { throw KeyGenerationService.Error.ReservedPrefix },
        )
        advanceUntilIdle()

        assertEquals(listOf(null, RESERVED), harness.prefixErrors)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(0, harness.generated)
        assertEquals(listOf(true, false), harness.generating)
    }

    @Test
    fun anUnexpectedGenerateExceptionUsesTheRandomFailedText() = runTest {
        val harness = IdentityHarness()
        launchIdentityGeneration(
            identityScope(),
            harness.generation { throw IllegalArgumentException("bad seed") },
        )
        advanceUntilIdle()

        assertEquals(listOf(null, RANDOM), harness.errors)
        assertEquals(0, harness.generated)
        assertEquals(listOf(true, false), harness.busy)
    }

    @Test
    fun aCancelledGeneratePropagatesWithoutAFailure() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = IdentityHarness()
        val scope = identityScope()
        launchIdentityGeneration(
            scope,
            harness.generation { throw cancelled },
        )
        val child = scope.coroutineContext[Job]!!.children.single()
        child.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertSame(cancelled, harness.cause)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(listOf<String?>(null), harness.prefixErrors)
        assertEquals(0, harness.generated)
        assertEquals(listOf(true, false), harness.generating)
    }

    @Test
    fun aGenerateErrorPropagatesWithoutAFailure() = runTest {
        var thrown: Throwable? = null
        val probe = IdentityRegenerationProbeError()
        val harness = IdentityHarness()
        launchIdentityGeneration(
            identityScope { thrown = it },
            harness.generation { throw probe },
        )
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(0, harness.generated)
        assertEquals(listOf(true, false), harness.generating)
    }

    @Test
    fun aMissingIdentityDoesNotStartAReplace() = runTest {
        val harness = IdentityHarness()
        launchIdentityReplace(identityScope(), harness.replace(identity = null))
        advanceUntilIdle()

        assertTrue(harness.imported.isEmpty())
        assertTrue(harness.importing.isEmpty())
        assertTrue(harness.busy.isEmpty())
        assertEquals(0, harness.cleared)
        assertEquals(0, harness.haptics)
        assertEquals(0, harness.successes)
    }

    @Test
    fun aReplaceImportsTheExpandedKeyAndClearsTheDraft() = runTest {
        val harness = IdentityHarness()
        launchIdentityReplace(identityScope(), harness.replace())
        advanceUntilIdle()

        assertTrue(harness.imported.single().contentEquals(SAMPLE.expandedPrivateKey))
        assertEquals(1, harness.cleared)
        assertEquals(1, harness.haptics)
        assertEquals(1, harness.successes)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(listOf(true, false), harness.importing)
        assertEquals(listOf(true, false), harness.busy)
    }

    @Test
    fun aDisabledFeatureReportsThatMessage() = runTest {
        val harness = IdentityHarness()
        launchIdentityReplace(
            identityScope(),
            harness.replace(
                write = { throw MeshBridgeException(BridgeErrorCode.FEATURE_DISABLED, "off") },
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(null, FEATURE), harness.errors)
        assertEquals(0, harness.cleared)
        assertEquals(0, harness.haptics)
        assertEquals(0, harness.successes)
        assertEquals(listOf(true, false), harness.importing)
    }

    @Test
    fun aDeviceRejectionReportsThatMessage() = runTest {
        val harness = IdentityHarness()
        launchIdentityReplace(
            identityScope(),
            harness.replace(
                write = { throw MeshBridgeException(BridgeErrorCode.DEVICE_ERROR, "no") },
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(null, REJECTED), harness.errors)
        assertEquals(0, harness.successes)
        assertEquals(listOf(true, false), harness.busy)
    }

    @Test
    fun anUnexpectedReplaceExceptionUsesTheSessionText() = runTest {
        val harness = IdentityHarness()
        launchIdentityReplace(
            identityScope(),
            harness.replace(write = { throw IllegalStateException("timed out") }),
        )
        advanceUntilIdle()

        assertEquals(listOf(null, "timed out"), harness.errors)
        assertEquals(0, harness.haptics)
        assertEquals(listOf(true, false), harness.importing)
    }

    @Test
    fun aCancelledReplacePropagatesWithoutAFailure() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = IdentityHarness()
        val scope = identityScope()
        launchIdentityReplace(
            scope,
            harness.replace(write = { throw cancelled }),
        )
        val child = scope.coroutineContext[Job]!!.children.single()
        child.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertSame(cancelled, harness.cause)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(0, harness.cleared)
        assertEquals(0, harness.haptics)
        assertEquals(listOf(true, false), harness.importing)
    }

    @Test
    fun aReplaceErrorPropagatesWithoutAFailure() = runTest {
        var thrown: Throwable? = null
        val probe = IdentityRegenerationProbeError()
        val harness = IdentityHarness()
        launchIdentityReplace(
            identityScope { thrown = it },
            harness.replace(write = { throw probe }),
        )
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(0, harness.haptics)
        assertEquals(0, harness.successes)
        assertEquals(listOf(true, false), harness.importing)
    }
}

private class IdentityRegenerationProbeError : Error("probe")

private const val LENGTH = "prefix length"
private const val RESERVED = "prefix reserved"
private const val MAX = "could not generate"
private const val RANDOM = "secure random failed"
private const val FEATURE = "import not supported"
private const val REJECTED = "device rejected"

private val SAMPLE = KeyGenerationService.GeneratedIdentity(
    expandedPrivateKey = byteArrayOf(1, 2),
    publicKey = byteArrayOf(3, 4),
)

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.identityScope(onError: (Throwable) -> Unit = {}): CoroutineScope = CoroutineScope(
    SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error -> onError(error) },
)

private class IdentityHarness {
    val prefixErrors = mutableListOf<String?>()
    val errors = mutableListOf<String?>()
    val busy = mutableListOf<Boolean>()
    val generating = mutableListOf<Boolean>()
    val importing = mutableListOf<Boolean>()
    val prefixes = mutableListOf<String?>()
    val imported = mutableListOf<ByteArray>()
    var generated = 0
    var cleared = 0
    var haptics = 0
    var successes = 0
    var cause: Throwable? = null

    fun generation(
        prefix: String = "",
        generate: suspend (String?) -> KeyGenerationService.GeneratedIdentity = { hexPrefix ->
            prefixes += hexPrefix
            SAMPLE
        },
    ) = IdentityGeneration(
        prefix = prefix,
        lengthError = LENGTH,
        reservedError = RESERVED,
        maxAttemptsError = MAX,
        randomFailedError = RANDOM,
        onPrefixError = { prefixErrors += it },
        onError = { errors += it },
        onBusyChange = { busy += it },
        onGeneratingChange = { generating += it },
        onGenerated = { generated += 1 },
        generate = generate,
    )

    fun replace(
        identity: KeyGenerationService.GeneratedIdentity? = SAMPLE,
        write: suspend (ByteArray) -> Unit = { imported += it },
    ) = IdentityReplace(
        identity = identity,
        featureDisabledMessage = FEATURE,
        rejectedMessage = REJECTED,
        sessionText = { error -> error.message ?: "session" },
        onError = { errors += it },
        onBusyChange = { busy += it },
        onImportingChange = { importing += it },
        onImportPrivateKey = write,
        onCleared = { cleared += 1 },
        onHapticSuccess = { haptics += 1 },
        onSuccess = { successes += 1 },
    )
}
