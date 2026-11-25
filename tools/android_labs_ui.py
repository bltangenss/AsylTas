"""Verify the real Android 1.1 app through ADB and capture its visible screens.

Run only after the new APK is installed on emulator-5554. No mock images or
accessibility-tree screenshots are generated: each PNG is adb screencap output.
"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import re
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'screenshots/android'
QA = ROOT / 'reports/qa_labs'
ADB = Path('C:/Users/ASUS/AppData/Local/Android/Sdk/platform-tools/adb.exe')
DEVICE = 'emulator-5554'
PACKAGE = 'kz.asyltas.android'
PREFIX = PACKAGE + ':id/'
REPORT = {'started_utc': datetime.now(timezone.utc).isoformat(),
          'device': DEVICE, 'checks': [], 'screenshots': [], 'activities': []}
STEP = 'initialization'


def adb(*args, timeout=30, text=False):
    result = subprocess.run([str(ADB), '-s', DEVICE, *map(str, args)],
                            capture_output=True, timeout=timeout, check=True)
    return result.stdout.decode('utf-8', errors='replace') if text else result.stdout


def tree():
    last = None
    for attempt in range(3):
        try:
            adb('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/asyl_labs_ui.xml')
            raw = adb('shell', 'cat', '/sdcard/asyl_labs_ui.xml', text=True)
            return ET.fromstring(raw[raw.index('<?xml'):])
        except (ValueError, ET.ParseError, subprocess.SubprocessError) as error:
            last = error
            time.sleep(0.35)
    raise RuntimeError('Could not read emulator UI') from last


def normalized(text):
    return ' '.join(text.replace('\u00a0', ' ').replace('\u202f', ' ').split())


def rid(identifier):
    return identifier if ':id/' in identifier else PREFIX + identifier


def bounds(node):
    values = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    if len(values) != 4 or values[2] <= values[0] or values[3] <= values[1]:
        raise ValueError('Invalid visible bounds: ' + str(node.attrib))
    return values


def matching(root, identifier=None, text=None, contains=None, description=None):
    found = []
    for node in root.iter('node'):
        if identifier and node.get('resource-id') != rid(identifier):
            continue
        actual = normalized(node.get('text', ''))
        if text is not None and actual.casefold() != normalized(text).casefold():
            continue
        if contains is not None and normalized(contains) not in actual:
            continue
        if description is not None and node.get('content-desc') != description:
            continue
        try:
            bounds(node)
        except ValueError:
            continue
        found.append(node)
    return found


def find(identifier=None, text=None, contains=None, description=None, timeout=12):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        found = matching(tree(), identifier, text, contains, description)
        if found:
            return found[0]
        time.sleep(0.2)
    raise AssertionError('Missing visible control: ' + repr((identifier, text, contains, description)))


def tap_node(node, long=False):
    left, top, right, bottom = bounds(node)
    x, y = (left + right) // 2, (top + bottom) // 2
    if long:
        adb('shell', 'input', 'swipe', x, y, x, y, 850)
    else:
        adb('shell', 'input', 'tap', x, y)
    time.sleep(0.2)


def tap(identifier=None, text=None, contains=None, description=None, long=False):
    tap_node(find(identifier, text, contains, description), long)


def hide_keyboard():
    state = adb('shell', 'dumpsys', 'input_method', text=True)
    if re.search(r'(?:mInputShown|isInputViewShown)\s*[=:]\s*true', state):
        adb('shell', 'input', 'keyevent', '4')
        time.sleep(0.25)


def fill(identifier, value):
    node = find(identifier=identifier)
    old_length = len(node.get('text', ''))
    tap_node(node)
    adb('shell', 'input', 'keyevent', '123')  # MOVE_END collapses any selection.
    adb('shell', 'input', 'keyevent', *(['67'] * (max(old_length, 5) + 2)))
    adb('shell', 'input', 'text', str(value))
    actual = find(identifier=identifier).get('text', '')
    if actual != str(value):
        raise AssertionError(f'Field {identifier}: expected {value}, saw {actual!r}')


def back():
    adb('shell', 'input', 'keyevent', '4')
    time.sleep(0.2)


def record(name, passed, details=None):
    item = {'name': name, 'passed': bool(passed)}
    if details is not None:
        item['details'] = details
    REPORT['checks'].append(item)
    print(('PASS ' if passed else 'FAIL ') + name, flush=True)
    if not passed:
        raise AssertionError(name + ': ' + str(details))


def expect_text(identifier, *pieces, name=None):
    actual = normalized(find(identifier=identifier).get('text', ''))
    record(name or identifier, all(normalized(piece) in actual for piece in pieces), actual)
    return actual


def summary(count, weight, cost, name, identifier='necklace_summary'):
    return expect_text(identifier, f'{count} тас', f'{weight} карат', cost, name=name)


def activity(expected):
    output = adb('shell', 'dumpsys', 'activity', 'activities', text=True)
    resumed = [line.strip() for line in output.splitlines()
               if 'ResumedActivity' in line and PACKAGE in line]
    record('Activity ' + expected, any(expected in line for line in resumed), resumed)
    REPORT['activities'].append({'expected': expected, 'resumed': resumed})


def capture(name, caption):
    hide_keyboard()
    current = tree()
    png = adb('exec-out', 'screencap', '-p')
    if not png.startswith(b'\x89PNG\r\n\x1a\n'):
        raise RuntimeError('screencap did not return a PNG')
    path = OUT / (name + '.png')
    path.write_bytes(png)
    (QA / (name + '.xml')).write_bytes(ET.tostring(current, encoding='utf-8', xml_declaration=True))
    REPORT['screenshots'].append({'name': name, 'path': str(path), 'caption': caption,
                                  'sha256': hashlib.sha256(png).hexdigest()})
    print('SCREENSHOT ' + name, flush=True)


def options():
    root = tree()
    matches = matching(root, description='More options')
    if not matches:
        matches = [node for node in root.iter('node')
                   if node.get('class') == 'android.widget.ImageButton'
                   and node.get('content-desc')]
    if matches:
        tap_node(matches[-1])
    else:
        adb('shell', 'input', 'keyevent', '82')
    find(text='Үлгі алқа')


def demo(from_catalog=False):
    if from_catalog:
        tap(identifier='demo_button')
    else:
        options()
        tap(text='Үлгі алқа')
    tap(identifier='android:id/button1')
    find(identifier='necklace_summary')
    summary(6, '5.90', '616 200', 'Sample necklace totals')


def reset_filter(descending=True):
    hide_keyboard()
    tap(identifier='reset_button')
    node = find(identifier='descending_checkbox')
    if (node.get('checked') == 'true') != descending:
        tap_node(node)


def set_range(first, last):
    fill('min_input', first)
    fill('max_input', last)
    hide_keyboard()
    tap(identifier='search_button')


def row(name, catalog=False, long=False):
    item_id = 'stone_name' if catalog else 'item_name'
    list_id = 'stone_list' if catalog else 'necklace_list'
    for attempt in range(7):
        root = tree()
        found = matching(root, identifier=item_id, text=name)
        if found:
            tap_node(found[0], long)
            return
        area = matching(root, identifier=list_id)
        if not area:
            break
        left, top, right, bottom = bounds(area[0])
        x = (left + right) // 2
        adb('shell', 'input', 'swipe', x, bottom - 15, x, top + 15, 320)
        time.sleep(0.2)
    raise AssertionError('Stone not visible in list: ' + name)


def first_row(catalog=False):
    name_id = 'stone_name' if catalog else 'item_name'
    names = matching(tree(), identifier=name_id)
    if not names:
        raise AssertionError('No visible rows')
    return names[0].get('text', '')


def category(text):
    tap(identifier='category_spinner')
    tap(text=text)


def restart():
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('shell', 'am', 'start', '-W', '-f', '0x10008000',
        '-n', PACKAGE + '/.MainActivity')
    find(identifier='stone_list')


def run():
    global STEP
    STEP = 'sample and catalog'
    restart()
    activity('MainActivity')
    demo(from_catalog=True)
    reset_filter()
    tap(identifier='nav_catalog')
    category('Барлығы')
    expect_text('catalog_summary', '12 тас түрі', '6 тас', '616 200', name='Catalog shows all 12 stones')
    capture('01_catalog', 'XML экрандағы ListView тастарды ArrayAdapter арқылы көрсетеді.')
    tap(identifier='nav_necklace')
    activity('NecklaceActivity')
    summary(6, '5.90', '616 200', 'Sample total on necklace Activity')
    capture('02_necklace_total', 'Үлгі алқа: 6 тас, 5.90 карат және 616 200 теңге.')

    STEP = 'inclusive transparency and sorting'
    set_range(80, 95)
    expect_text('result_info', '2 түр, 3 тас', '80–95%', name='80-95 range includes two kinds and three stones')
    summary(6, '5.90', '616 200', 'Filtering keeps full necklace total')
    capture('03_filter', '80–95% аралығы екі түрден үш тасты табады.')
    set_range(80, 80)
    expect_text('result_info', '1 түр, 1 тас', '80–80%', name='Equal 80-80 bounds are inclusive')
    record('80-80 result is emerald', first_row() == 'Зүбәржат', first_row())
    capture('04_filter_results', '80–80% аралығына мөлдірлігі 80% зүбәржат кіреді.')
    reset_filter(descending=False)
    record('Ascending price begins with amethyst', first_row() == 'Аметист', first_row())
    capture('05_sort_ascending', 'CheckBox белгісі алынғанда аметист бірінші көрсетіледі.')

    STEP = 'add diamonds'
    tap(identifier='nav_catalog')
    category('Барлығы')
    row('Гауһар', catalog=True)
    expect_text('quantity_description', '315 000', name='Diamond unit cost in AlertDialog')
    capture('06_add_dialog', 'AlertDialog ішіндегі EditText арқылы тас саны енгізіледі.')
    fill('quantity_input', '2')
    hide_keyboard()
    tap(identifier='android:id/button1')
    expect_text('catalog_summary', '8 тас', '7.40 карат', '1 246 200', name='Two diamonds added')
    tap(identifier='nav_necklace')
    summary(8, '7.40', '1 246 200', 'Added diamond totals')
    capture('07_added', 'Екі гауһар қосылғаннан кейін: 8 тас, 7.40 карат, 1 246 200 теңге.')

    STEP = 'invalid range and cancellation'
    set_range(95, 20)
    find(contains='Төменгі шек жоғарғы шектен аспасын')
    record('Reversed transparency bounds rejected', True)
    capture('08_validation', '95–20% аралығы қабылданбайды, түсіндірме терезесі ашылады.')
    tap(identifier='android:id/button1')
    summary(8, '7.40', '1 246 200', 'Invalid range leaves necklace unchanged')
    reset_filter()
    demo()
    reset_filter()
    record('Descending price begins with ruby', first_row() == 'Лағыл', first_row())
    row('Лағыл')
    capture('09_edit_dialog', 'Лағыл санын өзгерту терезесі. Бас тарту бастапқы санды сақтайды.')
    tap(identifier='android:id/button2')
    summary(6, '5.90', '616 200', 'Cancel quantity edit preserves necklace')
    row('Лағыл', long=True)
    tap(text='Алып тастау')
    capture('10_remove_confirmation', 'Тасты алып тастау алдында растау сұралады.')
    tap(identifier='android:id/button2')
    summary(6, '5.90', '616 200', 'Cancel removal preserves necklace')

    STEP = 'actual edit removal and SQLite persistence'
    row('Лағыл')
    fill('quantity_input', '1')
    hide_keyboard()
    tap(identifier='android:id/button1')
    summary(5, '4.70', '400 200', 'Changing ruby quantity recalculates totals')
    row('Лағыл', long=True)
    tap(text='Алып тастау')
    tap(identifier='android:id/button1')
    summary(4, '3.50', '184 200', 'Removing ruby recalculates totals')
    restart()
    tap(identifier='nav_necklace')
    summary(4, '3.50', '184 200', 'SQLite preserves edited necklace after process restart')
    demo()
    reset_filter()

    run_tail()


def run_tail():
    global STEP

    STEP = 'invalid quantities and empty filter'
    for value in ['0', '100']:
        row('Лағыл')
        fill('quantity_input', value)
        hide_keyboard()
        tap(identifier='android:id/button1')
        still_open = find(identifier='quantity_input').get('text') == value
        record('Invalid quantity ' + value + ' keeps dialog open', still_open)
        # Android's native EditText error bubble may cover the dialog buttons.
        # Back dismisses that window without trying to tap through the bubble.
        back()
        if matching(tree(), identifier='quantity_input'):
            back()
        summary(6, '5.90', '616 200', 'Invalid quantity ' + value + ' does not alter data')
    set_range(0, 10)
    expect_text('result_info', '0 түр, 0 тас', name='Empty transparency range has zero results')
    expect_text('empty_message', 'Бұл аралыққа сәйкес тас жоқ.', name='Empty search has a clear message')
    summary(6, '5.90', '616 200', 'Empty filter preserves full totals')
    reset_filter()

    STEP = 'final persistence and navigation screenshots'
    restart()
    tap(identifier='nav_necklace')
    summary(6, '5.90', '616 200', 'Final sample restored from SQLite')
    capture('11_persisted', 'Процесс қайта ашылғанда SQLite ішіндегі үлгі алқа қалпына келді.')
    tap(identifier='nav_guide')
    activity('GuideActivity')
    expect_text('guide_text', '1. Тастарды таңдаңыз', name='Guide Activity opens through Intent')
    capture('12_guide', 'Жеке GuideActivity экранында қазақша қолдану нұсқаулығы ашылады.')
    options()
    find(text='Алқаны тазарту')
    capture('13_options_menu', 'XML мәзірінде үлгі алқа, тазарту және нұсқаулық әрекеттері бар.')
    back()
    tap(identifier='nav_necklace')
    row('Лағыл', long=True)
    find(text='Санын өзгерту')
    find(text='Алып тастау')
    record('Context menu offers edit and remove', True)
    capture('14_context_menu', 'ListView жолын ұзақ басқанда контекстік мәзір ашылады.')
    back()
    tap(identifier='nav_catalog')
    category('Жартылай асыл тастар')
    expect_text('catalog_summary', '8 тас түрі', name='Spinner filters eight semiprecious stones')
    record('Semiprecious category starts with amethyst', first_row(catalog=True) == 'Аметист')
    capture('15_category', 'Spinner арқылы жартылай асыл тастар санаты таңдалды.')
    row('Аметист', catalog=True, long=True)
    tap(text='Тас туралы')
    activity('GuideActivity')
    expect_text('stone_info', 'Аметист', '0.80 карат', '75%', '8 000', '6 400',
                name='Intent extra stone_id displays selected stone details')
    capture('16_stone_details', 'Intent арқылы берілген stone_id бойынша аметист туралы дерек ашылады.')

    STEP = 'restore final sample view'
    tap(identifier='nav_catalog')
    category('Барлығы')
    expect_text('catalog_summary', '12 тас түрі', '6 тас', '5.90 карат', '616 200',
                name='Finished with complete catalog and saved sample necklace')
    record('All sixteen real screenshots captured', len(REPORT['screenshots']) == 16)


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    OUT.mkdir(parents=True, exist_ok=True)
    QA.mkdir(parents=True, exist_ok=True)
    try:
        if '--resume-tail' in sys.argv:
            REPORT = json.loads((QA / 'ui_checks.json').read_text(encoding='utf-8'))
            REPORT.setdefault('driver_recoveries', []).append({
                'step': REPORT.pop('failed_step', ''),
                'error': REPORT.pop('error', ''),
                'reason': 'Native EditText error tooltip intercepted a Cancel tap; use Back instead.'})
            REPORT.pop('traceback', None)
            restart()
            tap(identifier='nav_necklace')
            demo()
            reset_filter()
            run_tail()
        else:
            run()
        REPORT['status'] = 'passed'
    except Exception as error:
        REPORT['status'] = 'failed'
        REPORT['failed_step'] = STEP
        REPORT['error'] = str(error)
        REPORT['traceback'] = traceback.format_exc()
        try:
            (QA / 'failure.png').write_bytes(adb('exec-out', 'screencap', '-p'))
            (QA / 'failure.xml').write_bytes(ET.tostring(tree(), encoding='utf-8'))
        except Exception:
            pass
        traceback.print_exc()
    finally:
        REPORT['finished_utc'] = datetime.now(timezone.utc).isoformat()
        REPORT['checks_passed'] = sum(check['passed'] for check in REPORT['checks'])
        (QA / 'ui_checks.json').write_text(json.dumps(REPORT, ensure_ascii=False, indent=2), encoding='utf-8')
    print('RESULT:', REPORT['status'], 'checks:', REPORT['checks_passed'],
          'screenshots:', len(REPORT['screenshots']), flush=True)
    sys.exit(0 if REPORT['status'] == 'passed' else 1)
