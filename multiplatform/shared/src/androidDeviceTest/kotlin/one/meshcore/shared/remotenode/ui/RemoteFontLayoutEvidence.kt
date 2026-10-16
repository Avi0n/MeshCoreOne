package one.meshcore.shared.remotenode.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_check_back
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_contact_info
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_contact_info_placeholder
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_tab_telemetry
import org.jetbrains.compose.resources.stringResource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import java.io.File

internal abstract class RemoteFontLayoutEvidence {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val testName = TestName()
    protected val labels = RemoteFontLayoutLabels()

    @Before
    fun enableNativeInsets() {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
    }

    protected fun render(content: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme {
                labels.read()
                content()
            }
        }
        compose.waitForIdle()
    }

    protected fun archive(name: String, texts: List<RemoteFontNativeText>, observations: JSONObject) {
        val configuration = compose.activity.resources.configuration
        val directory = File(
            checkNotNull(compose.activity.getExternalFilesDir(null)),
            "remote-font-layout/font-${configuration.fontScale}/${testName.methodName}",
        )
        check(directory.mkdirs() || directory.isDirectory)
        val root = compose.onRoot()
        File(directory, "$name-hierarchy.txt").writeText(root.printToString())
        File(directory, "$name-unmerged-hierarchy.txt").writeText(
            compose.onRoot(useUnmergedTree = true).printToString(),
        )
        val bitmap = root.captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it))
        }
        val metadata = JSONObject()
            .put("targetPackage", compose.activity.packageName)
            .put("fontScale", configuration.fontScale.toDouble())
            .put("screenWidthDp", configuration.screenWidthDp)
            .put("screenHeightDp", configuration.screenHeightDp)
            .put("densityDpi", configuration.densityDpi)
            .put("locales", configuration.locales.toLanguageTags())
            .put("rootBounds", remoteFontRectJson(root.fetchSemanticsNode().boundsInRoot))
            .put("test", testName.methodName)
            .put("phase", name)
            .put("observations", observations)
            .put("nativeTexts", JSONArray(texts.map { it.report }))
        File(directory, "$name-layout.json").writeText(metadata.toString(JSON_INDENT))
    }

    private companion object {
        const val PNG_QUALITY = 100
        const val JSON_INDENT = 2
    }
}

internal class RemoteFontLayoutLabels {
    lateinit var telemetry: String
    lateinit var contactInfo: String
    lateinit var contactHint: String
    lateinit var historyNotice: String

    @Composable
    fun read() {
        telemetry = stringResource(Res.string.remotenodes_remote_nodes_settings_tab_telemetry)
        contactInfo = stringResource(Res.string.remotenodes_remote_nodes_settings_contact_info)
        contactHint = stringResource(Res.string.remotenodes_remote_nodes_settings_contact_info_placeholder)
        historyNotice = stringResource(Res.string.remotenodes_remote_nodes_history_check_back)
    }
}
