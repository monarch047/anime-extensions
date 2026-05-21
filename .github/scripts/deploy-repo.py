#!/usr/bin/env python3
"""Deploy APK + index files to the repo branch."""
import glob
import os
import subprocess
import shutil
import tempfile

# Find APK
apk_files = glob.glob("src/en/streamingunity/build/outputs/apk/debug/*.apk")
if not apk_files:
    print("No APK found!")
    exit(1)

apk_path = apk_files[0]
apk_name = os.path.basename(apk_path)

# Save files before switching branches
tmpdir = tempfile.mkdtemp()
shutil.copy2(apk_path, os.path.join(tmpdir, apk_name))
if os.path.exists("repo-index.json"):
    shutil.copy2("repo-index.json", os.path.join(tmpdir, "index.json"))
if os.path.exists("repo-index.min.json"):
    shutil.copy2("repo-index.min.json", os.path.join(tmpdir, "index.min.json"))

def run(cmd, check=True):
    result = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if check and result.returncode != 0:
        print(f"Error: {result.stderr}")
        exit(result.returncode)
    return result

# Set git config for CI
run("git config user.name 'github-actions[bot]'", check=False)
run("git config user.email 'github-actions[bot]@users.noreply.github.com'", check=False)

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

# Copy saved files
os.makedirs("apk", exist_ok=True)
shutil.copy2(os.path.join(tmpdir, apk_name), f"apk/{apk_name}")
shutil.copy2(os.path.join(tmpdir, "index.json"), "index.json")
shutil.copy2(os.path.join(tmpdir, "index.min.json"), "index.min.json")

shutil.rmtree(tmpdir)

run("git add -A")
run('git commit -m "deploy: StreamingUnity"', check=False)
run("git push origin HEAD:repo --force")

print("Deployed to repo branch")
