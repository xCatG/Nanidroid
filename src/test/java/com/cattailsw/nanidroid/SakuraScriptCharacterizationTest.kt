package com.cattailsw.nanidroid

import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Arrays

/** Characterizes Sakura Script as an ordered semantic event trace.  */
class SakuraScriptCharacterizationTest {
    @Rule
    @JvmField
    val androidStubs: com.cattailsw.nanidroid.HostAndroidStubRule =
        com.cattailsw.nanidroid.HostAndroidStubRule()
    private val trace = Trace()
    private lateinit var runner: com.cattailsw.nanidroid.SScriptRunner

    @Before
    fun setUp() {
        runner = SScriptRunner(null, GhostSessionCoordinator())
        runner.setNoWaitMode(true)
        runner.setGhost(null)
        runner.setCallback(null)
        runner.setUICallback(RecordingUiCallback(trace))
        runner.setPresentationRenderer(RecordingRenderer(trace))
        runner.clearMsgQueue()

        // The fresh runner is driven to a deterministic surface baseline before
        // observing each fixture.
        runScript("\\h\\s[0]\\u\\s[10]\\e")
        trace.clear()
    }

    @After
    fun tearDown() {
        runner.setUICallback(null)
        runner.clearMsgQueue()
        runner.setCallback(null)
        runner.setGhost(null)
        runner.setNoWaitMode(true)
    }

    @Test
    fun requiredMigrationInvariant_speakerTextSurfaceAndAnimationHaveOrderedTrace() {
        assertTrace(
            "eb3101be780c6f27d1876911986a544673a4807a1a33dbf87c132faef0bc4cf7",
            "\\hHi\\s[120]\\i[3]\\uYo\\s[11]\\i[4]\\e",
            "text:sakura:H",
            "text:sakura:Hi",
            "surface:sakura:120",
            "animation:sakura:3",
            "text:kero:Y",
            "text:kero:Yo",
            "surface:kero:11",
            "animation:kero:4"
        )
    }

    @Test
    fun requiredMigrationInvariant_newlineModifierAndClearHaveOrderedTextStates() {
        assertTrace(
            "d5c3ca435493d40f213b620286676313a762d113f7db94eaae96b7b9d3ca1893",
            "\\hA\\n[half]B\\cC\\e",
            "text:sakura:A",
            "text:sakura:A\\n",
            "text:sakura:A\\nB",
            "text:sakura:C"
        )
    }

    @Test
    fun requiredMigrationInvariant_quickSessionEmitsOneWholeLineTextState() {
        assertTrace(
            "16116174b6633c28f55373f47a54e84e6be455ce62707009bf39872757bd82eb",
            "\\h\\_qHello, world.\\e",
            "text:sakura:Hello, world."
        )
    }

    @Test
    fun requiredMigrationInvariant_distinctSurfaceTransitionsAndAnimationStartsAreOrdered() {
        assertTrace(
            "6fdd70fdfa7ba5db2e83a36481a963ed516bd595b7cfeb0fc131edbb94685047",
            "\\h\\s[120]\\s[120]\\i[3]\\i[3]\\e",
            "surface:sakura:120",
            "animation:sakura:3",
            "animation:sakura:3"
        )
    }

    @Test
    fun legacyObserved_choicesAreReportedThenTheirLabelsContinueAsText() {
        assertTrace(
            "85a129feecd76e217ff9495e44e159bc7db0088a830e3aaf28f4f74ecac08687",
            "\\hA\\q[One,id1]B\\q[Two,id2]\\e",
            "text:sakura:A",
            "choice:[One, Two]:[id1, id2]",
            "text:sakura:AOneBTwo"
        )
    }

    @Test
    fun legacyObserved_unsupportedTagsAreConsumedRatherThanRendered() {
        assertTrace(
            "4f983c4271218d8335f2352efd2adcd138e97514f82a6b5cb5537530298c7fbc",
            "\\hA\\4\\5\\6\\v\\_n\\_V\\_l[half]B\\e",
            "text:sakura:A",
            "text:sakura:AB"
        )
    }

    @Test
    fun rendererReceivesAnimationOnlyWhenTheRunnerSchedulesIt() {
        runScript("\\h\\i[3]\\u\\i[4]\\e")
        Assert.assertEquals(
            mutableListOf<String?>("animation:sakura:3", "animation:kero:4"),
            trace.events()
        )
    }

    private fun runScript(fixture: String?) {
        runner.addMsgToQueue(arrayOf(requireNotNull(fixture)))
        runner.run()
    }

    private fun assertTrace(hash: String?, fixture: String, vararg expectedEvents: String?) {
        assertFixtureSha256(hash, fixture)
        runScript(fixture)
        Assert.assertEquals(Arrays.asList<String?>(*expectedEvents), trace.events())
    }

    private class Trace {
        private val events: MutableList<String?> = ArrayList<String?>()

        fun add(event: String?) {
            events.add(event)
        }

        fun clear() {
            events.clear()
        }

        fun events(): MutableList<String?> {
            return ArrayList<String?>(events)
        }
    }

    private class RecordingUiCallback(private val trace: Trace) :
        com.cattailsw.nanidroid.SScriptRunner.UICallback {
        override fun showUserInputBox(id: String) {
            trace.add("input:" + id)
        }

        override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) {
            trace.add("choice:" + textlabel.contentToString() + ":" + ids.contentToString())
        }
    }

    /** UI-free renderer fixture: unexpected frame fields remain observable.  */
    private class RecordingRenderer(private val trace: Trace) :
        com.cattailsw.nanidroid.GhostPresentationRenderer {
        private var sakuraText = ""
        private var keroText = ""
        private var sakuraSurface: String? = null
        private var keroSurface: String? = null

        public override fun render(frame: com.cattailsw.nanidroid.GhostPresentationFrame) {
            recordSpeaker("sakura", frame.sakura, true)
            recordSpeaker("kero", frame.kero, false)
        }

        fun recordSpeaker(
            speaker: String?,
            state: com.cattailsw.nanidroid.GhostPresentationFrame.Speaker,
            sakura: Boolean
        ) {
            val previousText = if (sakura) sakuraText else keroText
            if (state.text != previousText) {
                if (!state.text.isEmpty()) {
                    trace.add("text:" + speaker + ":" + state.text.replace("\n", "\\n"))
                }
                if (sakura) {
                    sakuraText = state.text
                } else {
                    keroText = state.text
                }
            }
            val previousSurface = if (sakura) sakuraSurface else keroSurface
            if (state.surfaceId != previousSurface) {
                trace.add("surface:" + speaker + ":" + state.surfaceId)
                if (sakura) {
                    sakuraSurface = state.surfaceId
                } else {
                    keroSurface = state.surfaceId
                }
            }
            if (state.animationId != null) {
                trace.add("animation:" + speaker + ":" + state.animationId)
            }
        }
    }

    companion object {
        private fun assertFixtureSha256(expected: String?, fixture: String) {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(fixture.toByteArray(Charset.forName("UTF-8")))
                val actual = StringBuilder(digest.size * 2)
                for (value in digest) {
                    actual.append(String.format("%02x", value.toInt() and 0xff))
                }
                Assert.assertEquals("Synthetic fixture text changed", expected, actual.toString())
            } catch (error: Exception) {
                throw AssertionError(error)
            }
        }
    }
}
