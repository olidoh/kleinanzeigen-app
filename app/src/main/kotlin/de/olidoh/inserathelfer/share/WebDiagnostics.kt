package de.olidoh.inserathelfer.share

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

/**
 * Messpunkte für „man kann auf der Seite nichts antippen“. Beantwortet belastbar:
 * 1. Kommen Finger-Taps überhaupt in der WebView an? (Android-Seite, [onNativeTouch])
 * 2. Welches Seitenelement bekommt sie, und blockt die Seite sie (preventDefault, Fokus weg)?
 * 3. Ist die Seite hydriert (Astro/React aktiv) oder gab es JavaScript-Fehler?
 * 4. Was liegt per Treffer-Test über Titel-Feld und Absende-Knopf (ohne etwas zu ändern)?
 */
class WebDiagnostics {
    private var nativeDowns = 0
    private var lastNativeTouch = ""
    private val consoleErrors = ArrayDeque<String>()

    @SuppressLint("ClickableViewAccessibility")
    fun attach(view: WebView) {
        view.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                nativeDowns++
                lastNativeTouch = "x=${e.x.toInt()} y=${e.y.toInt()} fokussierbar=${v.isFocusableInTouchMode} " +
                    "aktiviert=${v.isEnabled} Fokus=${v.hasFocus()}"
            }
            false // nur zählen, nie verbrauchen
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, TRACE_SCRIPT, setOf("https://*.kleinanzeigen.de"))
        }
    }

    /** Für onPageFinished: falls Document-Start-Skripte nicht gehen, Protokoll nachträglich einrichten. */
    fun ensureTrace(view: WebView) = view.evaluateJavascript(TRACE_SCRIPT, null)

    fun onConsole(message: ConsoleMessage) {
        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
            consoleErrors.addLast("${message.message().take(200)} (${message.sourceId().substringAfterLast('/').take(40)}:${message.lineNumber()})")
            while (consoleErrors.size > 15) consoleErrors.removeFirst()
        }
    }

    fun report(view: WebView, onReport: (String) -> Unit) {
        view.evaluateJavascript("window.__ihDiag = null; ($DIAG_SCRIPT)().then(r => window.__ihDiag = r, e => window.__ihDiag = { error: String(e) });", null)
        poll(view, 0, onReport)
    }

    private fun poll(view: WebView, attempts: Int, onReport: (String) -> Unit) {
        view.evaluateJavascript("JSON.stringify(window.__ihDiag)") { raw ->
            val page = raw?.takeIf { it != "null" && it != "\"null\"" }
                ?.let { runCatching { JSONObject(JSONArray("[$it]").getString(0)) }.getOrNull() }
            if (page == null && attempts < 10) {
                Handler(Looper.getMainLooper()).postDelayed({ poll(view, attempts + 1, onReport) }, 300)
                return@evaluateJavascript
            }
            val lines = buildList {
                add("App-Seite:")
                add("Finger-Taps in der WebView angekommen: $nativeDowns")
                add("Letzter Tap: ${lastNativeTouch.ifEmpty { "keiner" }}")
                add("WebView: ${view.width}x${view.height}, aktiviert=${view.isEnabled}, Fokus=${view.hasFocus()}, Zoom-Stufe=${"%.2f".format(view.scale)}")
                add("Document-Start-Skript: ${WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)}")
                add("")
                add("Seite:")
                if (page == null) add("keine Antwort vom Seitenskript") else {
                    page.keys().forEach { key ->
                        when (val v = page.get(key)) {
                            is JSONArray -> { add("$key:"); for (i in 0 until v.length()) add("  - ${v.get(i)}") }
                            else -> add("$key: $v")
                        }
                    }
                }
                add("")
                add("JavaScript-Fehler (Konsole):")
                if (consoleErrors.isEmpty()) add("keine") else consoleErrors.forEach { add("  - $it") }
            }
            onReport(lines.joinToString("\n"))
        }
    }

    companion object {
        /** Protokolliert Taps, Fokus und Fehler der Seite in window.__ihTrace (läuft vor den Seitenskripten). */
        val TRACE_SCRIPT = """
            (function () {
              if (window.__ihTrace) return;
              const log = window.__ihTrace = [];
              const d = (el) => {
                if (!el || !el.tagName) return String(el);
                const cls = typeof el.className === 'string' && el.className.trim() ? '.' + el.className.trim().split(/\s+/).slice(0, 3).join('.') : '';
                return el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + cls;
              };
              const add = (s) => { log.push(new Date().toISOString().slice(14, 23) + ' ' + s); if (log.length > 40) log.shift(); };
              ['pointerdown', 'touchstart', 'mousedown', 'click', 'focusin'].forEach(t => {
                window.addEventListener(t, e => {
                  const p = (e.touches && e.touches[0]) || e;
                  const x = Math.round(p.clientX || 0), y = Math.round(p.clientY || 0);
                  const top = (t !== 'focusin' && document.elementFromPoint(x, y)) || null;
                  add(t + (e.isTrusted ? '' : ' (Skript)') + ' auf ' + d(e.target) + (t !== 'focusin' ? ' @' + x + ',' + y + ' oben=' + d(top) : ''));
                }, true);
                window.addEventListener(t, e => { if (e.defaultPrevented) add(t + ' von der Seite verhindert (preventDefault) auf ' + d(e.target)); }, false);
              });
              window.addEventListener('focusout', () => setTimeout(() => add('Fokus danach: ' + d(document.activeElement)), 60), true);
              window.addEventListener('error', e => add('Fehler: ' + (e.message || '') + ' ' + (e.filename || '').split('/').pop() + ':' + (e.lineno || '')));
              window.addEventListener('unhandledrejection', e => add('Promise-Fehler: ' + String(e.reason).slice(0, 150)));
            })();
        """.trimIndent()

        /** Reiner Befund, ändert nichts an der Seite. */
        private val DIAG_SCRIPT = """
            async function () {
              const norm = (s) => (s || '').trim().toLowerCase();
              const d = (el) => {
                if (!el || !el.tagName) return String(el);
                const cls = typeof el.className === 'string' && el.className.trim() ? '.' + el.className.trim().split(/\s+/).slice(0, 4).join('.') : '';
                const cs = getComputedStyle(el), r = el.getBoundingClientRect();
                return el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + cls + ' [pos=' + cs.position + ' z=' + cs.zIndex +
                  ' pe=' + cs.pointerEvents + ' ' + Math.round(r.width) + 'x' + Math.round(r.height) + ']';
              };
              const out = {};
              out.adresse = location.pathname + location.hash;
              out.zustand = document.readyState + ', Fenster ' + innerWidth + 'x' + innerHeight + ', Scroll ' + Math.round(scrollY);
              const islands = document.querySelectorAll('astro-island');
              out.hydriert = islands.length + ' Inseln, davon noch nicht aktiv: ' + document.querySelectorAll('astro-island[ssr]').length;
              out.fokus = d(document.activeElement);
              out.inert = document.querySelectorAll('[inert]').length + ' Elemente mit inert, aria-hidden an body-Kindern: ' +
                Array.from(document.body.children).filter(c => c.getAttribute('aria-hidden') === 'true').map(c => d(c)).join(', ');
              out.htmlBody = ['html', 'body'].map(t => { const cs = getComputedStyle(document.querySelector(t)); return t + ' pe=' + cs.pointerEvents + ' overflow=' + cs.overflow + ' touch-action=' + cs.touchAction; }).join('; ');
              out.dialoge = Array.from(document.querySelectorAll('[role="dialog"], [role="alertdialog"], [aria-modal="true"], dialog[open], [role="listbox"]'))
                .map(el => d(el) + ' "' + norm(el.textContent).slice(0, 60) + '"');
              out.versteckt = Array.from(document.querySelectorAll('[data-ih-hidden], [data-ih-nopseudo]')).map(d);
              const probes = [document.getElementById('ad-title'),
                Array.from(document.querySelectorAll('button')).find(b => norm(b.textContent) === 'anzeige aufgeben')].filter(Boolean);
              out.gesperrt = probes.map(p => d(p) + ': disabled=' + !!p.disabled + ' readonly=' + !!p.readOnly +
                ' aria-disabled=' + (p.closest('[aria-disabled="true"]') ? 'ja' : 'nein') +
                ' fieldset-disabled=' + (p.closest('fieldset[disabled]') ? 'ja' : 'nein') +
                ' aria-busy=' + (p.closest('[aria-busy="true"]') ? 'ja' : 'nein'));
              out.anfragen = performance.getEntriesByType('resource')
                .filter(e => e.initiatorType === 'xmlhttprequest' || e.initiatorType === 'fetch')
                .slice(-12).map(e => e.name.replace(/^https?:\/\/[^/]+/, '').split('?')[0].slice(0, 70) +
                  ' status=' + (e.responseStatus || '?') + ' ' + Math.round(e.duration) + 'ms');
              out.fotos = document.querySelectorAll('input[type="file"]').length + ' Datei-Felder, Bilder im Formular: ' +
                document.querySelectorAll('img[src^="blob:"], img[src*="img.kleinanzeigen"]').length;
              out.trefferTest = probes.map(p => {
                const r = p.getBoundingClientRect();
                if (r.bottom < 0 || r.top > innerHeight) return d(p) + ': nicht im Bild';
                const top = document.elementFromPoint(r.left + Math.min(r.width / 2, 20), r.top + r.height / 2);
                return d(p) + ' -> oben: ' + d(top) + (top === p || p.contains(top) ? ' (frei)' : ' (VERDECKT)');
              });
              out.protokoll = (window.__ihTrace || ['Protokoll fehlt']).slice(-25);
              return out;
            }
        """.trimIndent()
    }
}
