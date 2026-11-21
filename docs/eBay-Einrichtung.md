# eBay-Anbindung einrichten

Damit der Inserat-Helfer Anzeigen bei ebay.de einstellen kann, braucht es einmalig drei Dinge:

1. ein **eBay-Entwicklerkonto** mit einer Anwendung („Keyset“),
2. den kleinen **Token-Dienst** bei Cloudflare (kostenlos), der das geheime eBay-Schlüsselteil verwahrt,
3. drei **GitHub-Variablen**, damit die App-Builds wissen, wo alles liegt.

Danach meldet sich jeder Nutzer in der App einmal mit seinem eigenen eBay-Konto an. Nutzer brauchen
kein Entwicklerkonto.

## Warum ein Token-Dienst?

eBay verlangt beim Tausch des Anmeldecodes gegen einen Zugang das **Client Secret** der Anwendung
(per Basic-Auth, ein Verfahren ohne Secret wie PKCE bietet eBay nicht an). eBay schreibt selbst, dass
Apps das Secret nicht sicher verstecken können und dafür einen Server nutzen sollen. Der Token-Dienst
(`tools/ebay-token-dienst/src/index.js`) macht genau das und nichts sonst: Er speichert keine Daten,
reicht nur Code bzw. Refresh-Token an eBay weiter und gibt die Antwort an die App zurück. Die
Zugänge der Nutzer liegen verschlüsselt nur auf deren Handys.

Was die App hinterher über die offiziellen eBay-Schnittstellen macht:

| Schritt | eBay-Schnittstelle |
|---|---|
| Fotos hochladen | Media API (`create_image_from_file`) |
| Kategorie vorschlagen, Pflichtmerkmale holen | Taxonomy API |
| Erlaubte Zustände der Kategorie | Metadata API |
| Versand-, Zahlungs-, Rücknahme-Grundsätze | Account API |
| Artikel, Angebot anlegen und veröffentlichen | Inventory API (Festpreis, Marktplatz `EBAY_DE`) |

---

## Schritt 1: eBay-Entwicklerkonto anlegen

1. <https://developer.ebay.com/signin> öffnen und auf **Join** gehen.
2. Mit E-Mail und eigenem Benutzernamen registrieren (das ist ein eigenes Konto, nicht dein eBay-Käuferkonto).
3. Die Bestätigung per E-Mail abschließen. eBay schaltet das Konto manchmal erst nach bis zu einem Werktag frei.

## Schritt 2: Verifizierungs-Token ausdenken

eBay verlangt für Produktiv-Schlüssel einen Endpunkt für Meldungen „Konto gelöscht“ (Marketplace Account
Deletion). Der Token-Dienst hat ihn schon eingebaut, er braucht dafür ein selbst gewähltes Kennwort:

- 32 bis 80 Zeichen, nur Buchstaben, Ziffern, `_` und `-`.
- Zum Beispiel mit einem Passwort-Manager erzeugen. Notieren, es wird in Schritt 3 und 4 gebraucht.

## Schritt 3: Token-Dienst bei Cloudflare anlegen

1. Kostenloses Konto anlegen: <https://dash.cloudflare.com/sign-up>.
2. Links **Compute (Workers) > Workers & Pages** öffnen, dann **Create** > **Start with Hello World!**.
3. Als Namen `inserathelfer-ebay` eingeben und **Deploy** drücken.
4. **Edit code** öffnen, den gesamten Inhalt durch die Datei
   [`tools/ebay-token-dienst/src/index.js`](../tools/ebay-token-dienst/src/index.js) aus diesem Repo ersetzen
   und **Deploy** drücken.
5. Die Adresse des Workers notieren, sie sieht so aus: `https://inserathelfer-ebay.<dein-name>.workers.dev`.
   Kurzer Test: `…workers.dev/eingeloggt` im Browser öffnen, es erscheint „Bei eBay angemeldet“.
6. Im Worker unter **Settings > Variables and Secrets** hinzufügen:

   | Name | Typ | Wert |
   |---|---|---|
   | `EBAY_VERIFICATION_TOKEN` | Secret | Kennwort aus Schritt 2 |

   (Die übrigen Werte folgen in Schritt 5.)

Wer lieber die Kommandozeile nutzt: Im Ordner `tools/ebay-token-dienst` `npx wrangler deploy` ausführen und die
Secrets mit `npx wrangler secret put <NAME>` setzen (siehe `wrangler.toml`).

## Schritt 4: Produktiv-Schlüssel bei eBay erzeugen

1. Im Entwicklerportal oben rechts auf deinen Namen > **Application Keysets**.
2. Bei **Production** einen Namen eingeben (z. B. `InseratHelfer`) und **Create a keyset** wählen.
3. eBay fragt nun nach den Meldungen zu gelöschten Konten (**Marketplace Account Deletion**). Dort eintragen:
   - **Notification Endpoint:** `https://inserathelfer-ebay.<dein-name>.workers.dev/konto-loeschung`
   - **Verification token:** das Kennwort aus Schritt 2
   - Speichern. eBay prüft den Endpunkt sofort; es muss ein grünes Häkchen erscheinen.
     Klappt das nicht: Schritt 3.6 prüfen (Secret gesetzt und Worker neu bereitgestellt?).
