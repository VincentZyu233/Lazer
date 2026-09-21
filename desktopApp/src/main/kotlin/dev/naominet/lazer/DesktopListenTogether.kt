package dev.naominet.lazer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.naominet.lazer.gateway.model.ListenTogetherParticipant
import dev.naominet.lazer.gateway.model.ListenTogetherRoomKind
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.launch

private val listenTogetherRoomKinds = listOf(ListenTogetherRoomKind.Duo, ListenTogetherRoomKind.Multi)

private fun roomKindLabelKey(kind: ListenTogetherRoomKind): String = when (kind) {
    ListenTogetherRoomKind.Duo -> "listen_together.room_kind_duo"
    ListenTogetherRoomKind.Multi -> "listen_together.room_kind_multi"
}

private fun roomKindHintKey(kind: ListenTogetherRoomKind): String = when (kind) {
    ListenTogetherRoomKind.Duo -> "listen_together.room_kind_duo_hint"
    ListenTogetherRoomKind.Multi -> "listen_together.room_kind_multi_hint"
}

@Composable
internal fun ListenTogetherOverlay(controller: DesktopPlayerController) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.38f))
            .pointerInput(Unit) { detectTapGestures { controller.closeListenTogether() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .width(470.dp)
                .heightIn(max = 680.dp)
                .pointerInput(Unit) { detectTapGestures { } },
            shape = RoundedCornerShape(28.dp),
            color = colors.surface,
            shadowElevation = 18.dp,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(26.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ListenTogetherHeader(controller)

                controller.listenTogetherError?.let { error ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.errorContainer,
                        contentColor = colors.onErrorContainer,
                    ) {
                        Text(error, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp))
                    }
                }

                when {
                    !controller.isSignedIn -> ListenTogetherSignedOut(controller)
                    controller.listenTogether == null -> ListenTogetherLobby(
                        busy = controller.isListenTogetherBusy,
                        onCreate = controller::createListenTogetherRoom,
                        onJoin = controller::joinListenTogether,
                    )
                    else -> ActiveListenTogetherRoom(controller)
                }
            }
        }
    }
}

@Composable
private fun ListenTogetherHeader(controller: DesktopPlayerController) {
    val colors = MaterialTheme.colorScheme
    val room = controller.listenTogether
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
            color = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Headphones, null, Modifier.size(21.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                tr("listen_together.title"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                when {
                    room == null -> tr("listen_together.subtitle")
                    room.connection == DesktopListenTogetherConnection.RECONNECTING ->
                        tr("listen_together.reconnecting")
                    room.participants.size > 1 -> tr("listen_together.connected")
                    else -> tr("listen_together.waiting")
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        if (controller.isListenTogetherBusy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        IconButton(onClick = controller::closeListenTogether) {
            Icon(Icons.Outlined.Close, tr("listen_together.close"))
        }
    }
}

@Composable
private fun ListenTogetherSignedOut(controller: DesktopPlayerController) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            tr("listen_together.login_required"),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Button(onClick = controller::openLogin, modifier = Modifier.fillMaxWidth()) {
            Text(tr("listen_together.login_action"))
        }
    }
}

@Composable
private fun ListenTogetherLobby(
    busy: Boolean,
    onCreate: (ListenTogetherRoomKind) -> Unit,
    onJoin: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var invitation by remember { mutableStateOf("") }
    var roomKind by remember { mutableStateOf(ListenTogetherRoomKind.Duo) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(tr("listen_together.create_hint"), color = colors.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("listen_together.room_kind"),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            listenTogetherRoomKinds.forEach { kind ->
                FilterChip(
                    selected = roomKind == kind,
                    onClick = { roomKind = kind },
                    label = { Text(tr(roomKindLabelKey(kind))) },
                )
                Spacer(Modifier.width(8.dp))
            }
        }
        Text(
            tr(roomKindHintKey(roomKind)),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Button(onClick = { onCreate(roomKind) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(tr("listen_together.create"))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f))
            Text(
                tr("listen_together.or_join"),
                Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.weight(1f))
        }
        Text(tr("listen_together.join_hint_desktop"), color = colors.onSurfaceVariant)
        OutlinedTextField(
            value = invitation,
            onValueChange = { invitation = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(tr("listen_together.invite_placeholder")) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )
        Button(
            onClick = { onJoin(invitation) },
            enabled = !busy && invitation.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(tr("listen_together.join"))
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ActiveListenTogetherRoom(controller: DesktopPlayerController) {
    val colors = MaterialTheme.colorScheme
    val room = controller.listenTogether ?: return
    val shareUrl = controller.listenTogetherShareUrl
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(room.roomId) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (room.participants.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("listen_together.members"), style = MaterialTheme.typography.titleSmall)
                room.participants.forEach { participant -> ListenTogetherParticipantRow(participant) }
            }
        } else {
            Text(
                if (room.isHost) tr("listen_together.waiting_hint") else tr("listen_together.connecting_hint"),
                color = colors.onSurfaceVariant,
            )
        }
        Text(
            tr("listen_together.room", room.roomId),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
        if (room.isHost && shareUrl != null) {
            Button(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(StringSelection(shareUrl)))
                        copied = true
                    }
                },
                enabled = !controller.isListenTogetherBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr(if (copied) "listen_together.copied" else "listen_together.copy"))
            }
        }
        TextButton(
            onClick = controller::endListenTogetherRoom,
            enabled = !controller.isListenTogetherBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                tr(if (room.isHost) "listen_together.end" else "listen_together.leave"),
                color = colors.error,
            )
        }
    }
}

@Composable
private fun ListenTogetherParticipantRow(participant: ListenTogetherParticipant) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(colors.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(participant.nickname.firstOrNull()?.toString().orEmpty())
            participant.avatarUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp).clip(CircleShape),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            participant.nickname.ifBlank { participant.userId.toString() },
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
