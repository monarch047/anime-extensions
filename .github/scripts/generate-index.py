#!/usr/bin/env python3
"""Generate index.json and index.min.json for Aniyomi repo."""
import json
import glob
import os
import re

# Find the built APK
apk_files = glob.glob("src/en/streamingunity/build/outputs/apk/debug/*.apk")
if not apk_files:
    print("No APK found!")
    exit(1)

apk_path = apk_files[0]
apk_name = os.path.basename(apk_path)

# Extract version code from filename: aniyomi-en.streamingunity-v14.X
match = re.search(r'v14\.(\d+)', apk_name)
version_code = int(match.group(1)) if match else 1

index = [
    {
        "name": "Aniyomi: StreamingUnity",
        "pkg": "eu.kanade.tachiyomi.animeextension.en.streamingunity",
        "apk": f"apk/{apk_name}",
        "lang": "en",
        "code": version_code,
        "version": f"14.{version_code}",
        "nsfw": 0,
        "sources": [
            {
                "name": "StreamingUnity",
                "lang": "en",
                "id": "8542735178285060053",
                "baseUrl": "https://streamingunity.dog"
            }
        ]
    }
]

with open("repo-index.json", "w") as f:
    json.dump(index, f, indent=2)

with open("repo-index.min.json", "w") as f:
    json.dump(index, f, separators=(",", ":"))

print(f"Generated index.json for {apk_name} (version 14.{version_code})")
