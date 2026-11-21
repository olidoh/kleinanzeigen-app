"""Erhoeht versionCode um 1 und die letzte Stelle von versionName (1.0.0 -> 1.0.1) in app/build.gradle.kts.
Optional: --name 1.1.0 setzt versionName explizit. Gibt die neue Version aus."""
import argparse
import pathlib
import re
import sys

p = argparse.ArgumentParser()
p.add_argument("--name", help="versionName explizit setzen, z. B. 1.1.0")
a = p.parse_args()

f = pathlib.Path(__file__).resolve().parent.parent / "app" / "build.gradle.kts"
s = f.read_bytes().decode("utf-8")
CODE = r'^(\s*versionCode\s*=\s*)(\d+)'
NAME = r'^(\s*versionName\s*=\s*")([^"]+)"'
code = int(re.search(CODE, s, re.M).group(2))
name = re.search(NAME, s, re.M).group(2)

neu_code = code + 1
if a.name:
    neu_name = a.name
else:
    teile = name.split(".")
    teile[-1] = str(int(teile[-1]) + 1)
    neu_name = ".".join(teile)
if not re.fullmatch(r"\d+(\.\d+)*", neu_name):
    sys.exit(f"Ungueltiger versionName: {neu_name}")

s = re.sub(CODE, rf"\g<1>{neu_code}", s, count=1, flags=re.M)
s = re.sub(NAME, rf'\g<1>{neu_name}"', s, count=1, flags=re.M)
f.write_bytes(s.encode("utf-8"))
print(f"{name} ({code}) -> {neu_name} ({neu_code})")
print(f"TAG=v{neu_name}")
