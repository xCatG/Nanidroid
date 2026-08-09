package com.cattailsw.nanidroid.compose

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme

/** The app's Material color scheme, selected from the current system night mode. */
@Composable
internal fun NanidroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = nanidroidColorScheme(isSystemInDarkTheme()), content = content)
}

internal fun nanidroidColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) darkColorScheme() else lightColorScheme()
