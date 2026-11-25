"""Package only the finalized Android 3.0 release; no device access or build mutations."""
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import json
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "deliverables"
QA = ROOT / "reports/qa_v3_20261125"
SHOT_DIR = ROOT / "screenshots/redesign_20261124/v3_20261125"
APK = OUT / "AsylTas-3.0.apk"
DOC_NAMES = ("README_KZ.txt", "TEST_RESULTS.txt", "START_HERE.txt", "LAB_FEATURES_KZ.txt", "PHOTO_CREDITS.txt")
PRIVATE_SUFFIXES = {".p12", ".pfx", ".jks", ".keystore", ".pem", ".key"}
REQUIRED_EVIDENCE = (
    "ANDROID_READY_FOR_PACKAGING.json", "ui_checks.json", "database_checks.txt", "model_checks.txt",
    "model_hashes.json", "artifact_verification.json", "lint_summary.json", "lint-results-debug.xml",
    "photo_assets.json", "build_release.txt", "build_test.txt", "ui_begin.txt", "ui_cleanup.txt",
    "post_run_state.json", "signature.txt", "apk_badging.txt", "recent_crash_log.txt",
)


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def blob_digest(data):
    return hashlib.sha256(data).hexdigest()


def rel(path):
    return path.resolve().relative_to(ROOT).as_posix()


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def record(path):
    return {"path": rel(path), "bytes": path.stat().st_size, "sha256": digest(path)}


def safe_name(name):
    parts = PurePosixPath(name).parts
    require(not name.startswith("/") and not any(p in ("..", "") or ":" in p for p in parts), f"Unsafe ZIP path: {name}")


def archive(path, entries):
    """Verify CRC, case-insensitive uniqueness, and every entry against its source snapshot."""
    entries = sorted(entries, key=lambda pair: pair[1])
    names = [name for _, name in entries]
    require(len({name.casefold() for name in names}) == len(names), f"Duplicate archive entries: {path}")
    for name in names:
        safe_name(name)
    before = {name: digest(file) for file, name in entries}
    temporary = QA / ("packaging_" + path.name + ".tmp")
    try:
        with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zipped:
            for file, name in entries:
                zipped.write(file, name)
        with zipfile.ZipFile(temporary) as zipped:
            require(zipped.testzip() is None, f"CRC check failed: {path}")
            require(zipped.namelist() == names, f"ZIP entry list mismatch: {path}")
            for file, name in entries:
                require(blob_digest(zipped.read(name)) == before[name] == digest(file), f"Source changed while packaging: {file}")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)
    return {"entries": len(entries), "crc_passed": True, "duplicate_entries": 0, "byte_checks": len(entries)}


def source_files():
    files = {ROOT / name for name in DOC_NAMES}
    for folder in ("shared/src", "android/app/src", "android/gradle/wrapper"):
        files.update(p for p in (ROOT / folder).rglob("*") if p.is_file())
    files.update(ROOT / name for name in (
        "android/build.gradle.kts", "android/settings.gradle.kts", "android/gradle.properties",
        "android/gradlew", "android/gradlew.bat", "android/app/build.gradle.kts", "tests/ModelTest.java",
        # ModelTest imports this legacy file store; the Windows portal is deliberately excluded.
        "desktop/src/kz/asyltas/desktop/LocalStore.java", "tools/build_v3.ps1", "tools/qa_v3.py",
        "tools/qa_v3_package_test.py", "tools/android_labs_ui.py", "tools/package_android_v3.py",
        "tools/verify_android_v3.py",
    ))
    for file in files:
        require(file.is_file(), f"Missing source file: {file}")
        require(file.suffix.lower() not in PRIVATE_SUFFIXES and file.name.lower() != "local.properties", f"Private file selected: {file}")
        require(not file.is_symlink(), f"Source symlink not allowed: {file}")
    require(not any("presentation" in rel(p).lower() or p.suffix.lower() == ".html" for p in files), "Presentation output selected")
    return files


