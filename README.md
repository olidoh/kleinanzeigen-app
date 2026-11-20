# Inserat-Helfer

Android-App, die aus ein paar Fotos eine fertige Kleinanzeige macht:

1. Fotos mit der Kamera aufnehmen oder aus der Galerie wählen.
2. Optional Hinweise ergänzen, die man auf den Fotos nicht sieht (Mängel, Alter, Lieferumfang).
3. Die KI schreibt Titel, Beschreibung, Preisvorschlag, Kategorie und Zustand.
4. Alles prüfen und bearbeiten, dann „An Kleinanzeigen übergeben“: Der Text landet in der
   Zwischenablage, die Fotos gehen per Teilen an die offizielle Kleinanzeigen-App.

## Warum Übergabe statt Automatik

Kleinanzeigen bietet Privatnutzern keine Schnittstelle zum Aufgeben von Anzeigen. Automatisches
Einloggen oder Ausfüllen verstößt gegen die Nutzungsbedingungen und bricht bei jedem Update.
Die App fasst deshalb den Kleinanzeigen-Login nicht an: Du bleibst in der offiziellen App angemeldet.

Nimmt die Kleinanzeigen-App keine geteilten Fotos an, legt der Inserat-Helfer sie unter
`Bilder/Inserat-Helfer` in die Galerie und öffnet die Kleinanzeigen-App.

## KI-Anbieter und Schlüssel

Jeder Nutzer wählt in den Einstellungen einen Anbieter und trägt seinen eigenen API-Schlüssel ein:

| Anbieter | Anbindung | Vorgabe-Modell |
|---|---|---|
| Claude (Anthropic) | offizielles Anthropic-SDK, festes Antwortschema | `claude-opus-5-5` |
| Mistral | `https://api.mistral.ai/v1/chat/completions` | `mistral-medium-2508` |
| Langdock | `https://api.langdock.com/openai/eu/v1/chat/completions` | `gpt-5-mini` (je nach Workspace) |
| OpenAI | `https://api.openai.com/v1/chat/completions` | `gpt-4o` |
| Anderer Anbieter | jede OpenAI-kompatible Basis-URL | frei wählbar |

Modell und Basis-URL lassen sich je Anbieter ändern; das Modell muss Bilder verstehen.
Die Schlüssel werden je Anbieter mit einem AES-Schlüssel aus dem Android Keystore verschlüsselt
gespeichert, nicht gesichert und nur an den jeweiligen Anbieter gesendet. Es gibt keinen eigenen Server.
Pro Anfrage gehen höchstens 5 Fotos mit, auf 1568 px verkleinert.

## Aufbau

| Modul | Inhalt |
|---|---|
| `core` | Reines Kotlin: gemeinsamer Prompt und Auswertung, Claude über das Anthropic-SDK, weitere Anbieter über OpenAI-kompatibles HTTP. Mit Unit-Tests. |
| `app` | Android (Jetpack Compose, CameraX): Kamera, Einstellungen, Prüfen-Ansicht, Übergabe. |

## Bauen

```
./gradlew :core:test :app:assembleDebug
```

Benötigt JDK 17 und das Android SDK (compileSdk 35). Die GitHub Action baut bei jedem Push eine
Debug-APK und stellt sie als Artefakt bereit.
