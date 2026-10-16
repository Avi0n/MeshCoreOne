package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class RemoteFontLayoutInstrumentedTest : RemoteFontLayoutEvidence() {
    @Test
    fun telemetryTabShowsItsWholeLabelAndSelectsOnTouch() {
        val selected = mutableStateOf(AdminManagementTab.SETTINGS)
        var selections = 0
        render {
            Box(Modifier.fillMaxSize()) {
                AdminManagementTabRow(selected.value) {
                    selections++
                    selected.value = it
                }
            }
        }
        val tab = compose.onNode(
            hasText(labels.telemetry) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
        )
        var texts = remoteFontNativeTexts(compose)
        archive("before-touch", texts, JSONObject().put("selections", selections))
        val scrollable = generateSequence(tab.fetchSemanticsNode().parent) { it.parent }.any {
            it.config.contains(SemanticsActions.ScrollBy) &&
                it.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
        }
        if (scrollable) tab.performScrollTo()
        tab.performTouchInput { click() }
        compose.waitForIdle()
        texts = remoteFontNativeTexts(compose)
        val label = texts.singleOrNull { it.text == labels.telemetry }
        val tabNode = tab.fetchSemanticsNode()
        val observations = JSONObject()
            .put("selectedTab", selected.value.name)
            .put("selections", selections)
            .put("scrolledBeforeTouch", scrollable)
            .put("tabFrame", remoteFontRectJson(tabNode.boundsInRoot))
            .put("labelFound", label != null)
        archive("after-touch", texts, observations)

        assertEquals(AdminManagementTab.TELEMETRY, selected.value)
        assertEquals(1, selections)
        assertTrue(tabNode.config[SemanticsProperties.Selected])
        assertNotNull("Telemetry must have a native text layout", label)
        val measured = checkNotNull(label)
        assertTrue("Telemetry must render every character without ellipsis", measured.complete)
        assertTrue(
            "The whole Telemetry label must be inside its visible tab",
            remoteFontContains(tabNode.boundsInRoot, measured.rawFrame),
        )
    }

    @Test
    fun contactInfoLabelClearsItsHeaderAndRetainsTheDraftAcrossCollapse() {
        val state = mutableStateOf(
            NodeSettingsState(
                contactInfoExpanded = true,
                contactInfoLoaded = true,
                ownerInfo = INITIAL_OWNER,
            ),
        )
        var writes = 0
        var reloads = 0
        render {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = PreferenceHubHorizontalPadding),
            ) {
                RemoteContactInfoSection(
                    isRadioReady = true,
                    fields = state.value,
                    onExpandedChange = { state.value = state.value.copy(contactInfoExpanded = it) },
                    onReload = { reloads++ },
                    onOwnerInfoChange = {
                        state.value = state.value.copy(ownerInfo = it, contactInfoModified = true)
                    },
                    onApply = { writes++ },
                )
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement(RETAINED_DRAFT)
        compose.waitForIdle()
        archive("edited", remoteFontNativeTexts(compose), JSONObject().put("draft", state.value.ownerInfo))
        val header = compose.onNode(
            hasText(labels.contactInfo) and hasClickAction() and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription),
        )
        header.performClick()
        compose.waitForIdle()
        archive("collapsed", remoteFontNativeTexts(compose), JSONObject().put("draft", state.value.ownerInfo))
        header.performClick()
        compose.waitForIdle()
        val texts = remoteFontNativeTexts(compose)
        val headerNode = header.fetchSemanticsNode()
        val label = contactLabel(texts, headerNode.id)
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
        val renderedDraft = field.config[SemanticsProperties.EditableText].text
        val observations = JSONObject()
            .put("headerFrame", remoteFontRectJson(headerNode.boundsInRoot))
            .put("fieldFrame", remoteFontRectJson(field.boundsInRoot))
            .put("selectedLabel", label?.text ?: JSONObject.NULL)
            .put("draft", renderedDraft)
            .put("writes", writes)
            .put("reloads", reloads)
        archive("reopened", texts, observations)

        assertEquals(RETAINED_DRAFT, state.value.ownerInfo)
        assertEquals(RETAINED_DRAFT, renderedDraft)
        assertEquals(0, writes)
        assertEquals(0, reloads)
        assertNotNull("The populated owner field must retain a visible label", label)
        val measured = checkNotNull(label)
        assertTrue("The owner label must render its complete text", measured.complete)
        assertTrue(
            "The owner label must not paint into the section header",
            measured.lines.all { it.top >= headerNode.boundsInRoot.bottom - REMOTE_FONT_PIXEL_TOLERANCE },
        )
    }

    @Test
    fun singletonHistoryNoticeFitsItsGrowingContentFrame() {
        render {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = PreferenceHubHorizontalPadding),
            ) {
                Box(Modifier.fillMaxWidth().testTag(EMPTY_FRAME)) {
                    MetricChartEmptyState(value = SINGLE_VALUE, unit = VOLTAGE_UNIT)
                }
            }
        }
        val texts = remoteFontNativeTexts(compose)
        val notice = texts.singleOrNull { it.text == labels.historyNotice }
        val value = texts.singleOrNull { it.text == VALUE_CAPTION }
        val frame = compose.onNodeWithTag(EMPTY_FRAME).fetchSemanticsNode().boundsInRoot
        val requiredHeight = (notice?.paragraphHeight ?: 0f) + (value?.paragraphHeight ?: 0f)
        val observations = JSONObject()
            .put("contentFrame", remoteFontRectJson(frame))
            .put("contentHeight", frame.height.toDouble())
            .put("requiredTextHeight", requiredHeight.toDouble())
            .put("noticeFound", notice != null)
            .put("valueFound", value != null)
        archive("singleton", texts, observations)

        assertNotNull("The full localized history notice must be present", notice)
        assertNotNull("The singleton value must be present", value)
        assertTrue(
            "The singleton frame must grow to fit its text",
            frame.height + REMOTE_FONT_PIXEL_TOLERANCE >= requiredHeight,
        )
        val measured = checkNotNull(notice)
        assertTrue("Every history-notice character must render without clipping", measured.complete)
        assertTrue(
            "Every history-notice line must be inside the singleton frame",
            measured.lines.all { remoteFontContains(frame, it) },
        )
        assertTrue(
            "The singleton value must stay inside its frame",
            remoteFontContains(frame, checkNotNull(value).rawFrame),
        )
    }

    private fun contactLabel(texts: List<RemoteFontNativeText>, headerId: Int): RemoteFontNativeText? {
        val outsideHeader = texts.filter { headerId !in it.ancestorIds && headerId != it.nodeId }
        return outsideHeader.singleOrNull { it.text == labels.contactInfo }
            ?: outsideHeader.singleOrNull { it.text == labels.contactHint }
    }

    private companion object {
        const val INITIAL_OWNER = "Original owner"
        const val RETAINED_DRAFT = "Retained contact owner draft"
        const val EMPTY_FRAME = "remote-history-singleton-frame"
        const val SINGLE_VALUE = 3.99
        const val VOLTAGE_UNIT = "V"
        const val VALUE_CAPTION = "3.99 V"
    }
}