4. Danach zeigt eBay das Production-Keyset mit drei Werten:
   - **App ID (Client ID)**: wird gebraucht
   - **Cert ID (Client Secret)**: geheim, wird gebraucht
   - Dev ID: wird nicht gebraucht

## Schritt 5: Weiterleitungsadresse (RuName) anlegen

1. Beim Production-Keyset auf **User Tokens** klicken.
2. **Get a Token from eBay via Your Application** > **Add eBay Redirect URL**.
3. Ausfüllen:
   - **Display title:** `Inserat-Helfer` (sehen Nutzer bei der Zustimmung)
   - **Privacy policy URL:** `https://inserathelfer-ebay.<dein-name>.workers.dev/datenschutz`
   - **Auth accepted URL:** `https://inserathelfer-ebay.<dein-name>.workers.dev/eingeloggt`
   - **Auth declined URL:** `https://inserathelfer-ebay.<dein-name>.workers.dev/abgelehnt`
   - Als Verfahren **OAuth** wählen (nicht „Auth'n'Auth“).
4. Speichern. In der Zeile steht jetzt der **RuName** (eBay Redirect URL name), etwa
   `Oliver_Dohrenbu-InseratH-PRD-1a2b3c4d5-abcdef12`. Diesen Wert notieren.
5. Im Cloudflare-Worker unter **Settings > Variables and Secrets** ergänzen und danach **Deploy**:

   | Name | Typ | Wert |
   |---|---|---|
   | `EBAY_CLIENT_ID` | Text | App ID (Client ID) |
   | `EBAY_RUNAME` | Text | RuName |
   | `EBAY_CLIENT_SECRET` | Secret | Cert ID (Client Secret) |

Das Client Secret gehört **nur** in den Worker, niemals ins Repo, in GitHub oder in die App.

## Schritt 6: GitHub-Variablen setzen

Im Repo **Settings > Secrets and variables > Actions > Variables** (nicht „Secrets“, die Werte sind nicht geheim
und stehen später ohnehin in der App) drei Variablen anlegen:

| Name | Wert |
|---|---|
| `EBAY_CLIENT_ID` | App ID (Client ID) |
| `EBAY_RUNAME` | RuName |
| `EBAY_TOKEN_SERVICE_URL` | `https://inserathelfer-ebay.<dein-name>.workers.dev` |

Danach einen neuen Build anstoßen (z. B. im Tab **Actions** > Build > **Run workflow**) und die neue APK installieren.
Ohne die Variablen lässt sich die eBay-Anbindung testweise auch in der App unter
**Einstellungen > eBay > Erweiterte eBay-Einstellungen** eintragen.

## Schritt 7: In der App testen

1. **Einstellungen > eBay > Bei eBay anmelden**, mit dem eigenen eBay-Konto anmelden und **Zustimmen**.
2. In deinem eBay-Konto müssen Grundsätze für **Versand, Zahlung und Rücknahme** existieren
   (Mein eBay > Konto > Grundsätze für den Verkauf). Fehlen sie, schaltet die App die Funktion bei eBay ein und
   zeigt einen Hinweis; die Grundsätze legst du dann einmal bei eBay an.
3. Eine Anzeige erstellen, in der Prüfansicht **eBay** wählen und **Bei eBay einstellen** tippen.
4. Kategorie prüfen, Pflichtangaben (z. B. Marke) ausfüllen, PLZ eingeben, **Jetzt bei eBay einstellen**.
   Das Angebot geht sofort online; eBay berechnet dafür ggf. Gebühren.

## Optional: Testumgebung (Sandbox)

Zum gefahrlosen Ausprobieren gibt es bei eBay eine Sandbox mit eigenen Schlüsseln und Testkonten
(<https://developer.ebay.com/sandbox>). Dafür im Worker `EBAY_SANDBOX_CLIENT_ID`, `EBAY_SANDBOX_RUNAME` und das
Secret `EBAY_SANDBOX_CLIENT_SECRET` setzen und in der App unter **Erweiterte eBay-Einstellungen** den Schalter
**eBay-Sandbox** einschalten (dort dann die Sandbox-App-ID und den Sandbox-RuName eintragen).

## Bekannte Grenzen

- Nur Festpreis-Angebote („Sofort-Kaufen“), auf Wunsch mit Preisvorschlägen (entspricht VB). Keine Auktionen.
- „Zu verschenken“ gibt es bei eBay nicht; die App setzt dann 1 €.
- Die Anmeldung läuft im WebView der App. Anmelden mit Google oder Apple kann dort scheitern;
  E-Mail/Benutzername mit Passwort funktioniert.
- Abmelden: in der App (Einstellungen > eBay) oder bei eBay unter Konto > Einstellungen für Drittanbieter-Apps.
