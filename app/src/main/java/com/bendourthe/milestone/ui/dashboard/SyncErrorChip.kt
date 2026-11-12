package com.bendourthe.milestone.ui.dashboard

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bendourthe.milestone.domain.FailureReason

fun FailureReason.shortLabel(): String = when (this) {
    FailureReason.Timeout -> "Timed out"
    FailureReason.NeedsAttention -> "Needs attention"
    FailureReason.Unparseable, FailureReason.SelectorAmbiguous -> "Could not read balance"
    is FailureReason.Network -> "Site didn't load"
    FailureReason.Tls -> "Connection not secure"
    FailureReason.Implausible -> "Balance looked wrong"
    FailureReason.RendererGone -> "Page stopped responding"
    FailureReason.Unexpected -> "Sync failed"
    FailureReason.PasswordRefused -> "Password refused"
    FailureReason.CodeMissing -> "Texted code needed"
    FailureReason.SignInUnfinished -> "Sign-in unfinished"
}

fun FailureReason.fullReason(): String = when (this) {
    FailureReason.Timeout -> "The balance did not appear in time. With a saved sign-in, MileStone signs in once more right away, and tries again the next time you open it."
    FailureReason.NeedsAttention -> "The site showed a page MileStone does not recognize, such as a notice or survey. Sign in to clear it."
    FailureReason.Unparseable -> "The balance on the page was not a whole number MileStone could read."
    FailureReason.SelectorAmbiguous -> "The page showed more than one balance, so MileStone did not guess."
    is FailureReason.Network -> "The site returned an error${httpCode?.let { " (HTTP $it)" } ?: ""}."
    FailureReason.Tls -> "The site's security certificate was not valid, so MileStone stopped."
    FailureReason.Implausible -> "The new balance was far below the saved one, so MileStone kept the saved balance."
    FailureReason.RendererGone -> "The page stopped responding while loading."
    FailureReason.Unexpected -> "Something went wrong while syncing."
    FailureReason.PasswordRefused ->
        "The site refused the saved password, so MileStone stopped signing in by itself to keep the account from being locked. Update the saved sign-in."
    FailureReason.CodeMissing -> "The site asked for a texted code that MileStone could not enter. It tries again the next time you open MileStone."
    FailureReason.SignInUnfinished ->
        "The sign-in stopped on a page MileStone does not recognize. It tries once more right away and again the next time you open MileStone; after three in a row it waits for you to tap Sign in."
}

@Composable
fun SyncErrorChip(reason: FailureReason, programName: String, onClick: () -> Unit) =
    SyncErrorChip(reason.shortLabel(), reason.fullReason(), programName, onClick)

@Composable
fun SyncErrorChip(label: String, details: String, programName: String, onClick: () -> Unit) {
    var showDetails by remember { mutableStateOf(false) }
    // The chip is the way back from a failed sync: the touch target is at least 48 dp and announced as a button, while
    // the visible chip keeps its compact size inside it.
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .combinedClickable(
                role = Role.Button,
                onClickLabel = "Sign in to $programName",
                onLongClickLabel = "Show details",
                onLongClick = { showDetails = true },
                onClick = onClick,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(label) },
            text = { Text(details) },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("OK") } },
        )
    }
}
