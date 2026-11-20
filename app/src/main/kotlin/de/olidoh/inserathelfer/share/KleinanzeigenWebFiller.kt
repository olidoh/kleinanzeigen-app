package de.olidoh.inserathelfer.share

import android.webkit.WebView
import de.olidoh.inserathelfer.core.ListingDraft
import java.io.File

/**
 * Füllt das Formular „Anzeige aufgeben“ auf kleinanzeigen.de im WebView aus.
 *
 * Stand: Erste Ausbaustufe zum Prüfen, ob die Anmeldung im WebView funktioniert. Das eigentliche
 * Ausfüllen folgt, sobald der Aufbau des Formulars bekannt ist.
 */
class KleinanzeigenWebFiller(
    private val draft: ListingDraft,
    private val photos: List<File>,
) {

    fun fill(view: WebView, onResult: (String) -> Unit) {
        onResult(
            "Anmeldung klappt. Automatisches Ausfüllen folgt in der nächsten Version, " +
                "bis dahin: Text über „Zurück“ kopieren.",
        )
    }

    companion object {
        const val POST_URL = "https://www.kleinanzeigen.de/p-anzeige-aufgeben-schritt2.html"

        fun isPostingForm(url: String) = url.contains("p-anzeige-aufgeben")

        fun isLogin(url: String) = url.contains("login.kleinanzeigen.de") || url.contains("m-einloggen")
    }
}
