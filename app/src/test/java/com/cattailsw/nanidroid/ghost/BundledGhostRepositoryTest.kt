package com.cattailsw.nanidroid.ghost

import com.cattailsw.nanidroid.engine.BuiltInShiori
import com.cattailsw.nanidroid.engine.ShioriEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.test.runTest

class BundledGhostRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun existingLiveTargetIsNeverReplaced() {
        val root = temporaryFolder.newFolder("ghost")
        val live = File(root, "nanidroid").apply { mkdirs() }
        File(live, "keep.txt").writeText("original")
        val repository = BundledGhostRepository({ error("Asset must not be opened") }, root)
        assertEquals(live, repository.ensureInstalled())
        assertEquals("original", File(live, "keep.txt").readText())
    }

    @Test fun badEntryAfterGoodEntryLeavesNoLiveTarget() {
        val root = temporaryFolder.newFolder("ghost")
        val archive = zip("good.txt" to "written", "../escape.txt" to "bad")
        val repository = BundledGhostRepository({ ByteArrayInputStream(archive) }, root, sha256(archive))
        try {
            repository.ensureInstalled()
            throw AssertionError("Expected unsafe entry to fail")
        } catch (_: IllegalArgumentException) {
            assertFalse(File(root, "nanidroid").exists())
            assertFalse(File(root.parentFile, "escape.txt").exists())
        }
    }

    @Test fun retryCleansOnlyOwnedStaging() {
        val root = temporaryFolder.newFolder("ghost")
        val staging = File(root, ".nanidroid-staging").apply { mkdirs() }
        File(staging, "abandoned.txt").writeText("partial")
        val unrelated = File(root, "other").apply { mkdirs() }
        File(unrelated, "keep.txt").writeText("untouched")
        val archive = javaClass.getResourceAsStream("/nanidroid.zip")!!.use { it.readBytes() }
        val live = BundledGhostRepository({ ByteArrayInputStream(archive) }, root).ensureInstalled()
        assertTrue(File(live, "install.txt").isFile)
        assertFalse(File(live, "abandoned.txt").exists())
        assertFalse(staging.exists())
        assertEquals("untouched", File(unrelated, "keep.txt").readText())
    }

    @Test fun realBundleLoadsEnglishJapaneseAndFallback() = runTest {
        val root = temporaryFolder.newFolder("ghost")
        val archive = javaClass.getResourceAsStream("/nanidroid.zip")!!.use { it.readBytes() }
        val repository = BundledGhostRepository({ ByteArrayInputStream(archive) }, root)
        val installed = repository.ensureInstalled()
        val english = repository.load(installed, "en")
        val japanese = repository.load(installed, "ja")
        val fallback = repository.load(installed, "fr")
        assertEquals("nanidroid", english.directoryId)
        assertEquals("Nanidroid", english.name)
        assertEquals("郭嘉榆", english.sakuraName)
        assertEquals("ドロイド君", english.keroName)
        assertEquals(setOf(0, 10), english.surfaces.keys)
        assertEquals(File(installed, "shell/master"), english.shellDirectory)
        assertEquals(200, BuiltInShiori(english.content).request(ShioriEvent("OnFirstBoot")).status)
        assertEquals(204, BuiltInShiori(english.content).request(ShioriEvent("OnMouseClick")).status)
        assertEquals("\\0\\s0Nanidroid起動！\\e", BuiltInShiori(japanese.content).request(ShioriEvent("OnBoot")).value)
        assertEquals(japanese.content, fallback.content)
    }

    @Test fun bundledLoadRetainsShellPathWhenZeroAndTenAreElementOnly() {
        val root = temporaryFolder.newFolder("element-only")
        File(root, "install.txt").writeText("type,ghost\ndirectory,nanidroid")
        File(root, "ghost/master").mkdirs()
        File(root, "ghost/master/descript.txt").writeText("name,Element Ghost")
        File(root, "ghost/master/ja").mkdirs()
        File(root, "ghost/master/ja/content.txt").writeText("")
        val shell = File(root, "shell/master").apply { mkdirs() }
        File(shell, "surfaces.txt").writeText("surface0 {\nelement0,base,body.png,0,0\n}")
        val ghost = BundledGhostRepository({ error("Not installing") }, root.parentFile).load(root, "ja")
        assertTrue(ghost.surfaces.isEmpty())
        assertEquals(shell, ghost.shellDirectory)
    }

    @Test fun bundledSecondaryNameUsesDescriptorAndFallsBackWhenBlank() {
        val root = temporaryFolder.newFolder("names")
        File(root, "install.txt").writeText("type,ghost\ndirectory,nanidroid")
        val master = File(root, "ghost/master").apply { mkdirs() }
        val descriptor = File(master, "descript.txt")
        File(master, "ja").mkdirs()
        File(master, "ja/content.txt").writeText("")
        File(root, "shell/master").mkdirs()
        val repo = BundledGhostRepository({ error("Not installing") }, root.parentFile)
        descriptor.writeText("name,Named\nsakura.name,First\nsakura.name2,Second")
        assertEquals("Second", repo.load(root, "ja").sakuraName2)
        descriptor.writeText("name,Named\nsakura.name,First\nsakura.name2,  ")
        assertEquals("First", repo.load(root, "ja").sakuraName2)
    }

    @Test fun overlappingInstallsPublishOneCompleteLiveTree() {
        val root = temporaryFolder.newFolder("ghost")
        val archive = javaClass.getResourceAsStream("/nanidroid.zip")!!.use { it.readBytes() }
        val opened = CountDownLatch(1)
        val secondOpened = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        val repository = BundledGhostRepository({
            if (opens.incrementAndGet() == 1) {
                opened.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            } else {
                secondOpened.countDown()
            }
            ByteArrayInputStream(archive)
        }, root)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit<File> { repository.ensureInstalled() }
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            val second = workers.submit<File> {
                secondStarted.countDown()
                repository.ensureInstalled()
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse(secondOpened.await(250, TimeUnit.MILLISECONDS))
            release.countDown()
            val live = first.get(5, TimeUnit.SECONDS)
            assertEquals(live, second.get(5, TimeUnit.SECONDS))
            assertEquals(1, opens.get())
            assertTrue(File(live, "ghost/master/en/content.txt").isFile)
            assertEquals("nanidroid", repository.load(live, "en").directoryId)
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
