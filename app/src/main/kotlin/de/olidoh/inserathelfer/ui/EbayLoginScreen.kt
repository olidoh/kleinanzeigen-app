package de.olidoh.inserathelfer.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
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

/**
 * eBay-Anmeldung im WebView. Das Passwort geht nur an eBay; die App fängt danach die Rückleitung
 * mit dem Anmeldecode ab und lässt ihn vom Token-Dienst eintauschen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EbayLoginScreen(vm: ListingViewModel) {
    val flow = vm.ebay
    var progress by remember { mutableStateOf(0) }
    val startUrl = remember { flow.startLogin() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bei eBay anmelden") },
                navigationIcon = {
                    IconButton(onClick = { vm.onEbayLoginFinished(false) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (progress in 1..99 || flow.loginBusy) {
                LinearProgressIndicator(
                    progress = { if (flow.loginBusy) 1f else progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    flow.loginError ?: if (flow.loginBusy) "Anmeldung wird abgeschlossen …"
                    else "Melde dich mit deinem eBay-Konto an und erlaube Verkora, Angebote für dich einzustellen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (flow.loginError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        CookieManager.getInstance().setAcceptCookie(true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                flow.handleRedirect(request.url.toString(), vm::onEbayLoginFinished)

                            override fun onPageFinished(view: WebView, url: String) {
                                CookieManager.getInstance().flush()
                                // Falls die Rückleitung ohne Abfangen geladen wurde (z. B. per Formular-POST).
                                flow.handleRedirect(url, vm::onEbayLoginFinished)
                            }
                        }
                        loadUrl(startUrl)
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
