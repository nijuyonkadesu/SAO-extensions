#!/usr/bin/env python3
"""Prepare release-signed APKs and their catalog; optionally publish with gh. No CI required."""
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
PUBLISHER_SHA256 = "fd793dd1514b2a5c1d561f27df03ed76d06a94f872627eb7ec4e522871cf4441"
SOURCES = [
    ("immich", "Immich", "Search photos and videos on your Immich servers.", "immich-extension", "immich-sao"),
    ("local", "Local Sticker", "Local folders and selected media with OCR, labels and offline Context search.", "local-extension", "local-sticker"),
    ("moeview", "Moeview", "Browse supported image boards with tag search and suggestions.", "moeview-extension", "moeview-sao"),
    ("findthatmeme", "FindThatMeme", "Find public memes by keyword or caption, including GIFs and videos.", "findthatmeme-extension", "findthatmeme-sao"),
    ("googlephotos", "Google Photos (experimental)", "Google Photos with Vector-assisted connection, Context and Text matches. Native account authentication needs device verification.", "googlephotos-extension", "googlephotos-sao"),
]


def run(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def vector_svg(vector):
    """Export the APK's small path/group vector without a second artwork source."""
    android = "{http://schemas.android.com/apk/res/android}"

    def node(element):
        get = lambda key, default=None: element.get(android + key, default)
        if element.tag == "group":
            px, py = get("pivotX", "0"), get("pivotY", "0")
            transform = (f'translate({get("translateX", "0")} {get("translateY", "0")}) '
                         f'translate({px} {py}) rotate({get("rotation", "0")}) '
                         f'scale({get("scaleX", "1")} {get("scaleY", "1")}) translate({-float(px)} {-float(py)})')
            result = ET.Element("g", {"transform": transform})
            result.extend(node(child) for child in element)
            return result
        if element.tag != "path":
            raise ValueError(f"Unsupported icon vector element: {element.tag}")
        result = ET.Element("path", {"d": get("pathData")})
        for role in ["fill", "stroke"]:
            color = get(role + "Color", "#000000" if role == "fill" else None)
            if color is None:
                continue
            alpha = float(get(role + "Alpha", "1"))
            if re.fullmatch(r"#[0-9a-fA-F]{8}", color):
                alpha *= int(color[1:3], 16) / 255
                color = "#" + color[3:]
            if not re.fullmatch(r"#[0-9a-fA-F]{6}", color):
                raise ValueError("Catalog icons require literal RGB colors")
            result.set(role, color)
            if alpha != 1:
                result.set(role + "-opacity", str(alpha))
        for source, target in [("strokeWidth", "stroke-width"), ("strokeLineCap", "stroke-linecap"), ("strokeLineJoin", "stroke-linejoin")]:
            if get(source) is not None:
                result.set(target, get(source))
        if get("fillType") == "evenOdd":
            result.set("fill-rule", "evenodd")
        return result

    svg = ET.Element("svg", {"xmlns": "http://www.w3.org/2000/svg", "viewBox": f'0 0 {vector.get(android + "viewportWidth")} {vector.get(android + "viewportHeight")}'})
    svg.extend(node(child) for child in vector)
    return ET.tostring(svg, encoding="unicode") + "\n"


def prepare():
    keystore = Path(os.environ["SAO_RELEASE_KEYSTORE"]).expanduser().resolve(strict=True)
    alias = os.environ["SAO_RELEASE_ALIAS"]
    if not os.environ.get("SAO_RELEASE_STORE_PASSWORD"):
        raise RuntimeError("Set SAO_RELEASE_STORE_PASSWORD before signing")
    signing_env = os.environ.copy()
    signing_env.setdefault("SAO_RELEASE_KEY_PASSWORD", signing_env["SAO_RELEASE_STORE_PASSWORD"])
    # Gradle validates inputs for every published module; existing output files alone are not provenance.
    commit = run("git", "rev-parse", "HEAD")
    clean = not run("git", "status", "--porcelain")
    build_tasks = [f":{module}:assembleRelease" for _, _, _, module, _ in SOURCES]
    subprocess.run([str(ROOT / "gradlew"), *build_tasks], cwd=ROOT, check=True)
    if run("git", "rev-parse", "HEAD") != commit:
        raise RuntimeError("Source commit changed while release inputs were built")
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
            subprocess.run([str(tools / "apksigner"), "sign", "--ks", str(keystore),
                            "--ks-key-alias", alias, "--ks-pass", "env:SAO_RELEASE_STORE_PASSWORD",
                            "--key-pass", "env:SAO_RELEASE_KEY_PASSWORD",
                            "--out", str(target), str(apk_dir / element["outputFile"])], env=signing_env, check=True)
            certificate = re.search(r"certificate SHA-256 digest: ([0-9a-f]{64})", run(str(tools / "apksigner"), "verify", "--print-certs", str(target))).group(1)
            if certificate != PUBLISHER_SHA256:
                raise RuntimeError("Signing certificate differs from the official catalog publisher")
            assert fingerprint is None or fingerprint == certificate
            fingerprint = certificate
            badging = run(str(tools / "aapt2"), "dump", "badging", str(target))
            minimum = int(re.search(r"(?:minSdkVersion|sdkVersion):\s*'(\d+)'", badging).group(1))
            manifest = run(str(tools / "aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", str(target))
            assert 'one.njk.sao.source.EXTENSION' in manifest and 'one.njk.sao.source.API_VERSION' in manifest
            tag = f"{ident}-v{version}"
            artifacts.append({"abi": abi, "url": f"https://github.com/{REPOSITORY}/releases/download/{tag}/{target.name}",
                              "sha256": hashlib.sha256(target.read_bytes()).hexdigest(), "size": target.stat().st_size})
        icon = ET.parse(ROOT / module / "src/main/res/drawable/ic_extension.xml").getroot()
        (catalog_dir / "icons" / f"{ident}.svg").write_text(vector_svg(icon))
        entries.append({"id": ident, "packageName": package, "name": name, "description": description,
                        "versionCode": code, "versionName": version, "apiVersion": 1, "minSdk": minimum,
                        "signerSha256": fingerprint, "channel": "stable", "artifacts": artifacts,
                        "iconUrl": f"https://raw.githubusercontent.com/{REPOSITORY}/repo/icons/{ident}.svg",
                        "releaseUrl": f"https://github.com/{REPOSITORY}/releases/tag/{ident}-v{version}"})
    catalog = {"schemaVersion": 1, "name": "SAO extensions", "extensions": entries}
    (catalog_dir / "index.json").write_text(json.dumps(catalog, indent=2) + "\n")
    (output / "build-provenance.json").write_text(json.dumps({
        "commit": commit, "clean": clean and not run("git", "status", "--porcelain"),
        "tasks": build_tasks,
        "artifacts": {a["url"].rsplit('/', 1)[-1]: a["sha256"] for e in entries for a in e["artifacts"]},
    }, indent=2) + "\n")
    (catalog_dir / "repo.json").write_text(json.dumps({"name": "SAO extensions", "schemaVersion": 1,
        "index": f"https://raw.githubusercontent.com/{REPOSITORY}/repo/index.json", "website": f"https://github.com/{REPOSITORY}"}, indent=2) + "\n")
    cards = ''.join(f'<li><img src="icons/{e["id"]}.svg" width="72" height="72" alt=""><h2>{html.escape(e["name"])}</h2><p>{html.escape(e["description"])}</p><p>Version {html.escape(e["versionName"])} · Release signing</p>' +
                    ' '.join(f'<a href="{html.escape(a["url"], quote=True)}">Download {a["abi"]}</a>' for a in e["artifacts"]) + '</li>' for e in entries)
    (catalog_dir / "index.html").write_text('<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>SAO extensions</title><style>body{font:1rem system-ui;max-width:52rem;margin:3rem auto;padding:0 1rem}li{margin:2rem 0}a{margin-right:1rem}</style><h1>SAO extensions</h1><p>Independent, release-signed media sources for SAO.</p><ul>' + cards + '</ul><p><a href="https://github.com/nijuyonkadesu/SAO-extensions">Source and build instructions</a></p></html>\n')
    (catalog_dir / "README.md").write_text('# SAO extension catalog\n\nGenerated from tested, manually published, release-signed APKs. Install/update supported sources inside SAO, or use the [download page](index.html). Package IDs and the official publisher certificate are preserved for in-place updates.\n\nImplementation and publishing instructions: [main branch](https://github.com/nijuyonkadesu/SAO-extensions/tree/main).\n')
    shutil.copyfile(ROOT / "LICENSE", catalog_dir / "icons/LICENSE")
    (catalog_dir / "icons/NOTICE.md").write_text("SAO extension artwork is distributed under AGPL-3.0. Local Sticker, Moeview and FindThatMeme use original SAO artwork. Immich adapts the upstream Immich flower with a companion insert badge. Source, modifications and attribution: https://github.com/nijuyonkadesu/SAO-extensions/tree/main/third_party/immich\n")
    return catalog


def publish(catalog, notes_dir=None):
    commit = run("git", "rev-parse", "HEAD")
    if run("git", "status", "--porcelain"):
        raise RuntimeError("Commit the tested source before publishing")
    provenance = json.loads((ROOT / "build/distribution/build-provenance.json").read_text())
    if provenance["commit"] != commit or not provenance["clean"]:
        raise RuntimeError("Rebuild release inputs from the clean commit being published")
    if notes_dir and any(not (notes_dir / f'{entry["id"]}.md').is_file() for entry in catalog["extensions"]):
        raise RuntimeError("Provide release notes for every source before publishing")
    for entry in catalog["extensions"]:
        for artifact in entry["artifacts"]:
            name = artifact["url"].rsplit('/', 1)[-1]
            expected = artifact["sha256"]
            if provenance["artifacts"].get(name) != expected or hashlib.sha256((ROOT / "build/distribution" / name).read_bytes()).hexdigest() != expected:
                raise RuntimeError(f"Release artifact changed after its validated build: {name}")
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
        source_notes = notes_dir / f'{entry["id"]}.md' if notes_dir else None
        notes.write_text(source_notes.read_text() if source_notes else
            f'{entry["description"]}\n\nRelease-signed optimized APK, built from [{commit[:7]}](https://github.com/{REPOSITORY}/commit/{commit}). Package identity and existing profiles are preserved. Manual build; CI is deferred.\n')
        subprocess.run(["gh", "release", "create", tag, "--repo", REPOSITORY, "--target", commit,
                        "--title", f'{entry["name"]} {entry["versionName"]}', "--notes-file", str(notes),
                        *[str(ROOT / "build/distribution" / a["url"].rsplit('/', 1)[-1]) for a in entry["artifacts"]]], check=True)
    print("APKs published. Publish build/distribution/catalog as the repo branch after verifying the URLs.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publish", action="store_true", help="Also publish immutable per-extension releases through gh")
    parser.add_argument("--notes-dir", type=Path, help="Release notes directory containing <extension-id>.md for each source")
    args = parser.parse_args()
    catalog = prepare()
    if args.publish:
        publish(catalog, args.notes_dir)
    else:
        print("Prepared APKs and catalog under build/distribution. No uploads performed.")
