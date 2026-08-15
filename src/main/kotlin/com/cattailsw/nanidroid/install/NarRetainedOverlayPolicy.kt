package com.cattailsw.nanidroid.install

import java.util.Collections

/**
 * Pure diagnostic merge of a staged baseline and a NAR plan.
 *
 * This recipe is not an install authority and performs no I/O. In particular,
 * the diagnostic [NarInstallPlan] file fields are never consulted. Later code
 * must retain the verified archive owner independently.
 */
internal object NarRetainedOverlayPolicy {
    private const val MAX_ENTRIES = 10_000
    private const val MAX_FILE_BYTES = 128L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L

    fun build(
        plan: NarInstallPlan?,
        baseline: NarGhostTreePolicy.Manifest?,
        inventory: List<NarStagedTreeInventory.Entry>?,
    ): Result = try {
        inspect(plan, baseline, inventory)
    } catch (rejected: Rejected) {
        Result.failure(rejected.error, rejected.message)
    } catch (_: RuntimeException) {
        Result.failure(Error.MALFORMED_PLAN, "input")
    }

    private fun inspect(
        plan: NarInstallPlan?,
        baseline: NarGhostTreePolicy.Manifest?,
        inventory: List<NarStagedTreeInventory.Entry>?,
    ): Result {
        val checkedPlan = plan ?: reject(Error.MALFORMED_PLAN, "plan")
        val checkedBaseline = baseline ?: reject(Error.MALFORMED_BASELINE, "baseline")
        val checkedInventory = inventory ?: reject(Error.MALFORMED_BASELINE, "baseline")
        val descriptor = checkedPlan.descriptor
        requireThat(descriptor.getType() == "ghost", Error.UNSUPPORTED_TYPE, "type")
        val metadata = descriptor.getMetadata()
        requireThat(metadata["type"] == "ghost", Error.MALFORMED_PLAN, "descriptor type")
        for ((key, value) in metadata) {
            requireThat(
                value != "1" || (key != "refresh" && !key.endsWith(".refresh")),
                Error.UNSUPPORTED_REFRESH,
                "refresh",
            )
        }
        val target = descriptor.getTargetId()
        val normalized = NarRelativePathPolicy.normalize(target)
        requireThat(
            normalized.isSuccess() && target == normalized.normalized && target.indexOf('/') == -1,
            Error.MALFORMED_PLAN,
            "target",
        )
        requireThat(target == checkedBaseline.targetId, Error.TARGET_MISMATCH, "target")
        val retained = prepareBaseline(checkedBaseline, checkedInventory)
        val archive = prepareArchive(checkedPlan)
        val merged = HashMap(retained)
        val archiveEntries = ArrayList(archive.values)
        archiveEntries.sortWith(WORK_ORDER)
        for (incoming in archiveEntries) {
            val prior = retained[incoming.key]
            if (incoming.type == NarGhostTreePolicy.Type.FILE && prior != null &&
                prior.type == NarGhostTreePolicy.Type.DIRECTORY && hasDescendant(retained, incoming.key)
            ) reject(Error.INCOMPATIBLE_GHOST_UPDATE, incoming.logicalName)
            if (incoming.type == NarGhostTreePolicy.Type.DIRECTORY && prior != null &&
                prior.type == NarGhostTreePolicy.Type.DIRECTORY &&
                prior.logicalName != incoming.logicalName &&
                hasSurvivingDescendant(retained, archive, incoming.key)
            ) reject(Error.INCOMPATIBLE_GHOST_UPDATE, incoming.logicalName)
            merged[incoming.key] = incoming
        }
        requireThat(merged.size <= MAX_ENTRIES, Error.ENTRY_COUNT_LIMIT, "entry count")
        val ordered = ArrayList(merged.values)
        ordered.sortWith(WORK_ORDER)
        val output = ArrayList<Entry>(ordered.size)
        var fileOrdinal = 0
        var total = 0L
        var unknownTotal = false
        for (item in ordered) {
            if (item.type == NarGhostTreePolicy.Type.FILE) {
                requireThat(item.size >= -1 && item.size <= MAX_FILE_BYTES, Error.FILE_SIZE_LIMIT, item.logicalName)
                if (item.size < 0) unknownTotal = true else {
                    requireThat(total <= MAX_TOTAL_BYTES - item.size, Error.TOTAL_SIZE_LIMIT, item.logicalName)
                    total += item.size
                }
                output.add(item.finish(fileOrdinal++))
            } else output.add(item.finish(-1))
        }
        return Result.success(Recipe(checkedBaseline, output, fileOrdinal, if (unknownTotal) -1 else total))
    }

