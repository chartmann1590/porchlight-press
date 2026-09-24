package com.charleshartman.porchlightpress.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.charleshartman.porchlightpress.data.local.Story
import com.charleshartman.porchlightpress.ui.components.HeroStory
import com.charleshartman.porchlightpress.ui.components.Masthead
import com.charleshartman.porchlightpress.ui.components.StoryCard

private fun previewStory(ai: Boolean = true) = Story(
    id = "preview-1",
    headline = "City council approves downtown revitalization project",
    dek = "The 5-2 vote funds streetscape work set to begin in spring.",
    body = "The city council voted 5-2 on Monday to approve the project.",
    category = "local",
    publishedAt = "2026-09-23T10:00:00Z",
    generatedAt = "2026-09-23T12:00:00Z",
    aiGenerated = ai,
    aiModel = if (ai) "qwen3-4b-q4_k_m" else null,
    version = 1,
)

@Composable
private fun PreviewContent() {
    Column(Modifier.padding(16.dp)) {
        Masthead(placeLabel = "Schenectady, NY")
        HeroStory(story = previewStory(true), sourceLabel = "Example Gazette", onClick = {})
        StoryCard(story = previewStory(true), sourceLabel = "Example Gazette", onClick = {})
        StoryCard(story = previewStory(false), sourceLabel = "Example Gazette", onClick = {})
    }
}

@Preview(name = "Classic Light", showBackground = true)
@Composable
fun PreviewClassicLight() {
    PorchlightTheme(themePref = "classic", layout = ClassicNewspaper, darkTheme = false) {
        PreviewContent()
    }
}

@Preview(name = "Classic Dark", showBackground = true)
@Composable
fun PreviewClassicDark() {
    PorchlightTheme(themePref = "classic", layout = ClassicNewspaper, darkTheme = true) {
        PreviewContent()
    }
}

@Preview(name = "Modern Light", showBackground = true)
@Composable
fun PreviewModernLight() {
    PorchlightTheme(themePref = "modern", layout = ModernNewspaper, darkTheme = false) {
        PreviewContent()
    }
}

@Preview(name = "Modern Dark", showBackground = true)
@Composable
fun PreviewModernDark() {
    PorchlightTheme(themePref = "modern", layout = ModernNewspaper, darkTheme = true) {
        PreviewContent()
    }
}

@Preview(name = "Compact", showBackground = true)
@Composable
fun PreviewCompact() {
    PorchlightTheme(themePref = "classic", layout = CompactLayout, darkTheme = false) {
        PreviewContent()
    }
}

@Preview(name = "Large Text", showBackground = true)
@Composable
fun PreviewLargeText() {
    PorchlightTheme(themePref = "classic", layout = LargeTextLayout, darkTheme = false) {
        PreviewContent()
    }
}
