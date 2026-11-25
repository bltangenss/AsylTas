"""Verify the published v4 API using an isolated anonymous session; no accounts are created."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import http.cookiejar
import json
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BASE = 'https://asyl-tas-collection.sacredbracelet4.chatgpt.site'
QA = ROOT / 'reports/qa_online_v4_20261125'
session = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
state = None
checks = []


def check(name, value):
    checks.append({'name': name, 'passed': bool(value)})
    print(('PASS ' if value else 'FAIL ') + name, flush=True)
    if not value:
        raise AssertionError(name)


def fetch(path, body=None, extra=None):
    headers = {'Accept': 'application/json', 'User-Agent': 'AsylTas-release-QA/4.0'}
    if body is not None:
        headers.update({'Content-Type': 'application/json', 'Origin': BASE, 'X-CSRF-Token': state['csrf']})
    headers.update(extra or {})
    request = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None, headers=headers)
    try:
        result = session.open(request, timeout=40)
    except urllib.error.HTTPError as error:
        result = error
    return result.code, result.headers, result.read()


def action(action_name, **fields):
    global state
    code, _, raw = fetch('/api/action', {'action': action_name, **fields})
    value = json.loads(raw)
    assert code == 200 and value.get('ok'), (action_name, code, value.get('error'))
    state = value
    return value


def main():
    global state
    status = 'failed'
    collection = None
    cleanup = False
    try:
        code, headers, raw = fetch('/api/state')
        check('Public v4 state responds successfully', code == 200)
        state = json.loads(raw)
        check('Anonymous state has no account or owner claim', state['user'] is None and not state.get('admin_claim_available'))
        check('Personal API is not cached', 'no-store' in headers.get('Cache-Control', ''))
        expected = json.loads((ROOT / 'shared/catalog/catalog_v4.json').read_text(encoding='utf-8'))
        check('All48 catalog records match the shared dataset', state['catalog'] == expected)
        check('New guest starts with empty necklace', state['necklace']['count'] == 0)
        check('Public state exposes no user list', not state.get('users'))
        csrf_code, _, csrf_raw = fetch('/api/action', {'action': 'necklace_demo'}, {'X-CSRF-Token': 'invalid-test-token'})
        check('Invalid CSRF token is rejected', csrf_code == 403 and not json.loads(csrf_raw)['ok'])
        origin_code, _, _ = fetch('/api/action', {'action': 'necklace_demo'}, {'Origin': 'https://invalid.example'})
        check('Foreign origin is rejected', origin_code == 403)
        action('necklace_set', stone_id=48, quantity=2)
        check('New stone48 has exact online totals', state['necklace']['total'] == '18400.00' and state['necklace']['carats'] == '4.60')
        action('collection_save', name='V4 anonymous verification')
        collection = state['collections'][0]['id']
        action('necklace_clear')
        action('collection_load', id=collection)
        check('Guest collection reload preserves new IDs', state['necklace']['items'][0]['stone_id'] == 48 and state['necklace']['count'] == 2)
        action('collection_delete', id=collection)
        collection = None
        action('necklace_demo')
        check('Original demo remains6/5.90/616200', state['necklace']['count'] == 6 and state['necklace']['carats'] == '5.90' and state['necklace']['total'] == '616200.00')
        action('comparison_toggle', stone_id=13)
        check('Live comparison persists new stone ID', 13 in state['comparison'])
        action('note_save', stone_id=48, text='V4 anonymous QA private note')
        check('Guest notes stay in the current anonymous session', any(row['stone_id'] == 48 for row in state['notes']))
        other_request = urllib.request.Request(BASE + '/api/state', headers={'User-Agent': 'AsylTas-release-QA/4.0', 'Accept': 'application/json'})
        with urllib.request.urlopen(other_request, timeout=40) as other_response:
            other = json.load(other_response)
        check('Another anonymous visitor cannot read the private note', not other['notes'])
        action('note_save', stone_id=48, text='')
        action('comparison_clear')
        action('history_clear')
        action('necklace_clear')
        check('Anonymous fixture returns to empty state', not state['notes'] and not state['comparison'] and not state['collections'] and state['necklace']['count'] == 0)
        cleanup = True
        credits = json.loads((ROOT / 'site_online/public/assets/photo_credits.json').read_text(encoding='utf-8'))
        for stone in (1, 13, 24, 36, 48):
            code, _, photo = fetch(f'/assets/stone_{stone}.jpg')
            check(f'Published photo{stone} matches licensed source bytes', code == 200 and hashlib.sha256(photo).hexdigest() == next(row['sha256'] for row in credits if row['id'] == stone))
        code, _, campus = fetch('/assets/campus_v4.json')
        check('Published campus data matches verified destinations', code == 200 and json.loads(campus) == json.loads((ROOT / 'shared/catalog/campus_v4.json').read_text(encoding='utf-8')))
        code, _, sw = fetch('/sw.js')
        check('Published PWA service worker includes updated photo range', code == 200 and b'48' in sw)
        status = 'passed'
    finally:
        if state and not cleanup:
            try:
                if collection is not None:
                    action('collection_delete', id=collection)
                for row in list(state.get('notes', [])):
                    action('note_save', stone_id=row['stone_id'], text='')
                for name in ('comparison_clear', 'history_clear', 'necklace_clear'):
                    action(name)
                cleanup = True
            except Exception:
                cleanup = False
        report = {'status': status, 'url': BASE, 'created_utc': datetime.now(timezone.utc).isoformat(),
                  'checks': checks, 'passed': sum(row['passed'] for row in checks),
                  'anonymous_fixture_cleaned': cleanup, 'accounts_created': 0,
                  'other_users_records_modified': False}
        (QA / 'live_checks.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
