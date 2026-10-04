package dev.naominet.lazer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One paper menu surface shared by the tray menu, the account menu and the player pop-ups. They
 * differ only in width and anchor, so the card, its rows and its dividers live here instead of
 * being rebuilt at every call site.
 */
@Composable
internal fun PaperMenuCard(
    modifier: Modifier = Modifier,
    width: Dp? = null,
    cornerRadius: Dp = 14.dp,
    elevation: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(cornerRadius),
        color = colors.surface,
        shadowElevation = elevation,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = if (width != null) modifier.width(width) else modifier,
    ) {
        Column(Modifier.padding(vertical = 8.dp, horizontal = 6.dp)) {
            content()
        }
    }
}

/** Optional quiet heading above a group of rows. */
@Composable
internal fun PaperMenuTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
internal fun PaperMenuDivider() {
    HorizontalDivider(Modifier.padding(vertical = 5.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** A single menu row: icon, label, and an optional destructive tone. */
@Composable
internal fun PaperMenuRow(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 10.dp),
        colors = if (destructive) {
            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.textButtonColors()
        },
    ) {
        Icon(icon, null, Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
    }
}
