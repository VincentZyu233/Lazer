package dev.naominet.lazer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.naominet.lazer.gateway.model.ListenTogetherParticipant
import dev.naominet.lazer.gateway.model.ListenTogetherRoomKind

private val ListenTogetherRoomKind.labelKey: String
    get() = when (this) {
        ListenTogetherRoomKind.Duo -> "listen_together.room_kind_duo"
        ListenTogetherRoomKind.Multi -> "listen_together.room_kind_multi"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListenTogetherSheet(
    controller: AndroidGatewayController,
    onShare: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val room = controller.listenTogether
    var invitation by remember(room?.roomId) { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = controller::closeListenTogether,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = colors.surface,
        contentColor = colors.onSurface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Headphones, null, Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        tr("listen_together.title"),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when {
                            room == null -> tr("listen_together.subtitle")
                            room.connection == AndroidListenTogetherConnection.RECONNECTING ->
                                tr("listen_together.reconnecting")
                            room.participants.size > 1 -> tr("listen_together.connected")
                            else -> tr("listen_together.waiting")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (controller.isListenTogetherBusy) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }

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
                !controller.isSignedIn -> SignedOutListenTogether(controller)
                room == null -> ListenTogetherLobby(
                    invitation = invitation,
                    onInvitationChange = { invitation = it },
                    busy = controller.isListenTogetherBusy,
                    onCreate = controller::createListenTogetherRoom,
                    onJoin = { controller.joinListenTogether(invitation) },
                )
                else -> ActiveListenTogetherRoom(
                    room = room,
                    busy = controller.isListenTogetherBusy,
                    shareUrl = controller.listenTogetherShareUrl,
                    onShare = onShare,
                    onEnd = controller::endListenTogetherRoom,
                )
            }
        }
    }
}

@Composable
private fun SignedOutListenTogether(controller: AndroidGatewayController) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            tr("listen_together.login_required"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = controller::openLogin, modifier = Modifier.fillMaxWidth()) {
            Text(tr("listen_together.login_action"))
        }
    }
}

@Composable
private fun ListenTogetherLobby(
    invitation: String,
    onInvitationChange: (String) -> Unit,
    busy: Boolean,
    onCreate: (ListenTogetherRoomKind) -> Unit,
    onJoin: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
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
            ListenTogetherRoomKind.entries.forEach { kind ->
                FilterChip(
                    selected = roomKind == kind,
                    onClick = { roomKind = kind },
                    label = { Text(tr(kind.labelKey)) },
                )
                Spacer(Modifier.width(8.dp))
            }
        }
        Text(
            tr(
                when (roomKind) {
                    ListenTogetherRoomKind.Duo -> "listen_together.room_kind_duo_hint"
                    ListenTogetherRoomKind.Multi -> "listen_together.room_kind_multi_hint"
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Button(
            onClick = { onCreate(roomKind) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
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
            onValueChange = onInvitationChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(tr("listen_together.invite_placeholder")) },
            leadingIcon = { Icon(Icons.Outlined.Link, null) },
            minLines = 2,
            maxLines = 4,
            shape = RoundedCornerShape(16.dp),
        )
        Button(
            onClick = onJoin,
            enabled = !busy && invitation.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(tr("listen_together.join"))
        }
    }
}

@Composable
private fun ActiveListenTogetherRoom(
    room: AndroidListenTogetherState,
    busy: Boolean,
    shareUrl: String?,
    onShare: (String) -> Unit,
    onEnd: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (room.participants.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("listen_together.members"), style = MaterialTheme.typography.titleSmall)
                room.participants.forEach { participant ->
                    ListenTogetherParticipantRow(participant)
                }
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
            Button(onClick = { onShare(shareUrl) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("listen_together.share"))
            }
        }
        TextButton(onClick = onEnd, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (room.isHost) tr("listen_together.end") else tr("listen_together.leave"),
                color = colors.error,
            )
        }
    }
}

@Composable
private fun ListenTogetherParticipantRow(participant: ListenTogetherParticipant) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(colors.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(participant.nickname.firstOrNull()?.toString().orEmpty())
            normalizedArtworkUrl(participant.avatarUrl)?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            participant.nickname.ifBlank { participant.userId.toString() },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
