package de.olidoh.inserathelfer.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import de.olidoh.inserathelfer.share.WebDiagnostics
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import de.olidoh.inserathelfer.share.KleinanzeigenHandoff
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import de.olidoh.inserathelfer.ListingViewModel
import de.olidoh.inserathelfer.Screen
import de.olidoh.inserathelfer.share.KleinanzeigenWebFiller

/**
 * Zeigt die Seite „Anzeige aufgeben“ von kleinanzeigen.de. Der Nutzer meldet sich hier selbst an
 * (die Sitzung bleibt als Cookie im WebView, kein Passwort in der App). Sobald das Formular geladen
 * ist, füllt die App Text, Preis, Kategorie, Zustand und Fotos aus. Veröffentlichen tippt der Nutzer selbst.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PostScreen(vm: ListingViewModel) {
    val draft = vm.draft ?: return
    var progress by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("Kleinanzeigen wird geladen …") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var unblockReport by remember { mutableStateOf<String?>(null) }
    var showReport by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val filler = remember(draft) { KleinanzeigenWebFiller(draft, vm.photos.toList()) }
    val diagnostics = remember { WebDiagnostics() }
    // Gegenprobe: Seite ohne automatisches Ausfüllen laden, um die App als Ursache auszuschließen.
    var autoFill by remember { mutableStateOf(true) }
    var fillPhotos by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }

    fun showDiagnostics() {
        webView?.let { v ->
            status = "Bericht wird erstellt …"
            diagnostics.report(v) { text ->
                unblockReport = text
                showReport = true
                status = "Prüfbericht erstellt. Antippen, um ihn erneut zu sehen."
            }
        }
    }

    fun fill(view: WebView, auto: Boolean = false) {
        status = "Formular wird ausgefüllt …"
        filler.fill(view, auto, withPhotos = fillPhotos) { result -> status = result }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bei Kleinanzeigen einstellen") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.REVIEW }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        webView?.let { v ->
                            status = "Seite wird geprüft …"
                            KleinanzeigenWebFiller.unblock(v) { summary, details ->
                                status = summary
                                unblockReport = details
                                showReport = true
                            }
                        }
                    }) {
                        Icon(Icons.Filled.LockOpen, contentDescription = "Seite freigeben")
                    }
                    IconButton(onClick = { webView?.let { fill(it) } }) {
                        Icon(Icons.Filled.AutoFixHigh, contentDescription = "Erneut ausfüllen")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Weitere Optionen")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Prüfbericht (wenn Tippen nicht geht)") },
                            onClick = { menuOpen = false; showDiagnostics() },
                        )
                        DropdownMenuItem(
                            text = { Text("Neu laden ohne Ausfüllen") },
                            onClick = {
                                menuOpen = false
                                autoFill = false
                                fillPhotos = true
                                webView?.loadUrl(KleinanzeigenWebFiller.POST_URL)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Neu laden, ausfüllen ohne Fotos") },
                            onClick = {
                                menuOpen = false
                                autoFill = true
                                fillPhotos = false
                                webView?.loadUrl(KleinanzeigenWebFiller.POST_URL)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Neu laden und alles ausfüllen") },
                            onClick = {
                                menuOpen = false
                                autoFill = true
                                fillPhotos = true
                                webView?.loadUrl(KleinanzeigenWebFiller.POST_URL)
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().clickable(enabled = unblockReport != null) { showReport = true },
            ) {
                Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        // Wichtig: AndroidView gibt Views sonst WRAP_CONTENT. Damit läuft die WebView im
                        // „Wrap-Content-Modus“, in dem CSS-vh-Einheiten 0 werden: Dialoge mit max-h-[90vh]
                        // (Cookie-Hinweis, Versandmethoden) fallen auf Höhe 0 zusammen.
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        diagnostics.attach(this)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                diagnostics.onConsole(consoleMessage)
                                return super.onConsoleMessage(consoleMessage)
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                status = "Lädt …"
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                CookieManager.getInstance().flush()
                                diagnostics.ensureTrace(view)
                                status = when {
                                    KleinanzeigenWebFiller.isConfirmation(url) -> {
                                        vm.setPublished(true)
                                        "Veröffentlicht und unter „Meine Anzeigen“ vermerkt. Mit dem Pfeil oben geht es zurück."
                                    }
                                    KleinanzeigenWebFiller.isPostingForm(url) && !autoFill ->
                                        "Ohne Ausfüllen geladen (Gegenprobe). Lässt sich jetzt etwas antippen?"
                                    KleinanzeigenWebFiller.isPostingForm(url) -> {
                                        fill(view, auto = true)
                                        "Formular wird ausgefüllt …"
                                    }
                                    KleinanzeigenWebFiller.isLogin(url) ->
                                        "Bitte einmal bei Kleinanzeigen anmelden. Danach geht es automatisch weiter."
                                    else -> "Nach dem Veröffentlichen kannst du mit dem Pfeil oben zurück."
                                }
                            }
                        }
                        loadUrl(KleinanzeigenWebFiller.POST_URL)
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }

    if (showReport) {
        unblockReport?.let { report ->
            AlertDialog(
                onDismissRequest = { showReport = false },
                title = { Text("Seiten-Prüfung") },
                text = {
                    Text(
                        report,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        KleinanzeigenHandoff.copyToClipboard(context, "Seiten-Prüfung", report)
                        Toast.makeText(context, "Bericht kopiert", Toast.LENGTH_SHORT).show()
                    }) { Text("Kopieren") }
                },
                dismissButton = { TextButton(onClick = { showReport = false }) { Text("Schließen") } },
            )
        }
    }
}
