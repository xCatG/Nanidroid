package com.cattailsw.nanidroid.engine

import com.cattailsw.nanidroid.ghost.GhostDescriptor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EngineSelectorTest {
    @get:Rule val temp = TemporaryFolder()
    private fun ghost(declaration: String?, vararg files: Pair<String, String>): GhostDescriptor {
        val master = temp.newFolder().resolve("ghost/master").apply { mkdirs() }
        files.forEach { (name, body) -> master.resolve(name).writeText(body) }
        return GhostDescriptor("sample", "sample", "Sakura", "Kero", master, emptyMap(), declaration, null)
    }

    private fun file(name: String, body: String = "") = name to body

    @Test fun exactDeclarationsAndPrecedenceChooseSupportedEngines() {
        assertEquals(EngineKind.BUILTIN, EngineSelector.select(ghost("Nanidroid", file("yaya.dll"), file("yaya.txt"))))
        assertEquals(EngineKind.SATORI, EngineSelector.select(ghost("satori.dll", file("yaya.dll"), file("yaya.txt"))))
        assertEquals(EngineKind.KAWARI, EngineSelector.select(ghost("shiori.dll", file("kawarirc.kis"), file("yaya.dll"), file("yaya.txt"))))
    }

    @Test fun defaultYayaPairIsNative() {
        assertEquals(EngineKind.YAYA, EngineSelector.select(ghost("yaya.dll", file("yaya.dll"), file("yaya.txt"))))
    }

    @Test fun renamedModuleWithMarkerAndMatchingConfigIsNative() {
        assertEquals(EngineKind.YAYA, EngineSelector.select(ghost("X.dll", file("X.dll", "prefix yaya.dll suffix"), file("X.txt"))))
    }

    @Test fun overlappingMarkerIsFound() {
        assertEquals(EngineKind.YAYA, EngineSelector.select(ghost("X.dll", file("X.dll", "yayaya.dll"), file("X.txt"))))
    }

    @Test fun overlappingMarkerAcrossReadBoundaryIsFound() {
        val bytes = "x".repeat(8190) + "yayaya.dll"
        assertEquals(EngineKind.YAYA, EngineSelector.select(ghost("X.dll", file("X.dll", bytes), file("X.txt"))))
    }

    @Test fun aya5OnlyWithoutMarkerIsUnsupported() {
        assertEquals(EngineKind.UNSUPPORTED, EngineSelector.select(ghost("aya5.dll", file("aya5.dll"), file("aya5.txt"))))
    }

    @Test fun markerModuleWithoutMatchingConfigIsUnsupported() {
        assertEquals(EngineKind.UNSUPPORTED, EngineSelector.select(ghost("X.dll", file("X.dll", "yaya.dll"))))
    }

    @Test fun unrelatedDllIsUnsupported() {
        assertEquals(EngineKind.UNSUPPORTED, EngineSelector.select(ghost("other.dll", file("other.dll", "not a YAYA module"), file("other.txt"))))
    }

    @Test fun unsafeDeclarationCannotUseDefaultConfig() {
        assertEquals(EngineKind.UNSUPPORTED, EngineSelector.select(ghost("../yaya.dll", file("yaya.txt"))))
    }

    @Test fun mixedAya5AndYayaPairsKeepNativeYaya() {
        assertEquals(EngineKind.YAYA, EngineSelector.select(ghost("yaya.dll", file("aya5.dll"), file("aya5.txt"), file("yaya.dll"), file("yaya.txt"))))
    }

    @Test fun unsupportedDeclarationsAreNotInferredFromRandomDlls() {
        assertEquals(EngineKind.UNSUPPORTED, EngineSelector.select(ghost("shiori.dll", file("kawari.ini"))))
        assertEquals(EngineKind.UNSUPPORTED, EngineSelector.select(ghost("random.dll", file("satori.dll"))))
        assertEquals(EngineKind.UNSUPPORTED, EngineSelector.select(ghost(null)))
    }
}
