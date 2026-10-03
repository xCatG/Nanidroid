package com.cattailsw.nanidroid.install

object ArchivePath {
    fun normalize(raw: String): String {
        require(raw.isNotEmpty() && !raw.startsWith('/') && !raw.startsWith('\\')) { "Unsafe archive path" }
        val path = raw.replace('\\', '/')
        require(path.length <= ARCHIVE_PATH_LIMIT && !path.startsWith('/')) { "Archive path limit or absolute path" }
        val components = path.split('/')
        require(components.size <= ARCHIVE_COMPONENT_LIMIT && components.all { component ->
            component.isNotEmpty() && component != "." && component != ".." &&
                ':' !in component && component.none { Character.isISOControl(it) }
        }) { "Unsafe archive path" }
        return path
    }
}
