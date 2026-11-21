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

    /**
     * [auto] = vom Laden der Seite ausgelöst: dann nur einmal je Seite ausfüllen, damit spätere
     * Seitenereignisse (z. B. die Kategorie-Auswahl) nicht erneut alles überschreiben.
     */
    fun fill(view: WebView, auto: Boolean = false, withPhotos: Boolean = true, onResult: (String) -> Unit) {
        // Fotos im Hintergrund verkleinern (Upload-Limit 12 MB je Bild, kleinere Dateien laden schneller).
        io.execute {
            val encoded = photos.take(if (withPhotos) MAX_PHOTOS else 0).mapNotNull { file ->
                runCatching { Base64.encodeToString(ImagePreparer.jpeg(file, UPLOAD_EDGE), Base64.NO_WRAP) }.getOrNull()
            }
            main.post { inject(view, encoded, auto, onResult) }
        }
    }

    private fun inject(view: WebView, photosBase64: List<String>, auto: Boolean, onResult: (String) -> Unit) {
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
            .put("category", draft.category)
            .put("auto", auto)
        view.evaluateJavascript("($FILL_SCRIPT)($payload);", null)
        poll(view, attempts = 0, onResult = onResult)
    }

    private fun poll(view: WebView, attempts: Int, onResult: (String) -> Unit) {
        view.evaluateJavascript("JSON.stringify({ r: window.__ihResult || null, s: window.__ihStatus || null })") { raw ->
            val state = runCatching { JSONObject(JSONArray("[$raw]").getString(0)) }.getOrNull()
            val result = state?.optJSONObject("r")
            val waitingForConsent = state?.optString("s") == "consent"
            when {
                result != null -> onResult(summary(result.toString()))
                waitingForConsent -> {
                    onResult(CONSENT_HINT)
                    main.postDelayed({ poll(view, attempts, onResult) }, 1000)
                }
                attempts > 60 -> onResult("Ausfüllen dauert ungewöhnlich lange. Bitte die Felder prüfen.")
                else -> main.postDelayed({ poll(view, attempts + 1, onResult) }, 500)
            }
        }
    }

    /** Macht aus dem Ergebnis des Skripts eine Meldung für den Nutzer. */
    private fun summary(rawJson: String): String {
        // evaluateJavascript liefert einen JSON-String, der selbst JSON enthält.
        val obj = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: return "Formular ausgefüllt. Bitte prüfen und auf „Anzeige aufgeben“ tippen."
        if (obj.optBoolean("skipped")) return "Formular ist bereits ausgefüllt. Neu ausfüllen über das Zauberstab-Symbol oben."
        val missing = obj.optJSONArray("missing")?.let { arr -> List(arr.length()) { arr.getString(it) } }.orEmpty()
        return if (missing.isEmpty()) {
            "Alles ausgefüllt. Bitte prüfen und unten auf „Anzeige aufgeben“ tippen. Ist die Seite grau, oben auf das Schloss tippen."
        } else {
            val categoryHint = if (missing.any { it.startsWith("Kategorie") }) {
                "Bei Kategorie auf „Wähle deine Kategorie“ tippen, Vorschlag: ${draft.category}. "
            } else {
                ""
            }
            "Ausgefüllt, bitte noch selbst ergänzen: ${missing.joinToString(", ")}. $categoryHint" +
                "Dann auf „Anzeige aufgeben“ tippen."
        }
    }

    companion object {
        const val CONSENT_HINT = "Bitte zuerst unten den Cookie-Hinweis von Kleinanzeigen beantworten (einmalig). " +
            "Danach füllt die App automatisch weiter aus."

        const val POST_URL = "https://www.kleinanzeigen.de/p-anzeige-aufgeben-schritt2.html"
        private const val MAX_PHOTOS = 20
        private const val UPLOAD_EDGE = 2048

        fun isPostingForm(url: String) = url.contains("p-anzeige-aufgeben") && !isConfirmation(url)

        /** Seite nach erfolgreichem Aufgeben (z. B. p-anzeige-aufgeben-bestaetigung.html). */
        fun isConfirmation(url: String) = url.contains("bestaetigung")

        /**
         * Notfall-Knopf: Sperren beseitigen und einen Bericht liefern, was über dem Formular lag.
         * [onResult] bekommt eine kurze Meldung und den vollständigen Bericht zum Kopieren.
         */
        fun unblock(view: WebView, onResult: (summary: String, details: String) -> Unit) {
            view.evaluateJavascript(
                "window.__ihUnblocked = null; ($UNBLOCK_SCRIPT)().then(r => window.__ihUnblocked = r, " +
                    "e => window.__ihUnblocked = { fixed: [], report: ['Skriptfehler: ' + e.message] });",
                null,
            )
            pollUnblock(view, 0, onResult)
        }

        private fun pollUnblock(view: WebView, attempts: Int, onResult: (String, String) -> Unit) {
            view.evaluateJavascript("JSON.stringify(window.__ihUnblocked)") { raw ->
                val obj = raw?.takeIf { it != "null" && it != "\"null\"" }
                    ?.let { runCatching { JSONObject(JSONArray("[$it]").getString(0)) }.getOrNull() }
                when {
                    obj != null -> {
                        val fixed = obj.optJSONArray("fixed").toStrings()
                        val report = obj.optJSONArray("report").toStrings()
                        val summary = if (obj.optBoolean("consent")) {
                            CONSENT_HINT
                        } else if (fixed.isEmpty()) {
                            "Auf der Seite liegt nichts über dem Formular. Details über das Schloss."
                        } else {
                            "Seite freigegeben (${fixed.size} Eingriff(e)). Bitte jetzt antippen."
                        }
                        val details = (listOf("Eingriffe:") + fixed.ifEmpty { listOf("keine") } + "" + "Befund:" + report)
                            .joinToString("\n")
                        onResult(summary, details)
                    }
                    attempts > 20 -> onResult("Freigabe antwortet nicht.", "Kein Ergebnis vom Seitenskript nach 10 s.")
                    else -> Handler(Looper.getMainLooper()).postDelayed({ pollUnblock(view, attempts + 1, onResult) }, 500)
                }
            }
        }

        private fun JSONArray?.toStrings(): List<String> = this?.let { arr -> List(arr.length()) { arr.optString(it) } }.orEmpty()

        fun isLogin(url: String) = url.contains("login.kleinanzeigen.de") || url.contains("m-einloggen")

        /**
         * Cookie-Hinweis von Kleinanzeigen: ein per showModal() geöffnetes <dialog id="gdpr-banner">.
         * Solange er offen ist, ist der Rest der Seite gesperrt (Taps landen auf <html>). In der WebView
         * war er unsichtbar. Wir machen ihn sichtbar, damit der Nutzer ihn selbst beantwortet; die Wahl
         * bleibt als Cookie in der WebView gespeichert, danach erscheint er nicht mehr.
         */
        private val CONSENT_HELPERS = """
              const consentDialog = () => Array.from(document.querySelectorAll('dialog[open]')).find(dl =>
                dl.id === 'gdpr-banner' || dl.closest('#consentBanner') ||
                /werbung und tracking|cookie|einwillig|zustimm/.test((dl.textContent || '').toLowerCase())) || null;
              const revealConsent = (dl) => {
                for (let a = dl; a && a.nodeType === 1; a = a.parentElement) {
                  if (a.hasAttribute('data-ih-hidden')) { a.style.removeProperty('display'); a.removeAttribute('data-ih-hidden'); }
                }
                const set = (el, k, v) => el.style.setProperty(k, v, 'important');
                set(dl, 'position', 'fixed'); set(dl, 'left', '0'); set(dl, 'right', '0'); set(dl, 'top', 'auto'); set(dl, 'bottom', '0');
                set(dl, 'margin', '0 auto'); set(dl, 'max-width', '100vw'); set(dl, 'max-height', Math.round(innerHeight * 0.9) + 'px');
                set(dl, 'overflow', 'auto'); set(dl, 'display', 'block'); set(dl, 'visibility', 'visible'); set(dl, 'opacity', '1');
                set(dl, 'z-index', '2147483647');
                dl.querySelectorAll('*').forEach(c => {
                  const cs = getComputedStyle(c);
                  if (cs.maxHeight !== 'none' && parseFloat(cs.maxHeight) < 40) set(c, 'max-height', Math.round(innerHeight * 0.85) + 'px');
                });
              };
        """

        /**
         * Schließt offene Auswahlfenster und beseitigt alles, was Taps auf das Formular abfängt.
         * Statt zu raten, prüft das Skript per Treffer-Test (elementFromPoint), welches Element an der
         * Stelle von Titel-Feld und Absende-Knopf wirklich oben liegt, und entfernt genau das:
         * Overlays (auch iframes und Shadow-DOM), ::before/::after-Schleier, pointer-events:none,
         * inert und Abdunkeln per opacity/filter. Ergebnis: { fixed: [...], report: [...] }.
         */
        val UNBLOCK_SCRIPT get() = UNBLOCK_TEMPLATE.replace("__CONSENT__", CONSENT_HELPERS)

        private val UNBLOCK_TEMPLATE = """
            async function () {
              const sleep = (ms) => new Promise(r => setTimeout(r, ms));
              const norm = (s) => (s || '').trim().toLowerCase();
              const fixed = [], report = [];
              const desc = (el) => {
                if (!el || !el.tagName) return String(el);
                const cls = typeof el.className === 'string' ? el.className.trim().split(/\s+/).slice(0, 6).join('.') : '';
                const cs = getComputedStyle(el), r = el.getBoundingClientRect();
                return el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + (cls ? '.' + cls : '') +
                  ' [pos=' + cs.position + ' z=' + cs.zIndex + ' pe=' + cs.pointerEvents + ' op=' + cs.opacity +
                  (cs.filter !== 'none' ? ' filter=' + cs.filter : '') + ' bg=' + cs.backgroundColor +
                  ' ' + Math.round(r.width) + 'x' + Math.round(r.height) + (el.tagName === 'IFRAME' ? ' src=' + (el.src || '').slice(0, 80) : '') + ']';
              };
              const visible = (el) => { const r = el.getBoundingClientRect(); const cs = getComputedStyle(el);
                return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none'; };
              const hide = (el, why) => { fixed.push(why + ': ' + desc(el)); el.style.setProperty('display', 'none', 'important'); el.setAttribute('data-ih-hidden', '1'); };
              const esc = (t) => ['keydown', 'keyup'].forEach(type => (t || document).dispatchEvent(
                new KeyboardEvent(type, { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true })));
              report.push('Seite: ' + location.pathname + ' Fenster: ' + innerWidth + 'x' + innerHeight + ' Scroll: ' + Math.round(scrollY));
              __CONSENT__
              const consent = consentDialog();
              if (consent) {
                revealConsent(consent);
                fixed.push('Cookie-Hinweis von Kleinanzeigen sichtbar gemacht: bitte unten beantworten');
                report.push('Cookie-Hinweis offen (sperrt die Seite, bis er beantwortet ist)');
                return { fixed, report, consent: true };
              }

              // 1. Offene Dialoge regulär schließen
              const dialogs = () => Array.from(document.querySelectorAll('[role="dialog"], [role="alertdialog"], [aria-modal="true"], dialog[open]')).filter(visible);
              for (let round = 0; round < 3 && dialogs().length; round++) {
                for (const dlg of dialogs()) {
                  if (dlg.matches('dialog[open]') && dlg === consentDialog()) continue;
                  report.push('Dialog offen: ' + desc(dlg) + ' Text: ' + norm(dlg.textContent).slice(0, 120));
                  const buttons = Array.from(dlg.querySelectorAll('button, [role="button"]')).filter(visible);
                  const confirm = buttons.find(b => ['übernehmen', 'fertig', 'bestätigen', 'ok', 'auswählen', 'anwenden'].includes(norm(b.textContent)));
                  const close = buttons.find(b => /schließen|close|abbrechen/.test(norm(b.getAttribute('aria-label') || b.textContent)));
                  if (confirm) { confirm.click(); fixed.push('Dialog bestätigt: ' + norm(confirm.textContent)); }
                  else if (close) { close.click(); fixed.push('Dialog geschlossen'); }
                  else esc(dlg);
                }
                esc(document.activeElement); esc(document);
                await sleep(400);
              }

              // 2. Sperren über Attribute und Stile
              document.querySelectorAll('[inert]').forEach(el => { el.removeAttribute('inert'); fixed.push('inert entfernt: ' + desc(el)); });
              const form = document.getElementById('ad-title')?.closest('form') || document.querySelector('form');
              const probes = () => [document.getElementById('ad-title'),
                Array.from(document.querySelectorAll('button')).find(b => norm(b.textContent) === 'anzeige aufgeben'),
                Array.from(document.querySelectorAll('button')).find(b => norm(b.textContent) === 'entwurf speichern')].filter(Boolean);
              const relax = (el) => {
                for (let a = el; a && a.nodeType === 1; a = a.parentElement) {
                  const cs = getComputedStyle(a);
                  if (cs.pointerEvents === 'none') { a.style.setProperty('pointer-events', 'auto', 'important'); fixed.push('pointer-events freigegeben: ' + desc(a)); }
                  if (parseFloat(cs.opacity) < 1 && parseFloat(cs.opacity) > 0) { a.style.setProperty('opacity', '1', 'important'); fixed.push('Abdunkeln (opacity) entfernt: ' + desc(a)); }
                  if (cs.filter !== 'none' && /brightness|grayscale|blur/.test(cs.filter)) { a.style.setProperty('filter', 'none', 'important'); fixed.push('Abdunkeln (filter) entfernt: ' + desc(a)); }
                  if (a.hasAttribute('aria-hidden') && a.contains(form)) { a.removeAttribute('aria-hidden'); }
                }
              };
              probes().forEach(relax);
              if (!document.getElementById('ih-unblock-style')) {
                const st = document.createElement('style'); st.id = 'ih-unblock-style';
                st.textContent = '[data-ih-nopseudo]::before,[data-ih-nopseudo]::after{display:none!important;content:none!important}';
                document.head.appendChild(st);
              }
              const pseudoOverlay = (el) => ['::before', '::after'].some(p => {
                const cs = getComputedStyle(el, p);
                return cs.content !== 'none' && cs.content !== 'normal' && (cs.position === 'fixed' || cs.position === 'absolute');
              });

              // 3. Treffer-Test: was liegt wirklich über Titel-Feld und Absende-Knopf?
              for (const probe of probes()) {
                for (let i = 0; i < 8; i++) {
                  const r = probe.getBoundingClientRect();
                  if (r.bottom < 0 || r.top > innerHeight) { probe.scrollIntoView({ block: 'center' }); await sleep(150); continue; }
                  const x = r.left + Math.min(r.width / 2, 20), y = r.top + r.height / 2;
                  const top = document.elementFromPoint(x, y);
                  const name = probe.id || norm(probe.textContent);
                  if (top && (top.tagName === 'DIALOG' || top.closest('dialog[open]'))) { report.push('Treffer-Test ' + name + ': offener Dialog oben, nicht entfernt'); break; }
                  if (!top || top === probe || probe.contains(top)) { report.push('Treffer-Test ' + name + ': frei'); break; }
                  report.push('Treffer-Test ' + name + ': verdeckt von ' + desc(top));
                  if (top.contains(probe)) {
                    // Schleier als ::before/::after eines Vorfahren, oder der Vorfahr fängt Taps ab
                    let done = false;
                    for (let a = top; a && a.nodeType === 1; a = a.parentElement) {
                      if (pseudoOverlay(a) && !a.hasAttribute('data-ih-nopseudo')) { a.setAttribute('data-ih-nopseudo', '1'); fixed.push('Schleier (::before/::after) entfernt: ' + desc(a)); done = true; }
                    }
                    relax(probe);
                    if (!done) { probe.style.setProperty('pointer-events', 'auto', 'important'); probe.style.setProperty('position', 'relative'); probe.style.setProperty('z-index', '2147483647'); fixed.push('Feld nach vorne geholt: ' + name); }
                  } else {
                    // Fremdes Element liegt darüber: obersten Vorfahren entfernen, der das Formular nicht enthält
                    let overlay = top;
                    while (overlay.parentElement && !overlay.parentElement.contains(probe)) overlay = overlay.parentElement;
                    if (overlay.querySelector('dialog[open]')) { report.push('Overlay enthält offenen Dialog, nicht entfernt: ' + desc(overlay)); break; }
                    hide(overlay, 'Overlay entfernt');
                  }
                  await sleep(100);
                }
              }

              // 4. Seitenweite Sperren an html/body
              for (const el of [document.documentElement, document.body]) {
                const cs = getComputedStyle(el);
                if (cs.overflow === 'hidden') { el.style.setProperty('overflow', 'auto', 'important'); fixed.push('Scroll-Sperre entfernt: ' + el.tagName.toLowerCase()); }
                if (pseudoOverlay(el) && !el.hasAttribute('data-ih-nopseudo')) { el.setAttribute('data-ih-nopseudo', '1'); fixed.push('Schleier (::before/::after) entfernt: ' + desc(el)); }
              }
              if (!fixed.length) report.push('Keine Sperre gefunden');
              return { fixed, report };
            }
        """.trimIndent()

        /**
         * Läuft in der Seite. Setzt Werte so, dass das React-Formular sie übernimmt
         * (nativer Setter plus input/change-Events), und legt das Ergebnis in window.__ihResult ab.
         */
        private val FILL_SCRIPT get() = FILL_TEMPLATE.replace("__UNBLOCK__", UNBLOCK_SCRIPT).replace("__CONSENT__", CONSENT_HELPERS)

        private val FILL_TEMPLATE = """
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
              // Voller Zeiger-Ablauf, weil manche Auswahllisten erst auf pointerup reagieren.
              const realClick = (el) => {
                const o = { bubbles: true, cancelable: true, view: window, button: 0, pointerId: 1, pointerType: 'touch', isPrimary: true };
                el.dispatchEvent(new PointerEvent('pointerdown', o)); el.dispatchEvent(new MouseEvent('mousedown', o));
                el.dispatchEvent(new PointerEvent('pointerup', o)); el.dispatchEvent(new MouseEvent('mouseup', o));
                el.click();
              };
              __CONSENT__
              const unblock = (__UNBLOCK__);
              async function pickOption(combo, label) {
                realClick(combo);
                const opt = await waitFor(() => Array.from(document.querySelectorAll('[role="option"]'))
                  .find(o => norm(o.textContent) === norm(label)), 3000);
                if (opt) realClick(opt);
                await sleep(300);
                await unblock();
                return !!opt;
              }
              // Kategorie-Vorschläge von Kleinanzeigen: passendsten anklicken (Wortüberlappung mit dem KI-Vorschlag).
              async function pickSuggestedCategory(wanted) {
                const box = document.getElementById('ad-category-suggestions');
                if (!box) return false;
                const isPicker = (el) => norm(el.textContent).startsWith('wähle deine kategorie');
                const candidates = await waitFor(() => {
                  const list = Array.from(box.querySelectorAll('button, a, [role="radio"], [role="option"], input[type="radio"], label'))
                    .filter(el => !isPicker(el) && norm(el.textContent || el.value).length > 1);
                  return list.length ? list : null;
                }, 4000);
                if (candidates) {
                  const words = norm(wanted).split(/[^a-zäöüß0-9]+/).filter(w => w.length > 2);
                  const score = (el) => words.filter(w => norm(el.textContent || el.value).includes(w)).length;
                  const best = candidates.slice().sort((a, b) => score(b) - score(a))[0];
                  best.click();
                  if (await waitFor(() => { const c = document.querySelector('input[name="categoryId"]'); return c && c.value; }, 3000)) return true;
                }
                return false;
              }
              try {
                if (d.auto && window.__ihFilledOnce) { window.__ihResult = { skipped: true, missing: [] }; return; }
                window.__ihFilledOnce = true;
                const form = await waitFor(() => document.getElementById('ad-title'), 10000);
                if (!form) { window.__ihResult = { missing: ['Formular nicht gefunden (Seite geändert?)'] }; return; }

                // Cookie-Hinweis zuerst: er sperrt die Seite, bis der Nutzer ihn beantwortet hat.
                await sleep(1200);
                if (consentDialog()) {
                  window.__ihStatus = 'consent';
                  for (let t = 0; t < 1800 && consentDialog(); t++) { revealConsent(consentDialog()); await sleep(1000); }
                  window.__ihStatus = null;
                  await sleep(800);
                }

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

                // Titel, danach schlägt Kleinanzeigen beim Verlassen des Feldes Kategorien vor
                const title = document.getElementById('ad-title');
                title.focus(); setValue(title, d.title); title.blur();
                title.dispatchEvent(new FocusEvent('focusout', { bubbles: true }));
                const categoryInput = () => document.querySelector('input[name="categoryId"]');
                const hasCategory = () => { const c = categoryInput(); return !!(c && c.value); };
                let category = await waitFor(hasCategory, 4000);
                if (!category) category = await pickSuggestedCategory(d.category);
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
                await unblock();
                // Keine Kategorie: erst jetzt die Kategorie-Auswahl der Seite öffnen, damit der Nutzer direkt wählen kann.
                if (!category) {
                  const box = document.getElementById('ad-category-suggestions');
                  const picker = box && Array.from(box.querySelectorAll('a, button'))
                    .find(el => norm(el.textContent).startsWith('wähle deine kategorie'));
                  if (picker) realClick(picker);
                }
                window.__ihResult = { missing };
              } catch (e) {
                try { await unblock(); } catch (_) {}
                window.__ihResult = { missing: ['Fehler beim Ausfüllen: ' + e.message] };
              }
            }
        """.trimIndent()
    }
}
