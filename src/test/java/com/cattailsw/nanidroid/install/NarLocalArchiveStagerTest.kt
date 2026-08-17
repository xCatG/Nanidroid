package com.cattailsw.nanidroid.install

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class NarLocalArchiveStagerTest {
    @Rule
    @JvmField
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `failed temporary stage leaves no private file`() {
        val directory = temporaryFolder.newFolder("failed-temporary-archive")

        val result = NarLocalArchiveStager.stage(directory) { null }

        assertTrue(result is NarLocalArchiveStager.Result.Failed)
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun `discarded temporary stage leaves no private file`() {
        val directory = temporaryFolder.newFolder("discarded-temporary-archive")
        val staged = NarLocalArchiveStager.stage(directory) {
            ByteArrayInputStream(byteArrayOf(1, 2, 3))
        } as NarLocalArchiveStager.Result.Staged

        NarLocalArchiveStager.discard(staged.location)

        assertTrue(directory.listFiles().isNullOrEmpty())
    }
}
