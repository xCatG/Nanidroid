package com.cattailsw.nanidroid.ghost

import java.io.File

object NativeProfileDirectory {
    fun prepare(master: File): File = prepare(master) { it.mkdirs() }

    internal fun prepare(master: File, createDirectory: (File) -> Boolean): File {
        check(master.isDirectory) { "Native ghost master is not a directory: $master" }
        val profile = File(master, "profile")
        val masterPath = master.canonicalFile.toPath()

        fun checkContained() {
            val profilePath = profile.canonicalFile.toPath()
            check(profilePath != masterPath && profilePath.startsWith(masterPath)) {
                "Native profile escapes master: $profile"
            }
        }

        checkContained()
        if (!profile.isDirectory) {
            check(!profile.exists()) { "Native profile is not a directory: $profile" }
            createDirectory(profile)
            check(profile.isDirectory) { "Cannot create native profile directory: $profile" }
        }
        checkContained()
        check(profile.canWrite() && profile.canExecute()) {
            "Native profile is not writable: $profile"
        }
        return profile
    }
}
