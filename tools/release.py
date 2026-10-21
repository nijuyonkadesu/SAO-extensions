#!/usr/bin/env python3
"""Prepare signed development APKs and one catalog; optionally publish with gh. No CI required."""
import argparse
import hashlib
import html
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "nijuyonkadesu/SAO-extensions"
SOURCES = [
    ("immich", "Immich", "Search photos and videos on your Immich servers.", "immich-extension", "immich-sao"),
    ("local", "Local Sticker", "Local folders and selected media with OCR, labels and offline Context search.", "local-extension", "local-sticker"),
    ("moeview", "Moeview", "Browse supported image boards with tag search and suggestions.", "moeview-extension", "moeview-sao"),
    ("findthatmeme", "FindThatMeme", "Find public memes by keyword or caption, including GIFs and videos.", "findthatmeme-extension", "findthatmeme-sao"),
]


def run(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def prepare():
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk"))
    tools = sorted((sdk / "build-tools").glob("*/apksigner"), key=lambda p: tuple(int(n) for n in p.parent.name.split(".") if n.isdigit()))[-1].parent
    output = ROOT / "build/distribution"
    catalog_dir = output / "catalog"
    (catalog_dir / "icons").mkdir(parents=True, exist_ok=True)
    entries = []
    for ident, name, description, module, stem in SOURCES:
        apk_dir = ROOT / module / "build/outputs/apk/release"
        metadata = json.loads((apk_dir / "output-metadata.json").read_text())
        package = metadata["applicationId"]
        assert package == f"one.njk.sao.extension.{ident}", package
        artifacts = []
        version = code = fingerprint = minimum = None
        for element in metadata["elements"]:
            assert version is None or version == element["versionName"]
            version, code = element["versionName"], element["versionCode"]
            abi = next((f["value"] for f in element["filters"] if f["filterType"] == "ABI"), "universal")
            target = output / f"{stem}-{abi}.apk" if abi != "universal" else output / f"{stem}.apk"
            subprocess.run([str(tools / "apksigner"), "sign", "--ks", str(Path.home() / ".android/debug.keystore"),
                            "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android",
                            "--out", str(target), str(apk_dir / element["outputFile"])], check=True)
            certificate = re.search(r"certificate SHA-256 digest: ([0-9a-f]{64})", run(str(tools / "apksigner"), "verify", "--print-certs", str(target))).group(1)
            assert fingerprint is None or fingerprint == certificate
            fingerprint = certificate
            badging = run(str(tools / "aapt2"), "dump", "badging", str(target))
            minimum = int(re.search(r"sdkVersion:'(\d+)'", badging).group(1))
            manifest = run(str(tools / "aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", str(target))
            assert 'one.njk.sao.source.EXTENSION' in manifest and 'one.njk.sao.source.API_VERSION' in manifest
            tag = f"{ident}-v{version}-debug"
            artifacts.append({"abi": abi, "url": f"https://github.com/{REPOSITORY}/releases/download/{tag}/{target.name}",
                              "sha256": hashlib.sha256(target.read_bytes()).hexdigest(), "size": target.stat().st_size})
        icon = ET.parse(ROOT / module / "src/main/res/drawable/ic_extension.xml").getroot()
        android = "{http://schemas.android.com/apk/res/android}"
        paths = ''.join(f'<path fill="{p.get(android + "fillColor")}" d="{p.get(android + "pathData")}"/>' for p in icon)
        (catalog_dir / "icons" / f"{ident}.svg").write_text(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {icon.get(android + "viewportWidth")} {icon.get(android + "viewportHeight")}">{paths}</svg>\n')
        entries.append({"id": ident, "packageName": package, "name": name, "description": description,
                        "versionCode": code, "versionName": version, "apiVersion": 1, "minSdk": minimum,
                        "signerSha256": fingerprint, "channel": "development", "artifacts": artifacts,
                        "iconUrl": f"https://raw.githubusercontent.com/{REPOSITORY}/repo/icons/{ident}.svg",
                        "releaseUrl": f"https://github.com/{REPOSITORY}/releases/tag/{ident}-v{version}-debug"})
    catalog = {"schemaVersion": 1, "name": "SAO extensions", "extensions": entries}
    (catalog_dir / "index.json").write_text(json.dumps(catalog, indent=2) + "\n")
    (catalog_dir / "repo.json").write_text(json.dumps({"name": "SAO extensions", "schemaVersion": 1,
        "index": f"https://raw.githubusercontent.com/{REPOSITORY}/repo/index.json", "website": f"https://github.com/{REPOSITORY}"}, indent=2) + "\n")
    cards = ''.join(f'<li><h2>{html.escape(e["name"])}</h2><p>{html.escape(e["description"])}</p><p>Version {html.escape(e["versionName"])} · Development signing</p>' +
                    ' '.join(f'<a href="{html.escape(a["url"], quote=True)}">Download {a["abi"]}</a>' for a in e["artifacts"]) + '</li>' for e in entries)
    (catalog_dir / "index.html").write_text('<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>SAO extensions</title><style>body{font:1rem system-ui;max-width:52rem;margin:3rem auto;padding:0 1rem}li{margin:2rem 0}a{margin-right:1rem}</style><h1>SAO extensions</h1><p>Independent media sources for SAO. These builds use the existing development signing certificate.</p><ul>' + cards + '</ul><p><a href="https://github.com/nijuyonkadesu/SAO-extensions">Source and build instructions</a></p></html>\n')
    (catalog_dir / "README.md").write_text('# SAO extension catalog\n\nGenerated from tested, manually published APKs. Install/update supported sources inside SAO, or use the [download page](index.html). Development builds retain their existing package IDs and debug signing certificate.\n\nImplementation and publishing instructions: [main branch](https://github.com/nijuyonkadesu/SAO-extensions/tree/main).\n')
    shutil.copyfile(ROOT / "third_party/material-symbols/LICENSE", catalog_dir / "icons/LICENSE")
    return catalog


def publish(catalog):
    commit = run("git", "rev-parse", "HEAD")
    if run("git", "status", "--porcelain"):
        raise RuntimeError("Commit the tested source before publishing")
    for entry in catalog["extensions"]:
        tag = entry["releaseUrl"].rsplit('/', 1)[-1]
        lookup = subprocess.run(["gh", "api", f"repos/{REPOSITORY}/releases/tags/{tag}"], capture_output=True, text=True)
        if lookup.returncode == 0:
            remote = {a["name"]: a.get("digest") for a in json.loads(lookup.stdout)["assets"]}
            for artifact in entry["artifacts"]:
                assert remote.get(artifact["url"].rsplit('/', 1)[-1]) == "sha256:" + artifact["sha256"], "Published version differs; increment the version instead of replacing it"
            continue
        if "404" not in lookup.stderr:
            raise RuntimeError(lookup.stderr)
        notes = ROOT / "build/distribution" / f'{entry["id"]}-notes.md'
        notes.write_text(f'{entry["description"]}\n\nDevelopment-signed optimized APK, built independently against Source SDK 1.0.0. Package identity and existing profiles are preserved. Manual build; CI is deferred.\n')
        subprocess.run(["gh", "release", "create", tag, "--repo", REPOSITORY, "--target", commit, "--prerelease",
                        "--title", f'{entry["name"]} {entry["versionName"]} (development)', "--notes-file", str(notes),
                        *[str(ROOT / "build/distribution" / a["url"].rsplit('/', 1)[-1]) for a in entry["artifacts"]]], check=True)
    print("APKs published. Publish build/distribution/catalog as the repo branch after verifying the URLs.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publish", action="store_true", help="Also publish immutable per-extension releases through gh")
    args = parser.parse_args()
    catalog = prepare()
    if args.publish:
        publish(catalog)
    else:
        print("Prepared APKs and catalog under build/distribution. No uploads performed.")