    private fun prepareBaseline(
        manifest: NarGhostTreePolicy.Manifest,
        supplied: List<NarStagedTreeInventory.Entry>,
    ): Map<String, Work> = try {
        prepareBaselineFacts(manifest, supplied)
    } catch (rejected: Rejected) {
        throw rejected
    } catch (_: RuntimeException) {
        reject(Error.MALFORMED_BASELINE, "inventory")
    }

    private fun prepareBaselineFacts(
        manifest: NarGhostTreePolicy.Manifest,
        supplied: List<NarStagedTreeInventory.Entry>,
    ): Map<String, Work> {
        val facts = manifest.entries
        val inventory = HashMap<String, NarStagedTreeInventory.Entry>()
        val policyFacts = ArrayList<NarGhostTreePolicy.InputEntry>()
        val seenOrdinals = BooleanArray(MAX_ENTRIES)
        var fileCount = 0
        var count = 0
        val iterator = supplied.iterator()
        while (iterator.hasNext()) {
            requireThat(count++ < MAX_ENTRIES, Error.MALFORMED_BASELINE, "inventory count")
            val entry = iterator.next()
            val entryPath = entry.path()
            val normalized = NarRelativePathPolicy.normalize(entryPath)
            requireThat(normalized.isSuccess() && normalized.normalized == entryPath, Error.MALFORMED_BASELINE, "inventory name")
            val path = entryPath!!
            if (entry.type() == NarGhostTreePolicy.Type.DIRECTORY) {
                requireThat(entry.size() == 0L && entry.blobOrdinal() == -1 && entry.sha256() == null, Error.MALFORMED_BASELINE, path)
                policyFacts.add(NarGhostTreePolicy.InputEntry.directory(path))
            } else {
                val sourceOrdinal = entry.blobOrdinal()
                val digest = entry.sha256()
                requireThat(entry.type() == NarGhostTreePolicy.Type.FILE && entry.size() >= 0 &&
                    sourceOrdinal >= 0 && sourceOrdinal < MAX_ENTRIES && !seenOrdinals[sourceOrdinal] &&
                    digest != null && digest.size == 32, Error.MALFORMED_BASELINE, path)
                seenOrdinals[sourceOrdinal] = true
                fileCount++
                policyFacts.add(NarGhostTreePolicy.InputEntry.file(path, entry.size(), digest))
            }
            requireThat(inventory.put(normalized.key!!, entry) == null, Error.MALFORMED_BASELINE, path)
        }
        for (index in 0 until fileCount) requireThat(seenOrdinals[index], Error.MALFORMED_BASELINE, "inventory ordinal")
        val rebuilt = NarGhostTreePolicy.build(manifest.targetId, manifest.storageRootIdentity, manifest.state, policyFacts)
        requireThat(rebuilt.isSuccess() && arraysEqual(rebuilt.manifest!!.fingerprint, manifest.fingerprint), Error.MALFORMED_BASELINE, "manifest fingerprint")
        requireThat(facts.size == inventory.size, Error.MALFORMED_BASELINE, "inventory size")
        requireThat(manifest.state != NarGhostTreePolicy.State.ABSENT || facts.isEmpty(), Error.MALFORMED_BASELINE, "absent inventory")
        val result = HashMap<String, Work>()
        for (fact in facts) {
            val normalized = NarRelativePathPolicy.normalize(fact.path)
            requireThat(normalized.isSuccess() && normalized.normalized == fact.path, Error.MALFORMED_BASELINE, "manifest name")
            val key = normalized.key!!
            val sourceValue = inventory[key] ?: reject(Error.MALFORMED_BASELINE, fact.path)
            requireThat(fact.path == sourceValue.path() && fact.type == sourceValue.type() && fact.length == sourceValue.size(), Error.MALFORMED_BASELINE, fact.path)
            val expected = fact.contentDigest
            val actual = sourceValue.sha256()
            requireThat(arraysEqual(expected, actual), Error.MALFORMED_BASELINE, fact.path)
            val sourceOrdinal = if (fact.type == NarGhostTreePolicy.Type.FILE) sourceValue.blobOrdinal() else -1
            val kind = if (fact.type == NarGhostTreePolicy.Type.FILE) Source.RETAINED else Source.DIRECTORY
            requireThat(result.put(key, Work(fact.path, key, fact.type, kind, sourceOrdinal, fact.length, actual)) == null, Error.MALFORMED_BASELINE, fact.path)
        }
        requireThat(result.size == inventory.size, Error.MALFORMED_BASELINE, "manifest entries")
        return result
    }