def photo_audit():
    credits = read_json(ROOT / "android/app/src/main/assets/photo_credits.json")
    require(len(credits) == 12 and {r["id"] for r in credits} == set(range(1, 13)), "Photo credits must contain IDs 1–12")
    notice = (ROOT / "PHOTO_CREDITS.txt").read_text(encoding="utf-8-sig")
    for row in credits:
        require(row["resource"] == f"stone_{row['id']}.jpg", "Unexpected photo resource name")
        path = ROOT / "android/app/src/main/res/drawable-nodpi" / row["resource"]
        require(digest(path) == row["sha256"], f"Photo hash mismatch: {path}")
        require(row["source"].startswith("https://commons.wikimedia.org/wiki/File:") and row["license"], "Incomplete photo source/license")
        require(row["sha256"] in notice and row["source"] in notice and row["license"] in notice, f"Photo notice mismatch: {row['id']}")
    return credits


def selected_screenshots(ui):
    rows = []
    for item in ui["screenshots"]:
        name = item["name"].lower()
        if item.get("diagnostic") or "inspect" in name or "preview" in name:
            continue
        require(name.startswith("android_"), f"Non-Android screenshot selected: {name}")
        path = (ROOT / item["path"].replace("\\", "/")).resolve()
        require(path.is_relative_to(SHOT_DIR.resolve()) and path.suffix.lower() == ".png", f"Screenshot outside release folder: {path}")
        require(digest(path) == item["sha256"], f"Screenshot changed: {path}")
        require(re.fullmatch("[0-9a-f]{64}", item.get("apk_sha256", "")), f"Screenshot lacks build provenance: {name}")
        rows.append({"path": rel(path), "sha256": item["sha256"], "apk_sha256": item["apk_sha256"]})
    require(rows and len({r["path"].casefold() for r in rows}) == len(rows), "Missing or duplicate release screenshots")
    return rows


def final_inputs():
    require((QA / "ANDROID_READY_FOR_PACKAGING.json").is_file(), "Waiting for ANDROID_READY_FOR_PACKAGING.json; no archives were written")
    for name in REQUIRED_EVIDENCE:
        require((QA / name).is_file(), f"Missing final evidence: {name}")
    marker = read_json(QA / "ANDROID_READY_FOR_PACKAGING.json")
    ui = read_json(QA / "ui_checks.json")
    verification = read_json(QA / "artifact_verification.json")
    apk_hash = digest(APK)
    require(ui.get("status") == "passed" and ui["checks"] and all(c.get("passed") is True for c in ui["checks"]), "UI verification is not fully passed")
    require(marker.get("status") == "ready" and marker.get("ui_checks") == len(ui["checks"]) and marker.get("model_checks") == 43 and marker.get("database_checks") == 92, "Ready marker counts/status disagree with final QA")
    require(ui["apk_sha256"] == apk_hash == verification["apk_sha256"], "Final APK hash disagrees with QA")
    if "apk_sha256" in marker:
        require(marker["apk_sha256"] == apk_hash, "Ready marker refers to a different APK")
    require(verification.get("status") == "passed" and verification.get("installed_apk_matches") is True, "Installed APK identity was not verified")
    require(verification.get("version") == "3.0" and verification.get("version_code") == 5 and verification.get("min_sdk") == 24, "Unexpected Android version")
    require(verification.get("recent_fatal_exceptions") == 0, "Unresolved runtime crash in final audit")
    for check in ui["checks"]:
        require(re.fullmatch("[0-9a-f]{64}", check.get("apk_sha256", "")), f"UI check lacks build provenance: {check['name']}")
    require("PASS: 43 checks" in (QA / "model_checks.txt").read_text(encoding="utf-8-sig"), "Model checks did not pass")
    require("PASS: 92 portal checks" in (QA / "database_checks.txt").read_text(encoding="utf-8-sig"), "SQLite checks did not pass")
    models = read_json(QA / "model_hashes.json")
    require(len(models) == 7 and all(r["unchanged"] is True and digest(ROOT / r["file"]) == r["sha256"] for r in models), "Shared model changed")
    lint = read_json(QA / "lint_summary.json")
    require(lint.get("Error") == 0 and lint.get("Fatal") == 0, "Lint has errors")
    require(all(verification.get("lint", {}).get(kind) == lint.get(kind) for kind in ("Error", "Fatal", "Warning")), "Final lint summaries disagree")
    badging = (QA / "apk_badging.txt").read_text(encoding="utf-8-sig")
    require(all(value in badging for value in ("versionCode='5'", "versionName='3.0'", "sdkVersion:'24'", "targetSdkVersion:'35'")), "Final APK metadata evidence is inconsistent")
    cleanup = (QA / "ui_cleanup.txt").read_text(encoding="utf-8-sig")
    require("UI fixture removed" in cleanup and "FAIL" not in cleanup, "QA account cleanup was not successful")
    post_run = read_json(QA / "post_run_state.json")
    require(post_run.get("status") == "passed" and post_run.get("apk_sha256") == apk_hash, "Post-run state is not verified for final APK")
    require(all(post_run.get(key) is True for key in ("qa_accounts_removed", "original_guest_necklace_preserved", "store_settings_restored", "display_restored", "temporary_media_files_removed")), "QA cleanup or restoration is incomplete")
    credits = photo_audit()
    photo_entries = []
    with zipfile.ZipFile(APK) as zipped:
        require(zipped.testzip() is None, "APK ZIP CRC failed")
        require(zipped.read("assets/photo_credits.json") == (ROOT / "android/app/src/main/assets/photo_credits.json").read_bytes(), "APK credits differ from source")
        # AAPT shortens resource paths (for example res/EZ.jpg). Match original bytes,
        # not the source filename, and retain the exact APK mapping as evidence.
        jpeg_hashes = {name: blob_digest(zipped.read(name)) for name in zipped.namelist() if name.startswith("res/") and name.lower().endswith((".jpg", ".jpeg"))}
        for row in credits:
            matches = [name for name, hash_value in jpeg_hashes.items() if hash_value == row["sha256"]]
            require(len(matches) == 1, f"APK photo differs or is duplicated: {row['resource']}")
            photo_entries.append({"source_resource": row["resource"], "apk_entry": matches[0], "sha256": row["sha256"]})
    return ui, verification, lint, selected_screenshots(ui), photo_entries


