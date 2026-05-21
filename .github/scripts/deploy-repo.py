#!/usr/bin/env python3
"""Deploy APK + index files to the repo branch."""
import glob
import os
import subprocess
import shutil

# Find APK
apk_files = glob.glob("src/en/streamingunity/build/outputs/apk/release/*.apk")
if not apk_files:
    apk_files = glob.glob("src/en/streamingunity/build/outputs/apk/debug/*.apk")
if not apk_files:
    print("No APK found!")
    exit(1)

apk_path = apk_files[0]

def run(cmd, check=True):
    result = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if check and result.returncode != 0:
        print(f"Error: {result.stderr}")
        exit(result.returncode)
    return result

# Check if repo branch exists remotely
result = run("git rev-parse --verify origin/repo", check=False)
if result.returncode == 0:
    run("git fetch origin repo")
    run("git checkout repo")
else:
    run("git checkout --orphan repo")
    for item in os.listdir("."):
        if item not in (".", "..", ".git"):
            if os.path.isdir(item):
                shutil.rmtree(item)
            else:
                os.remove(item)

# Copy APK
os.makedirs("apk", exist_ok=True)
shutil.copy2(apk_path, "apk/")

# Copy index files
shutil.copy2("repo-index.json", "index.json")
shutil.copy2("repo-index.min.json", "index.min.json")

run("git add -A")
run('git commit -m "deploy: StreamingUnity"', check=False)
run("git push origin repo --force")

print("Deployed to repo branch")
