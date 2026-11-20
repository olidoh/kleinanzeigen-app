# Inserat-Helfer

Android-App, die aus ein paar Fotos eine fertige Kleinanzeige macht:

1. Fotos mit der Kamera aufnehmen oder aus der Galerie wählen.
2. Optional Hinweise ergänzen, die man auf den Fotos nicht sieht (Mängel, Alter, Lieferumfang).
3. Claude schreibt Titel, Beschreibung, Preisvorschlag, Kategorie und Zustand.
4. Alles prüfen und bearbeiten, dann „An Kleinanzeigen übergeben“: Der Text landet in der
   Zwischenablage, die Fotos gehen per Teilen an die offizielle Kleinanzeigen-App.

## Warum Übergabe statt Automatik

Kleinanzeigen bietet Privatnutzern keine Schnittstelle zum Aufgeben von Anzeigen. Automatisches
Einloggen oder Ausfüllen verstößt gegen die Nutzungsbedingungen und bricht bei jedem Update.
Die App fasst deshalb den Kleinanzeigen-Login nicht an: Du bleibst in der offiziellen App angemeldet.

Nimmt die Kleinanzeigen-App keine geteilten Fotos an, legt der Inserat-Helfer sie unter
`Bilder/Inserat-Helfer` in die Galerie und öffnet die Kleinanzeigen-App.

## KI-Schlüssel

Jeder Nutzer trägt beim ersten Start seinen eigenen Claude-API-Schlüssel ein
([console.anthropic.com](https://console.anthropic.com/settings/keys)). Er wird mit einem
AES-Schlüssel aus dem Android Keystore verschlüsselt gespeichert, nicht gesichert und nur an
`api.anthropic.com` gesendet. Es gibt keinen eigenen Server.

Modell: `claude-opus-5-5`, höchstens 5 Fotos pro Anfrage, auf 1568 px verkleinert.

## Aufbau

| Modul | Inhalt |
|---|---|
| `core` | Reines Kotlin: Prompt, Antwortformat (JSON-Schema) und Auswertung über das offizielle Anthropic-SDK. Mit Unit-Tests. |
| `app` | Android (Jetpack Compose, CameraX): Kamera, Einstellungen, Prüfen-Ansicht, Übergabe. |

## Bauen

```
./gradlew :core:test :app:assembleDebug
```

Benötigt JDK 17 und das Android SDK (compileSdk 35). Die GitHub Action baut bei jedem Push eine
Debug-APK und stellt sie als Artefakt bereit.
