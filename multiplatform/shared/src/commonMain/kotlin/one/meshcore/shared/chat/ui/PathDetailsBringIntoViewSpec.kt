package one.meshcore.shared.chat.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.BringIntoViewSpec

/** Pins a child's top edge to the viewport, whether the child is short or tall. */
internal class PathDetailsBringIntoViewSpec(reduceMotion: Boolean) : BringIntoViewSpec {
    @Suppress("OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> = if (reduceMotion) snap() else spring()

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset
}
