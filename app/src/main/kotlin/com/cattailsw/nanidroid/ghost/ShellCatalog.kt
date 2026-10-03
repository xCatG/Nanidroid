package com.cattailsw.nanidroid.ghost

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

data class ShellCatalog(
    val numbered: Map<Int, File>,
    val definitions: SurfaceDefinitions,
    val shellDirectory: File,
)

suspend fun loadShellCatalog(shellDirectory: File, numbered: Map<Int, File>,
    dispatcher: CoroutineDispatcher = Dispatchers.IO): ShellCatalog =
    withContext(dispatcher) {
        val candidates = shellDirectory.listFiles().orEmpty().filter {
            it.name.equals("surfaces.txt", ignoreCase = true) && ShellFiles.isDirectChild(shellDirectory, it)
        }
        val file = candidates.firstOrNull { it.name == "surfaces.txt" } ?: candidates.firstOrNull()
        val definitions = if (file == null) SurfaceDefinitions.parse("") else try {
            SurfaceDefinitions.read(file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SurfaceDefinitions.parse("")
        }
        ShellCatalog(numbered, definitions, shellDirectory)
    }