def write_results(ui, verification, lint, screenshots):
    checks = len(ui["checks"])
    builds = Counter(c["apk_sha256"] for c in ui["checks"])
    final_count = builds[verification["apk_sha256"]]
    text = f"""АСЫЛ ТАС 3.0 — ANDROID ТЕКСЕРУ НӘТИЖЕЛЕРІ
Күні: 25.11.2026

ОРТАҚ МОДЕЛЬ
43 / 43 тексеріс өтті: BigDecimal, 12 тас (4 асыл / 8 жартылай асыл),
сұрыптау, мөлдірліктің екі шекарасы, 1–99 дана, сақтау және қалпына келтіру.
Үлгі: 6 тас / 5.90 карат / 616 200 теңге.
7 ортақ модель файлының SHA-256 мәндері бастапқы көшірмемен бірдей.

ANDROID SQLITE
92 / 92 PortalTestRunner тексерісі өтті: тіркелу/кіру, PBKDF2 және salt,
бес қате құпиясөзден кейін уақытша бұғаттау, аккаунттардың оқшаулануы,
профиль, таңдаулылар, өтінім мен әкімші құқықтары, пікір/хабарлама,
медиа сілтемелері және қайта ашылған дерекқордағы тұрақтылық.
NecklaceDatabase 1/2 → 3 көшуінде ескі қонақ алқасы мен жинақтар сақталды.
Аспаптық сынақ жеке тексеріс дерекқорларын қолданды.
Соңғы 92 тексеріс түпкі APK орнатылған күйде қайта орындалды.

НАҚТЫ ANDROID ИНТЕРФЕЙСІ
Pixel_4 эмуляторы, Android 10 / API 29, emulator-5554.
UI есебінде {checks} / {checks} ағымдағы тексеріс сәтті.
Бұлар {len(builds)} аралық 3.0 жинағында орындалған: соңғы APK-да {final_count} тексеріс.
Әр тексеріс пен скриншоттың өз apk_sha256 мәні ui_checks.json ішінде сақталған.
Алдыңғы 2.0 нұсқасының 61 UI тексерісі және тарихи 42 UI / 23 rotation саны
бұл санға қосылған жоқ. Қайта орындалған қате әрекеттер previous_attempts ішінде,
соңғы сәтті checks санына қосылмайды.

Негізгі сценарийлер: қонақ алқасы, кіру/тіркелу, аккаунттар арасындағы деректер,
каталог/фото/таңдаулылар, тас қосу және санын өзгерту, сүзгі, жинақ,
профиль, өтінім, әкімші күйі, мекенжай, пікірлер, хабарлама және медиа.
Әр сценарийдің нақты нәтижесі ui_checks.json ішіндегі атауларымен берілген.
Аудио/сурет/бейне, экранды бұру және бейімделу сценарийлерінің дәлелі сол есепте.
Жинаққа {len(screenshots)} диагностикалық емес Android скриншоты енгізілді.
inspection/preview және Windows суреттері Android архивіне кірмеді.

QA ДЕРЕКТЕРІН ТАЗАЛАУ
Тек allowlist ішіндегі екі QA аккаунты мен оларға тиесілі жолдар жойылды.
Қолданбаның бүкіл дерегі тазартылған жоқ. Қонақ алқасы өзгермеді;
бастапқы сеанс, дүкен деректері және көрініс баптаулары қалпына келтірілді.
Дәлелдер: ui_cleanup.txt және post_run_state.json.

СОҢҒЫ APK
AsylTas-3.0.apk: versionName 3.0, versionCode 5, minSdk 24, targetSdk 35.
SHA-256: {verification['apk_sha256']}
Қолтаңба тексерілді. Эмуляторда орнатылған APK осы файлмен байт бойынша бірдей.
Жинау мен lint аяқталды: {lint['Error']} қате, {lint['Fatal']} fatal, {lint['Warning']} ескерту.
Ескертулер толық lint-results-debug.xml ішінде берілген.
Соңғы runtime аудитінде FATAL EXCEPTION саны: 0.
Барлық 12 Commons фотосы APK-дағы және бастапқы ресурстағы хэштерімен тексерілді.

АРХИВТЕР
AsylTas-Android-source-3.0.zip — Android/shared кодтары, build/QA құралдары және құжаттар.
AsylTas-Android-3.0-tolyk-jinaq.zip — APK, source ZIP, құжаттар, дәлелдер, скриншоттар.
Қол қою кілті, local.properties, build/cache, Windows портал коды және
презентация материалдары source ZIP-ке қосылмаған. ModelTest үшін ғана бұрынғы
desktop/src/kz/asyltas/desktop/LocalStore.java файлы берілген.
CRC/testzip, қайталанатын ZIP жолдары және әр файлдың байттық сәйкестігі тексеріледі.
Нақты қорытынды: packaging_completion.json; хэштер: SHA256SUMS_Android_3.0.txt.

ШЕКТЕР
Нақты телефонда және Android API 24/35 құрылғыларында жеке тексерілмеді.
Онлайн backend, банк төлемі, email-растау және Android–Windows синхрондауы жоқ.
Өтінімдер құрылғыда сақталады; сыртқа жіберу пайдаланушының «Бөлісу» әрекетімен ғана.
Алматы — анық белгіленген үлгі мекенжай. Ойдан дүкен телефоны қосылмаған.
Каталогтағы баға/салмақ/мөлдірлік — оқу деректері; фотолар — нақты минерал иллюстрациясы.
Windows 3.0 толық UI тексеруі аяқталмаған; EXE пайдаланушы талабынан алынды.
Бұл есеп пен архивтер Android жеткізуіне арналған.

ДӘЛЕЛДЕР
reports/qa_v3_20261125/ui_checks.json, database_checks.txt, model_checks.txt,
model_hashes.json, artifact_verification.json, signature.txt, apk_badging.txt,
lint_summary.json, lint-results-debug.xml, photo_assets.json, build_release.txt,
build_test.txt, ui_begin.txt, ui_cleanup.txt, post_run_state.json,
recent_crash_log.txt, release_manifest.json және packaging_completion.json.

Орындағандар: Болат Тәңірберді, Ергаш Назым, Ғалымжан Шынай.
"""
    (ROOT / "TEST_RESULTS.txt").write_text(text, encoding="utf-8")