    private fun prepareArchive(plan: NarInstallPlan): Map<String, Work> {
        val result = HashMap<String, Work>()
        var ordinal = 0
        val iterator = plan.entries.iterator()
        while (iterator.hasNext()) {
            requireThat(ordinal < MAX_ENTRIES, Error.MALFORMED_PLAN, "entry count")
            val entry = iterator.next()
            requireThat(entry.ordinal == ordinal, Error.MALFORMED_PLAN, "ordinal")
            ordinal++
            if (!entry.isInstallEntry) {
                requireThat(entry.isDirectory, Error.MALFORMED_PLAN, "wrapper")
                continue
            }
            val path = NarRelativePathPolicy.normalize(entry.relativePath)
            requireThat(path.isSuccess() && path.normalized == entry.relativePath, Error.MALFORMED_PLAN, "entry name")
            val item = if (entry.isDirectory) {
                requireThat(entry.declaredSize == 0L, Error.MALFORMED_PLAN, path.normalized!!)
                Work.directory(path.normalized)
            } else {
                requireThat(entry.declaredSize >= -1, Error.MALFORMED_PLAN, path.normalized!!)
                Work.archiveFile(path.normalized, entry.ordinal, entry.declaredSize)
            }
            addArchive(result, item)
            var parent = item.logicalName
            while (parent.lastIndexOf('/') >= 0) {
                parent = parent.substring(0, parent.lastIndexOf('/'))
                addArchive(result, Work.directory(parent))
            }
        }
        return result
    }

    private fun addArchive(entries: MutableMap<String, Work>, item: Work) {
        val prior = entries[item.key]
        if (prior == null) {
            entries[item.key] = item
            requireThat(entries.size <= MAX_ENTRIES, Error.ENTRY_COUNT_LIMIT, "archive expansion")
        } else requireThat(prior.type == NarGhostTreePolicy.Type.DIRECTORY && item.type == NarGhostTreePolicy.Type.DIRECTORY && prior.logicalName == item.logicalName, Error.MALFORMED_PLAN, item.logicalName)
        var parent = item.logicalName
        while (parent.lastIndexOf('/') >= 0) {
            parent = parent.substring(0, parent.lastIndexOf('/'))
            val owner = entries[NarRelativePathPolicy.collisionKey(parent)]
            requireThat(owner == null || owner.type == NarGhostTreePolicy.Type.DIRECTORY, Error.MALFORMED_PLAN, parent)
        }
    }

    private fun hasDescendant(entries: Map<String, Work>, owner: String): Boolean = entries.keys.any { it.startsWith("$owner/") }
    private fun hasSurvivingDescendant(retained: Map<String, Work>, archive: Map<String, Work>, owner: String): Boolean = retained.keys.any { it.startsWith("$owner/") && !archive.containsKey(it) }

