# Hinweise für Claude

## Veröffentlichen der internen App (Intune / Managed Google Play)
- Ablauf und feste Werte: `docs/Veroeffentlichung.md`. Nur diesen Weg nutzen: `python tools/version_erhoehen.py` → Commit → Push auf `main` → Workflow `release-intern.yml` per API starten (`upload=true`, `track=production`). Tags nicht selbst anlegen, das macht der Workflow.
- Niemals ändern: `applicationId`/`applicationIdSuffix`, `signingConfigs`, `EXPECTED_CERT_SHA256` und `PACKAGE_NAME` im Workflow.
- Das Repo ist öffentlich: niemals Keystore (`*.jks`), Passwörter oder Dienstkonto-JSON committen oder ausgeben.
- Schlägt die Signaturprüfung fehl: abbrechen und dem Nutzer melden, nichts „reparieren“.
- Play App Signing nicht einschalten.
- Bringt eine Version neue Android-Berechtigungen mit: Nutzer daran erinnern, die App in Managed Google Play neu zu genehmigen.
