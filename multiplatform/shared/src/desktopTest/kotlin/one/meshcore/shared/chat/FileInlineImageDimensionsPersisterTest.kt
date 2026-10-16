package one.meshcore.shared.chat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileInlineImageDimensionsPersisterTest {
    @Test
    fun roundTripAndCorruptFile() {
        val dir = File(System.getProperty("java.io.tmpdir"), "inline-dims-${System.nanoTime()}")
        val file = File(dir, "InlineImageDimensions.json")
        try {
            val writer = InlineImageDimensionsStore(FileInlineImageDimensionsPersister(file))
            writer.save("https://ex/a.png", width = 800, height = 400)

            val reader = InlineImageDimensionsStore(FileInlineImageDimensionsPersister(file))
            assertEquals(2.0, reader.aspect("https://ex/a.png"))

            file.writeText("not json")
            val recovered = InlineImageDimensionsStore(FileInlineImageDimensionsPersister(file))
            assertNull(recovered.aspect("https://ex/a.png"))
        } finally {
            file.delete()
            dir.delete()
        }
    }
}