    private fun arraysEqual(left: ByteArray?, right: ByteArray?): Boolean {
        if (left == null || right == null) return left === right
        if (left.size != right.size) return false
        var difference = 0
        for (index in left.indices) difference = difference or (left[index].toInt() xor right[index].toInt())
        return difference == 0
    }

    private fun requireThat(condition: Boolean, error: Error, detail: String) { if (!condition) reject(error, detail) }
    private fun reject(error: Error, detail: String): Nothing = throw Rejected(error, detail)

    enum class Error { MALFORMED_PLAN, MALFORMED_BASELINE, UNSUPPORTED_TYPE, UNSUPPORTED_REFRESH, TARGET_MISMATCH, INCOMPATIBLE_GHOST_UPDATE, ENTRY_COUNT_LIMIT, FILE_SIZE_LIMIT, TOTAL_SIZE_LIMIT }
    enum class Source { RETAINED, ARCHIVE, DIRECTORY }

    class Entry internal constructor(private val logicalNameValue: String, private val typeValue: NarGhostTreePolicy.Type, private val sourceValue: Source, private val finalFileOrdinalValue: Int, private val sourceOrdinalValue: Int, private val sizeValue: Long, sha256: ByteArray?) {
        private val sha256Value = sha256?.clone()
        fun logicalName(): String = logicalNameValue
        fun type(): NarGhostTreePolicy.Type = typeValue
        fun source(): Source = sourceValue
        fun finalFileOrdinal(): Int = finalFileOrdinalValue
        fun sourceOrdinal(): Int = sourceOrdinalValue
        fun size(): Long = sizeValue
        fun sha256(): ByteArray? = sha256Value?.clone()
    }

    class Recipe internal constructor(private val baselineValue: NarGhostTreePolicy.Manifest, entries: List<Entry>, private val fileCountValue: Int, private val totalSizeValue: Long) {
        private val baselineFingerprintValue = baselineValue.fingerprint
        private val entriesValue = Collections.unmodifiableList(ArrayList(entries))
        fun baselineManifest(): NarGhostTreePolicy.Manifest = baselineValue
        fun baselineFingerprint(): ByteArray = baselineFingerprintValue.clone()
        fun entries(): List<Entry> = entriesValue
        fun fileCount(): Int = fileCountValue
        fun hasKnownTotalSize(): Boolean = totalSizeValue >= 0
        fun totalSize(): Long = totalSizeValue
    }

    class Result private constructor(private val recipeValue: Recipe?, private val errorValue: Error?, private val detailValue: String) {
        fun isSuccess(): Boolean = recipeValue != null
        fun recipe(): Recipe? = recipeValue
        fun error(): Error? = errorValue
        fun detail(): String = detailValue
        companion object {
            fun success(recipe: Recipe): Result = Result(recipe, null, "")
            fun failure(error: Error, detail: String?): Result = Result(null, error, detail ?: "input")
        }
    }

    private class Work(val logicalName: String, val key: String, val type: NarGhostTreePolicy.Type, val source: Source, val sourceOrdinal: Int, val size: Long, sha256: ByteArray?) {
        private val sha256Value = sha256?.clone()
        fun finish(ordinal: Int): Entry = Entry(logicalName, type, source, ordinal, sourceOrdinal, size, sha256Value)
        companion object {
            fun directory(name: String): Work = Work(name, NarRelativePathPolicy.collisionKey(name), NarGhostTreePolicy.Type.DIRECTORY, Source.DIRECTORY, -1, 0, null)
            fun archiveFile(name: String, ordinal: Int, size: Long): Work = Work(name, NarRelativePathPolicy.collisionKey(name), NarGhostTreePolicy.Type.FILE, Source.ARCHIVE, ordinal, size, null)
        }
    }

    private val WORK_ORDER = Comparator<Work> { left, right -> left.logicalName.compareTo(right.logicalName) }
    private class Rejected(val error: Error, detail: String) : Exception(detail)
}
