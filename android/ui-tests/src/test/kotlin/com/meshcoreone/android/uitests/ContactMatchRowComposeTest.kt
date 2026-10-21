// AndroidOnly: WP-502 Mounted existing ContactMatchRow consumers; not a new frozen original, picker, GPS or golden verdict.
// ReferenceInput: MC1/Views/Chats/ContactMatchRow.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1Tests/Views/Chats/ContactMatchRowTests.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Components/RelativeTimestampText.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1Services/Sources/MC1Services/Models/Contact.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.uitests

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.ColorScheme
import com.meshcoreone.android.core.designsystem.LocalMeshTheme
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeId
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.list.ui.ContactMatchRow
import com.meshcoreone.android.feature.chats.list.ui.ContactMatchSelection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w1080dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactMatchRowComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val radio = RadioId(UUID.fromString("50200000-0000-0000-0000-000000000360"))
    private val relay = ContactDTO(
        id = UUID.fromString("50200000-0000-0000-0000-000000000361"),
        radioId = radio, publicKey = Bytes(ByteArray(32) { 0xAB.toByte() }), name = "Relay",
        typeRawValue = 2u, flags = 0u, outPathLength = 0u, outPath = Bytes.EMPTY,
        lastAdvertTimestamp = 1000u, latitude = 0.0, longitude = 0.0,
        lastModified = 10000u, lastHeardTimestamp = 1000u, nickname = null,
        isBlocked = false, isMuted = false, isFavorite = false, lastMessageDate = null,
        unreadCount = 0, unreadMentionCount = 0, ocvPreset = null,
        customOCVArrayString = null, avatarImageData = null,
    )
    private val contact = mutableStateOf(relay)
    private val selection = mutableStateOf<ContactMatchSelection>(ContactMatchSelection.Tap)
    private val distance = mutableStateOf<String?>(null)
    private val clock = mutableStateOf<Clock>(Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.ofHours(-1)))
    private val frame = mutableStateOf(ScreenCase("contact-match", locale = "en-US"))
    private val clicks = mutableListOf<ContactDTO>()
    private var effectiveScheme: ColorScheme? = null

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
    }

    @After fun close() {
        host.pause().stop().destroy()
    }

    private fun mount() {
        host.get().setContent {
            val case = frame.value
            val configuration = Configuration(LocalConfiguration.current).apply {
                setLocales(LocaleList(Locale.forLanguageTag(case.locale)))
            }
            val localized = LocalContext.current.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localized, LocalConfiguration provides configuration,
                LocalResources provides localized.resources,
                LocalDensity provides Density(LocalDensity.current.density, case.fontScale),
                LocalLayoutDirection provides if (case.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                MeshCoreTheme(
                    theme = requireNotNull(ThemeRegistry.theme(case.theme)),
                    colorSchemePreference = case.preference, systemDark = case.systemDark, motionScale = 0f,
                ) {
                    val scheme = LocalMeshTheme.current.frame.colorScheme
                    SideEffect { effectiveScheme = scheme }
                    Box(Modifier.width(case.widthDp.dp).height(case.heightDp.dp).testTag("component-window")) {
                        Surface {
                            val captured = contact.value
                            ContactMatchRow(
                                captured, selection.value, clock.value, onClick = { clicks += captured },
                                modifier = Modifier.testTag("contact-match-row"), distanceText = distance.value,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun row() = compose.onNodeWithTag("contact-match-row", useUnmergedTree = true)

    private fun assertRowBoundsAndAction(role: Role) {
        compose.runOnIdle { assertEquals(frame.value.expectedScheme, effectiveScheme) }
        val bounds = row().assertIsDisplayed().assertHasClickAction()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionEquals(contact.value.displayName)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, role))
            .fetchSemanticsNode().boundsInRoot
        val window = compose.onNodeWithTag("component-window").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= window.left && bounds.right <= window.right)
        assertTrue(bounds.top >= window.top && bounds.bottom <= window.bottom)
    }

    private fun assertReadable(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val rowBounds = row().fetchSemanticsNode().boundsInRoot
        assertTrue("$text outside row", bounds.left >= rowBounds.left && bounds.right <= rowBounds.right)
        assertTrue("$text outside row", bounds.top >= rowBounds.top && bounds.bottom <= rowBounds.bottom)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertEquals(text, layout.layoutInput.text.text)
        assertEquals(frame.value.fontScale, layout.layoutInput.density.fontScale, 0f)
        assertEquals(if (frame.value.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr, layout.layoutInput.layoutDirection)
        assertFalse("$text clipped vertically", layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) assertFalse("$text ellipsized", layout.isLineEllipsized(line))
        for (index in text.indices.filterNot { text[it].isWhitespace() }) {
            val glyph = layout.getBoundingBox(index)
            assertTrue("$text glyph $index outside measured text", glyph.left >= 0f && glyph.right <= bounds.width)
            assertTrue("$text glyph $index outside measured text", glyph.top >= 0f && glyph.bottom <= bounds.height)
        }
    }

    @Test fun sourceRelayAdvertWinsOverRecencyAndLastMessageWhileTapReturnsTheFullContact() {
        frame.value = frame.value.copy(widthDp = 744)
        clock.value = Clock.fixed(Instant.ofEpochSecond(700000), ZoneOffset.ofHours(-1))
        mount()
        val recentMessage = relay.copy(lastMessageDate = Instant.ofEpochSecond(700000))
        for ((index, input) in listOf(relay, recentMessage).withIndex()) {
            compose.runOnIdle { contact.value = input }
            assertRowBoundsAndAction(Role.Button)
            assertReadable("Relay")
            assertReadable("Repeater")
            assertReadable("Dec 31")
            assertReadable("Key: $SPACED_KEY")
            compose.onNodeWithText("Jan 1", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("Now", useUnmergedTree = true).assertDoesNotExist()
            row().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            assertEquals(index, clicks.size)
            row().performClick()
            compose.runOnIdle {
                assertEquals(index + 1, clicks.size)
                assertSame(input, clicks.last())
                assertEquals(listOf(relay, recentMessage).take(index + 1), clicks)
            }
        }
    }

    @Test fun localizedKindsAndLocationMetadataConsumeOnlyTheCallerSuppliedDistance() {
        mount()
        val kinds = listOf(
            Triple(1.toUByte(), "Chat", "\u804a\u5929"),
            Triple(2.toUByte(), "Repeater", "\u8f6c\u53d1\u8282\u70b9"),
            Triple(3.toUByte(), "Room", "\u623f\u95f4"),
        )
        for (case in componentFrames) for ((type, english, chinese) in kinds) {
            val located = relay.copy(name = "\u4e2d\u7ee7 \u05e9\u05dc\u05d5\u05dd", typeRawValue = type, latitude = 37.0, longitude = -122.0)
            for ((input, supplied) in listOf(
                located to null,
                located.copy(latitude = 0.0, longitude = 0.0) to "must not leak",
                located to "25 m away",
            )) {
                compose.runOnIdle { frame.value = case; contact.value = input; distance.value = supplied }
                assertRowBoundsAndAction(Role.Button)
                assertReadable(input.displayName)
                assertReadable(if (case.locale == "en-US") english else chinese)
                assertReadable(if (case.locale == "en-US") "Now" else "\u521a\u521a")
                val keyLabel = if (case.locale == "en-US") "Key: " else "\u5bc6\u94a5\uff1a"
                compose.onNodeWithText(keyLabel + SPACED_KEY, useUnmergedTree = true).assertIsDisplayed()
                val location = compose.onNodeWithContentDescription(
                    if (case.locale == "en-US") "Location" else "\u4f4d\u7f6e", useUnmergedTree = true,
                )
                if (input.latitude == 0.0) location.assertDoesNotExist() else {
                    val marker = location.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    val bounds = row().fetchSemanticsNode().boundsInRoot
                    assertTrue(marker.width > 0f && marker.height > 0f)
                    assertTrue(marker.left >= bounds.left && marker.right <= bounds.right)
                    assertTrue(marker.top >= bounds.top && marker.bottom <= bounds.bottom)
                }
                if (supplied == "25 m away") assertReadable("25 m away")
                else compose.onNodeWithText("25 m away", useUnmergedTree = true).assertDoesNotExist()
                compose.onNodeWithText("must not leak", useUnmergedTree = true).assertDoesNotExist()
                compose.onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and
                        hasAnyAncestor(hasTestTag("contact-match-row")),
                    useUnmergedTree = true,
                ).assertCountEquals(if (supplied == "25 m away") 6 else 5)
                assertTrue(clicks.isEmpty())
            }
        }
    }

    @Test fun toggleModesExposeLocalizedCheckboxSelectionWithoutImplicitlyChangingItOnClick() {
        contact.value = relay.copy(name = "\u4e2d\u7ee7 \u05e9\u05dc\u05d5\u05dd")
        mount()
        for (case in componentFrames) for (selected in listOf(false, true)) {
            compose.runOnIdle { frame.value = case; selection.value = ContactMatchSelection.Toggle(selected) }
            val stateText = if (case.locale == "en-US") {
                if (selected) "Selected" else "Not selected"
            } else if (selected) "\u5df2\u9009\u62e9" else "\u672a\u9009\u62e9"
            assertRowBoundsAndAction(Role.Checkbox)
            assertReadable(contact.value.displayName)
            row().assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, selected))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, stateText))
            val before = clicks.size
            row().performClick()
            compose.runOnIdle {
                assertEquals(before + 1, clicks.size)
                assertSame(contact.value, clicks.last())
            }
            row().assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, selected))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, stateText))
        }
    }

    private val componentFrames = listOf(
        ScreenCase("compact-light", locale = "en-US"),
        ScreenCase(
            "compact-200-cjk-rtl-forced-dark", theme = ThemeId.EMBER,
            preference = AppColorSchemePreference.LIGHT, expectedScheme = ColorScheme.DARK,
            locale = "zh-Hans", fontScale = 2f, rtl = true,
        ),
    )

    companion object {
        private const val SPACED_KEY =
            "AB AB AB AB AB AB AB AB AB AB AB AB AB AB AB AB AB AB AB AB AB AB AB AB AB AB AB AB AB AB AB AB"
    }
}
