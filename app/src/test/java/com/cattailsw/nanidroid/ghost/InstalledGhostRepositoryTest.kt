package com.cattailsw.nanidroid.ghost

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeNoException
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files

class InstalledGhostRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    private fun root() = temp.newFolder("files").resolve("ghost").apply { mkdirs() }

    private fun install(root: File, id: String, descriptor: String = "name,$id\r\nshiori,Nanidroid\r\n"): File =
        root.resolve(id).apply {
            resolve("ghost/master").mkdirs()
            resolve("ghost/master/descript.txt").writeBytes(descriptor.toByteArray(Charset.forName("windows-31j")))
            resolve("shell/master").mkdirs()
        }

    @Test fun elementOnlyInstalledShellRetainsValidatedMasterPath() {
        val root = root()
        val installed = install(root, "element-only")
        installed.resolve("shell/master/surfaces.txt").writeText(
            "surface0 {\nelement0,base,body.png,0,0\n}\nsurface10 {\nelement0,base,menu.png,0,0\n}")
        val descriptor = InstalledGhostRepository(root).validate("element-only")
        assertTrue(descriptor.surfaces.isEmpty())
        assertEquals(installed.resolve("ghost/master"), descriptor.masterPath)
        assertTrue(installed.resolve("shell/master").isDirectory)
    }

    @Test fun secondaryNameUsesExplicitValueOrFallsBackForMissingAndBlank() {
        val root = root()
        install(root, "explicit", "sakura.name,First\nsakura.name2,Second\n")
        install(root, "missing", "sakura.name,First\n")
        install(root, "blank", "sakura.name,First\nsakura.name2,  \n")
        val repo = InstalledGhostRepository(root)
        assertEquals("Second", repo.validate("explicit").sakuraName2)
        assertEquals("First", repo.validate("missing").sakuraName2)
        assertEquals("First", repo.validate("blank").sakuraName2)
    }

    @Test fun listsSortedValidPrivateGhostsAndUsesCp932Names() {
        val root = root()
        val z = install(root, "zulu", "name,こんにちは\r\nshiori,satori.dll\r\n")
        z.resolve("shell/master/surface0000.png").writeBytes(byteArrayOf(1))
        z.resolve("readme.txt").writeText("readme")
        install(root, "alpha", "shiori,Nanidroid\r\n")
        root.resolve("broken").mkdirs()
        val found = InstalledGhostRepository(root).list()
        assertEquals(listOf("alpha", "zulu"), found.map { it.directoryId })
        assertEquals(listOf("alpha", "こんにちは"), found.map { it.displayName })
        assertEquals(z.resolve("ghost/master"), found[1].masterPath)
        assertEquals(z.resolve("shell/master/surface0000.png"), found[1].surfaces[0])
        assertEquals(z.resolve("readme.txt"), found[1].readmePath)
    }

    @Test fun validationRejectsTraversalAndIncompleteCandidates() {
        val root = root()
        install(root, "valid")
        val repo = InstalledGhostRepository(root)
        for (id in listOf("", ".", "..", "../valid", "valid/other", "valid\\other", "C:valid")) {
            assertThrows(IllegalArgumentException::class.java) { repo.validate(id) }
        }
        val noShell = install(root, "no-shell")
        noShell.resolve("shell/master").delete()
        assertThrows(IllegalArgumentException::class.java) { repo.validate("no-shell") }
        val malformed = install(root, "malformed", "bad line only\r\n")
        assertThrows(IllegalArgumentException::class.java) { repo.validate("malformed") }
        assertEquals(listOf("valid"), repo.list().map { it.directoryId })
    }

    @Test fun descriptorAtByteLimitValidatesAndLongerInstalledDescriptorIsHidden() {
        val root = root()
        val exact = install(root, "exact", "name,Exact\n")
        val oversize = install(root, "oversize", "name,Longer\n")
        val bound = exact.resolve("ghost/master/descript.txt").length().toInt()
        val repo = InstalledGhostRepository(root, descriptorByteLimit = bound)

        assertEquals("Exact", repo.validate("exact").displayName)
        val error = assertThrows(IllegalArgumentException::class.java) { repo.validate("oversize") }
        assertEquals("Ghost descriptor byte limit exceeded", error.message)
        assertEquals(listOf("exact"), repo.list().map { it.directoryId })
        assertTrue(oversize.resolve("ghost/master/descript.txt").length() > bound)
    }

    @Test fun surfaceIndexKeepsExactCaseInsensitiveNumberedPngNames() {
        val root = root()
        val shell = install(root, "named").resolve("shell/master")
        for (name in listOf("surface0000.png", "SURFACE0010.PNG", "surface00021.png",
            "surface-1.png", "surface2.png.bak", "xsurface3.png", "surface2147483648.png")) {
            shell.resolve(name).writeBytes(byteArrayOf(1))
        }

        val surfaces = InstalledGhostRepository(root).validate("named").surfaces
        assertEquals(setOf(0, 10, 21), surfaces.keys)
        assertEquals("SURFACE0010.PNG", surfaces.getValue(10).name)
    }

    @Test fun symlinkCandidatesCannotEscapePrivateRoot() {
        val root = root()
        val outside = temp.newFolder("outside")
        install(outside, "external")
        try {
            Files.createSymbolicLink(root.resolve("linked").toPath(), outside.resolve("external").toPath())
        } catch (e: java.nio.file.FileSystemException) {
            assumeNoException(e)
        }
        assertThrows(IllegalArgumentException::class.java) { InstalledGhostRepository(root).validate("linked") }
        assertTrue(InstalledGhostRepository(root).list().isEmpty())
    }
}
