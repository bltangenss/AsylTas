"""Fetch licensed Commons metadata/photos; never synthesize or alter gemstone images."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
import argparse
import hashlib
import html
import io
import json
import re
import shutil
import sys
import time
import urllib.parse
import urllib.request
from PIL import Image
from catalog_v4_data import EXTRA

ROOT=Path(__file__).resolve().parents[1]
QA=ROOT/'reports/qa_assets_v4_20261125'
API='https://commons.wikimedia.org/w/api.php'
USER_AGENT='AsylTas-EducationalCatalog/4.0 (Wikimedia Commons licensed gemstone catalog)'
if hasattr(sys.stdout,'reconfigure'):sys.stdout.reconfigure(encoding='utf-8')
_last_request=0.0

def request(url):
    global _last_request
    for attempt in range(5):
        try:
            # Deliberately sequential, paced access avoids hammering Commons.
            time.sleep(max(0.0,2.1-(time.monotonic()-_last_request)))
            _last_request=time.monotonic()
            with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':USER_AGENT}),timeout=35) as response:
                return response.read()
        except Exception as error:
            if attempt==4: raise
            retry_after=getattr(error,'headers',{}).get('Retry-After','')
            delay=int(retry_after) if str(retry_after).isdigit() else min(45,5*(2**attempt))
            time.sleep(min(delay,45))

def clean(value):return html.unescape(re.sub('<[^>]*>','',value or '')).strip()

def search(row):
    sid,name,english,*_=row
    target=QA/f'candidates_{sid}.json'
    if target.exists():return json.loads(target.read_text(encoding='utf-8'))
    terms={26:'rock crystal',38:'tiger eye',36:'pearl'}.get(sid,english)
    parameters={'action':'query','format':'json','generator':'search','gsrsearch':f'intitle:"{terms}" filetype:bitmap gemstone',
        'gsrnamespace':6,'gsrlimit':12,'prop':'imageinfo','iiprop':'url|extmetadata|size','iiurlwidth':600}
    data=json.loads(request(API+'?'+urllib.parse.urlencode(parameters)))
    pages=list(data.get('query',{}).get('pages',{}).values())
    if not pages:
        parameters['gsrsearch']=f'intitle:"{terms}" filetype:bitmap'
        data=json.loads(request(API+'?'+urllib.parse.urlencode(parameters)))
        pages=list(data.get('query',{}).get('pages',{}).values())
    candidates=[]
    for page in pages:
        filename=page['title'].removeprefix('File:')
        if not filename.lower().endswith(('.jpg','.jpeg')):continue
        info=page.get('imageinfo',[{}])[0];meta=info.get('extmetadata',{})
        license=clean(meta.get('LicenseShortName',{}).get('value',''))
        if not (license.startswith('CC BY') or license in ('Public domain','CC0')):continue
        artist=clean(meta.get('Artist',{}).get('value',''))
        description=clean(meta.get('ImageDescription',{}).get('value',''))
        title=filename.lower()
        if re.search(r'portrait|painting|map |stamp|logo|diagram|earring|necklace|nail|film|poster|synthetic|tooth|warship|ship ',title):continue
        score=sum(7 if word in title else 0 for word in ['faceted','facet cut','cabochon','gemstone',' cut '])
        score+=sum(2 if word in title else 0 for word in ['polished','tumbled','round','oval'])
        if min(info.get('width',0),info.get('height',0))>=500:score+=2
        if 'ring' in title:score-=9
        candidates.append({'id':sid,'file':filename,'url':info.get('thumburl',info.get('url')),'original_url':info.get('url'),
            'source':info.get('descriptionurl','https://commons.wikimedia.org/wiki/File:'+filename.replace(' ','_')),
            'author':artist,'license':license,'license_url':clean(meta.get('LicenseUrl',{}).get('value','')),
            'description':description[:800],'width':info.get('width'),'height':info.get('height'),'score':score})
    candidates.sort(key=lambda x:-x['score'])
    result={'id':sid,'name':name,'english':english,'candidates':candidates}
    target.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return result

def download(record):
    sid=record['id']
    if sid not in range(13,49):raise ValueError('Only new photo IDs 13–48 may be downloaded')
    if not (record['license'].startswith('CC BY') or record['license'] in ('Public domain','CC0')):
        raise ValueError(f'Unsupported photo license for {sid}')
    if not record['source'].startswith('https://commons.wikimedia.org/'):
        raise ValueError(f'Missing Commons attribution for {sid}')
    if not record.get('author') or not record.get('license_url'):
        raise ValueError(f'Incomplete author/license attribution for {sid}')
    destination=ROOT/f'android/app/src/main/res/drawable-nodpi/stone_{sid}.jpg'
    cached_credit=QA/f'credit_{sid}.json'
    content=None
    if destination.exists() and cached_credit.exists():
        cached=json.loads(cached_credit.read_text(encoding='utf-8'))
        cached_bytes=destination.read_bytes()
        if cached.get('url')==record['url'] and cached.get('sha256')==hashlib.sha256(cached_bytes).hexdigest():
            content=cached_bytes
    if content is None:content=request(record['url'])
    with Image.open(io.BytesIO(content)) as pic:
        if pic.format!='JPEG' or min(pic.size)<150:raise RuntimeError(f'Invalid photo for {sid}')
        dimensions=list(pic.size)
        pic.verify()
    destination.write_bytes(content)
    shutil.copyfile(destination,ROOT/f'site_online/public/assets/stone_{sid}.jpg')
    credit={key:value for key,value in record.items() if key not in ('width','height','score','original_url')}
    credit.update(resource=f'stone_{sid}.jpg',sha256=hashlib.sha256(content).hexdigest(),dimensions=dimensions)
    cached_credit.write_text(json.dumps(credit,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return {'id':sid,'bytes':len(content),'dimensions':dimensions,'file':record['file']}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('mode',choices=['search','download','credits']);parser.add_argument('--ids',default='')
    args=parser.parse_args();QA.mkdir(parents=True,exist_ok=True)
    ids={int(x) for x in args.ids.split(',') if x} or set(range(13,49))
    if not ids.issubset(set(range(13,49))):parser.error('Only IDs 13–48 are permitted')
    errors=[]
    if args.mode=='search':
        with ThreadPoolExecutor(max_workers=1) as pool:
            jobs={pool.submit(search,row):row[0] for row in EXTRA if row[0] in ids}
            for job in as_completed(jobs):
                try:
                    result=job.result();print(json.dumps({'id':result['id'],'name':result['name'],'candidates':[r['file'] for r in result['candidates'][:4]]},ensure_ascii=False),flush=True)
                except Exception as error:
                    errors.append(jobs[job]);print(json.dumps({'id':jobs[job],'error':str(error)}),flush=True)
    elif args.mode=='download':
        selections=json.loads((QA/'selections.json').read_text(encoding='utf-8'))
        if sorted(record['id'] for record in selections)!=list(range(13,49)):
            raise ValueError('Selections must contain each new ID exactly once')
        with ThreadPoolExecutor(max_workers=1) as pool:
            jobs={pool.submit(download,record):record['id'] for record in selections if record['id'] in ids}
            for job in as_completed(jobs):
                try:print(json.dumps(job.result(),ensure_ascii=False),flush=True)
                except Exception as error:
                    errors.append(jobs[job]);print(json.dumps({'id':jobs[job],'error':str(error)}),flush=True)
    else:
        credits=json.loads((ROOT/'android/app/src/main/assets/photo_credits.json').read_text(encoding='utf-8-sig'))[:12]
        credits.extend(json.loads((QA/f'credit_{sid}.json').read_text(encoding='utf-8')) for sid in range(13,49))
        assert [credit['id'] for credit in credits]==list(range(1,49)), 'Credit IDs must be unique and complete'
        for credit in credits:
            if credit['id']>12:
                assert credit.get('author') and credit.get('license_url'), 'New photo attribution is incomplete'
            file=ROOT/'android/app/src/main/res/drawable-nodpi'/credit['resource']
            assert hashlib.sha256(file.read_bytes()).hexdigest()==credit['sha256']
            assert (ROOT/'site_online/public/assets'/credit['resource']).read_bytes()==file.read_bytes()
        encoded=json.dumps(credits,ensure_ascii=False,indent=2)+'\n'
        for target in ['android/app/src/main/assets/photo_credits.json','site_online/public/assets/photo_credits.json','shared/catalog/photo_credits_v4.json']:
            (ROOT/target).write_text(encoded,encoding='utf-8')
        notice='АСЫЛ ТАС 4.0 — 48 НАҚТЫ ТАС ФОТОСУРЕТІ\n\nWikimedia Commons лицензияланған фотосуреттері. Әр фото тас түрін таныстырады.\nБағалар, салмақтар және мөлдірлік сандары — оқу деректері; нақты фотодағы бұйымға баға емес.\nТүпнұсқа немесе Commons дайындаған кішірейтілген JPEG файлдары өзгеріссіз сақталған.\nҚолданба фотоларды карточкаға сыйғызып көрсетеді. Жасанды сурет генерациясы қолданылмады.\nАвторлық құқық әр фотосуреттің авторында; төмендегі лицензиясы қолданылады.\n\n'
        for credit in credits:
            notice+=f"{credit['id']}. {credit['file']} ({credit['resource']})\nАвтор: {credit['author'] or 'Commons дерегінде көрсетілмеген; Public domain'}\nЛицензия: {credit['license']} {credit['license_url']}\nДереккөз: {credit['source']}\nSHA-256: {credit['sha256']}\n\n"
        for target in ['PHOTO_CREDITS.txt','android/app/src/main/assets/PHOTO_CREDITS.txt','site_online/public/assets/PHOTO_CREDITS.txt']:(ROOT/target).write_text(notice,encoding='utf-8')
        print('48 licensed photo credits and matching Android/online JPEG bytes verified.')
    if errors:raise SystemExit(f'Failed photo IDs: {errors}')

if __name__=='__main__':main()
