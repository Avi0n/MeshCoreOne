// AndroidOnly: WP-308 Mounted production mention picker metadata, ordered scrolling and callback regressions.
// ReferenceInput: MC1/Views/Chats/Mentions/MentionPickerSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/ContactMatchRow.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1Tests/Views/Chats/ContactMatchRowTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.composer.MentionPickerContext
import com.meshcoreone.android.feature.chats.composer.ui.MentionPickerSheet
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
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MentionPickerSheetComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val radio = RadioId(UUID.fromString("30800000-0000-0000-0000-000000000001"))
    private val clock = Clock.fixed(Instant.ofEpochSecond(700000), ZoneOffset.ofHours(-1))
    private val matches = (1..32).map { index ->
        ContactDTO(
            id = UUID(308, index.toLong()), radioId = radio,
            publicKey = Bytes(ByteArray(32) { index.toByte() }), name = "Relay",
            typeRawValue = 2u, lastAdvertTimestamp = 1000u, lastModified = 10000u,
            lastHeardTimestamp = 1000u, lastMessageDate = clock.instant(),
            latitude = 37.0, longitude = -122.0, isBlocked = index == 32,
        )
    }
    private val context = mutableStateOf(MentionPickerContext("Relay", radio, matches, false))
    private val visible = mutableStateOf(true)
    private val selected = mutableListOf<ContactDTO>()
    private var dismissals = 0

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
    }

    @After fun closeHost() {
        host.pause().stop().destroy()
    }

    @Suppress("DEPRECATION")
    private fun mount(fontScale: Float = 1f, cjkRtl: Boolean = false) {
        val resources = host.get().resources
        resources.updateConfiguration(Configuration(resources.configuration).apply {
            this.fontScale = fontScale
            setLocales(LocaleList(Locale.forLanguageTag(if (cjkRtl) "zh-Hans" else "en-US")))
        }, resources.displayMetrics)
        host.get().setContent {
            val configuration = LocalConfiguration.current
            CompositionLocalProvider(
                LocalResources provides resources,
                LocalContext provides host.get().createConfigurationContext(configuration),
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalLayoutDirection provides if (cjkRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                MeshCoreTheme(theme = ThemeRegistry.default, systemDark = cjkRtl, motionScale = 0f) {
                    if (visible.value) {
                        MentionPickerSheet(
                            context.value,
                            onSelect = { selected += it },
                            onDismiss = { dismissals++; visible.value = false },
                            clock = clock,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun key(contact: ContactDTO) =
        AppChatsStrings.chatsContactMatchKey(
            host.get().resources, contact.publicKey.hexString.uppercase().chunked(2).joinToString(" "),
        )

    private fun row(contact: ContactDTO) = compose.onNode(
        hasContentDescription(contact.displayName) and hasAnyDescendant(hasText(key(contact))),
        useUnmergedTree = true,
    )

    private fun list() = compose.onNode(hasScrollToIndexAction())
    private fun done() = compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsCommonDone))

    private fun assertDoneReachable() {
        done().assertIsDisplayed().assertHasClickAction()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test fun mountedPickerUsesAdvertNotRecencyAndKeepsTheFullContactTapContract() {
        context.value = context.value.copy(matches = listOf(matches.first()))
        mount()
        list().performScrollToIndex(2)
        val contact = matches.first()
        row(contact).assertIsDisplayed().assertHasClickAction()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.onNodeWithText("Repeater", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Dec 31", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Now", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Location", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(key(contact), useUnmergedTree = true).assertExists()
        row(contact).performClick()
        compose.runOnIdle {
            assertEquals(1, selected.size)
            assertSame(contact, selected.single())
            assertEquals(0, dismissals)
            assertTrue(visible.value)
        }
        val updated = contact.copy(name = "Updated", typeRawValue = 3u, latitude = 0.0, longitude = 0.0)
        compose.runOnIdle { context.value = context.value.copy(matches = listOf(updated)) }
        compose.onNodeWithText("Room", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Location", useUnmergedTree = true).assertDoesNotExist()
        row(updated).performClick()
        compose.runOnIdle {
            assertEquals(2, selected.size)
            assertSame(updated, selected.last())
            assertEquals(0, dismissals)
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w840dp-h800dp-mdpi")
    fun expandedWindowKeepsAll32SameNameIdentitiesOrderedAndSelectableBeyondTheEditorLimit() {
        mount()
        assertAllMatchesReachable()
        assertDoneDismissesWithoutAnotherSelection()
    }

    @Test fun compact200PercentCjkRtlKeepsEveryMatchAndDoneReachable() {
        val name = "\u4e2d\u7ee7 \u05e9\u05dc\u05d5\u05dd"
        context.value = context.value.copy(name = name, matches = matches.map { it.copy(name = name) })
        mount(fontScale = 2f, cjkRtl = true)
        assertAllMatchesReachable()
        val layouts = mutableListOf<TextLayoutResult>()
        val last = context.value.matches.last()
        compose.onNode(
            hasText(last.displayName) and hasAnyAncestor(
                hasContentDescription(last.displayName) and hasAnyDescendant(hasText(key(last))),
            ),
            useUnmergedTree = true,
        )
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(2f, layouts.single().layoutInput.density.fontScale, 0f)
        assertEquals(LayoutDirection.Rtl, layouts.single().layoutInput.layoutDirection)
        assertFalse(layouts.single().didOverflowHeight)
        assertDoneDismissesWithoutAnotherSelection()
    }

    private fun assertAllMatchesReachable() {
        val contacts = context.value.matches
        assertDoneReachable()
        contacts.forEachIndexed { index, contact ->
            list().performScrollToIndex(index + 2)
            row(contact).assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
            assertDoneReachable()
        }
        row(contacts.last()).performClick()
        compose.runOnIdle {
            assertEquals(1, selected.size)
            assertSame(contacts.last(), selected.single())
            assertTrue(selected.single().isBlocked)
            assertEquals(0, dismissals)
        }
    }

    private fun assertDoneDismissesWithoutAnotherSelection() {
        done().performClick()
        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertEquals(1, selected.size)
        }
        list().assertDoesNotExist()
    }

    @Test fun selfAndUnknownKeepTheirStatusAndDismissWithoutSelectingAMatch() {
        context.value = context.value.copy(isSelfMention = true)
        mount()
        compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsMentionPickerSelfTitle)).assertIsDisplayed()
        compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsMentionPickerMatchingContacts)).assertDoesNotExist()
        assertDoneReachable()
        done().performClick()
        compose.runOnIdle {
            assertTrue(selected.isEmpty())
            assertEquals(1, dismissals)
            context.value = context.value.copy(matches = emptyList(), isSelfMention = false)
            visible.value = true
        }
        compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsMentionPickerNotSavedTitle)).assertIsDisplayed()
        compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsMentionPickerSelfTitle)).assertDoesNotExist()
        assertDoneReachable()
        done().performClick()
        compose.runOnIdle {
            assertTrue(selected.isEmpty())
            assertEquals(2, dismissals)
        }
        list().assertDoesNotExist()
    }
}
