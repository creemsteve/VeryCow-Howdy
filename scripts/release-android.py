"""Publish the verified APK as a GitHub prerelease at an explicit clean commit.

Uses GITHUB_TOKEN, or Git's existing credential helper. Credentials are never printed.
"""
import argparse
import hashlib
import http.client
import json
import os
import pathlib
import subprocess
import urllib.error
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--commit", required=True)
parser.add_argument("--tag", default="v0.2.1-sonim")
parser.add_argument("--repository", default="creemsteve/VeryCow-Howdy")
parser.add_argument("--apk", default="releases/v0.2.1-sonim/VCH-Flip-0.2.1-release.apk")
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parent.parent
os.chdir(root)
if subprocess.check_output(["git", "status", "--porcelain"], text=True).strip():
    raise SystemExit("Commit the complete source before publishing.")
if subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() != args.commit:
    raise SystemExit("Release commit does not match the current checkout.")
apk = pathlib.Path(args.apk)
digest = hashlib.file_digest(apk.open("rb"), "sha256").hexdigest()
checksum = pathlib.Path(str(apk) + ".sha256")
if checksum.read_text().split()[0].lower() != digest:
    raise SystemExit("APK checksum mismatch.")
report = json.loads((root / "build/android-verification/package.json").read_text())
if report["sha256"] != digest or report["build"]["commit"] != args.commit or report["build"]["dirty"]:
    raise SystemExit("Verify an APK built from the release commit before publishing.")
token = os.environ.get("GITHUB_TOKEN")
if not token:
    credentials = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n", text=True, capture_output=True, check=True)
    token = dict(line.split("=", 1) for line in credentials.stdout.splitlines() if "=" in line).get("password")
if not token:
    raise SystemExit("No GitHub credential is available.")
headers = {"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "VCH-Android-Release"}


def api(method, path, data=None):
    request = urllib.request.Request("https://api.github.com" + path, data=None if data is None else json.dumps(data).encode(), method=method, headers={**headers, "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=90) as response:
        return json.load(response)


base = "/repos/" + args.repository
remote = api("GET", base + "/commits/" + args.commit)
if remote["sha"] != args.commit:
    raise SystemExit("The requested commit is not available on GitHub.")
try:
    api("GET", base + "/releases/tags/" + urllib.parse.quote(args.tag, safe=""))
except urllib.error.HTTPError as error:
    if error.code != 404:
        raise
else:
    raise SystemExit("This release tag already has a release. No existing assets were modified.")
notes = (root / "docs/RELEASE-0.2.1-SONIM.md").read_text(encoding="utf-8")
notes = notes.replace("(ANDROID.md)", f"(https://github.com/{args.repository}/blob/{args.commit}/docs/ANDROID.md)")
release = api("POST", base + "/releases", {"tag_name": args.tag, "target_commitish": args.commit, "name": "VCH Flip 0.2.1 — Sonim XP3plus 5G preview", "body": notes, "draft": True, "prerelease": True})
assets = []
for file, content_type in ((apk, "application/vnd.android.package-archive"), (checksum, "text/plain")):
    connection = http.client.HTTPSConnection("uploads.github.com", timeout=180)
    path = base + f"/releases/{release['id']}/assets?" + urllib.parse.urlencode({"name": file.name})
    connection.putrequest("POST", path)
    for key, value in {**headers, "Content-Type": content_type, "Content-Length": str(file.stat().st_size)}.items():
        connection.putheader(key, value)
    connection.endheaders()
    with file.open("rb") as source:
        for chunk in iter(lambda: source.read(4 * 1024 * 1024), b""):
            connection.send(chunk)
    response = connection.getresponse()
    payload = response.read()
    if response.status != 201:
        raise RuntimeError(f"Asset upload failed ({response.status}); release remains a draft.")
    asset = json.loads(payload)
    expected = hashlib.file_digest(file.open("rb"), "sha256").hexdigest()
    if asset["size"] != file.stat().st_size or (asset.get("digest") and asset["digest"] != "sha256:" + expected):
        raise RuntimeError("Uploaded asset integrity mismatch; release remains a draft.")
    assets.append({"name": asset["name"], "size": asset["size"], "url": asset["browser_download_url"]})
    connection.close()
published = api("PATCH", base + f"/releases/{release['id']}", {"draft": False, "prerelease": True})
print(json.dumps({"url": published["html_url"], "tag": args.tag, "commit": args.commit, "assets": assets}, indent=2))
