"""ADB checks against the signed 3.0 APK, with screenshots from the real emulator."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib, json, sys, time
import re
import xml.etree.ElementTree as ET
import android_labs_ui as u

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'screenshots/redesign_20261124/v3_20261125'
QA=ROOT/'reports/qa_v3_20261125'
u.OUT=OUT;u.QA=QA
REPORT_PATH=QA/'ui_checks.json'
REPORT=json.loads(REPORT_PATH.read_text(encoding='utf-8')) if REPORT_PATH.exists() else {
    'started_utc':datetime.now(timezone.utc).isoformat(),'device':u.DEVICE,
    'apk_sha256':hashlib.sha256((ROOT/'deliverables/AsylTas-3.0.apk').read_bytes()).hexdigest(),
    'checks':[],'screenshots':[]}

def fresh_tree():
    remote='/sdcard/asyl_v3_ui.xml'
    for _ in range(3):
        u.adb('shell','rm','-f',remote)
        output=u.adb('shell','uiautomator','dump','--compressed',remote,text=True)
        if 'dumped to' in output:
            raw=u.adb('shell','cat',remote,text=True)
            return ET.fromstring(raw[raw.index('<?xml'):])
        time.sleep(.3)
    raise AssertionError('No fresh accessibility hierarchy: '+output)
u.tree=fresh_tree

def save():REPORT_PATH.write_text(json.dumps(REPORT,ensure_ascii=False,indent=2),encoding='utf-8')
def check(name,ok,detail=''):
    for previous in REPORT['checks']:
        if previous['name']==name and not previous['passed']:REPORT.setdefault('previous_attempts',[]).append(previous)
    REPORT['checks']=[r for r in REPORT['checks'] if r['name']!=name]
    REPORT['checks'].append({'name':name,'passed':bool(ok),'detail':detail,'apk_sha256':REPORT['apk_sha256']});save()
    print(('PASS ' if ok else 'FAIL ')+name,flush=True)
    if not ok:raise AssertionError(name+': '+str(detail))
def texts():return '\n'.join(n.get('text','') for n in u.tree().iter('node'))
def dump():print([(n.get('resource-id').split('/')[-1],n.get('text'),n.get('content-desc'),n.get('checked')) for n in u.tree().iter('node') if n.get('text') or n.get('content-desc')],flush=True)
def shot(name):
    u.hide_keyboard();path=OUT/(name+'.png');path.write_bytes(u.adb('exec-out','screencap','-p'))
    REPORT['screenshots']=[r for r in REPORT['screenshots'] if r['name']!=name]
    REPORT['screenshots'].append({'name':name,'path':str(path.relative_to(ROOT)),'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'apk_sha256':REPORT['apk_sha256']});save()
def scroll(direction='down'):
    nodes=[n for n in u.tree().iter('node') if n.get('scrollable')=='true']
    node=nodes[0] if nodes else None
    l,t,r,b=u.bounds(node) if node is not None else (0,200,1080,1900)
    x=(l+r)//2;a=t+(b-t)*3//4;z=t+(b-t)//4
    if direction=='up':a,z=z,a
    u.adb('shell','input','swipe',x,a,x,z,350);time.sleep(.25)
def locate(**kw):
    for _ in range(8):
        found=u.matching(u.tree(),**kw)
        if found:return found[0]
        scroll()
    raise AssertionError('Missing scrolled control '+repr(kw))
def tap(**kw):u.tap_node(locate(**kw))
def fill(value,identifier=None,description=None):
    node=locate(identifier=identifier,description=description)
    old=len(node.get('text',''));u.tap_node(node)
    u.adb('shell','input','keyevent','123')
    u.adb('shell','input','keyevent',*(['67']*(max(5,old)+2)))
    if value:u.adb('shell','input','text',value.replace(' ','%s'))
    u.hide_keyboard()
def home():u.adb('shell','am','start','-W','-n',u.PACKAGE+'/.HomeActivity')
def hub(page):u.adb('shell','am','start','-W','-n',u.PACKAGE+'/.HubActivity','--es','page',page)
def top():
    nodes=[n for n in u.tree().iter('node') if n.get('scrollable')=='true']
    if not nodes:return
    l,t,r,b=u.bounds(nodes[0]);x=(l+r)//2
    for _ in range(5):u.adb('shell','input','swipe',x,t+(b-t)//8,x,b-(b-t)//8,180)
    time.sleep(.3)
def fixture(mode):
    output=u.adb('shell','am','instrument','-w','-e','mode',mode,u.PACKAGE+'.test/kz.asyltas.android.PortalTestRunner',text=True,timeout=120)
    (QA/(mode+'.txt')).write_text(output,encoding='utf-8');check(mode,'FAIL' not in output and 'UI fixture' in output,output)

def installed_build(description='Final settings toggle, order filter, license buttons; Java formatting'):
    old=REPORT['apk_sha256'];new=hashlib.sha256((ROOT/'deliverables/AsylTas-3.0.apk').read_bytes()).hexdigest()
    for item in REPORT['checks']+REPORT['screenshots']:item.setdefault('apk_sha256',old)
    REPORT.setdefault('builds',[])
    if not REPORT['builds']:REPORT['builds'].append({'sha256':old,'description':'Initial 3.0 functional verification build'})
    if old!=new:REPORT['builds'].append({'sha256':new,'description':description})
    REPORT['apk_sha256']=new;save()

def registration():
    fixture('ui_begin');home()
    check('Legacy guest sample after upgrade', '616 200' in texts() and '5.90' in texts())
    shot('android_01_home_guest')
    u.tap(identifier='nav_profile');tap(text='Кіру немесе тіркелу');shot('android_02_login')
    u.tap(identifier='login_submit');time.sleep(1)
    check('Empty login validation appears',bool(u.find(identifier='login_error').get('text')))
    u.tap(identifier='login_switch');fill('Aruzhan',identifier='login_name');fill('qa3.verify@asyltas.test',identifier='login_email');fill('QaTest2026!',identifier='login_password')
    shot('android_03_registration');u.tap(identifier='login_submit');u.find(identifier='home_total',timeout=20)
    check('Registration opens home and imports guest copy',u.find(identifier='home_total').get('text')=='616 200 ₸')
    check('Registered profile appears on home','Aruzhan' in texts());shot('android_04_home_account')
    u.tap(identifier='nav_catalog');shot('android_05_catalog_photos')
    u.tap(identifier='stone_favorite');u.tap(identifier='favorite_only')
    names=[n.get('text') for n in u.matching(u.tree(),identifier='stone_name')]
    check('Favorites filter shows selected stone',names==['Гауһар'],names)
    u.tap(identifier='stone_info_action');shot('android_06_stone_photo')
    check('Stone detail and favorite actions appear',u.find(identifier='detail_name').get('text')=='Гауһар' and 'Таңдаулыдан' in texts())
    u.tap(identifier='nav_necklace');shot('android_07_necklace')
    check('Account necklace exact totals','616 200' in texts() and '5.90' in texts())

def checkout():
    u.tap(identifier='nav_profile');tap(description='Профильді өзгерту')
    fill('+77011234567',description='Телефон');fill('Almaty, Abay 10',description='Жеткізу мекенжайы');tap(text='Профильді сақтау')
    u.back();tap(description='Профильді өзгерту')
    check('Profile phone and address survive reopening','+77011234567' in texts() and 'Almaty, Abay 10' in texts());shot('android_08_profile_edit')
    u.tap(identifier='nav_necklace');u.tap(identifier='save_design');u.tap(identifier='android:id/button1')
    u.tap(identifier='checkout_button');check('Checkout uses exact necklace total','616 200' in texts())
    tap(text='Жеткізу');fill('Almaty, Abay 15',description='Жеткізу мекенжайы');tap(text='Сыйлық ретінде дайындау')
    tap(contains='Алу күні:');u.tap(identifier='android:id/button1');fill('Gift',description='Қосымша ескерту');tap(text='Мәліметтерді тексердім')
    shot('android_09_checkout')
    checkout_finish()

def checkout_finish():
    original={'accelerometer_rotation':u.adb('shell','settings','get','system','accelerometer_rotation',text=True).strip(),'user_rotation':u.adb('shell','settings','get','system','user_rotation',text=True).strip()}
    (QA/'rotation_original.json').write_text(json.dumps(original),encoding='utf-8')
    try:
        u.adb('shell','settings','put','system','accelerometer_rotation','0');u.adb('shell','settings','put','system','user_rotation','1');time.sleep(1)
        check('Checkout note survives landscape rotation',locate(description='Қосымша ескерту').get('text')=='Gift')
        shot('android_10_checkout_landscape');top()
        check('Checkout delivery and address survive rotation',locate(text='Жеткізу').get('checked')=='true' and locate(description='Жеткізу мекенжайы').get('text')=='Almaty, Abay 15')
    finally:
        for key,val in original.items():u.adb('shell','settings','put','system',key,val)
        time.sleep(1)
    tap(text='Өтінімді сақтау');time.sleep(.5)
    check('Order stores exact total and contact','616 200' in texts() and '+77011234567' in texts())
    check('Order stores gift and requested date','Сыйлық қаптамасы сұралған' in texts() and 'Күні:' in texts());shot('android_11_order')
    tap(text='Өтініммен бөлісу');time.sleep(.3)
    check('Order opens Android share chooser','android.intent.action.CHOOSER' in u.adb('shell','dumpsys','activity','activities',text=True));u.back()
    tap(text='Мәртебені өзгерту');u.tap(text='Қабылданды')
    check('Admin status changes visible','Қабылданды' in texts());shot('android_12_order_accepted')
    u.tap(identifier='nav_profile');tap(description='Менің өтінімдерім');check('Order appears in personal history','Қабылданды' in texts());shot('android_13_orders')

def features():
    home()
    u.tap(identifier='nav_profile');shot('android_14_profile');tap(description='Біз осындамыз')
    check('Example store location is clearly marked','ҮЛГІ ДҮКЕН' in texts() and 'Алматы' in texts());shot('android_15_location')
    tap(text='Картада ашу');time.sleep(.7)
    state=u.adb('shell','dumpsys','activity','activities',text=True)
    check('Map action launches geo or maps URL','geo:0,0?' in state or 'maps/search/' in state,state[-1200:]);home();u.tap(identifier='nav_profile');tap(description='Біз осындамыз')
    tap(text='Дүкен деректерін өзгерту')
    fill('Almaty, Kazakhstan',description='Дүкен мекенжайы');fill('+77011234567',description='Дүкен телефоны');fill('10:00 - 19:00',description='Жұмыс уақыты');tap(text='Дүкен деректерін сақтау')
    check('Admin updates store contact details','Almaty, Kazakhstan' in texts() and '10:00 - 19:00' in texts())
    tap(text='Қоңырау шалу');time.sleep(.3)
    state=u.adb('shell','dumpsys','activity','activities',text=True)
    check('Contact opens dialer without placing call','android.intent.action.DIAL' in state);home()
    u.tap(identifier='nav_profile');tap(description='Хабарламалар');fill('Pickup',description='Хабарлама тақырыбы');fill('Pickup time',description='Сұрағыңыз');tap(text='Хабарламаны сақтау')
    features_finish()

def features_finish():
    check('Support message is saved',locate(text='Pickup') is not None);tap(text='Жауап жазу');fill('Ready tomorrow',description='Жауап');u.tap(identifier='android:id/button1')
    check('Administrator reply appears',locate(contains='Ready tomorrow') is not None);shot('android_16_support')
    u.tap(identifier='nav_catalog');u.tap(identifier='stone_info_action');check('Stone detail and favorite actions appear',u.find(identifier='detail_name').get('text')=='Гауһар' and 'Таңдаулыдан' in texts())
    tap(text='Пікірлерді ашу');fill('Beautiful stone',description='Пікіріңіз');tap(text='Пікірді сақтау')
    check('Stone review with rating is visible','Beautiful stone' in texts() and '5 / 5' in texts());shot('android_17_review')
    u.tap(identifier='nav_profile');tap(description='Баптаулар');preferences()

def preferences():
    tap(contains='Алқа бейнесін көрсету');tap(text='Дыбыс қосулы');shot('android_18_settings')
    u.tap(identifier='nav_home');check('Preview preference hides necklace image',not u.matching(u.tree(),identifier='home_preview'))
    u.tap(identifier='nav_profile');tap(description='Баптаулар');tap(contains='Алқа бейнесін көрсету');tap(text='Дыбыс өшірулі');check('Settings restore preview and sound',locate(text='Дыбыс қосулы').get('checked')=='true');tap(description='Фотосуреттер авторлары')
    check('Photo attributions are available offline','Petragems' in texts() and 'CC BY-SA 4.0' in texts());shot('android_19_photo_credits')

def pick_file(kind,name):
    tap(text='Файл қосу');u.tap(text=kind)
    nodes=[n for n in u.tree().iter('node') if n.get('text')==name or n.get('content-desc','').startswith(name+',')]
    if not nodes:
        u.tap(description='Show roots');u.tap(text='Downloads')
    choose_document(name)

def choose_document(name):
    nodes=[n for n in u.tree().iter('node') if n.get('text')==name or n.get('content-desc','').startswith(name+',')]
    if not nodes:raise AssertionError('Document not visible: '+name)
    u.tap_node(nodes[0]);u.find(identifier='nav_profile')

def media_audio():
    u.tap(text='asyl_v3_qa_audio.wav');u.find(identifier='nav_profile')
    check('Audio file imported through system picker','asyl_v3_qa_audio.wav' in texts())
    tap(text='Ашу / ойнату');media_playback()

def media_playback():
    check('Audio duration loaded',u.find(contains='/ 0:06').get('text')=='0:00 / 0:06');tap(text='Ойнату');time.sleep(1)
    visible=texts();check('Audio playback advances','/ 0:06' in visible and '0:00 /' not in visible,visible);shot('android_21_audio')
    u.back();tap(text='Материал әрекеттері');u.tap(text='Атауын өзгерту');fill('Jewelry audio',description='Материал атауы');u.tap(identifier='android:id/button1')
    check('Media reference renamed','Jewelry audio' in texts());fill('missing',description='Материал іздеу');check('Media search empty state','Материал табылмады.' in texts());fill('',description='Материал іздеу')
    pick_file('Сурет / GIF','asyl_v3_qa_image.jpg');media_image()

def media_image():
    tap(text='Ашу / ойнату');check('Original image opened in media viewer','asyl_v3_qa_image.jpg' in texts() and 'Файл қолжетімсіз' not in texts());shot('android_22_image');u.back()
    pick_file('Видео','asyl_v3_qa_video.mp4');tap(text='Ашу / ойнату');media_video_finish()

def media_video_finish():
    duration=u.find(contains='/ 0:0').get('text');check('Video duration loaded',duration!='0:00 / 0:00',duration);tap(text='Ойнату');time.sleep(1);shot('android_23_video');u.back()
    check('Media library retains all three types',all(locate(text=name) is not None for name in ['asyl_v3_qa_video.mp4','asyl_v3_qa_image.jpg','Jewelry audio']));top();shot('android_24_media_library')
    tap(text='Материал әрекеттері');u.tap(text='Тізімнен жою');u.tap(identifier='android:id/button1')
    check('Media removal keeps original file',u.adb('shell','test','-f','/sdcard/Download/asyl_v3_qa_video.mp4',text=True)=='')

def logout():
    u.tap(identifier='nav_profile');tap(text='Аккаунттан шығу');u.tap(identifier='android:id/button1');u.find(identifier='login_submit')

def customer():
    home();logout();u.tap(identifier='login_switch');fill('Dana',identifier='login_name');fill('qa3.customer@asyltas.test',identifier='login_email');fill('QaCustomer2026!',identifier='login_password')
    if locate(identifier='login_copy').get('checked')=='true':u.tap(identifier='login_copy')
    u.tap(identifier='login_submit');u.find(identifier='home_total',timeout=20)
    check('Second account has an independent empty necklace',u.find(identifier='home_total').get('text')=='0 ₸')
    u.tap(identifier='nav_profile');tap(description='Менің өтінімдерім');check('Second account cannot see first account orders','Әзірге өтінім жоқ' in texts())
    u.tap(identifier='nav_catalog');u.tap(identifier='favorite_only');check('Second account has no private favorites','Тас табылмады' in texts());u.tap(identifier='favorite_only')
    u.tap(identifier='stone_add');fill('0',identifier='quantity_input');u.tap(identifier='android:id/button1');check('Zero stone quantity rejected','1–99' in u.find(identifier='quantity_error').get('text'))
    fill('2',identifier='quantity_input');u.tap(identifier='android:id/button1');u.tap(identifier='nav_necklace')
    check('Two diamonds calculate exactly','630 000' in texts() and '1.50' in texts())
    u.tap(identifier='item_plus');check('Quantity plus updates exact total','945 000' in texts());u.tap(identifier='item_minus');check('Quantity minus restores exact total','630 000' in texts())
    u.demo();check('Demo sample remains exact in new account','616 200' in texts() and '5.90' in texts());shot('android_07_necklace')
    u.tap(identifier='filter_button');fill('80',identifier='min_input');fill('80',identifier='max_input');u.tap(identifier='search_button')
    check('Inclusive transparency endpoints return emerald','1 түр, 1 тас' in texts() and 'Зүбәржат' in texts());check('Filter leaves full necklace total unchanged','616 200' in texts())
    u.tap(identifier='filter_button');u.tap(identifier='reset_button');u.tap(identifier='save_design');u.tap(identifier='android:id/button1');u.tap(identifier='nav_home');u.tap(identifier='home_saved')
    check('Only own saved collection is listed','1 сақталған' in texts());shot('android_25_saved')
    u.tap(identifier='nav_necklace');u.tap(identifier='checkout_button');fill('+77011234567',description='Байланыс телефоны');tap(text='Мәліметтерді тексердім');tap(text='Өтінімді сақтау')
    check('Customer creates own order','616 200' in texts());tap(text='Өтінімнен бас тарту');u.tap(identifier='android:id/button1');check('Customer cancels own draft','Бас тартылды' in texts());shot('android_26_cancelled_order')
    customer_finish()

def customer_finish():
    logout();fill('qa3.verify@asyltas.test',identifier='login_email');fill('IncorrectPass',identifier='login_password');u.tap(identifier='login_submit');time.sleep(.5);check('Wrong password is rejected on real login screen','қате' in u.find(identifier='login_error').get('text'))
    fill('QaTest2026!',identifier='login_password');u.tap(identifier='login_submit');u.find(identifier='home_total',timeout=20)
    check('Owner login restores original necklace','616 200' in texts() and 'Aruzhan' in texts());u.tap(identifier='nav_profile');tap(description='Менің өтінімдерім');check('Original owner order remains accepted','Қабылданды' in texts() and 'Бас тартылды' not in texts())
    shot('android_13_orders');u.tap(identifier='nav_profile');tap(text='Дүкенді басқару');check('Admin overview includes both local accounts','2 аккаунт' in texts());shot('android_27_admin')

def adaptive():
    old={key:u.adb('shell','settings','get','system',key,text=True).strip() for key in ['accelerometer_rotation','user_rotation','font_scale']}
    size=u.adb('shell','wm','size',text=True);density=u.adb('shell','wm','density',text=True)
    (QA/'display_original.json').write_text(json.dumps({'settings':old,'size':size,'density':density}),encoding='utf-8')
    try:
        u.adb('shell','settings','put','system','accelerometer_rotation','0');u.adb('shell','settings','put','system','user_rotation','0')
        u.adb('shell','wm','size','990x1760');u.adb('shell','wm','density','440');u.adb('shell','settings','put','system','font_scale','1.3');time.sleep(1)
        u.tap(identifier='nav_necklace');node=u.find(identifier='necklace_list');b=u.bounds(node)
        check('360x640 dp with 130 percent font keeps editable list',b[3]-b[1]>160 and u.find(identifier='item_plus') is not None);shot('android_28_small_portrait')
        u.adb('shell','settings','put','system','user_rotation','1');time.sleep(1)
        node=u.find(identifier='necklace_list');b=u.bounds(node);check('Landscape keeps list and checkout accessible',b[3]-b[1]>160 and locate(identifier='checkout_button') is not None);shot('android_29_small_landscape')
        u.tap(identifier='nav_catalog');check('Landscape catalog has readable stone rows',u.find(identifier='stone_name') is not None);shot('android_30_catalog_landscape')
    finally:
        for key,val in old.items():u.adb('shell','settings','put','system',key,val)
        for key,text in [('size',size),('density',density)]:
            match=re.search(r'Override '+key+r':\s*(\S+)',text,re.I);u.adb('shell','wm',key,match.group(1) if match else 'reset')
        time.sleep(1)
    home();u.tap(identifier='nav_profile')
    def hub_count():return len(re.findall(r'Hist #\d+:.*\.HubActivity',u.adb('shell','dumpsys','activity','activities',text=True)))
    initial=hub_count()
    for _ in range(3):u.tap(identifier='nav_home');u.tap(identifier='nav_profile')
    check('Repeated profile navigation reuses existing Activity',hub_count()==initial);shot('android_31_profile_final')
    u.tap(identifier='nav_home');shot('android_04_home_account')
    u.tap(identifier='nav_catalog');shot('android_05_catalog_photos')
    u.tap(identifier='stone_info_action');shot('android_06_stone_photo')






if __name__=='__main__':
    globals()[sys.argv[1]]()
