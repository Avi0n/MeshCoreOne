package one.meshcore.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UuidRandomTest {
    @Test
    fun randomIsUppercaseCanonical() {
        val id = Uuid.random()

        assertEquals(id, Uuid.normalized(id.value))
        assertEquals(36, id.value.length)
        assertTrue(id.value.all { it.isDigit() || it in 'A'..'F' || it == '-' })
    }
}
