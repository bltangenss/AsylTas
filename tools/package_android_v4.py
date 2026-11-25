"""Audit/package the finalized Android 4.0 release without building or using a device.

--audit-sources is safe before final QA: it inventories sources/photos only.
Packaging requires the Android QA ready marker and independently checks its evidence.
The script never edits project documentation or includes local signing/runtime data.
"""
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import json
import os
import re
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
QA = ROOT / "reports/qa_android_v4_20261125"
CORE_QA = ROOT / "reports/qa_v4_20261125"
PHOTO_QA = ROOT / "reports/qa_assets_v4_20261125"
OUT = ROOT / "deliverables"
APK = OUT / "AsylTas-4.0.apk"
MARKER = QA / "ANDROID_READY_FOR_PACKAGING.json"
SHOT_DIR = ROOT / "screenshots/redesign_20261124/v4_20261125"
DOC_NAMES = ("README_KZ.txt", "START_HERE.txt", "TEST_RESULTS.txt", "LAB_FEATURES_KZ.txt", "PHOTO_CREDITS.txt")
SOURCE_TREES = {
    "shared/src": {".java"},
    "shared/catalog": {".json"},
    "android/app/src/main": {".java", ".xml", ".json", ".txt", ".jpg", ".jpeg", ".png", ".webp"},
    "android/app/src/androidTest": {".java", ".xml", ".json", ".txt"},
}
SOURCE_EXACT = (
    "android/build.gradle.kts", "android/settings.gradle.kts", "android/gradle.properties",
    "android/gradlew", "android/gradlew.bat", "android/app/build.gradle.kts",
    "android/gradle/wrapper/gradle-wrapper.jar", "android/gradle/wrapper/gradle-wrapper.properties",
    "tests/ModelTest.java", "tests/CatalogExpansionTest.java",
    # ModelTest imports only the legacy local store, not the desktop portal.
    "desktop/src/kz/asyltas/desktop/LocalStore.java",
    "tools/build_v4.ps1", "tools/verify_android_v4.py", "tools/qa_v4_core.py",
    "tools/catalog_v4_data.py", "tools/fetch_catalog_v4_photos.py", "tools/package_android_v4.py",
    "tools/qa_v4_ui.py", "tools/android_labs_ui.py",
)
PRIVATE_SUFFIXES = {
    ".p12", ".pfx", ".jks", ".keystore", ".pem", ".key", ".env", ".db", ".sqlite", ".sqlite3",
    ".exe", ".dll", ".class", ".pyc", ".apk", ".zip", ".mp3", ".mp4", ".wav", ".m4a", ".webm",
}
PRIVATE_DIRS = {
    ".git", ".gradle", ".idea", ".cxx", ".externalnativebuild", "__pycache__", "node_modules",
    "build", "cache", "caches", "media", "data", "db", "databases", "uploads", "recordings",
}
TEXT_SUFFIXES = {".java", ".xml", ".json", ".txt", ".py", ".ps1", ".kts", ".properties", ".bat", ".pro"}
REQUIRED_QA = (
    "ANDROID_READY_FOR_PACKAGING.json", "artifact_verification.json", "ui_checks.json",
    "lint_summary.json", "lint-results-debug.xml", "signature.txt", "apk_badging.txt",
)
# An explicit list prevents installed APKs, fixture databases, diagnostic captures,
# arbitrary logs and old reports from leaking into the release bundle.
OPTIONAL_QA = (
    "build_release.txt", "build_test.txt", "compile_lint.txt", "recent_crash_log.txt",
    "ui_begin.txt", "ui_cleanup.txt", "post_run_state.json", "source_hashes.json",
    "model_hashes.json", "photo_assets.json", "catalog_checks.json", "portal_checks.json",
)
PORTAL_REPORT_NAMES = ("portal_checks.txt", "database_checks.txt", "instrumentation_portal.txt")
CATALOG_REPORT_NAMES = ("catalog_checks.txt", "v4_checks.txt", "instrumentation_v4.txt")
HASH_RE = re.compile(r"[0-9a-f]{64}")
SECRET_PATTERNS = (
    ("literal apksigner password", re.compile(r"--(?:ks|key)-pass[\"']?(?:[\s,]+|=)[\"']?pass:[^\s\"']+", re.I)),
    ("literal Gradle signing password", re.compile(r"\b(?:storePassword|keyPassword)\s*(?:=\s*|\(\s*|\s+)[\"'][^\"'\r\n]+[\"']", re.I)),
    ("literal signing environment password", re.compile(r"(?:\$env:)?ASYL_(?:STORE|KEY)_PASSWORD\s*=\s*[\"'][^\"'\r\n]+[\"']", re.I)),
    ("literal injected Gradle signing password", re.compile(r"(?m)^\s*android\.injected\.signing\.(?:store|key)\.password\s*=\s*\S+", re.I)),
    ("private key block", re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----")),
)


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def sha_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def rel(path):
    return path.resolve().relative_to(ROOT.resolve()).as_posix()


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def record(path):
    return {"path": rel(path), "bytes": path.stat().st_size, "sha256": sha(path)}


def checked_file(path):
    path = Path(path)
    require(path.is_file(), f"Missing release input: {path}")
    require(path.resolve().is_relative_to(ROOT.resolve()), f"Input outside project: {path}")
    for part in (path, *path.parents):
        if part == ROOT:
            break
        require(not part.is_symlink() and not part.is_junction(), f"Link/junction input is not permitted: {path}")
    return path


def safe_zip_name(name):
    require(isinstance(name, str) and name and "\\" not in name and not name.startswith("/"), "Unsafe ZIP path")
    parts = name.split("/")
    require(all(p not in ("", ".", "..") and not re.search(r'[<>:"|?*\x00-\x1f]', p) and not p.endswith((" ", ".")) for p in parts), f"Unsafe ZIP path: {name}")


def private_source_name(name):
    path = PurePosixPath(name.replace("\\", "/"))
    leaf = path.name.casefold()
    return (any(part.casefold() in PRIVATE_DIRS for part in path.parts)
            or path.suffix.casefold() in PRIVATE_SUFFIXES
            or leaf == "local.properties" or leaf.startswith((".env", ".dev.vars"))
            or leaf.endswith(("-wal", "-shm"))
            or leaf in {"credentials.json", "secrets.json", "service-account.json"})


def secret_findings(text, label):
    """Report only locations/kinds, never secret values. Ordinary test passwords are valid."""
    findings = []
    for kind, pattern in SECRET_PATTERNS:
        for match in pattern.finditer(text):
            findings.append({"path": label, "line": text.count("\n", 0, match.start()) + 1, "kind": kind})
    return findings


def scan_secrets(files):
    findings = []
    for path in files:
        if path.suffix.casefold() in TEXT_SUFFIXES or path.name == "gradlew":
            findings.extend(secret_findings(path.read_text(encoding="utf-8-sig"), rel(path)))
    require(not findings, "Signing/private key content detected (values redacted): " + json.dumps(findings, ensure_ascii=False))
    return {"files_scanned": sum(p.suffix.casefold() in TEXT_SUFFIXES or p.name == "gradlew" for p in files), "findings": 0}


def source_inventory():
    files = {ROOT / name for name in (*DOC_NAMES, *SOURCE_EXACT)}
    excluded = []
    for name, suffixes in SOURCE_TREES.items():
        folder = ROOT / name
        require(folder.is_dir() and folder.resolve().is_relative_to(ROOT.resolve()), f"Missing/unsafe source directory: {folder}")
        require(not folder.is_symlink() and not folder.is_junction(), f"Linked source directory: {folder}")
        for current, directories, names in os.walk(folder, followlinks=False):
            for subdir in list(directories):
                candidate = Path(current) / subdir
                if subdir.casefold() in PRIVATE_DIRS:
                    directories.remove(subdir)
                    excluded.append(candidate.relative_to(ROOT).as_posix() + "/")
                else:
                    require(not candidate.is_symlink() and not candidate.is_junction(), f"Linked source directory: {candidate}")
            for filename in names:
                path = Path(current) / filename
                name_in_project = path.relative_to(ROOT).as_posix()
                if private_source_name(name_in_project) or path.suffix.casefold() not in suffixes:
                    excluded.append(name_in_project)
                    continue
                files.add(path)
    for path in files:
        checked_file(path)
        require(not private_source_name(rel(path)), f"Private file selected: {rel(path)}")
        require(not rel(path).startswith("site_online/"), "Online source selected for Android release")
    ordered = sorted(files, key=rel)
    require(len({rel(p).casefold() for p in ordered}) == len(ordered), "Case-insensitive source name collision")
    return ordered, excluded, scan_secrets(ordered)


def photo_audit(require_ready=False):
    manifest_paths = (
        ROOT / "android/app/src/main/assets/photo_credits.json",
        ROOT / "shared/catalog/photo_credits_v4.json",
    )
    credits = read_json(checked_file(manifest_paths[0]))
    require([r.get("id") for r in credits] == list(range(1, 49)), "Photo credits must contain IDs 1–48 exactly once")
    require(manifest_paths[0].read_bytes() == checked_file(manifest_paths[1]).read_bytes(), "Shared and Android photo manifests differ")
    catalog_file = ROOT / "shared/catalog/catalog_v4.json"
    catalog = read_json(checked_file(catalog_file))
    require([r.get("id") for r in catalog] == list(range(1, 49)), "Catalog must contain IDs 1–48")
    require(read_json(checked_file(ROOT / "android/app/src/main/assets/catalog_v4.json")) == catalog, "Shared and Android catalog differ")
    notices = [ROOT / "PHOTO_CREDITS.txt", ROOT / "android/app/src/main/assets/PHOTO_CREDITS.txt"]
    require(checked_file(notices[0]).read_bytes() == checked_file(notices[1]).read_bytes(), "Android photo notices differ")
    notice = notices[0].read_text(encoding="utf-8-sig")
    online = ROOT / "site_online/public/assets"
    online_present = online.is_dir()
    if online_present:
        require(checked_file(online / "photo_credits.json").read_bytes() == manifest_paths[0].read_bytes(), "Online photo credits differ")
        require(read_json(checked_file(online / "catalog.json")) == catalog, "Online catalog differs")
    for credit, item in zip(credits, catalog):
        sid = credit["id"]
        require(credit["resource"] == f"stone_{sid}.jpg" and item["photo"] == f"/assets/stone_{sid}.jpg", f"Photo/catalog resource mismatch: {sid}")
        path = checked_file(ROOT / "android/app/src/main/res/drawable-nodpi" / credit["resource"])
        data = path.read_bytes()
        require(data.startswith(b"\xff\xd8\xff") and sha_bytes(data) == credit["sha256"], f"JPEG/hash mismatch: {sid}")
        require(credit["source"].startswith("https://commons.wikimedia.org/wiki/File:") and (credit["license"].startswith("CC BY") or credit["license"] in ("Public domain", "CC0")), f"Missing source/permitted license: {sid}")
        if sid > 12:
            require(credit.get("author") and credit.get("license_url"), f"Missing new photo attribution: {sid}")
        require(all(credit[key] in notice for key in ("sha256", "source", "license")), f"Photo notice mismatch: {sid}")
        if online_present:
            require(checked_file(online / credit["resource"]).read_bytes() == data, f"Online photo differs: {sid}")
    ready_path = PHOTO_QA / "PHOTOS_READY.json"
    if require_ready or ready_path.exists():
        ready = read_json(checked_file(ready_path))
        require(ready.get("status") == "passed", "Photo readiness audit did not pass")
        counts = ready.get("verification_counts", {})
        require(all(counts.get(key) == 48 for key in ("photos", "valid_jpegs", "sha256_matches", "android_online_byte_matches", "permitted_licenses")), "Photo readiness counts disagree")
        require(counts.get("original_photos_preserved") == 12 and counts.get("original_credit_records_preserved") == 12 and counts.get("visually_inspected_new_photos") == 36, "Photo preservation/visual inspection is incomplete")
        require({r["id"]: r["sha256"] for r in ready["photos"]} == {r["id"]: r["sha256"] for r in credits}, "Photo readiness hashes are stale")
        for entry in ready.get("credit_manifests", []):
            path = ROOT / entry["path"]
            if path.is_file():
                require(sha(checked_file(path)) == entry["sha256"], "Photo manifest hash is stale")
    return {"credits": credits, "catalog_sha256": sha(catalog_file), "credits_sha256": sha(manifest_paths[0]), "online_source_audited": online_present}


def check_core(marker):
    path = checked_file(CORE_QA / "core_checks.json")
    core = read_json(path)
    require(core.get("status") == "passed" and core.get("java_checks") == {"ModelTest": 43, "CatalogExpansionTest": 344}, "Core tests must show 43 + 344 passed checks")
    require(marker.get("model_checks") == sum(core["java_checks"].values()) == 387, "Marker model check count disagrees with core evidence")
    require(core.get("catalog_records") == 48 and core.get("preserved_original_ids") == 12, "Core catalog preservation evidence is incomplete")
    require(core["catalog_sha256"] == sha(ROOT / "shared/catalog/catalog_v4.json"), "Core catalog test hash is stale")
    require(core.get("model_changes") == ["shared/src/kz/asyltas/model/Catalog.java"] and core.get("unchanged_other_model_classes") == 6, "Unexpected shared model changes")
    baseline = read_json(checked_file(ROOT / "shared/catalog/model_baseline_sha256.json"))
    require(len(baseline) == 7, "Expected seven model baseline hashes")
    changed = [r["path"] for r in baseline if sha(checked_file(ROOT / r["path"])) != r["sha256"]]
    require(changed == ["shared/src/kz/asyltas/model/Catalog.java"], "Six stable model classes changed")
    return core, path


def successful_instrumentation(names, expression, expected, label):
    for name in names:
        path = QA / name
        if not path.is_file():
            continue
        checked_file(path)
        text = path.read_text(encoding="utf-8-sig")
        matches = [int(value) for value in re.findall(expression, text, re.I)]
        failed = re.search(r"(?m)^\s*(?:FAIL(?:ED)?\b|INSTRUMENTATION_FAILED\b|FAILURES!!!)", text)
        if matches and matches[-1] == expected and not failed:
            return path
    raise RuntimeError(f"Missing successful {label} evidence with exactly {expected} checks; expected one of {', '.join(names)}")


def selected_screenshots(ui):
    rows, excluded = [], []
    for item in ui.get("screenshots", []):
        raw_path = item.get("path", "").replace("\\", "/")
        name = item.get("name", PurePosixPath(raw_path).name).casefold()
        if item.get("diagnostic") or any(word in name or word in raw_path.casefold() for word in ("inspect", "preview", "diagnostic", "working_screenshots")):
            excluded.append(raw_path)
            continue
        safe_zip_name(raw_path)
        path = checked_file(ROOT / raw_path)
        require(path.resolve().is_relative_to(SHOT_DIR.resolve()) and path.suffix.casefold() == ".png" and path.name.startswith("android_"), f"Screenshot outside the Android 4.0 release folder/type: {raw_path}")
        require(HASH_RE.fullmatch(item.get("sha256", "")) and sha(path) == item["sha256"], f"Screenshot hash mismatch: {raw_path}")
        require(HASH_RE.fullmatch(item.get("apk_sha256", "")), f"Screenshot lacks APK provenance: {raw_path}")
        rows.append({"path": rel(path), "sha256": item["sha256"], "apk_sha256": item["apk_sha256"]})
    require(rows and len({r["path"].casefold() for r in rows}) == len(rows), "Missing/duplicate final screenshots")
    return rows, excluded


def apk_audit(credits):
    photos = []
    with zipfile.ZipFile(checked_file(APK)) as zipped:
        require(zipped.testzip() is None, "APK CRC check failed")
        names = zipped.namelist()
        # AAPT resource aliases are case-sensitive (e.g. res/Aa.xml vs res/aa.xml).
        # The APK stays intact in the bundle; only exact APK entry duplicates are invalid.
        require(len(set(names)) == len(names), "Duplicate APK ZIP entries")
        for asset in ("catalog_v4.json", "campus_places.json", "photo_credits.json", "PHOTO_CREDITS.txt"):
            require(zipped.read("assets/" + asset) == checked_file(ROOT / "android/app/src/main/assets" / asset).read_bytes(), f"APK asset differs from source: {asset}")
        hashes = {name: sha_bytes(zipped.read(name)) for name in names if name.startswith("res/") and name.casefold().endswith((".jpg", ".jpeg"))}
        for row in credits:
            matches = [name for name, value in hashes.items() if value == row["sha256"]]
            require(len(matches) == 1, f"APK photo missing/different/duplicated: {row['resource']}")
            source = ROOT / "android/app/src/main/res/drawable-nodpi" / row["resource"]
            require(zipped.read(matches[0]) == source.read_bytes(), f"APK photo bytes differ: {row['resource']}")
            photos.append({"id": row["id"], "source_resource": row["resource"], "apk_entry": matches[0], "sha256": row["sha256"]})
    return photos


def final_inputs():
    require(MARKER.is_file(), "Waiting for ANDROID_READY_FOR_PACKAGING.json; no release archives were written")
    marker = read_json(checked_file(MARKER))
    require(marker.get("status") == "ready", "Android ready marker is not ready; no release archives were written")
    evidence = [checked_file(QA / name) for name in REQUIRED_QA]
    verification = read_json(QA / "artifact_verification.json")
    ui = read_json(QA / "ui_checks.json")
    apk_hash = sha(checked_file(APK))
    require(HASH_RE.fullmatch(marker.get("apk_sha256", "")), "Ready marker lacks APK SHA256")
    require(apk_hash == marker["apk_sha256"] == verification.get("apk_sha256") == ui.get("apk_sha256"), "APK hash disagrees with final evidence")
    require(verification.get("status") == "passed" and verification.get("installed_apk_matches") is True, "Installed APK verification did not pass")
    require((verification.get("version"), verification.get("version_code"), verification.get("min_sdk"), verification.get("target_sdk")) == ("4.0", 6, 24, 35), "Unexpected final APK version/SDK")
    require(verification.get("photo_count") == verification.get("catalog_rows") == 48, "Artifact audit does not cover 48 catalog rows/photos")
    if "recent_fatal_exceptions" in verification:
        require(verification["recent_fatal_exceptions"] == 0, "Final runtime audit reports crashes")
    require(ui.get("status") == "passed" and ui.get("checks") and all(c.get("passed") is True for c in ui["checks"]), "UI checks are not all passed")
    require(type(marker.get("ui_checks")) is int and marker["ui_checks"] == len(ui["checks"]), "Marker/UI count mismatch")
    for item in ui["checks"]:
        require(HASH_RE.fullmatch(item.get("apk_sha256", "")), "UI check lacks APK provenance")
    ui_builds = dict(Counter(c["apk_sha256"] for c in ui["checks"]))
    require(ui_builds.get(apk_hash, 0) > 0, "No UI checks ran on the final APK")
    require(marker.get("portal_checks") == 92, "Marker must record the 92 Portal checks")
    catalog_count = marker.get("catalog_checks")
    require(type(catalog_count) is int and catalog_count > 0, "Marker lacks the actual catalog instrumentation check count")
    portal_report = successful_instrumentation(PORTAL_REPORT_NAMES, r"PASS:\s*(\d+)\s+portal checks", marker["portal_checks"], "Portal SQLite")
    catalog_report = successful_instrumentation(CATALOG_REPORT_NAMES, r"PASS:\s*(\d+)\s+(?:v4|catalog(?:ue)?) checks", catalog_count, "catalog/GPS")
    evidence.extend((portal_report, catalog_report))
    core, core_path = check_core(marker)
    evidence.append(core_path)
    lint = read_json(QA / "lint_summary.json")
    require(lint.get("Error") == lint.get("Fatal") == 0, "Final lint contains errors/fatal issues")
    require(all(verification.get("lint", {}).get(k) == lint.get(k) for k in ("Error", "Fatal", "Warning")), "Artifact and final lint summaries differ")
    badging = (QA / "apk_badging.txt").read_text(encoding="utf-8-sig")
    require(all(value in badging for value in ("versionCode='6'", "versionName='4.0'", "sdkVersion:'24'", "targetSdkVersion:'35'")), "APK metadata evidence is inconsistent")
    require("android.permission.ACCESS_BACKGROUND_LOCATION" not in badging, "Unexpected background location permission")
    for name in OPTIONAL_QA:
        path = QA / name
        if path.is_file():
            evidence.append(checked_file(path))
    post_run_path = QA / "post_run_state.json"
    if post_run_path.is_file():
        post_run = read_json(post_run_path)
        require(post_run.get("status") == "passed" and post_run.get("apk_sha256") == apk_hash, "Final cleanup state is stale or failed")
        for key in ("qa_accounts_removed", "original_guest_necklace_preserved", "store_settings_restored", "display_restored", "temporary_media_files_removed"):
            if key in post_run:
                require(post_run[key] is True, f"Incomplete QA restoration: {key}")
    photos = photo_audit(require_ready=True)
    evidence.append(PHOTO_QA / "PHOTOS_READY.json")
    for name in ("photo_checks.json", "attribution_source_wikitext.json"):
        if (PHOTO_QA / name).is_file():
            evidence.append(checked_file(PHOTO_QA / name))
    screenshots, excluded_shots = selected_screenshots(ui)
    photo_entries = apk_audit(photos["credits"])
    scan_secrets(evidence)
    return {"marker": marker, "ui": ui, "verification": verification, "lint": lint, "core": core,
            "photos": photos, "apk_photo_entries": photo_entries, "screenshots": screenshots,
            "excluded_screenshots": excluded_shots, "evidence": sorted(set(evidence), key=rel), "ui_builds": ui_builds}


def verify_zip(zipped, names, loader):
    """CRC, safe/unique entry names and exact bytes; loader also supports in-memory tests."""
    actual = zipped.namelist()
    for name in actual:
        safe_zip_name(name)
    require(len({n.casefold() for n in actual}) == len(actual), "Duplicate ZIP entries")
    require(actual == names, "ZIP entry inventory differs from the snapshot")
    require(zipped.testzip() is None, "ZIP CRC check failed")
    for name in names:
        require(zipped.read(name) == loader(name), f"ZIP bytes differ: {name}")
    return {"entries": len(names), "crc_passed": True, "duplicate_entries": 0, "byte_checks": len(names)}


def archive(path, entries, expected_hashes=None):
    entries = sorted(entries, key=lambda pair: pair[1])
    names = [name for _, name in entries]
    require(len({name.casefold() for name in names}) == len(names), f"Duplicate requested ZIP entries: {path.name}")
    for name in names:
        safe_zip_name(name)
    files = {name: checked_file(file) for file, name in entries}
    snapshots = {name: sha(file) for name, file in files.items()}
    if expected_hashes is not None:
        require(snapshots == expected_hashes, "Source changed before ZIP creation")
    temporary = QA / ("packaging_" + path.name + ".tmp")
    def loader(name):
        data = files[name].read_bytes()
        require(sha_bytes(data) == snapshots[name], f"Input changed during packaging: {rel(files[name])}")
        return data
    try:
        with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zipped:
            for name, file in files.items():
                zipped.write(file, name)
        with zipfile.ZipFile(temporary) as zipped:
            validation = verify_zip(zipped, names, loader)
        require(all(sha(file) == snapshots[name] for name, file in files.items()), "Release input changed after ZIP verification")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)
    return validation


