"""Verify final signing, installed APK identity, resource photos and build diagnostics."""
from pathlib import Path
import hashlib,json,re,subprocess,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
QA=ROOT/'reports/qa_v3_20261125'
SDK=Path('C:/Users/ASUS/AppData/Local/Android/Sdk')
JAVA=Path('C:/Program Files/Java/jdk-21/bin/java.exe')
APK=ROOT/'deliverables/AsylTas-3.0.apk'
ADB=SDK/'platform-tools/adb.exe'
def run(args):
    p=subprocess.run(list(map(str,args)),capture_output=True,check=True)
    return p.stdout.decode('utf-8','replace')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
signature=run([JAVA,'-jar',SDK/'build-tools/36.0.0/lib/apksigner.jar','verify','--verbose','--print-certs',APK])
(QA/'signature.txt').write_text(signature,encoding='utf-8')
badging=run([SDK/'build-tools/36.0.0/aapt.exe','dump','badging',APK])
assert "versionCode='5'" in badging and "versionName='3.0'" in badging
assert "sdkVersion:'24'" in badging
(QA/'apk_badging.txt').write_text(badging,encoding='utf-8')
device_path=run([ADB,'-s','emulator-5554','shell','pm','path','kz.asyltas.android']).strip().split('package:',1)[1]
installed=QA/'installed-final.apk'
run([ADB,'-s','emulator-5554','pull',device_path,installed]);assert sha(installed)==sha(APK)
issues=ET.parse(ROOT/'android/app/build/reports/lint-results-debug.xml').getroot().findall('issue')
lint={kind:sum(x.get('severity')==kind for x in issues) for kind in ['Error','Fatal','Warning']}
assert not lint['Error'] and not lint['Fatal']
(QA/'lint_summary.json').write_text(json.dumps(lint),encoding='utf-8')
(QA/'lint-results-debug.xml').write_bytes((ROOT/'android/app/build/reports/lint-results-debug.xml').read_bytes())
for item in json.loads((QA/'model_hashes.json').read_text(encoding='utf-8')):assert sha(ROOT/item['file'])==item['sha256']
photos=json.loads((ROOT/'android/app/src/main/assets/photo_credits.json').read_text(encoding='utf-8'))
assert len(photos)==12
for row in photos:assert sha(ROOT/'android/app/src/main/res/drawable-nodpi'/row['resource'])==row['sha256']
crashes=run([ADB,'-s','emulator-5554','shell','logcat','-d','-v','brief','AndroidRuntime:E','*:S'])
(QA/'recent_crash_log.txt').write_text(crashes,encoding='utf-8')
assert 'FATAL EXCEPTION' not in crashes
report={'status':'passed','apk_sha256':sha(APK),'installed_apk_matches':True,'version':'3.0','version_code':5,
    'min_sdk':24,'device':'emulator-5554','api':29,'photo_count':12,'model_sources_unchanged':True,'lint':lint,
    'recent_fatal_exceptions':0}
(QA/'artifact_verification.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,indent=2))
