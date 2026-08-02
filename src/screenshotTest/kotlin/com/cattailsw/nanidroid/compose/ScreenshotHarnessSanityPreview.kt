package com.cattailsw.nanidroid.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun ScreenshotHarnessSanityPreview() {
    ScreenshotHarness {
        Box(modifier = Modifier.padding(24.dp)) {
            Text("Nanidroid screenshot harness")
        }
    }
}
