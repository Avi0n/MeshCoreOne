// AndroidOnly: WP-503 Supplemental WP-316 tr/az producer cases on actual Android; not independent Foundation oracle evidence.
package com.meshcoreone.android.accessibility

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLocalizedCompareLocalePreservationTest {
    @get:Rule val errors = ErrorCollector()

    @Test fun turkishProducerCasesPreserveLocaleTailoringOnAndroid() = assertProducerCases("tr-TR")
    @Test fun azeriProducerCasesPreserveLocaleTailoringOnAndroid() = assertProducerCases("az-AZ")

    private fun assertProducerCases(tag: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expectedApi = requireNotNull(InstrumentationRegistry.getArguments().getString("expectedApi")).toInt()
        assertTrue(expectedApi == 31 || expectedApi == 37)
        assertEquals(expectedApi, Build.VERSION.SDK_INT)
        assertEquals("com.meshcoreone.android.debug", instrumentation.targetContext.packageName)
        val targetClass = instrumentation.targetContext.classLoader.loadClass(
            "com.meshcoreone.android.feature.tools.diagnostics.text.LocalizedCompare",
        )
        val production = targetClass.getConstructor(Locale::class.java).newInstance(Locale.forLanguageTag(tag))
        val equality = targetClass.getMethod("equal", String::class.java, String::class.java)
        val comparator = targetClass.getMethod("getComparator").invoke(production)
        assertTrue(comparator is Comparator<*>)
        val comparison = Comparator::class.java.getMethod("compare", Any::class.java, Any::class.java)
        fun compare(left: String, right: String) = comparison.invoke(comparator, left, right) as Int
        val equalPairs = listOf(
            "I" to "\u0131", "\u0130" to "i", "I\u0307" to "i", "stra\u00dfe" to "STRASSE",
        )
        val unequalPairs = listOf("I" to "i", "\u0130" to "\u0131", "\u00e9" to "e")
        println("WP503_NATIVE_LOCALE_PRESERVATION_RUNTIME|api=${Build.VERSION.SDK_INT}|locale=$tag" +
            "|icu=${android.icu.util.VersionInfo.ICU_VERSION}|production=${targetClass.name}" +
            "|targetApk=${instrumentation.targetContext.applicationInfo.sourceDir}" +
            "|producer=8a8c6348af1c98b3f11f21769800308cf302b8f9|independentFoundationOracle=false")
        for ((expectedEqual, pairs) in listOf(true to equalPairs, false to unequalPairs)) {
            for ((left, right) in pairs) {
                val forwardEqual = equality.invoke(production, left, right) as Boolean
                val reverseEqual = equality.invoke(production, right, left) as Boolean
                val forward = compare(left, right)
                val reverse = compare(right, left)
                val identity = "$tag:${scalars(left)}|${scalars(right)}"
                println("WP503_NATIVE_LOCALE_PRESERVATION_VECTOR|pair=$identity|expectedEqual=$expectedEqual" +
                    "|forwardEqual=$forwardEqual|reverseEqual=$reverseEqual" +
                    "|forwardCompare=$forward|reverseCompare=$reverse|productionInvocation=true")
                errors.checkThat("$identity equality", forwardEqual, equalTo(expectedEqual))
                errors.checkThat("$identity reverse equality", reverseEqual, equalTo(expectedEqual))
                errors.checkThat("$identity comparator zero", forward == 0, equalTo(expectedEqual))
                errors.checkThat("$identity reverse comparator zero", reverse == 0, equalTo(expectedEqual))
                if (expectedEqual) {
                    for (input in listOf(listOf(left, right), listOf(right, left))) {
                        val actual = input.sortedWith(::compare)
                        println("WP503_NATIVE_LOCALE_PRESERVATION_STABLE|locale=$tag" +
                            "|input=${input.joinToString(",", transform = ::scalars)}" +
                            "|actual=${actual.joinToString(",", transform = ::scalars)}")
                        errors.checkThat("$identity stable input order", actual, equalTo(input))
                    }
                }
            }
        }
    }

    private fun scalars(value: String): String =
        value.codePoints().toArray().joinToString("+") { it.toString(16).uppercase(Locale.ROOT) }
}
