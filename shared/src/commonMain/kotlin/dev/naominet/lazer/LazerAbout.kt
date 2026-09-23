package dev.naominet.lazer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What Lazer is, what it stands on, and what it is allowed to be.
 *
 * The sheet opens the way the player looks: one line in focus, the next still soft. Everything
 * below that is deliberately quiet, because the page has exactly one thing to press.
 */
@Composable
fun LazerAboutSection(
    modifier: Modifier = Modifier,
    heading: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val links = LocalUriHandler.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (heading != null) {
            Text(
                heading,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                tr("about.tagline"),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 22.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.onBackground,
            )
            // The line after the focused one: same type, held back the way the sheet holds back
            // every line the song has not reached yet.
            Text(
                tr("about.version", LazerRelease.versionName, LazerRelease.versionCode),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.graphicsLayer {
                    alpha = 0.72f
                    scaleX = 0.97f
                    scaleY = 0.97f
                    transformOrigin = TransformOrigin.Center
                },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                tr("about.repository"),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.surfaceContainerHigh)
                    .clickable { links.openUri(LazerRelease.repositoryUrl) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    LazerRelease.repositoryLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.primary,
                )
                Text(
                    tr("about.repository.hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        AboutNote(tr("about.thanks"), tr("about.thanks.body"))
        AboutNote(tr("about.license"), tr("about.license.body"))
        AboutNote(tr("about.notice"), tr("about.notice.body"), footnote = true)
    }
}

@Composable
private fun AboutNote(title: String, body: String, footnote: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
        Text(
            body,
            style = if (footnote) {
                MaterialTheme.typography.bodySmall
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = if (footnote) colors.onSurfaceVariant else colors.onSurface,
        )
    }
}
