"""Sign the instrumentation APK using explicitly configured local credentials."""
from pathlib import Path
import os
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
keystore = os.environ.get("ASYL_KEYSTORE", "")
if not keystore or not Path(keystore).is_file():
    raise SystemExit("Set ASYL_KEYSTORE to your local signing keystore file.")
if not os.environ.get("ASYL_STORE_PASSWORD"):
    raise SystemExit("Set ASYL_STORE_PASSWORD in the environment.")
sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
if not sdk_root:
    sdk_root = str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk")
SDK = Path(sdk_root) / "build-tools/36.0.0"
java_home = os.environ.get("JAVA_HOME")
JAVA = str(Path(java_home) / "bin/java.exe") if java_home else shutil.which("java")
if not JAVA:
    raise SystemExit("Set JAVA_HOME or add Java to PATH.")
source = ROOT / "android/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk"
output = ROOT / "reports/qa_v3_20261125/portal-test.apk"
key_password = "env:ASYL_KEY_PASSWORD" if os.environ.get("ASYL_KEY_PASSWORD") else "env:ASYL_STORE_PASSWORD"
for args in (
    [SDK / "zipalign.exe", "-f", "-p", "4", source, output],
    [JAVA, "-jar", SDK / "lib/apksigner.jar", "sign", "--ks", keystore,
     "--ks-key-alias", os.environ.get("ASYL_KEY_ALIAS", "asyltas"),
     "--ks-pass", "env:ASYL_STORE_PASSWORD", "--key-pass", key_password, output],
):
    result = subprocess.run(list(map(str, args)), capture_output=True)
    if result.returncode:
        raise SystemExit("Test APK packaging failed")
print("Instrumentation APK aligned and signed")
