package de.olidoh.inserathelfer.share

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebView
import de.olidoh.inserathelfer.core.ListingDraft
import de.olidoh.inserathelfer.core.PriceType
import de.olidoh.inserathelfer.image.ImagePreparer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Füllt das Formular „Anzeige aufgeben“ auf kleinanzeigen.de im WebView aus:
 * Fotos, Titel (Kleinanzeigen wählt daraus beim Verlassen des Feldes selbst die Kategorie),
 * Beschreibung, Preis, Preisart und, falls die Kategorie danach fragt, den Zustand.
 * Auf „Anzeige aufgeben“ tippt der Nutzer selbst.
 *
 * Die Feld-IDs stammen aus dem Formular vom November 2026 (ad-title, ad-description,
 * ad-price-amount, ad-price-type). Ändert Kleinanzeigen das Formular, meldet das Skript,
 * welche Schritte nicht geklappt haben, statt still zu scheitern.
 */
class KleinanzeigenWebFiller(
    private val draft: ListingDraft,
    private val photos: List<File>,
) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    fun fill(view: WebView, onResult: (String) -> Unit) {
        // Fotos im Hintergrund verkleinern (Upload-Limit 12 MB je Bild, kleinere Dateien laden schneller).
        io.execute {
            val encoded = photos.take(MAX_PHOTOS).mapNotNull { file ->
                runCatching { Base64.encodeToString(ImagePreparer.jpeg(file, UPLOAD_EDGE), Base64.NO_WRAP) }.getOrNull()
            }
            main.post { inject(view, encoded, onResult) }
        }
    }

    private fun inject(view: WebView, photosBase64: List<String>, onResult: (String) -> Unit) {
        // Fotos einzeln übergeben, damit kein riesiger Skript-String entsteht.
        view.evaluateJavascript("window.__ihPhotos = []; window.__ihResult = null;", null)
        photosBase64.forEach { b64 ->
            view.evaluateJavascript("window.__ihPhotos.push(${JSONObject.quote(b64)});", null)
        }
        val payload = JSONObject()
            .put("title", draft.title)
            .put("description", draft.description)
            .put("price", if (draft.priceType == PriceType.FREE) "" else draft.priceEur.toString())
            .put("priceType", draft.priceType.label)
            .put("condition", draft.condition)
        view.evaluateJavascript("($FILL_SCRIPT)($payload);", null)
        poll(view, attempts = 0, onResult = onResult)
    }

    private fun poll(view: WebView, attempts: Int, onResult: (String) -> Unit) {
        view.evaluateJavascript("JSON.stringify(window.__ihResult)") { raw ->
            val result = raw?.takeIf { it != "null" && it != "\"null\"" }
            when {
                result != null -> onResult(summary(result))
                attempts > 60 -> onResult("Ausfüllen dauert ungewöhnlich lange. Bitte die Felder prüfen.")
                else -> main.postDelayed({ poll(view, attempts + 1, onResult) }, 500)
            }
        }
    }

    /** Macht aus dem Ergebnis des Skripts eine Meldung für den Nutzer. */
    private fun summary(rawJson: String): String {
        // evaluateJavascript liefert einen JSON-String, der selbst JSON enthält.
        val obj = runCatching { JSONObject(JSONArray("[$rawJson]").getString(0)) }.getOrNull()
            ?: return "Formular ausgefüllt. Bitte prüfen und auf „Anzeige aufgeben“ tippen."
        val missing = obj.optJSONArray("missing")?.let { arr -> List(arr.length()) { arr.getString(it) } }.orEmpty()
        return if (missing.isEmpty()) {
            "Alles ausgefüllt. Bitte prüfen und unten auf „Anzeige aufgeben“ tippen."
        } else {
            "Ausgefüllt, bitte noch selbst ergänzen: ${missing.joinToString(", ")}. " +
                "Vorschlag Kategorie: ${draft.category}. Dann auf „Anzeige aufgeben“ tippen."
        }
    }

    companion object {
        const val POST_URL = "https://www.kleinanzeigen.de/p-anzeige-aufgeben-schritt2.html"
        private const val MAX_PHOTOS = 20
        private const val UPLOAD_EDGE = 2048

        fun isPostingForm(url: String) = url.contains("p-anzeige-aufgeben")

        fun isLogin(url: String) = url.contains("login.kleinanzeigen.de") || url.contains("m-einloggen")

        /**
         * Läuft in der Seite. Setzt Werte so, dass das React-Formular sie übernimmt
         * (nativer Setter plus input/change-Events), und legt das Ergebnis in window.__ihResult ab.
         */
        private val FILL_SCRIPT = """
            async function (d) {
              const missing = [];
              const sleep = (ms) => new Promise(r => setTimeout(r, ms));
              const setValue = (el, v) => {
                const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
              };
              const norm = (s) => (s || '').trim().toLowerCase();
              async function waitFor(fn, ms) {
                for (let t = 0; t < ms; t += 250) { const v = fn(); if (v) return v; await sleep(250); }
                return null;
              }
              async function pickOption(combo, label) {
                combo.click();
                const opt = await waitFor(() => Array.from(document.querySelectorAll('[role="option"]'))
                  .find(o => norm(o.textContent) === norm(label)), 3000);
                if (opt) { opt.click(); return true; }
                combo.click();
                return false;
              }
              try {
                const form = await waitFor(() => document.getElementById('ad-title'), 10000);
                if (!form) { window.__ihResult = { missing: ['Formular nicht gefunden (Seite geändert?)'] }; return; }

                // Fotos
                const fileInput = document.querySelector('input[type="file"]');
                if (fileInput && (window.__ihPhotos || []).length) {
                  const dt = new DataTransfer();
                  window.__ihPhotos.forEach((b64, i) => {
                    const bin = atob(b64); const bytes = new Uint8Array(bin.length);
                    for (let k = 0; k < bin.length; k++) bytes[k] = bin.charCodeAt(k);
                    dt.items.add(new File([bytes], 'foto_' + (i + 1) + '.jpg', { type: 'image/jpeg' }));
                  });
                  fileInput.files = dt.files;
                  fileInput.dispatchEvent(new Event('change', { bubbles: true }));
                } else if ((window.__ihPhotos || []).length) { missing.push('Fotos'); }
                window.__ihPhotos = [];

                // Titel, danach wählt Kleinanzeigen die Kategorie selbst
                const title = document.getElementById('ad-title');
                title.focus(); setValue(title, d.title); title.blur();
                const category = await waitFor(() => {
                  const c = document.querySelector('input[name="categoryId"]'); return c && c.value ? c : null;
                }, 6000);
                if (!category) missing.push('Kategorie');

                const desc = await waitFor(() => document.getElementById('ad-description'), 3000);
                if (desc) setValue(desc, d.description); else missing.push('Beschreibung');

                const priceType = document.getElementById('ad-price-type');
                if (!priceType || !(await pickOption(priceType, d.priceType))) missing.push('Preisart (' + d.priceType + ')');
                const price = document.getElementById('ad-price-amount');
                if (d.price) { if (price) setValue(price, d.price); else missing.push('Preis'); }

                // Zustand gibt es nur in manchen Kategorien
                if (category) {
                  await sleep(800);
                  const label = Array.from(document.querySelectorAll('label, span, legend'))
                    .find(l => norm(l.textContent).startsWith('zustand'));
                  const combo = label && (document.getElementById(label.getAttribute('for') || '') ||
                    label.closest('div')?.querySelector('[role="combobox"], select'));
                  if (combo && combo.tagName === 'SELECT') {
                    const o = Array.from(combo.options).find(o => norm(o.text) === norm(d.condition));
                    if (o) { combo.value = o.value; combo.dispatchEvent(new Event('change', { bubbles: true })); }
                    else missing.push('Zustand (' + d.condition + ')');
                  } else if (combo) {
                    if (!(await pickOption(combo, d.condition))) missing.push('Zustand (' + d.condition + ')');
                  }
                }
                window.__ihResult = { missing };
              } catch (e) {
                window.__ihResult = { missing: ['Fehler beim Ausfüllen: ' + e.message] };
              }
            }
        """.trimIndent()
    }
}
