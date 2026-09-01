package com.cattailsw.nanidroid.runtime.stage

import androidx.compose.ui.unit.dp
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageEnvironmentTest {
    @Test
    fun `canonical app bar is reserved once from offset safe bounds`() {
        val layout = GhostStageLayoutPolicy.calculate(
            environment(
                bounds = rect(12, 20, 372, 796),
                appBarHeight = 56,
            ),
        )

        assertEquals(StageMode.STANDARD, layout.mode)
        assertRect(12, 76, 372, 796, layout.content)
    }

    @Test
    fun `viewport matrix classifies from the selected post-app-bar candidate`() {
        val cases = listOf(
            Triple(360, 720, StageMode.STANDARD),
            Triple(720, 360, StageMode.COMPACT_LANDSCAPE),
            Triple(400, 1000, StageMode.STANDARD),
            Triple(610, 500, StageMode.STANDARD),
            Triple(800, 1280, StageMode.STANDARD),
            Triple(1280, 800, StageMode.STANDARD),
            Triple(480, 230, StageMode.TINY),
            Triple(230, 400, StageMode.TINY),
        )

        cases.forEach { (width, height, expected) ->
            assertEquals("${width}x$height", expected, GhostStageLayoutPolicy.calculate(environment(width, height)).mode)
        }
    }

    @Test
    fun `classification thresholds use the documented inclusive order`() {
        val cases = listOf(
            Triple(419, 240, StageMode.TINY),
            Triple(420, 239, StageMode.TINY),
            Triple(420, 240, StageMode.COMPACT_LANDSCAPE),
            Triple(420, 241, StageMode.COMPACT_LANDSCAPE),
            Triple(419, 350, StageMode.STANDARD),
            Triple(420, 350, StageMode.COMPACT_LANDSCAPE),
            Triple(421, 350, StageMode.COMPACT_LANDSCAPE),
            Triple(300, 319, StageMode.TINY),
            Triple(300, 320, StageMode.STANDARD),
            Triple(300, 321, StageMode.STANDARD),
            Triple(575, 480, StageMode.STANDARD),
            Triple(576, 480, StageMode.STANDARD),
            Triple(577, 480, StageMode.STANDARD),
            Triple(720, 479, StageMode.COMPACT_LANDSCAPE),
            Triple(720, 480, StageMode.STANDARD),
            Triple(720, 481, StageMode.STANDARD),
        )

        cases.forEach { (width, height, expected) ->
            assertEquals("${width}x$height", expected, GhostStageLayoutPolicy.calculate(environment(width, height)).mode)
        }
    }

    @Test
    fun `feature order cannot change the chosen rectangle`() {
        val vertical = feature(rect(490, 0, 510, 700), separating = true)
        val horizontal = feature(rect(0, 330, 490, 350), occluding = true)
        val forward = GhostStageLayoutPolicy.calculate(
            environment(bounds = rect(100, 40, 1100, 740), features = listOf(vertical, horizontal)),
        )
        val reverse = GhostStageLayoutPolicy.calculate(
            environment(bounds = rect(100, 40, 1100, 740), features = listOf(horizontal, vertical)),
        )

        assertEquals(forward, reverse)
        assertRect(510, 40, 1100, 740, forward.content)
    }

    @Test
    fun `smaller valid top pane wins over larger tiny left pane`() {
        val layout = GhostStageLayoutPolicy.calculate(
            environment(
                700,
                1000,
                features = listOf(feature(rect(230, 300, 700, 1000), occluding = true)),
            ),
        )

        assertEquals(StageMode.COMPACT_LANDSCAPE, layout.mode)
        assertRect(0, 0, 700, 300, layout.content)
    }

    @Test
    fun `cross split valid pane wins over higher area tiny pane`() {
        val layout = GhostStageLayoutPolicy.calculate(
            environment(
                650,
                1500,
                features = listOf(
                    feature(rect(0, 0, 230, 500), occluding = true),
                    feature(rect(230, 500, 650, 1500), occluding = true),
                ),
            ),
        )

        assertEquals(StageMode.STANDARD, layout.mode)
        assertRect(230, 0, 650, 500, layout.content)
    }

    @Test
    fun `equal area candidates choose topmost then leftmost`() {
        val cross = listOf(
            feature(rect(200, 0, 220, 420), separating = true),
            feature(rect(0, 200, 420, 220), separating = true),
        )

        val layout = GhostStageLayoutPolicy.calculate(
            environment(bounds = rect(0, 0, 420, 420), features = cross),
        )

        assertRect(0, 0, 200, 200, layout.content)
        assertEquals(StageMode.TINY, layout.mode)
    }

    @Test
    fun `zero width separating fold splits while zero area occluding feature is ignored`() {
        val split = GhostStageLayoutPolicy.calculate(
            environment(
                bounds = rect(10, 20, 1010, 720),
                features = listOf(feature(rect(510, 20, 510, 720), separating = true)),
            ),
        )
        val ignored = GhostStageLayoutPolicy.calculate(
            environment(
                bounds = rect(10, 20, 1010, 720),
                features = listOf(feature(rect(510, 20, 510, 720), occluding = true)),
            ),
        )

        assertRect(10, 20, 510, 720, split.content)
        assertRect(30, 20, 990, 720, ignored.content)
    }

    @Test
    fun `partial blocker is never crossed and removing it restores full result`() {
        val blocker = feature(rect(400, 200, 600, 500), occluding = true)
        val blocked = GhostStageLayoutPolicy.calculate(environment(1000, 700, features = listOf(blocker)))
        val restored = GhostStageLayoutPolicy.calculate(environment(1000, 700))

        assertRect(0, 0, 400, 700, blocked.content)
        assertRect(20, 0, 980, 700, restored.content)
        assertNull(blocked.content.positiveIntersection(blocker.bounds))
    }

    @Test
    fun `fixed seed blocker order never changes or intersects the selected candidate`() {
        val random = Random(0x46454154555245L)
        repeat(100) {
            val blockers = List(1 + random.nextInt(3)) {
                val left = random.nextInt(900)
                val top = random.nextInt(600)
                feature(
                    rect(
                        left,
                        top,
                        minOf(1000, left + 1 + random.nextInt(100)),
                        minOf(700, top + 1 + random.nextInt(100)),
                    ),
                    occluding = true,
                )
            }
            val forward = GhostStageLayoutPolicy.calculate(environment(1000, 700, blockers))
            val reverse = GhostStageLayoutPolicy.calculate(environment(1000, 700, blockers.reversed()))

            assertEquals(forward, reverse)
            assertTrue(blockers.none { blocker -> forward.content.positiveIntersection(blocker.bounds) != null })
        }
    }

    private fun environment(
        width: Int,
        height: Int,
        features: List<StageDisplayFeature> = emptyList(),
    ) = environment(rect(0, 0, width, height), features = features)

    private fun environment(
        bounds: StageDpRect,
        features: List<StageDisplayFeature> = emptyList(),
        appBarHeight: Int = 0,
    ) = StageEnvironment(
        safeBounds = bounds,
        density = 2f,
        canonicalAppBarHeight = appBarHeight.dp,
        posture = StagePosture.FLAT,
        displayFeatures = features,
    )

    private fun feature(
        bounds: StageDpRect,
        separating: Boolean = false,
        occluding: Boolean = false,
    ) = StageDisplayFeature(bounds, separating, occluding)

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) =
        StageDpRect(left.dp, top.dp, right.dp, bottom.dp)

    private fun assertRect(left: Int, top: Int, right: Int, bottom: Int, actual: StageDpRect) {
        assertEquals(left.toFloat(), actual.left.value, 0.001f)
        assertEquals(top.toFloat(), actual.top.value, 0.001f)
        assertEquals(right.toFloat(), actual.right.value, 0.001f)
        assertEquals(bottom.toFloat(), actual.bottom.value, 0.001f)
    }
}
