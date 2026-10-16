package one.meshcore.shared.remotenode.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.resolveDefaults
import androidx.compose.ui.unit.Constraints
import org.json.JSONArray
import org.json.JSONObject

internal data class RemoteFontNativeText(
    val text: String,
    val nodeId: Int,
    val ancestorIds: List<Int>,
    val rawFrame: Rect,
    val clippedFrame: Rect,
    val lines: List<Rect>,
    val paragraphHeight: Float,
    val complete: Boolean,
    val report: JSONObject,
)

internal fun remoteFontNativeTexts(compose: ComposeContentTestRule): List<RemoteFontNativeText> {
    val nodes = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
        useUnmergedTree = true,
    ).fetchSemanticsNodes()
    return compose.runOnIdle {
        nodes.flatMap { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            results.map { remoteFontNativeText(node, it) }
        }
    }
}

private fun remoteFontNativeText(node: SemanticsNode, result: TextLayoutResult): RemoteFontNativeText {
    val input = result.layoutInput
    val coordinates = node.layoutInfo.coordinates
    // String Text semantics can rebuild at maxWidth; measure the paragraph at its actual native width.
    val paragraph = Paragraph(
        text = input.text.text,
        style = resolveDefaults(input.style, input.layoutDirection),
        constraints = Constraints(maxWidth = coordinates.size.width),
        density = input.density,
        fontFamilyResolver = input.fontFamilyResolver,
        spanStyles = input.text.spanStyles,
        placeholders = input.placeholders,
        maxLines = input.maxLines,
        overflow = input.overflow,
    )
    val rawFrame = remoteFontRootRect(
        node,
        Rect(0f, 0f, coordinates.size.width.toFloat(), coordinates.size.height.toFloat()),
    )
    val lines = List(paragraph.lineCount) { line ->
        remoteFontRootRect(
            node,
            Rect(
                paragraph.getLineLeft(line),
                paragraph.getLineTop(line),
                paragraph.getLineRight(line),
                paragraph.getLineBottom(line),
            ),
        )
    }
    val last = paragraph.lineCount - 1
    val complete = last >= 0 &&
        !result.didOverflowHeight &&
        !paragraph.didExceedMaxLines &&
        paragraph.height <= coordinates.size.height + REMOTE_FONT_PIXEL_TOLERANCE &&
        paragraph.getLineEnd(last) == input.text.length &&
        (0..last).none(paragraph::isLineEllipsized) &&
        lines.all { remoteFontContains(rawFrame, it) }
    val ancestors = generateSequence(node.parent) { it.parent }.map { it.id }.toList()
    val report = JSONObject()
        .put("text", input.text.text)
        .put("nodeId", node.id)
        .put("ancestorIds", JSONArray(ancestors))
        .put("rawFrame", remoteFontRectJson(rawFrame))
        .put("clippedFrame", remoteFontRectJson(node.boundsInRoot))
        .put("lines", JSONArray(lines.map(::remoteFontRectJson)))
        .put("coordinateWidth", coordinates.size.width)
        .put("coordinateHeight", coordinates.size.height)
        .put("layoutWidth", result.size.width)
        .put("layoutHeight", result.size.height)
        .put("paragraphHeight", paragraph.height.toDouble())
        .put("lineCount", paragraph.lineCount)
        .put("heightOverflow", result.didOverflowHeight)
        .put("widthOverflow", result.didOverflowWidth)
        .put("exceededMaxLines", paragraph.didExceedMaxLines)
        .put("lastLineEnd", if (last >= 0) paragraph.getLineEnd(last) else 0)
        .put("textLength", input.text.length)
        .put("ellipsizedLines", JSONArray((0..last).map(paragraph::isLineEllipsized)))
        .put("constraints", input.constraints.toString())
        .put("density", input.density.density.toDouble())
        .put("fontScale", input.density.fontScale.toDouble())
        .put("complete", complete)
    return RemoteFontNativeText(
        input.text.text,
        node.id,
        ancestors,
        rawFrame,
        node.boundsInRoot,
        lines,
        paragraph.height,
        complete,
        report,
    )
}

internal fun remoteFontContains(outer: Rect, inner: Rect): Boolean =
    inner.left >= outer.left - REMOTE_FONT_PIXEL_TOLERANCE &&
        inner.top >= outer.top - REMOTE_FONT_PIXEL_TOLERANCE &&
        inner.right <= outer.right + REMOTE_FONT_PIXEL_TOLERANCE &&
        inner.bottom <= outer.bottom + REMOTE_FONT_PIXEL_TOLERANCE

internal fun remoteFontRectJson(rect: Rect): JSONObject = JSONObject()
    .put("left", rect.left.toDouble())
    .put("top", rect.top.toDouble())
    .put("right", rect.right.toDouble())
    .put("bottom", rect.bottom.toDouble())

private fun remoteFontRootRect(node: SemanticsNode, local: Rect): Rect {
    val coordinates = node.layoutInfo.coordinates
    val corners = listOf(local.topLeft, local.topRight, local.bottomLeft, local.bottomRight)
        .map { coordinates.localToRoot(it) }
    return Rect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
}

internal const val REMOTE_FONT_PIXEL_TOLERANCE = 1f
