package com.cattailsw.nanidroid.ui

import android.content.res.AssetManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.nio.charset.StandardCharsets

data class Notice(val title: String, val text: String)

/** Files are copied from the exact sources listed in docs/testing/notice-inventory.md. */
object NoticeCatalog {
    val entries = listOf(
        "Satori" to "satori.txt",
        "Kawari 8" to "kawari.txt",
        "Kawari Mersenne Twister" to "kawari-mt19937.txt",
        "YAYA" to "yaya.txt",
        "YAYA Mersenne Twister" to "yaya-mt19937.txt",
        "Bundled Nanidroid ghost and artwork" to "bundled-ghost.txt",
        "Apache Commons Compress 1.28.0 license" to "commons-compress-LICENSE.txt",
        "Apache Commons Compress 1.28.0 notice" to "commons-compress-NOTICE.txt",
        "Apache Commons IO 2.20.0 license" to "commons-io-LICENSE.txt",
        "Apache Commons IO 2.20.0 notice" to "commons-io-NOTICE.txt",
        "Apache Commons Lang 3.18.0 license" to "commons-lang3-LICENSE.txt",
        "Apache Commons Lang 3.18.0 notice" to "commons-lang3-NOTICE.txt",
        "Apache Commons Codec 1.19.0 license" to "commons-codec-LICENSE.txt",
        "Apache Commons Codec 1.19.0 notice" to "commons-codec-NOTICE.txt",
        "AndroidX / Compose and Apache-licensed Kotlin dependencies" to "androidx-apache.txt",
        "Kotlin standard library copyright" to "kotlin-stdlib-copyright.txt",
        "Kotlin standard library GWT and Guava terms" to "kotlin-stdlib-gwt.txt",
        "Kotlin standard library ThreeTenBP terms" to "kotlin-stdlib-threetenbp.txt",
        "Kotlin standard library Boost terms" to "kotlin-stdlib-boost.txt",
    )

    fun load(assets: AssetManager): List<Notice> = entries.map { (title, file) ->
        Notice(title, assets.open("notices/$file").bufferedReader(StandardCharsets.UTF_8).use { it.readText() })
    }
}

@Composable
fun AboutDialog(versionName: String, notices: List<Notice>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            Modifier.fillMaxWidth().fillMaxHeight(0.94f).testTag("about-dialog"),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Nanidroid", style = MaterialTheme.typography.titleLarge)
                Text("Version $versionName", style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("about-version"))
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("about-notices")) {
                    if (notices.isEmpty()) item { Text("Loading notices…") }
                    itemsIndexed(notices) { index, notice ->
                        Text(notice.title, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 16.dp).testTag("notice-title-$index"))
                        Text(notice.text, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("notice-text-$index"))
                    }
                    item {
                        Text("End of notices", modifier = Modifier.padding(top = 16.dp)
                            .testTag("notice-end"))
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).testTag("about-close")) {
                    Text("Close")
                }
            }
        }
    }
}
