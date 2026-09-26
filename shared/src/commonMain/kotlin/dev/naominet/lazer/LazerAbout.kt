package dev.naominet.lazer

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * What Lazer is, what it stands on, and what it is allowed to be.
 *
 * The header answers "which build am I reading": mark, name, version, and the commit it came from.
 * Everything under the divider is reference text, so it stays quiet and keeps one action only.
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
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (heading != null) {
            Text(
                heading,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurfaceVariant,
            )
        }
        BrandHeader()
        Text(
            tr("about.tagline"),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                tr("about.repository"),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            val shape = MaterialTheme.shapes.medium
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.surfaceContainerHigh)
                    .clickable(onClickLabel = tr("about.repository")) {
                        links.openUri(LazerRelease.repositoryUrl)
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    Modifier.weight(1f),
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
                // The one affordance a link needs without an icon set on this module.
                Text(
                    "↗",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            AboutNote(tr("about.thanks"), tr("about.thanks.body"))
            AboutNote(tr("about.license"), tr("about.license.body"))
            AboutNote(tr("about.notice"), tr("about.notice.body"), footnote = true)
        }
    }
}

/** The build's own identity: mark, name, version, commit. */
@Composable
private fun BrandHeader() {
    val colors = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                LazerRelease.name.first().toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onPrimaryContainer,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                LazerRelease.name,
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
            )
            Text(
                tr("about.version", LazerRelease.versionName, LazerRelease.versionCode),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            CommitStamp()
        }
    }
}

/**
 * The commit this build was cut from. Tapping hands over the full hash and its committer date, which
 * is what a bug report needs; the label answers in place so nothing on the page moves.
 */
@Composable
private fun CommitStamp() {
    if (LazerBuildInfo.commit.isBlank()) return

    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_800)
            copied = false
        }
    }
    val container by animateColorAsState(
        if (copied) colors.secondaryContainer else colors.surfaceContainerHigh,
        label = "commit-stamp-container",
    )
    val content by animateColorAsState(
        if (copied) colors.onSecondaryContainer else colors.onSurfaceVariant,
        label = "commit-stamp-content",
    )
    // On screen the date stops at the minute; the full ISO timestamp goes to the clipboard.
    val stamp = "${LazerBuildInfo.commit} · ${LazerBuildInfo.commitTime.take(16).replace('T', ' ')}"
    Box(
        Modifier
            .clip(CircleShape)
            .background(container)
            .border(1.dp, colors.outline, CircleShape)
            .clickable(onClickLabel = tr("about.commit.copy")) {
                clipboard.setText(
                    AnnotatedString("${LazerBuildInfo.commitHash}\n${LazerBuildInfo.commitTime}"),
                )
                copied = true
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Crossfade(targetState = copied, label = "commit-stamp") { isCopied ->
            Text(
                if (isCopied) tr("about.commit.copied") else stamp,
                style = MaterialTheme.typography.labelMedium,
                color = content,
            )
        }
    }
}

@Composable
private fun AboutNote(title: String, body: String, footnote: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
