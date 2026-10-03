package com.cattailsw.nanidroid.data

interface LastGhostStore {
    fun read(): String?
    fun write(directoryId: String)
}
