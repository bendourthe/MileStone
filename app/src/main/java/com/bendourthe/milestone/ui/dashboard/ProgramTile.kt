package com.bendourthe.milestone.ui.dashboard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bendourthe.milestone.ui.components.ProgramBadge

private const val SIGN_IN_NEEDED = "Sign-in needed"
private const val SIGN_IN_NEEDED_DETAILS =
    "MileStone has no saved sign-in it can use for this program. Tap to sign in once; after that it signs in by itself."

/** Pulses while a sync runs, so the tile says what is happening without a spinner of its own. */
@Composable
private fun SyncStatusText(text: String) {
    val alpha by rememberInfiniteTransition(label = "syncStatus").animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "syncStatusAlpha",
    )
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp).graphicsLayer { this.alpha = alpha },
    )
}

@Composable
fun ProgramTile(
    tile: TileUiState,
    showDragHandle: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    onErrorClick: () -> Unit = {},
    onOpen: (() -> Unit)? = null,
    extraMenuItems: @Composable (closeMenu: () -> Unit) -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    // One TalkBack stop per tile, carrying the Move up and Move down actions from [modifier]; the error chip and the
    // menu button stay their own stops because they are clickable.
    ElevatedCard(modifier.fillMaxWidth().then(if (onOpen == null) Modifier else Modifier.clickable(onClickLabel = "Open ${tile.displayName} benefits", onClick = onOpen)).semantics(mergeDescendants = true) {}) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ProgramBadge(tile.monogram, tile.logoKey, tile.brandColorArgb, size = 66.dp)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tile.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tile.lastUpdatedLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    tile.syncStatus != null -> SyncStatusText(tile.syncStatus)
                    tile.errorReason != null -> SyncErrorChip(tile.errorReason, tile.displayName, onErrorClick)
                    tile.needsReconnect && tile.canSync -> SyncErrorChip(SIGN_IN_NEEDED, SIGN_IN_NEEDED_DETAILS, tile.displayName, onErrorClick)
                }
            }
            Text(
                tile.formattedBalance,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.semantics { contentDescription = "Balance ${tile.formattedBalance} ${tile.unitLabel}" },
            )
            if (showDragHandle) {
                Icon(Icons.Filled.DragHandle, contentDescription = "Reorder ${tile.displayName}", modifier = Modifier.padding(start = 8.dp))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options for ${tile.displayName}") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    extraMenuItems { menuOpen = false }
                    DropdownMenuItem(text = { Text("Remove") }, onClick = {
                        menuOpen = false
                        confirmRemove = true
                    })
                }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${tile.displayName}?") },
            text = { Text("Its balance and history are deleted from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemove()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}