def package():
    ui, verification, lint, screenshots, photo_entries = final_inputs()
    write_results(ui, verification, lint, screenshots)
    files = source_files()
    source = OUT / "AsylTas-Android-source-3.0.zip"
    source_validation = archive(source, [(p, "AsylTas/" + rel(p)) for p in files])
    with zipfile.ZipFile(source) as zipped:
        for name in zipped.namelist():
            path = PurePosixPath(name)
            require(path.suffix.lower() not in PRIVATE_SUFFIXES and path.name.lower() != "local.properties", f"Private file in source ZIP: {name}")
        for row in photo_audit():
            name = "AsylTas/android/app/src/main/res/drawable-nodpi/" + row["resource"]
            require(blob_digest(zipped.read(name)) == row["sha256"], f"Source ZIP photo mismatch: {name}")
    counts = dict(Counter(c["apk_sha256"] for c in ui["checks"]))
    manifest = {
        "version": "3.0", "platform": "Android", "created_utc": datetime.now(timezone.utc).isoformat(),
        "model_checks": 43, "database_checks": 92, "ui_checks": len(ui["checks"]),
        "ui_checks_by_apk_sha256": counts, "final_apk_ui_checks": counts[verification["apk_sha256"]],
        "lint": lint, "artifacts": [record(APK), record(source)], "screenshots": screenshots,
        "shared_models_unchanged": 7, "licensed_photos": 12, "source_files": len(files), "apk_photo_entries": photo_entries,
        "bundle_checksum_location": "deliverables/SHA256SUMS_Android_3.0.txt",
        "scope": "Android only; Windows EXE and presentation outputs excluded",
    }
    write_json(QA / "release_manifest.json", manifest)
    evidence = [QA / name for name in REQUIRED_EVIDENCE] + [QA / "release_manifest.json"]
    bundled = [APK, source] + [ROOT / name for name in DOC_NAMES] + [ROOT / r["path"] for r in screenshots] + evidence
    sums = QA / "packaging_contents_SHA256.txt"
    sums.write_text("".join(digest(p) + "  " + rel(p) + "\n" for p in bundled), encoding="utf-8")
    bundle = OUT / "AsylTas-Android-3.0-tolyk-jinaq.zip"
    prefix = "AsylTas-Android-3.0/"
    bundle_validation = archive(bundle, [(p, prefix + rel(p)) for p in bundled] + [(sums, prefix + "CONTENTS_SHA256.txt")])
    with zipfile.ZipFile(bundle) as zipped:
        require(zipped.read(prefix + rel(APK)) == APK.read_bytes(), "Bundled APK is not byte-identical")
        require(zipped.read(prefix + rel(source)) == source.read_bytes(), "Nested source ZIP is not byte-identical")
        require(zipped.read(prefix + rel(QA / "release_manifest.json")) == (QA / "release_manifest.json").read_bytes(), "Bundled manifest mismatch")
    artifacts = [APK, source, bundle]
    sum_path = OUT / "SHA256SUMS_Android_3.0.txt"
    sum_path.write_text("".join(digest(p) + "  " + p.name + "\n" for p in artifacts), encoding="utf-8")
    completion = {
        "status": "passed", "created_utc": datetime.now(timezone.utc).isoformat(), "platform": "Android",
        "artifacts": [record(p) for p in artifacts + [sum_path]],
        "checks": {"model": 43, "sqlite": 92, "ui": len(ui["checks"]), "ui_on_final_apk": counts[verification["apk_sha256"]], "photo_credits": 12, "shared_model_files": 7},
        "ui_checks_by_apk_sha256": counts, "screenshots": len(screenshots), "lint": lint,
        "source_archive": source_validation, "full_bundle": bundle_validation,
        "apk_byte_identical": True, "source_zip_byte_identical": True, "private_files_excluded": True,
        "photo_credits_match_apk_source_and_archive": True, "diagnostic_screenshots_excluded": True,
        "device_access_performed_by_packaging": False, "exe_packaging": "not requested",
        "limitations": ["UI checks span multiple 3.0 builds; each check and screenshot retains its APK hash.", "Pixel_4 API29 only; physical phone and API24/API35 not separately tested.", "No backend, online payment, email verification, or Android-Windows sync."],
    }
    write_json(QA / "packaging_completion.json", completion)
    print(json.dumps(completion, ensure_ascii=False, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit-sources", action="store_true", help="Static source/photo inventory only; no archives and no device access")
    args = parser.parse_args()
    if args.audit_sources:
        files = source_files()
        photos = photo_audit()
        audit = {"status": "passed", "source_files": len(files), "photos": len(photos), "private_files_excluded": True, "files": sorted(rel(p) for p in files), "ready_marker_exists": (QA / "ANDROID_READY_FOR_PACKAGING.json").exists()}
        write_json(QA / "packaging_preflight.json", audit)
        print(json.dumps({key: value for key, value in audit.items() if key != "files"}, ensure_ascii=False, indent=2))
    else:
        package()


if __name__ == "__main__":
    main()
