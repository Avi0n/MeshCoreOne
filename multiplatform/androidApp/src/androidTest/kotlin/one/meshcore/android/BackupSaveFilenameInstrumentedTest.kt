package one.meshcore.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.CancellationException
import one.meshcore.android.BackupSaveFilenameFixture.Mode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.FileNotFoundException

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BackupSaveFilenameInstrumentedTest {
    @get:Rule val testName = TestName()

    @Test fun chosenDocumentName() = checkName(Mode.NAME)

    @Test fun unicodeAndWhitespacePreserved() = checkName(Mode.NAME, "  Shift ü ☂.mc1backup  ")

    @Test fun providerCollisionName() = checkName(Mode.NAME, "Shift notes (1).mc1backup")

    @Test fun earlyPickerResultName() = checkName(Mode.NAME, early = true)

    @Test fun nullCursorKeepsSuggestedName() = checkName(Mode.NULL_CURSOR)

    @Test fun emptyCursorKeepsSuggestedName() = checkName(Mode.EMPTY_CURSOR)

    @Test fun missingColumnKeepsSuggestedName() = checkName(Mode.MISSING_COLUMN)

    @Test fun nullNameKeepsSuggestedName() = checkName(Mode.NULL_NAME)

    @Test fun blankNameKeepsSuggestedName() = checkName(Mode.BLANK_NAME)

    @Test fun deniedMetadataKeepsSavedFile() = checkName(Mode.SECURITY)

    @Test fun unsupportedMetadataKeepsSavedFile() = checkName(Mode.UNSUPPORTED)

    @Test fun illegalMetadataKeepsSavedFile() = checkName(Mode.ILLEGAL_ARGUMENT)

    @Test fun failedMetadataKeepsSavedFile() = checkName(Mode.RUNTIME)

    @Test
    fun cancelledPickerDoesNotWriteOrQuery() = checkOutcome(Mode.NAME, cancelPick = true) { result, _ ->
        result.isSuccess && result.getOrNull() == null
    }

    @Test
    fun failedOutputDoesNotQueryOrReportSuccess() = checkOutcome(Mode.OPEN_FAILURE) { result, _ ->
        result.exceptionOrNull() is FileNotFoundException
    }

    @Test
    fun metadataCancellationPropagatesAfterSavedWrite() = checkOutcome(Mode.CANCELLATION) { result, _ ->
        val error = result.exceptionOrNull()
        error is CancellationException && error.message == BackupSaveFilenameFixture.METADATA_CANCELLED
    }

    private fun checkName(mode: Mode, name: String = BackupSaveFilenameFixture.CHOSEN_NAME, early: Boolean = false) =
        checkOutcome(mode, name, early) { result, fixture ->
            result.isSuccess && result.getOrNull() == if (mode == Mode.NAME) fixture.expectedName else fixture.fallback
        }

    private fun checkOutcome(
        mode: Mode,
        name: String = BackupSaveFilenameFixture.CHOSEN_NAME,
        early: Boolean = false,
        cancelPick: Boolean = false,
        matches: (Result<String?>, BackupSaveFilenameFixture) -> Boolean,
    ) {
        val fixture = BackupSaveFilenameFixture(mode, name, early, cancelPick)
        try {
            val result = fixture.save()
            fixture.assertWriteAndResources()
            val expectedOutcome = matches(result, fixture)
            fixture.record(testName.methodName, expectedOutcome)
            assertTrue("LAST: actual saved name or unchanged fallback/failure policy", expectedOutcome)
        } finally {
            fixture.close(testName.methodName)
        }
    }
}
