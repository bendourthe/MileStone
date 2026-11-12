package com.bendourthe.milestone.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.compose.ui.viewinterop.AndroidView
import com.bendourthe.milestone.sync.webview.WebViewConfig
import com.bendourthe.milestone.ui.theme.MileStoneTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SiteCheckProgram(val id: String, val name: String, val signInUrl: String)

/** Debug-only feasibility screen: opens each program's sign-in page and records the hosts it visits. */
class SiteCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val programs = loadPrograms()
        setContent {
            MileStoneTheme {
                var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
                val selected = programs.firstOrNull { it.id == selectedId }
                if (selected == null) {
                    ProgramList(programs) { selectedId = it.id }
                } else {
                    BackHandler { selectedId = null }
                    SiteCheckBrowser(selected, onCopy = ::copyHosts)
                }
            }
        }
    }

    private fun loadPrograms(): List<SiteCheckProgram> =
        assets.open("site_check_programs.json").bufferedReader().use { Json.decodeFromString(it.readText()) }

    private fun copyHosts(program: SiteCheckProgram, currentUrl: String, hosts: List<String>) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = "${program.id}\npage: ${currentUrl.substringBefore('?').substringBefore('#')}\nhosts: ${hosts.joinToString(", ")}"
        clipboard.setPrimaryClip(ClipData.newPlainText("${program.id} hosts", text))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgramList(programs: List<SiteCheckProgram>, onOpen: (SiteCheckProgram) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Site check (${programs.size} programs)") }) }) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            if (!WebViewConfig.supportsProfiles) {
                item { SharedProfileBanner() }
            }
            items(programs, key = { it.id }) { program ->
                ListItem(
                    headlineContent = { Text(program.name) },
                    supportingContent = { Text(program.signInUrl) },
                    modifier = Modifier.clickable { onOpen(program) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SharedProfileBanner() {
    Text(
        "Shared profile: this WebView has no per-program profiles, so signing in to one program can affect another.",
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(16.dp),
    )
}

/** The copied https link when its host shares the program's sign-in domain, such as an emailed sign-in link. */
private fun pastedSiteLink(context: Context, program: SiteCheckProgram): String? {
    val text = context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim()
    val host = text?.toUri()?.takeIf { it.scheme == "https" }?.host ?: return null
    val domain = program.signInUrl.toUri().host.orEmpty().split('.').takeLast(2).joinToString(".")
    return text.takeIf { host == domain || host.endsWith(".$domain") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SiteCheckBrowser(program: SiteCheckProgram, onCopy: (SiteCheckProgram, String, List<String>) -> Unit) {
    var currentHost by remember(program.id) { mutableStateOf("") }
    var currentUrl by remember(program.id) { mutableStateOf(program.signInUrl) }
    val hosts = remember(program.id) { mutableStateListOf<String>() }
    val context = LocalContext.current
    var webView by remember(program.id) { mutableStateOf<WebView?>(null) }
    fun record(host: String?) {
        if (!host.isNullOrEmpty() && host !in hosts) hosts += host
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentHost.ifEmpty { program.name }, style = MaterialTheme.typography.titleMedium) },
                actions = {
                    TextButton(onClick = { pastedSiteLink(context, program)?.let { webView?.loadUrl(it) } }) { Text("Paste link") }
                    TextButton(onClick = { onCopy(program, currentUrl, hosts.toList()) }) { Text("Copy hosts") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!WebViewConfig.supportsProfiles) SharedProfileBanner()
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context ->
                    WebView(context).apply {
                        WebViewConfig.configure(this, "site_check_${program.id}")
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                val host = android.net.Uri.parse(url).host
                                currentHost = host.orEmpty()
                                currentUrl = url
                                record(host)
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                currentUrl = url
                            }

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                if (request.isForMainFrame) view.post { record(request.url.host) }
                                return null
                            }
                        }
                        loadUrl(program.signInUrl)
                    }.also { webView = it }
                },
            )
            Text(
                "Visited hosts (${hosts.size}): ${hosts.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()).padding(8.dp),
            )
        }
    }
}
