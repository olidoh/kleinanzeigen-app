"""Laedt ein signiertes APK (Verkora, uebernommen aus easyTDD) ueber die Google Play Developer API hoch und gibt es auf einem Track frei.

Anmeldung: Dienstkonto-JSON in der Umgebungsvariable PLAY_SERVICE_ACCOUNT_JSON (GitHub-Secret).
Bricht ab, wenn der versionCode nicht hoeher ist als alles, was bereits bei Google Play liegt.
"""
import argparse
import json
import os
import sys

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaFileUpload

SCOPE = "https://www.googleapis.com/auth/androidpublisher"


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--package", required=True)
    p.add_argument("--apk", required=True)
    p.add_argument("--version-code", required=True, type=int)
    p.add_argument("--release-name", required=True)
    p.add_argument("--track", default="production")
    a = p.parse_args()

    info = json.loads(os.environ["PLAY_SERVICE_ACCOUNT_JSON"])
    creds = service_account.Credentials.from_service_account_info(info, scopes=[SCOPE])
    api = build("androidpublisher", "v3", credentials=creds, cache_discovery=False)
    edits = api.edits()

    edit_id = edits.insert(packageName=a.package, body={}).execute()["id"]
    try:
        vorhanden = [int(v) for t in edits.tracks().list(packageName=a.package, editId=edit_id).execute().get("tracks", [])
                     for r in t.get("releases", []) for v in r.get("versionCodes", [])]
        hoechste = max(vorhanden, default=0)
        print(f"Hoechster versionCode bei Google Play: {hoechste}, neu: {a.version_code}")
        if a.version_code <= hoechste:
            sys.exit(f"FEHLER: versionCode {a.version_code} ist nicht hoeher als {hoechste}. Version erhoehen.")

        apk = edits.apks().upload(
            packageName=a.package, editId=edit_id,
            media_body=MediaFileUpload(a.apk, mimetype="application/vnd.android.package-archive", resumable=True),
        ).execute()
        print(f"APK hochgeladen: versionCode {apk['versionCode']}, SHA-256 {apk['binary'].get('sha256', '?')}")

        edits.tracks().update(
            packageName=a.package, editId=edit_id, track=a.track,
            body={"track": a.track, "releases": [{
                "name": a.release_name, "versionCodes": [str(a.version_code)], "status": "completed"}]},
        ).execute()

        try:
            edits.commit(packageName=a.package, editId=edit_id).execute()
        except HttpError as e:
            if "changesNotSentForReview" not in str(e):
                raise
            edits.commit(packageName=a.package, editId=edit_id, changesNotSentForReview=True).execute()
        print(f"Freigegeben auf Track '{a.track}': {a.release_name} ({a.version_code})")
    except BaseException:
        try:
            edits.delete(packageName=a.package, editId=edit_id).execute()
        except Exception:
            pass
        raise


if __name__ == "__main__":
    main()
