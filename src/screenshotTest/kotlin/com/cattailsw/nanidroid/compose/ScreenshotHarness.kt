package com.cattailsw.nanidroid.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Fixed, state-free framing for screenshot previews. */
@Composable
fun ScreenshotHarness(content: @Composable () -> Unit) {
    MaterialTheme {
        Surface(
            color = ScreenshotBackground,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content()
                ScreenshotSentinel(
                    color = TopLeftSentinel,
                    modifier = Modifier.align(Alignment.TopStart),
                )
                ScreenshotSentinel(
                    color = BottomRightSentinel,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
    }
}

@Composable
private fun ScreenshotSentinel(color: Color, modifier: Modifier) {
    Box(
        modifier = modifier
            .size(16.dp)
            .background(color)
    )
}

private val ScreenshotBackground = Color(0xFFF5F1F7)
private val TopLeftSentinel = Color(0xFF0057B8)
private val BottomRightSentinel = Color(0xFFFFA000)
