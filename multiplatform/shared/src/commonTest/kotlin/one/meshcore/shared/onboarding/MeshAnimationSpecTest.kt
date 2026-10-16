package one.meshcore.shared.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MeshAnimationSpecTest {
    @Test
    fun edgeOpacityAtRestIsMinimum() {
        assertEquals(MeshAnimationSpec.EDGE_MIN_OPACITY, meshEdgeOpacity(0, 0.0), 1e-9)
    }

    @Test
    fun nodeScaleAtRestIsOne() {
        assertEquals(1.0, meshNodeScale(0, 0.0), 1e-9)
    }

    @Test
    fun messageStartsOnTheFirstPathNode() {
        val sample = assertNotNull(meshMessagePosition(0.0))
        val start = MeshAnimationSpec.nodes[MeshAnimationSpec.messagePath[0]]
        assertEquals(start.nx, sample.nx, 1e-9)
        assertEquals(start.ny, sample.ny, 1e-9)
    }

    @Test
    fun messageIsHalfwayAlongTheSecondHopAtMidCycle() {
        val sample = assertNotNull(meshMessagePosition(MeshAnimationSpec.MESSAGE_CYCLE_SECONDS / 2.0))
        val from = MeshAnimationSpec.nodes[4]
        val to = MeshAnimationSpec.nodes[1]
        assertEquals((from.nx + to.nx) / 2.0, sample.nx, 1e-9)
        assertEquals((from.ny + to.ny) / 2.0, sample.ny, 1e-9)
    }

    @Test
    fun userNodeIsTheCenterHub() {
        val users = MeshAnimationSpec.nodes.filter { it.isUser }
        assertEquals(1, users.size)
        assertEquals(0.5, users.single().nx, 1e-9)
        assertEquals(0.55, users.single().ny, 1e-9)
    }
}
