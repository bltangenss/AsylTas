"""Small real-device UI QA harness; writes only the v4 report/screenshot directories."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import time
import xml.etree.ElementTree as ET
import android_labs_ui as u

ROOT = Path(__file__).resolve().parents[1]
QA = ROOT / 'reports/qa_android_v4_20261125'
OUT = ROOT / 'screenshots/redesign_20261124/v4_20261125'
QA.mkdir(parents=True, exist_ok=True)
OUT.mkdir(parents=True, exist_ok=True)
u.QA = QA
u.OUT = OUT
PATH = QA / 'ui_checks.json'
APK_HASH = hashlib.sha256((ROOT / 'deliverables/AsylTas-4.0.apk').read_bytes()).hexdigest()
REPORT = json.loads(PATH.read_text(encoding='utf-8')) if PATH.exists() else {
    'status': 'running', 'started_utc': datetime.now(timezone.utc).isoformat(),
    'device': u.DEVICE, 'api': 29, 'apk_sha256': APK_HASH,
    'checks': [], 'screenshots': [], 'gps_test_environment': 'Android emulator; synthetic ENU coordinates, no physical GPS validation'}
assert REPORT['apk_sha256'] == APK_HASH, 'Start a new report for a new APK build'


def tree():
    remote = '/sdcard/asyl_v4_ui.xml'
    for attempt in range(3):
        result = u.adb('shell', 'uiautomator', 'dump', '--compressed', remote, text=True)
        if 'dumped to' in result:
            raw = u.adb('shell', 'cat', remote, text=True)
            return ET.fromstring(raw[raw.index('<?xml'):])
        time.sleep(.3)
    raise RuntimeError('No fresh UI hierarchy')


u.tree = tree


def save():
    PATH.write_text(json.dumps(REPORT, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def check(name, passed, detail=''):
    previous = [x for x in REPORT['checks'] if x['name'] == name]
    if previous:
        REPORT.setdefault('previous_attempts', []).extend(previous)
    REPORT['checks'] = [x for x in REPORT['checks'] if x['name'] != name]
    REPORT['checks'].append({'name': name, 'passed': bool(passed), 'detail': detail, 'apk_sha256': APK_HASH})
    save()
    print(('PASS ' if passed else 'FAIL ') + name, flush=True)
    if not passed:
        raise AssertionError(name + ': ' + str(detail))


def texts():
    return '\n'.join(n.get('text', '') for n in tree().iter('node'))


def dump():
    print(json.dumps([{k: n.get(k) for k in ('resource-id', 'text', 'content-desc', 'checked', 'enabled', 'bounds')}
                      for n in tree().iter('node') if n.get('text') or n.get('content-desc')], ensure_ascii=False, indent=2))


def scroll(up=False):
    nodes = [n for n in tree().iter('node') if n.get('scrollable') == 'true' and 'Horizontal' not in n.get('class', '')]
    if not nodes:
        raise AssertionError('No scrollable content')
    l, t, r, b = u.bounds(nodes[0])
    a, z = t + (b-t)*3//4, t + (b-t)//4
    if up:
        a, z = z, a
    u.adb('shell', 'input', 'swipe', (l+r)//2, a, (l+r)//2, z, 250)
    time.sleep(.2)


def find(**selector):
    for attempt in range(9):
        found = u.matching(tree(), **selector)
        if found:
            return found[0]
        scroll()
    raise AssertionError('Missing scrollable control: ' + repr(selector))


def tap(**selector):
    u.tap_node(find(**selector))


def fill(value, **selector):
    node = find(**selector)
    old = len(node.get('text', ''))
    u.tap_node(node)
    u.adb('shell', 'input', 'keyevent', '123')
    u.adb('shell', 'input', 'keyevent', *(['67'] * (max(5, old)+2)))
    if value:
        u.adb('shell', 'input', 'text', value.replace(' ', '%s'))
    u.hide_keyboard()


def shot(name):
    u.hide_keyboard()
    path = OUT / (name+'.png')
    path.write_bytes(u.adb('exec-out', 'screencap', '-p'))
    REPORT['screenshots'] = [x for x in REPORT['screenshots'] if x['name'] != name]
    REPORT['screenshots'].append({'name': name, 'path': path.relative_to(ROOT).as_posix(),
                                  'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'apk_sha256': APK_HASH})
    save()


def home():
    u.adb('shell', 'am', 'start', '-W', '-f', '0x14000000', '-n', u.PACKAGE+'/.HomeActivity')
    u.find(identifier='home_total')


def catalog():
    u.adb('shell', 'am', 'start', '-W', '-f', '0x14000000', '-n', u.PACKAGE+'/.MainActivity')
    u.find(identifier='catalog_search')


def stone(alias):
    catalog()
    fill(alias, identifier='catalog_search')
    tap(identifier='stone_info_action')


def top():
    for _ in range(6):
        try:
            scroll(up=True)
        except AssertionError:
            break


def finish():
    assert REPORT['checks'] and all(row['passed'] for row in REPORT['checks'])
    REPORT['status'] = 'passed'
    REPORT['finished_utc'] = datetime.now(timezone.utc).isoformat()
    save()
    print('PASS:', len(REPORT['checks']), 'Android UI checks;', len(REPORT['screenshots']), 'screenshots')


if __name__ == '__main__':
    dump()
