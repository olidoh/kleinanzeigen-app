# Verkora über Intune verteilen (private App, Managed Google Play)

Aufbau wie bei easyTDD: Die interne App `de.olidoh.verkora.intern` wird über den GitHub-Workflow
`.github/workflows/release-intern.yml` veröffentlicht. Er baut, prüft Signatur, Paketname und versionCode,
lädt das APK zu Managed Google Play hoch (Intune holt die App von dort) und legt es zusätzlich als
GitHub-Release ab.

| Variante | Paketname | Zweck |
|---|---|---|
| `intern` | `de.olidoh.verkora.intern` | Intune / Managed Google Play, private App (Release, fester Schlüssel) |
| `store` | `de.olidoh.verkora` | bisherige Test-APKs aus der CI, spätere öffentliche Veröffentlichung |

Die beiden Varianten sind für Android zwei verschiedene Apps und können nebeneinander installiert sein.
Die Anzeigen-Historie und die KI-Schlüssel der Test-APK werden nicht übernommen. Seit der Umbenennung in
„Verkora“ heißt auch die Test-APK anders (`de.olidoh.verkora`); die alte `de.olidoh.inserathelfer` kann gelöscht werden.

## Feste Werte – niemals ändern

| Was | Wert |
|---|---|
| Paketname | `de.olidoh.verkora.intern` (Variante `intern`) |
| Signierschlüssel | Alias `inserathelfer`, CN=Inserat-Helfer, gültig bis 2054 (GitHub-Secrets, Sicherung außerhalb des Repos) |
| Zertifikat SHA-256 | `7C:71:71:FC:A3:38:12:F5:CF:36:FB:63:48:86:05:32:CB:99:26:7F:16:3C:F2:36:3E:F4:A2:3B:8C:A6:EE:EC` |
| Play App Signing | **nicht einschalten** – sonst passen Play-Version und Direkt-APK nicht mehr zusammen |

Das Repo ist öffentlich: Keystore, Passwort und Dienstkonto-JSON **nie** ins Repo legen.

## Einmalige Einrichtung

1. **GitHub-Secrets** (Repo › Settings › Secrets and variables › Actions):

   | Secret | Inhalt |
   |---|---|
   | `SIGNING_KEYSTORE_BASE64` | Keystore als Base64 |
   | `SIGNING_PASSWORD` | Passwort für Keystore und Schlüssel |
   | `PLAY_SERVICE_ACCOUNT_JSON` | kompletter Inhalt der JSON-Datei des Play-Dienstkontos (dasselbe wie bei easyTDD) |

2. **Erstes APK holen:** Nach dem nächsten grünen Lauf des Workflows *Build* liegt unter den Artefakten
   `verkora-intern-release-<Nr>` (Version 1.0.0, versionCode 100, fester Schlüssel).
3. **Private App in Intune anlegen** (einmalig von Hand, Google legt die App dabei an):
   Intune Admin Center › *Apps › Android › Hinzufügen* › **Managed Google Play-App** › *Private Apps* (Schloss) › **+**,
   Titel `Verkora`, APK hochladen › *Erstellen*. Nach einigen Minuten (bis zu einer Stunde) die App
   **Genehmigen** (Kamera, Internet), in Intune **Synchronisieren** und unter *Eigenschaften › Zuweisungen*
   Gruppen zuweisen (Erforderlich oder Verfügbar).
4. **Dienstkonto freischalten:** Play Console (dasselbe Entwicklerkonto wie easyTDD) › *Nutzer und Berechtigungen* ›
   Dienstkonto `play-upload@…` › Berechtigungen für die App *Verkora* hinzufügen (Releases verwalten,
   auch für Produktion). Wirkt teils erst nach einigen Stunden.

## Ablauf „neue Version veröffentlichen“

1. Alle Änderungen sind auf `main` gemergt.
2. Version erhöhen: `python tools/version_erhoehen.py` (1.0.0 → 1.0.1, 100 → 101)
   oder `python tools/version_erhoehen.py --name 1.1.0`.
3. Commit `Version 1.0.1 (101)` und auf `main` pushen.
4. Workflow starten:
   - **Claude / API:** `gh api -X POST repos/olidoh/kleinanzeigen-app/actions/workflows/release-intern.yml/dispatches -f ref=main -f 'inputs[upload]=true' -f 'inputs[track]=production'`
   - **Von Hand:** GitHub › Actions › *Release intern* › **Run workflow** (Branch `main`).
   Der Workflow legt Tag `v1.0.1` und das GitHub-Release selbst an. Dauer ca. 5–10 Minuten.
5. Geräte erhalten das Update über Managed Google Play, je nach Update-Richtlinie meist innerhalb eines Tages.

Nur bauen und prüfen: *Run workflow* mit „hochladen“ abgewählt. Interner Test: Track `internal`.

## Neue Berechtigungen

Bringt eine Version neue Android-Berechtigungen mit und ist „Neue Berechtigungen automatisch genehmigen“ nicht
aktiv, die App in Managed Google Play (Intune) erneut **genehmigen**, sonst bleibt das Update aus.

## Typische Fehler

| Meldung | Ursache / Lösung |
|---|---|
| `GitHub-Secret … fehlt` | Secret anlegen (siehe oben). |
| `FALSCHE SIGNATUR` | Secrets enthalten einen anderen Keystore. **Nicht** den Sollwert ändern – richtigen Keystore hinterlegen. |
| `versionCode … nicht höher` | Version vergessen zu erhöhen → Schritt 2. |
| `Version … bereits veröffentlicht` | Diese Version gibt es schon → Schritt 2. |
| `Package not found` / 404 | App wurde noch nicht einmalig über Intune angelegt (Einrichtung, Schritt 3). |
| HTTP 403 / `The caller does not have permission` | Dienstkonto hat für diese App keine Release-Rechte (Einrichtung, Schritt 4), teils bis zu 24 h Wartezeit. |
