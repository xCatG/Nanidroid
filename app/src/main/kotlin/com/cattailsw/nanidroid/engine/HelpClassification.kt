package com.cattailsw.nanidroid.engine

sealed interface HelpClassification {
    data class Topic(val label: String) : HelpClassification
    data class Downloading(val percent: Int? = null, val startedNow: Boolean = false) : HelpClassification
    data object TimedOut : HelpClassification
    data object Busy : HelpClassification
    data object Unavailable : HelpClassification
    data object Failed : HelpClassification
}
