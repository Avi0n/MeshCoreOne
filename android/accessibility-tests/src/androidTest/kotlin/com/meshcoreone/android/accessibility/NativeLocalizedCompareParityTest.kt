// AndroidOnly: WP-503 Run retained WP-316 independent Swift/Foundation vectors against production Android collation.
package com.meshcoreone.android.accessibility

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLocalizedCompareParityTest {
    @get:Rule val errors = ErrorCollector()

    private val targetClass by lazy {
        // Kotlin's internal class has a public JVM ABI; load only the installed target implementation.
        InstrumentationRegistry.getInstrumentation().targetContext.classLoader.loadClass(
            "com.meshcoreone.android.feature.tools.diagnostics.text.LocalizedCompare",
        )
    }
    private val production by lazy {
        targetClass.getConstructor(Locale::class.java).newInstance(Locale.US)
    }
    private val equality by lazy {
        targetClass.getMethod("equal", String::class.java, String::class.java)
    }
    private val comparator by lazy { targetClass.getMethod("getComparator").invoke(production) }
    private val comparison by lazy {
        Comparator::class.java.getMethod("compare", Any::class.java, Any::class.java)
    }

    @Before fun requireActualSelectedAndroidApiAndProductionClass() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expectedApi = requireNotNull(InstrumentationRegistry.getArguments().getString("expectedApi")).toInt()
        assertTrue(expectedApi == 31 || expectedApi == 37)
        assertEquals(expectedApi, Build.VERSION.SDK_INT)
        assertEquals("com.meshcoreone.android.debug", instrumentation.targetContext.packageName)
        assertTrue(comparator is Comparator<*>)
        println("WP503_NATIVE_COLLATION_RUNTIME|api=${Build.VERSION.SDK_INT}|locale=${Locale.US}" +
            "|icu=${android.icu.util.VersionInfo.ICU_VERSION}|production=${targetClass.name}" +
            "|loader=${targetClass.classLoader}|comparator=${comparator.javaClass.name}" +
            "|targetApk=${instrumentation.targetContext.applicationInfo.sourceDir}|hostJdk=false")
    }

    private fun compare(left: String, right: String): Int =
        comparison.invoke(comparator, left, right) as Int

    private fun assertPair(left: String, right: String, expectedEqual: Boolean) {
        val forwardEqual = equality.invoke(production, left, right) as Boolean
        val reverseEqual = equality.invoke(production, right, left) as Boolean
        val forward = compare(left, right)
        val reverse = compare(right, left)
        println("WP503_NATIVE_COLLATION_VECTOR|left=${scalars(left)}|right=${scalars(right)}" +
            "|expectedEqual=$expectedEqual|forwardEqual=$forwardEqual|reverseEqual=$reverseEqual" +
            "|forwardCompare=$forward|reverseCompare=$reverse|productionInvocation=true")
        errors.checkThat("$left|$right equality", forwardEqual, equalTo(expectedEqual))
        errors.checkThat("$right|$left equality", reverseEqual, equalTo(expectedEqual))
        errors.checkThat("$left|$right comparator zero", forward == 0, equalTo(expectedEqual))
        errors.checkThat("$right|$left comparator zero", reverse == 0, equalTo(expectedEqual))
    }

    private fun scalars(value: String): String =
        value.codePoints().toArray().joinToString("+") { it.toString(16).uppercase(Locale.ROOT) }

    @Test fun asciiCaseMatchesSwiftEquality() = assertPair("abc", "ABC", true)
    @Test fun sharpSMatchesSwiftEquality() = assertPair("stra\u00dfe", "STRASSE", true)
    @Test fun canonicalAccentMatchesSwiftEquality() = assertPair("\u00e9", "e\u0301", true)
    @Test fun fullWidthMatchesSwiftEquality() = assertPair("\uff21", "A", true)
    @Test fun nodeNameCaseMatchesSwiftEquality() = assertPair("node 1", "NODE 1", true)
    @Test fun digraphCaseMatchesSwiftEquality() = assertPair("\u01c6", "\u01c4", true)
    @Test fun ligatureMatchesSwiftEquality() = assertPair("\ufb01", "FI", true)
    @Test fun accentRemainsUnequalAsInSwift() = assertPair("\u00e9", "e", false)
    @Test fun dotlessIRemainsUnequalAsInSwift() = assertPair("\u0131", "I", false)
    @Test fun dottedIRemainsUnequalAsInSwift() = assertPair("\u0130", "i", false)

    @Test fun equalNamesKeepTheirInputOrderWithTheProductionComparator() {
        val pairs = listOf(
            "abc" to "ABC", "stra\u00dfe" to "STRASSE", "\u00e9" to "e\u0301", "\uff21" to "A",
            "node 1" to "NODE 1", "\u01c6" to "\u01c4", "\ufb01" to "FI",
        )
        for ((left, right) in pairs) {
            for (input in listOf(listOf(left, right), listOf(right, left))) {
                val actual = input.sortedWith(::compare)
                println("WP503_NATIVE_COLLATION_STABLE|input=${input.joinToString(",", transform = ::scalars)}" +
                    "|actual=${actual.joinToString(",", transform = ::scalars)}|productionInvocation=true")
                errors.checkThat("Equal names must retain their actual input order", actual, equalTo(input))
            }
        }
    }
}
