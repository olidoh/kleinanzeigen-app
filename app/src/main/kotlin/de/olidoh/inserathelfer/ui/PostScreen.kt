package de.olidoh.inserathelfer.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
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
    val filler = remember(draft) { KleinanzeigenWebFiller(draft, vm.photos.toList()) }

    fun fill(view: WebView, auto: Boolean = false) {
        status = "Formular wird ausgefüllt …"
        filler.fill(view, auto) { result -> status = result }
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
                    IconButton(onClick = { webView?.let { v -> KleinanzeigenWebFiller.unblock(v) { status = it } } }) {
                        Icon(Icons.Filled.LockOpen, contentDescription = "Seite freigeben")
                    }
                    IconButton(onClick = { webView?.let { fill(it) } }) {
                        Icon(Icons.Filled.AutoFixHigh, contentDescription = "Erneut ausfüllen")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                status = "Lädt …"
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                CookieManager.getInstance().flush()
                                status = when {
                                    KleinanzeigenWebFiller.isConfirmation(url) -> {
                                        vm.setPublished(true)
                                        "Veröffentlicht und unter „Meine Anzeigen“ vermerkt. Mit dem Pfeil oben geht es zurück."
                                    }
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
}
