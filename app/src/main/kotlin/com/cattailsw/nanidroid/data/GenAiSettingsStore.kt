package com.cattailsw.nanidroid.data

interface GenAiSettingsStore {
    fun read(): Boolean
    fun write(enabled: Boolean)
}