def package():
    final = final_inputs()  # The ready gate precedes all archive/output creation.
    files, excluded_sources, secret_audit = source_inventory()
    source_records = [record(p) for p in files]
    source_hashes = {"AsylTas/" + row["path"]: row["sha256"] for row in source_records}
    marker = final["marker"]
    verification = final["verification"]
    counts = {"model": marker["model_checks"], "portal": marker["portal_checks"],
              "catalog": marker["catalog_checks"], "ui": marker["ui_checks"],
              "ui_on_final_apk": final["ui_builds"][verification["apk_sha256"]], "photos": 48}
    input_snapshot = {rel(p): sha(p) for p in files + final["evidence"] + [APK] + [ROOT / row["path"] for row in final["screenshots"]]}
    QA.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    source = OUT / "AsylTas-Android-source-4.0.zip"
    source_validation = archive(source, [(p, "AsylTas/" + rel(p)) for p in files], source_hashes)
    with zipfile.ZipFile(source) as zipped:
        require(not any(private_source_name(n) for n in zipped.namelist()), "Private/runtime file present in source ZIP")
        for row in final["photos"]["credits"]:
            name = "AsylTas/android/app/src/main/res/drawable-nodpi/" + row["resource"]
            require(sha_bytes(zipped.read(name)) == row["sha256"], "Source archive photo mismatch")
    require(all(sha(ROOT / name) == value for name, value in input_snapshot.items()), "Final inputs changed after source packaging")
    manifest = {
        "version": "4.0", "platform": "Android", "created_utc": datetime.now(timezone.utc).isoformat(),
        "checks": counts, "model_test_checks": final["core"]["java_checks"], "lint": final["lint"],
        "model_checks": marker["model_checks"], "portal_checks": marker["portal_checks"],
        "catalog_checks": marker["catalog_checks"], "ui_checks": marker["ui_checks"],
        "artifacts": [record(APK), record(source)], "sources": source_records, "source_files": len(files),
        "source_inventory_sha256": sha_bytes(json.dumps(source_records, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")),
        "screenshots": final["screenshots"], "ui_checks_by_apk_sha256": final["ui_builds"],
        "evidence": [record(p) for p in final["evidence"]], "apk_photo_entries": final["apk_photo_entries"],
        "catalog_sha256": final["photos"]["catalog_sha256"], "photo_credits_sha256": final["photos"]["credits_sha256"],
        "licensed_photos": 48, "original_photo_ids_preserved": 12, "stable_model_classes": 6,
        "source_archive": source_validation, "secret_scan": secret_audit,
        "excluded_sources": excluded_sources, "excluded_screenshots": final["excluded_screenshots"],
        "bundle_checksum_location": "deliverables/SHA256SUMS_Android_4.0.txt",
        "scope": "Android only; no signing keys, env/local.properties, build/cache, runtime media/database, Windows EXE or online source",
    }
    manifest_path = QA / "release_manifest.json"
    write_json(manifest_path, manifest)
    bundled = [APK, source, manifest_path] + [ROOT / name for name in DOC_NAMES] + final["evidence"] + [ROOT / row["path"] for row in final["screenshots"]]
    require(len(bundled) == len(set(bundled)), "Duplicate bundle input")
    sums = QA / "packaging_contents_SHA256.txt"
    sums.write_text("".join(sha(p) + "  " + rel(p) + "\n" for p in sorted(bundled, key=rel)), encoding="utf-8")
    bundle = OUT / "AsylTas-Android-4.0-tolyk-jinaq.zip"
    prefix = "AsylTas-Android-4.0/"
    bundle_validation = archive(bundle, [(p, prefix + rel(p)) for p in bundled] + [(sums, prefix + "CONTENTS_SHA256.txt")])
    with zipfile.ZipFile(bundle) as zipped:
        require(zipped.read(prefix + rel(APK)) == APK.read_bytes(), "Nested APK is not byte-identical")
        require(zipped.read(prefix + rel(source)) == source.read_bytes(), "Nested source ZIP is not byte-identical")
        require(zipped.read(prefix + rel(manifest_path)) == manifest_path.read_bytes(), "Nested manifest is not byte-identical")
    require(all(sha(ROOT / name) == value for name, value in input_snapshot.items()), "Final inputs changed during bundle packaging")
    artifacts = [APK, source, bundle]
    sum_path = OUT / "SHA256SUMS_Android_4.0.txt"
    sum_path.write_text("".join(sha(p) + "  " + p.name + "\n" for p in artifacts), encoding="utf-8")
    completion = {
        "status": "passed", "version": "4.0", "platform": "Android", "created_utc": datetime.now(timezone.utc).isoformat(),
        "artifacts": [record(p) for p in artifacts + [sum_path]], "checks": counts,
        "bundle_hashes": {p.name: sha(p) for p in artifacts}, "source_archive": source_validation, "full_bundle": bundle_validation,
        "screenshots": len(final["screenshots"]), "ui_checks_by_apk_sha256": final["ui_builds"], "lint": final["lint"],
        "apk_byte_identical": True, "source_zip_byte_identical": True, "all_entry_bytes_checked": True,
        "photo_credits_match_apk_source_archive": True, "licensed_photos": 48,
        "private_files_excluded": True, "diagnostic_screenshots_excluded": True, "secret_scan": secret_audit,
        "device_access_performed_by_packaging": False, "project_documents_modified_by_packaging": False,
        "exe_packaging": "not requested", "release_manifest": record(manifest_path),
        "limitations": marker.get("limitations", []),
    }
    write_json(QA / "packaging_completion.json", completion)
    print(json.dumps(completion, ensure_ascii=False, indent=2))


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit-sources", action="store_true", help="Check source allowlist, photo hashes and signing-secret patterns; do not create archives or access devices")
    args = parser.parse_args()
    try:
        if args.audit_sources:
            files, excluded, secret_audit = source_inventory()
            photos = photo_audit()
            audit = {"status": "passed", "version": "4.0", "source_files": len(files), "photos": len(photos["credits"]),
                     "private_files_excluded": True, "secret_scan": secret_audit, "archives_created": False,
                     "ready_marker_exists": MARKER.is_file(), "files": [record(p) for p in files],
                     "excluded": excluded, "catalog_sha256": photos["catalog_sha256"], "photo_credits_sha256": photos["credits_sha256"]}
            write_json(QA / "packaging_preflight.json", audit)
            print(json.dumps({k: v for k, v in audit.items() if k not in ("files", "excluded")}, ensure_ascii=False, indent=2))
        else:
            package()
    except (RuntimeError, KeyError, ValueError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, "Packaging stopped: " + str(error) + "\n")


if __name__ == "__main__":
    main()
