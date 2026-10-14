package com.cattailsw.nanidroid.ghost

import com.cattailsw.nanidroid.engine.BuiltInShiori
import com.cattailsw.nanidroid.engine.ShioriEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
        assertTrue(BuiltInShiori(japanese.content).request(ShioriEvent("OnBoot")).value!!.contains("おかえりなさい"))
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
        val ghost = BundledGhostRepository({ error("Not installing") }, requireNotNull(root.parentFile))
            .load(root, "ja")
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
        val repo = BundledGhostRepository({ error("Not installing") }, requireNotNull(root.parentFile))
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
            assertEquals(2, opens.get())
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

    private fun realRepository(root: File) = BundledGhostRepository(
        { javaClass.getResourceAsStream("/nanidroid.zip")!! }, root
    )
    private fun snapshot(root: File) = root.walkTopDown().associate {
        it.relativeTo(root).invariantSeparatorsPath to if (it.isFile) sha256(it.readBytes()) else "directory"
    }
    private fun previous(locale: String) =
        javaClass.getResourceAsStream("/previous-bundled-$locale-content.txt")!!.use {
            it.readBytes()
        }

    @Test fun productionAndTestArchiveMatchWithOriginalPngBytesAndPinnedPreviousHashes() {
        val bytes = javaClass.getResourceAsStream("/nanidroid.zip")!!.use { it.readBytes() }
        assertArrayEquals(File("src/main/assets/nanidroid.zip").readBytes(), bytes)
        assertEquals(BundledGhostRepository.BUNDLED_SHA256, sha256(bytes))
        assertEquals("b4621c0532d0027a539aecfadd85448b2167d9671f0f9c78239735b302b67f0c", sha256(previous("en")))
        assertEquals("3b1fbeee59cb47e1e015fe41535218ec9a778444be2dc98d145b91df85b36002", sha256(previous("ja")))
        val root = realRepository(temporaryFolder.newFolder()).ensureInstalled()
        assertEquals(
            "5c1c95e3f0c7de16d279c92d8ea5de01e4a80771bb270226588c8d1bd4316c9f",
            sha256(File(root, "shell/master/surface0000.png").readBytes())
        )
        assertEquals(
            "f2358bab6b34ac088ff6db3f73e39b9c7143ad627f7015a6efd49aaae7d56d53",
            sha256(File(root, "shell/master/surface0010.png").readBytes())
        )
    }

    @Test fun startupLocaleUsesFullScriptAndRegionWithHansPriority() {
        val repo = realRepository(temporaryFolder.newFolder())
        val root = repo.ensureInstalled()
        val japanese = repo.load(root, "ja").content
        val chinese = repo.load(root, Locale.forLanguageTag("zh-TW")).content
        val english = repo.load(root, "en").content
        assertTrue(chinese.contains("歡迎使用"))
        for (tag in listOf("zh-Hant", "zh-HK", "zh-MO", "zh-Hant-HK", "zh-Hant-MO")) {
            assertEquals(chinese, repo.load(root, Locale.forLanguageTag(tag)).content)
        }
        for (tag in listOf("zh-Hans", "zh-Hans-TW", "zh-CN", "zh", "fr", "ja")) {
            assertEquals(japanese, repo.load(root, Locale.forLanguageTag(tag)).content)
        }
        assertEquals(english, repo.load(root, Locale.forLanguageTag("en-GB")).content)
        File(root, "ghost/master/zh-TW/content.txt").delete()
        assertEquals(japanese, repo.load(root, Locale.forLanguageTag("zh-Hant")).content)
    }

    @Test fun selectedAndFallbackContentReadsAreBoundedAt64KiB() {
        val repo = realRepository(temporaryFolder.newFolder())
        val root = repo.ensureInstalled()
        val en = File(root, "ghost/master/en/content.txt")
        en.writeBytes(ByteArray(64 * 1024) { 65 })
        assertEquals(64 * 1024, repo.load(root, "en").content.length)
        en.appendBytes(byteArrayOf(65))
        assertThrows { repo.load(root, "en") }
        en.delete()
        File(root, "ghost/master/ja/content.txt").writeBytes(ByteArray(64 * 1024 + 1))
        assertThrows { repo.load(root, "en") }
        assertThrows { repo.load(root, "fr") }
    }

    @Test fun previousDictionariesRefreshIdempotentlyAndOnlyManagedBytesChange() {
        val repo = realRepository(temporaryFolder.newFolder())
        val root = repo.ensureInstalled()
        val expectedEn = File(root, "ghost/master/en/content.txt").readBytes()
        val expectedJa = File(root, "ghost/master/ja/content.txt").readBytes()
        File(root, "ghost/master/en/content.txt").writeBytes(previous("en"))
        File(root, "ghost/master/ja/content.txt").writeBytes(previous("ja"))
        val zh = File(root, "ghost/master/zh-TW/content.txt").apply { delete() }
        for (path in listOf(
            "ghost/master/activation", "ghost/master/state.dat", "ghost/master/custom.dll",
            "ghost/master/en/.nanidroid-dictionary-user.tmp", "unknown.txt"
        )) {
            File(root, path).writeText("preserve:$path")
        }
        val before = snapshot(root).filterKeys { !it.endsWith("/content.txt") }
        assertEquals(root, repo.ensureInstalled())
        assertArrayEquals(expectedEn, File(root, "ghost/master/en/content.txt").readBytes())
        assertArrayEquals(expectedJa, File(root, "ghost/master/ja/content.txt").readBytes())
        assertTrue(zh.isFile)
        assertEquals(before, snapshot(root).filterKeys { !it.endsWith("/content.txt") })
        val updated = snapshot(root)
        val timestamp = File(root, "ghost/master/en/content.txt").lastModified()
        repo.ensureInstalled()
        assertEquals(updated, snapshot(root))
        assertEquals(timestamp, File(root, "ghost/master/en/content.txt").lastModified())
    }

    @Test fun customizedAllLocalesAndDescriptorDisplayNamesRemainAuthoritative() = runTest {
        val repo = realRepository(temporaryFolder.newFolder())
        val root = repo.ensureInstalled()
        for (locale in listOf("en", "ja", "zh-TW")) {
            File(root, "ghost/master/$locale/content.txt")
                .writeText("OnMouseClick@r3=0,\\1My tools\\e")
        }
        val descriptor = File(root, "ghost/master/descript.txt")
        descriptor.writeText("id,nanidroid\nname,Custom name\nshiori,Nanidroid")
        val before = snapshot(root)
        repo.ensureInstalled()
        assertEquals(before, snapshot(root))
        val engine = BuiltInShiori.bundled(repo.load(root, "en").content, true)
        assertEquals("\\1My tools\\e", engine.request(ShioriEvent("OnMouseClick", listOf("0", "0", "0", "0"))).value)
        assertEquals(204, engine.request(ShioriEvent("OnFirstBoot")).status)
        assertEquals(204, engine.request(ShioriEvent("OnClose")).status)
    }

    @Test fun wrongInstallIdentityAndNativeSameNamedTargetsGetNoWrites() {
        for ((install, descriptor) in listOf(
            "type,shell\ndirectory,nanidroid" to "id,nanidroid\nshiori,Nanidroid",
            "type,ghost\ndirectory,unrelated" to "id,nanidroid\nshiori,Nanidroid",
            "type,ghost\ndirectory,nanidroid" to "id,other\nshiori,Nanidroid",
            "type,ghost\ndirectory,nanidroid" to "id,nanidroid\nshiori,satori.dll"
        )) {
            val root = temporaryFolder.newFolder()
            val live = File(root, "nanidroid").apply { mkdirs() }
            File(live, "install.txt").writeText(install)
            val master = File(live, "ghost/master").apply { mkdirs() }
            File(master, "descript.txt").writeText(descriptor)
            File(master, "keep.dat").writeText("native/custom state")
            val before = snapshot(live)
            BundledGhostRepository({ error("Unrelated target must not open ZIP") }, root).ensureInstalled()
            assertEquals(before, snapshot(live))
        }
    }

    @Test fun missingAllDictionariesAreAddedWithoutAnInstallMarker() {
        val repo = realRepository(temporaryFolder.newFolder())
        val root = repo.ensureInstalled()
        for (locale in listOf("en", "ja", "zh-TW")) {
            File(root, "ghost/master/$locale/content.txt").delete()
            File(root, "ghost/master/$locale").delete()
        }
        repo.ensureInstalled()
        for (locale in listOf("en", "ja", "zh-TW")) assertTrue(File(root, "ghost/master/$locale/content.txt").isFile)
        assertFalse(File(root, "version.txt").exists())
    }

    @Test fun refreshIoFailureKeepsReadableInstalledDictionaryAndCanRetry() {
        val directory = temporaryFolder.newFolder()
        val repo = realRepository(directory)
        val root = repo.ensureInstalled()
        val en = File(root, "ghost/master/en/content.txt")
        val current = en.readBytes()
        en.writeBytes(previous("en"))
        val before = snapshot(root)
        val unavailable = BundledGhostRepository({ throw java.io.IOException("asset read failed") }, directory)
        assertEquals(root, unavailable.ensureInstalled())
        assertEquals(before, snapshot(root))
        assertEquals(previous("en").toString(Charsets.UTF_8), unavailable.load(root, "en").content)
        repo.ensureInstalled()
        assertArrayEquals(current, en.readBytes())
    }

    @Test fun wrongArchiveHashPropagatesWithoutChangingDictionaries() {
        val directory = temporaryFolder.newFolder()
        val root = realRepository(directory).ensureInstalled()
        File(root, "ghost/master/en/content.txt").writeBytes(previous("en"))
        val before = snapshot(root)
        assertThrows { BundledGhostRepository({ ByteArrayInputStream(byteArrayOf(1)) }, directory).ensureInstalled() }
        assertEquals(before, snapshot(root))
    }

    @Test fun interruptedMixedRefreshRetriesAndIgnoresOrphanTemporaryFiles() {
        val repo = realRepository(temporaryFolder.newFolder())
        val root = repo.ensureInstalled()
        val en = File(root, "ghost/master/en/content.txt").readBytes()
        val ja = File(root, "ghost/master/ja/content.txt").readBytes()
        // State after en publication and process interruption before ja/zh publication.
        File(root, "ghost/master/ja/content.txt").writeBytes(previous("ja"))
        File(root, "ghost/master/zh-TW/content.txt").delete()
        val orphan = File(root, "ghost/master/ja/.nanidroid-dictionary-orphan.tmp").apply { writeText("partial") }
        repo.ensureInstalled()
        assertArrayEquals(en, File(root, "ghost/master/en/content.txt").readBytes())
        assertArrayEquals(ja, File(root, "ghost/master/ja/content.txt").readBytes())
        assertEquals("partial", orphan.readText())
        val before = snapshot(root); repo.ensureInstalled(); assertEquals(before, snapshot(root))
    }

    @Test fun managedFileAndEscapedParentLinksAreRejected() {
        val directory = temporaryFolder.newFolder()
        val root = realRepository(directory).ensureInstalled()
        val external = temporaryFolder.newFile().apply { writeText("external") }
        val en = File(root, "ghost/master/en/content.txt")
        en.delete()
        try {
            Files.createSymbolicLink(en.toPath(), external.toPath())
        } catch (unavailable: Exception) { assumeNoException("Host cannot create symlinks", unavailable) }
        assertThrows { realRepository(directory).ensureInstalled() }
        assertEquals("external", external.readText())
        Files.delete(en.toPath())
        File(root, "ghost/master/en").delete()
        Files.createSymbolicLink(File(root, "ghost/master/en").toPath(), requireNotNull(external.parentFile).toPath())
        assertThrows { realRepository(directory).ensureInstalled() }
    }

    @Test fun regularManagedDirectoryCollisionFailsInsteadOfApparentSuccess() {
        val repo = realRepository(temporaryFolder.newFolder())
        val root = repo.ensureInstalled()
        val path = File(root, "ghost/master/en/content.txt").apply { delete(); mkdir() }
        assertThrows { repo.ensureInstalled() }
        assertTrue(path.isDirectory)
    }
    private fun assertThrows(action: () -> Unit) {
        try {
            action(); throw AssertionError("Expected invalid archive/content/path to fail")
        } catch (_: IllegalArgumentException) { }
    }
}
