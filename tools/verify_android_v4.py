"""Audit the signed Android 4.0 release against its source and optional installed copy."""
from collections import Counter
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
QA = ROOT / "reports/qa_android_v4_20261125"
APK = ROOT / "deliverables/AsylTas-4.0.apk"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(args):
    result = subprocess.run(list(map(str, args)), capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", "replace") or "Verification command failed")
    return result.stdout.decode("utf-8", "replace")


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", help="ADB serial; omitted for an artifact-only audit")
    parser.add_argument("--logcat-since", help="Android logcat -T timestamp for this QA run")
    args = parser.parse_args()
    QA.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")
    java = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Android/Android Studio/jbr")) / "bin/java.exe"
    build = sdk / "build-tools/36.0.0"
    signature = run([java, "-jar", build / "lib/apksigner.jar", "verify", "--verbose", "--print-certs", APK])
    (QA / "signature.txt").write_text(signature, encoding="utf-8")
    metadata = run([build / "aapt.exe", "dump", "badging", APK])
    for value in ("versionCode='6'", "versionName='4.0'", "sdkVersion:'24'", "targetSdkVersion:'35'"):
        assert value in metadata, value
    (QA / "apk_badging.txt").write_text(metadata, encoding="utf-8")
    assert "android.permission.ACCESS_FINE_LOCATION" in metadata
    assert "android.permission.ACCESS_COARSE_LOCATION" in metadata
    assert "android.permission.ACCESS_BACKGROUND_LOCATION" not in metadata
    credits = read(ROOT / "android/app/src/main/assets/photo_credits.json")
    catalog = read(ROOT / "android/app/src/main/assets/catalog_v4.json")
    assert len(credits) == len(catalog) == 48
    assert {p["id"] for p in credits} == set(range(1, 49))
    photos = []
    with zipfile.ZipFile(APK) as archive:
        assert archive.testzip() is None
        for asset in ("catalog_v4.json", "campus_places.json", "photo_credits.json", "PHOTO_CREDITS.txt"):
            assert archive.read("assets/" + asset) == (ROOT / "android/app/src/main/assets" / asset).read_bytes(), asset
        jpeg_hashes = {name: hashlib.sha256(archive.read(name)).hexdigest() for name in archive.namelist() if name.startswith("res/") and name.lower().endswith((".jpg", ".jpeg"))}
        for row in credits:
            source = ROOT / "android/app/src/main/res/drawable-nodpi" / row["resource"]
            assert sha(source) == row["sha256"]
            entries = [name for name, digest in jpeg_hashes.items() if digest == row["sha256"]]
            assert len(entries) == 1, row["id"]
            photos.append({"id": row["id"], "apk_entry": entries[0], "sha256": row["sha256"]})
    lint_path = ROOT / "android/app/build/reports/lint-results-debug.xml"
    lint_raw = lint_path.read_bytes()
    issues = ET.fromstring(lint_raw).findall("issue")
    lint = {kind: sum(issue.get("severity") == kind for issue in issues) for kind in ("Error", "Fatal", "Warning")}
    assert lint["Error"] == lint["Fatal"] == 0
    (QA / "lint-results-debug.xml").write_bytes(lint_raw)
    (QA / "lint_summary.json").write_text(json.dumps(lint, indent=2) + "\n", encoding="utf-8")
    report = {"status": "passed", "apk_sha256": sha(APK), "bytes": APK.stat().st_size,
              "version": "4.0", "version_code": 6, "min_sdk": 24, "target_sdk": 35,
              "photo_count": 48, "catalog_rows": 48, "apk_photo_entries": photos, "lint": lint,
              "background_location_permission": False, "installed_apk_matches": None}
    if args.device:
        adb = [sdk / "platform-tools/adb.exe", "-s", args.device]
        package_path = run(adb + ["shell", "pm", "path", "kz.asyltas.android"]).strip().removeprefix("package:")
        assert package_path.startswith("/data/app/") and "\n" not in package_path
        installed = QA / "installed-final.apk"
        run(adb + ["pull", package_path, installed])
        assert sha(installed) == sha(APK)
        report.update(installed_apk_matches=True, device=args.device,
                      api=int(run(adb + ["shell", "getprop", "ro.build.version.sdk"]).strip()))
        since = ["-T", args.logcat_since] if args.logcat_since else []
        crashes = run(adb + ["shell", "logcat", "-d"] + since + ["-v", "brief", "AndroidRuntime:E", "*:S"])
        (QA / "recent_crash_log.txt").write_text(crashes, encoding="utf-8")
        assert "FATAL EXCEPTION" not in crashes, "Runtime exception during the current QA window"
        report.update(recent_fatal_exceptions=0, logcat_since=args.logcat_since or "all retained logs since emulator restart before v4 installation")
    (QA / "artifact_verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "apk_photo_entries"}, indent=2))


if __name__ == "__main__":
    main()
