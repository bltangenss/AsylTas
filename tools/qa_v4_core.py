"""Verify catalog consistency and shared Java compatibility without touching devices."""
from pathlib import Path
import hashlib,json,os,re,shutil,subprocess
from decimal import Decimal

ROOT=Path(__file__).resolve().parents[1]
QA=ROOT/'reports/qa_v4_20261125'
QA.mkdir(parents=True,exist_ok=True)
rows=json.loads((ROOT/'shared/catalog/catalog_v4.json').read_text(encoding='utf-8'))
base=json.loads((ROOT/'shared/catalog/catalog_base12.json').read_text(encoding='utf-8'))
assert len(rows)==48 and [r['id'] for r in rows]==list(range(1,49))
for a,b in zip(base,rows):
    for key,value in a.items():assert b[key]==value,(a['id'],key)
for row in rows:
    assert Decimal(row['carats'])*Decimal(row['price_per_carat'])==Decimal(row['cost'])
    assert row['description'] and row['care'] and row['aliases'] and row['educational'] is True
for target in ['android/app/src/main/assets/catalog_v4.json']:
    assert json.loads((ROOT/target).read_text(encoding='utf-8'))==rows
online_present=(ROOT/'site_online/worker/catalog.js').is_file()
if online_present:
    assert json.loads((ROOT/'site_online/public/assets/catalog.json').read_text(encoding='utf-8'))==rows
    worker=(ROOT/'site_online/worker/catalog.js').read_text(encoding='utf-8').split('export default ',1)[1].strip().removesuffix(';')
    assert json.loads(worker)==rows
model_changes=[]
baseline=json.loads((ROOT/'shared/catalog/model_baseline_sha256.json').read_text(encoding='utf-8'))
assert len(baseline)==7
for entry in sorted(baseline,key=lambda row:row['path']):
    if hashlib.sha256((ROOT/entry['path']).read_bytes()).hexdigest()!=entry['sha256']:model_changes.append(entry['path'])
assert model_changes==['shared/src/kz/asyltas/model/Catalog.java']
java=Path(os.environ['JAVA_HOME'])/'bin' if os.environ.get('JAVA_HOME') else Path(shutil.which('javac') or 'C:/Program Files/Java/jdk-21/bin/javac.exe').parent
extension='.exe' if os.name=='nt' else ''
classes=QA/'model_classes';classes.mkdir(exist_ok=True)
sources=list((ROOT/'shared/src').rglob('*.java'))+[ROOT/'desktop/src/kz/asyltas/desktop/LocalStore.java',ROOT/'tests/ModelTest.java',ROOT/'tests/CatalogExpansionTest.java']
subprocess.run([str(java/('javac'+extension)),'--release','11','-encoding','UTF-8','-d',str(classes)]+list(map(str,sources)),check=True)
outputs={}
for name in ['ModelTest','CatalogExpansionTest']:
    result=subprocess.run([str(java/('java'+extension)),'-cp',str(classes),name],check=True,capture_output=True,text=True,encoding='utf-8')
    outputs[name]=result.stdout.strip()
report={'status':'passed','catalog_records':48,'preserved_original_ids':12,'model_changes':model_changes,'unchanged_other_model_classes':6,
    'java_checks':{name:int(re.search(r'PASS: (\d+)',output).group(1)) for name,output in outputs.items()},'outputs':outputs,
    'catalog_sha256':hashlib.sha256((ROOT/'shared/catalog/catalog_v4.json').read_bytes()).hexdigest(),'online_catalog_checked':online_present}
(QA/'core_checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report,ensure_ascii=True,indent=2))
