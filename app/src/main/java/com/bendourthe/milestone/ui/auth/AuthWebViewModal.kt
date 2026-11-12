package com.bendourthe.milestone.ui.auth

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bendourthe.milestone.BuildConfig
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.sync.extract.BridgeEvent
import com.bendourthe.milestone.sync.webview.HostPolicy
import com.bendourthe.milestone.sync.webview.LoginBridge
import com.bendourthe.milestone.ui.theme.AuroraBackground
import kotlinx.serialization.json.Json
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthWebViewModal(
    program: Program,
    createBridge: (Context, Program) -> LoginBridge,
    onEvent: (BridgeEvent) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val bridge = remember(program.programId) { createBridge(context, program) }
    val host by bridge.host.collectAsStateWithLifecycle()
    val progress by bridge.progress.collectAsStateWithLifecycle()
    val canGoBack by bridge.canGoBack.collectAsStateWithLifecycle()
    var externalLink by remember { mutableStateOf<String?>(null) }
    var pasteRejected by remember { mutableStateOf(false) }

    LaunchedEffect(bridge) {
        bridge.events.collect { event ->
            // The capture build keeps the page open after a failed read so the user can still capture it.
            if (BuildConfig.BUILD_TYPE != CAPTURE_BUILD || event !is BridgeEvent.Failed) onEvent(event)
        }
    }
    LaunchedEffect(bridge) { bridge.externalLinks.collect { externalLink = it } }
    DisposableEffect(bridge) {
        bridge.start()
        onDispose { bridge.destroy() }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(enabled = canGoBack) { bridge.goBack() }
        // The theme's background and surface are transparent, so without the aurora the dashboard shows through.
        AuroraBackground(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(program.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                host?.let { current ->
                                    Text(current, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        },
                        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") } },
                        actions = {
                            IconButton(onClick = {
                                val copied = context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                                if (!bridge.openPastedLink(copied)) pasteRejected = true
                            }) {
                                Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste sign-in link")
                            }
                            if (BuildConfig.BUILD_TYPE == CAPTURE_BUILD) {
                                IconButton(onClick = { capturePage(context, bridge.webView, program.programId) }) {
                                    Icon(Icons.Outlined.SaveAlt, contentDescription = "Capture page")
                                }
                            }
                        },
                    )
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Box(Modifier.fillMaxWidth()) {
                        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    AndroidView(factory = { bridge.webView }, modifier = Modifier.fillMaxSize(), onRelease = { bridge.destroy() })
                }
            }
        }
    }

    if (pasteRejected) {
        AlertDialog(
            onDismissRequest = { pasteRejected = false },
            title = { Text("No sign-in link copied") },
            text = { Text("Copy the sign-in link from the ${program.displayName} email, then tap Paste sign-in link again.") },
            confirmButton = { TextButton(onClick = { pasteRejected = false }) { Text("OK") } },
        )
    }

    externalLink?.let { url ->
        AlertDialog(
            onDismissRequest = { externalLink = null },
            icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
            title = { Text("Open in your browser?") },
            text = { Text("This link leaves ${program.displayName}: ${HostPolicy.httpsHost(url)}") },
            confirmButton = {
                TextButton(onClick = {
                    externalLink = null
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (_: ActivityNotFoundException) {
                        Log.w(TAG, "No browser for ${HostPolicy.httpsHost(url)}")
                    }
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { externalLink = null }) { Text("Stay") } },
        )
    }
}

private const val TAG = "MileStoneAuth"
private const val CAPTURE_BUILD = "capture"

/** Capture build only: saves the rendered page under files/captures/ for `adb shell run-as ... cat`. */
private fun capturePage(context: Context, webView: WebView, programId: String) {
    webView.evaluateJavascript("document.documentElement.outerHTML") { encoded ->
        val html = runCatching { Json.decodeFromString<String>(encoded) }.getOrNull()
        if (html == null) {
            Toast.makeText(context, "Capture failed", Toast.LENGTH_SHORT).show()
            return@evaluateJavascript
        }
        val dir = File(context.filesDir, "captures").apply { mkdirs() }
        File(dir, "$programId-${System.currentTimeMillis()}.html").writeText(html)
        Log.i(TAG, "Captured page for $programId")
        Toast.makeText(context, "Page captured", Toast.LENGTH_SHORT).show()
    }
}
