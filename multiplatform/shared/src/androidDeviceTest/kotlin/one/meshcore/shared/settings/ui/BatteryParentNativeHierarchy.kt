package one.meshcore.shared.settings.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import org.xmlpull.v1.XmlSerializer
import java.io.File

internal fun writeBatteryParentNativeHierarchy(automation: UiAutomation, file: File) {
    val originalInfo = automation.serviceInfo
    val originalFlags = originalInfo.flags
    try {
        originalInfo.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = originalInfo
        writeNativeHierarchyXml(automation, file)
    } finally {
        originalInfo.flags = originalFlags
        automation.serviceInfo = originalInfo
    }
}

private fun writeNativeHierarchyXml(automation: UiAutomation, file: File) {
    file.outputStream().use { output ->
        val xml = Xml.newSerializer()
        xml.setOutput(output, "UTF-8")
        xml.startDocument("UTF-8", true)
        xml.startTag(null, "hierarchy")
        automation.windows.forEach { window ->
            xml.startTag(null, "window").attribute(null, "id", window.id.toString())
            window.root?.let { writeNativeNode(xml, it) }
            xml.endTag(null, "window")
        }
        automation.rootInActiveWindow?.let { writeNativeNode(xml, it) }
        xml.endTag(null, "hierarchy")
        xml.endDocument()
    }
}

private fun writeNativeNode(xml: XmlSerializer, node: AccessibilityNodeInfo) {
    val bounds = android.graphics.Rect()
    node.getBoundsInScreen(bounds)
    xml.startTag(null, "node")
        .attribute(null, "class", node.className?.toString().orEmpty())
        .attribute(null, "text", node.text?.toString().orEmpty())
        .attribute(null, "description", node.contentDescription?.toString().orEmpty())
        .attribute(null, "bounds", bounds.toShortString())
        .attribute(null, "focused", node.isFocused.toString())
        .attribute(null, "enabled", node.isEnabled.toString())
    repeat(node.childCount) { index -> node.getChild(index)?.let { writeNativeNode(xml, it) } }
    xml.endTag(null, "node")
}
