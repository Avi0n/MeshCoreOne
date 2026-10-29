// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/RemoteCLICommandRewriterTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.cli

import java.time.Instant
import kotlin.test.assertEquals

/** Swift suite "RemoteCLICommandRewriter"; parameterized families run every Swift argument. */
@org.junit.runner.RunWith(org.junit.runners.Parameterized::class)
class FeatureRemoteCLICommandRewriterVectorTest(private val label: String, private val body: () -> Unit) {
    @org.junit.Test
    fun originalCase() = body()

    companion object {
        @JvmStatic
        @org.junit.runners.Parameterized.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = RemoteCLICommandRewriterTestVectors().sourceCases().map {
            arrayOf<Any>(it.first, it.second)
        }
    }
}

private fun sourceCase(name: String, body: () -> Unit): Pair<String, () -> Unit> = name to body

private class RemoteCLICommandRewriterTestVectors {
    private val now = Instant.ofEpochSecond(1_786_722_487)
    private val expected = "time 1786722487"

    private fun case(name: String, signature: String = "()", body: () -> Unit): Pair<String, () -> Unit> =
        sourceCase("RemoteCLICommandRewriterTests::$name$signature", body)

    fun sourceCases(): List<Pair<String, () -> Unit>> = listOf(
        case("clock sync becomes time with the host epoch") {
            assertEquals(expected, RemoteCLICommandRewriter.rewrite("clock sync", now))
        },
        case("clock sync match is case and whitespace insensitive", "(command : String)") {
            for (command in listOf("CLOCK SYNC", "  clock   sync  ", "Clock Sync")) {
                assertEquals(expected, RemoteCLICommandRewriter.rewrite(command, now), "argument: '$command'")
            }
        },
        case("other commands are left unchanged", "(command : String)") {
            for (command in listOf("clock", "time 123", "clock sync extra", "sync_time", "st")) {
                assertEquals(command, RemoteCLICommandRewriter.rewrite(command, now), "argument: '$command'")
            }
        },
        case("pre epoch dates saturate to zero") {
            assertEquals("time 0", RemoteCLICommandRewriter.rewrite("clock sync", Instant.ofEpochSecond(-1000)))
        },
    )
}
