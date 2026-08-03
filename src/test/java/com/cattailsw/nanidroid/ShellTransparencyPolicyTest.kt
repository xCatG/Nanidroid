package com.cattailsw.nanidroid

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShellTransparencyPolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @get:Rule
    val androidStubs = HostAndroidStubRule()

    @Test
    fun `shell descriptor one selects authored alpha while all other values retain legacy keying`() {
        assertEquals(
            SurfaceTransparencyPolicy.AUTHORED_ALPHA,
            SurfaceTransparencyPolicy.fromShellDescriptor(mapOf("seriko.use_self_alpha" to "1")),
        )
        assertEquals(
            SurfaceTransparencyPolicy.LEGACY_COLOR_KEY,
            SurfaceTransparencyPolicy.fromShellDescriptor(mapOf("seriko.use_self_alpha" to "0")),
        )
        assertEquals(
            SurfaceTransparencyPolicy.LEGACY_COLOR_KEY,
            SurfaceTransparencyPolicy.fromShellDescriptor(emptyMap()),
        )
    }

    @Test
    fun `surface reader propagates the selected shell policy into every definition`() {
        val shell = temporaryFolder.newFolder("shell")
        File(shell, "surfaces.txt").writeText(
            """
            charset,UTF-8
            surface0
            {
            collision0,0,0,0,0,Hit
            }
            """.trimIndent(),
        )
        val manager = SurfaceManager("fixture")

        SurfaceReader(
            manager,
            shell.absolutePath,
            File(shell, "surfaces.txt").absolutePath,
            SurfaceTransparencyPolicy.AUTHORED_ALPHA,
        )

        assertEquals(
            SurfaceTransparencyPolicy.AUTHORED_ALPHA,
            requireNotNull(manager.getSurface("0")).toSurfaceDefinition().transparencyPolicy,
        )
    }
}
