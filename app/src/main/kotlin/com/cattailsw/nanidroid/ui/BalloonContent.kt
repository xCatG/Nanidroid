package com.cattailsw.nanidroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.runtime.DialogueToken
import com.cattailsw.nanidroid.runtime.InteractionToken
import com.cattailsw.nanidroid.runtime.PresentedChoice

@Composable
fun BalloonContent(
    text: String,
    choices: List<PresentedChoice>,
    dialogueToken: DialogueToken?,
    onChoose: (InteractionToken) -> Unit,
    onOpenLink: (DialogueToken, String) -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(text, choices) { scroll.scrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(8.dp)) {
        var position = 0
        choices.forEach { choice ->
            val end = choice.textOffset.coerceIn(position, text.length)
            if (end > position) LinkText(text.substring(position, end), dialogueToken, onOpenLink)
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clipToBounds()
                .clickable { onChoose(choice.token) }
                .testTag("choice-${choice.token.itemKey}")) {
                Text(choice.label, modifier = Modifier.fillMaxWidth().padding(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge)
            }
            position = end
        }
        if (position < text.length) LinkText(text.substring(position), dialogueToken, onOpenLink)
    }
}

@Composable
private fun LinkText(text: String, dialogueToken: DialogueToken?,
    onOpenLink: (DialogueToken, String) -> Unit) {
    val openLink by rememberUpdatedState(onOpenLink)
    val annotated = remember(text, dialogueToken) { buildAnnotatedString {
        val links = BalloonLinks.find(text)
        var position = 0
        links.forEach { link ->
            append(text.substring(position, link.start))
            val token = dialogueToken
            if (token != null) {
                withLink(LinkAnnotation.Clickable(
                    tag = link.url,
                    styles = TextLinkStyles(SpanStyle(color = Color(0xFF1457A3))),
                    linkInteractionListener = { openLink(token, link.url) },
                )) { append(text.substring(link.start, link.end)) }
            } else append(text.substring(link.start, link.end))
            position = link.end
        }
        append(text.substring(position))
    } }
    Text(annotated, style = MaterialTheme.typography.bodyLarge, color = Color.Black)
}
