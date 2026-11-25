import catalog from './catalog.js';

const SESSION_AGE = 30 * 24 * 60 * 60;
const PASSWORD_ROUNDS = 100000;
const COOKIE_NAME = 'asyltas_session';
const MAX_BODY = 65536;
const DEFAULT_STORE = {address:'Алматы, Қазақстан',phone:'',hours:'',example:true};
const encoder = new TextEncoder();
const byId = new Map(catalog.map(stone => [stone.id, stone]));
const now = () => Math.floor(Date.now() / 1000);
const timestamp = () => new Date().toISOString();
const guard = 'EXISTS(SELECT 1 FROM mutation_guards WHERE id=?)';
const catalogPayload = JSON.stringify(catalog);

class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

function text(data, field, min = 0, max = 500, strip = true) {
  let value = data[field] ?? '';
  if (typeof value !== 'string') throw new ApiError(400, 'Мәтін өрісінің пішімі дұрыс емес.');
  if (strip) value = value.trim();
  const size = Array.from(value).length;
  if (size < min || size > max || /[\u0000-\u0008\u000b-\u001f\u007f]/u.test(value)) {
    throw new ApiError(400, `«${field}» өрісінде ${min}–${max} таңба болуы керек.`);
  }
  return value;
}

function integer(data, field, min, max) {
  const value = data[field];
  if (!Number.isSafeInteger(value) || value < min || value > max) {
    throw new ApiError(400, `«${field}» мәні ${min}–${max} аралығындағы бүтін сан болуы керек.`);
  }
  return value;
}

function stoneId(data) {
  const id = integer(data, 'stone_id', 1, 48);
  if (!byId.has(id)) throw new ApiError(400, 'Каталогта мұндай тас жоқ.');
  return id;
}

function boolean(data, field) {
  const value = data[field] ?? false;
  if (typeof value !== 'boolean') throw new ApiError(400, 'Белгінің мәні дұрыс емес.');
  return value;
}

function email(data) {
  const value = text(data, 'email', 3, 254).toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/u.test(value)) throw new ApiError(400, 'Email мекенжайын дұрыс енгізіңіз.');
  return value;
}

const password = (data, field = 'password') => text(data, field, 8, 128, false);

function phone(data, required = false) {
  const value = text(data, 'phone', 0, 32);
  const digits = value.replace(/\D/g, '').length;
  if (value && (!/^[+\d ()-]+$/.test(value) || digits < 5 || digits > 20)) {
    throw new ApiError(400, 'Телефон нөмірін дұрыс енгізіңіз.');
  }
  if (required && !value) throw new ApiError(400, 'Телефон нөмірін енгізіңіз.');
  return value;
}

function constantEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let difference = 0;
  for (let i = 0; i < a.length; i++) difference |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return difference === 0;
}

const hex = bytes => Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
const randomBytes = length => crypto.getRandomValues(new Uint8Array(length));
const randomToken = (length = 32) => btoa(String.fromCharCode(...randomBytes(length))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const sha256 = async value => hex(new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(value))));

async function hashPassword(value, salt) {
  const saltBytes = Uint8Array.from(salt.match(/.{2}/g), pair => Number.parseInt(pair, 16));
  const key = await crypto.subtle.importKey('raw', encoder.encode(value), 'PBKDF2', false, ['deriveBits']);
  return hex(new Uint8Array(await crypto.subtle.deriveBits({name:'PBKDF2', hash:'SHA-256', salt:saltBytes, iterations:PASSWORD_ROUNDS}, key, 256)));
}

function hundredths(value) {
  const [whole, fraction = ''] = String(value).split('.');
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
}

const fixed = value => `${value / 100n}.${(value % 100n).toString().padStart(2, '0')}`;

function snapshot(rows) {
  let count = 0, carats = 0n, total = 0n;
  const items = rows.map(({stone_id, quantity}) => {
    const stone = byId.get(stone_id);
    const weight = hundredths(stone.carats) * BigInt(quantity);
    // Price is an integer KZT amount; weight is hundredths of a carat.
    const cost = weight * BigInt(stone.price_per_carat);
    count += quantity; carats += weight; total += cost;
    return {stone_id, quantity, carats:fixed(weight), total:fixed(cost)};
  });
  return {items, count, carats:fixed(carats), total:fixed(total)};
}

const statement = (db, sql, args = []) => db.prepare(sql).bind(...args);
const first = (db, sql, args = []) => statement(db, sql, args).first();
async function all(db, sql, args = []) { return (await statement(db, sql, args).all()).results; }

async function ensureCatalog(db) {
  const signature = await sha256(catalogPayload);
  if ((await first(db, "SELECT value FROM app_versions WHERE key='catalog'"))?.value === signature) return;
  const pending = "NOT EXISTS(SELECT 1 FROM app_versions WHERE key='catalog' AND value=?)";
  const legacy = "NOT EXISTS(SELECT 1 FROM app_versions WHERE key='legacy_v3_copied')";
  // One JSON bind seeds all catalog rows. No table is created or dropped here.
  // D1 batches serialize initializers; the version predicates make a concurrent
  // second initializer harmless and preserve every existing user-owned record.
  await db.batch([
    statement(db, 'INSERT OR IGNORE INTO runtime(id,revision) VALUES(1,0)'),
    statement(db, "INSERT OR IGNORE INTO settings(key,value) VALUES('store',?)", [JSON.stringify(DEFAULT_STORE)]),
    statement(db, `INSERT INTO catalog_data(id,name,category,carats,price_per_carat,transparency,record)
      SELECT json_extract(value,'$.id'),json_extract(value,'$.name'),json_extract(value,'$.category'),
      json_extract(value,'$.carats'),json_extract(value,'$.price_per_carat'),json_extract(value,'$.transparency'),value
      FROM json_each(?) WHERE ${pending}
      ON CONFLICT(id) DO UPDATE SET name=excluded.name,category=excluded.category,carats=excluded.carats,
      price_per_carat=excluded.price_per_carat,transparency=excluded.transparency,record=excluded.record`, [catalogPayload,signature]),
    statement(db, `INSERT INTO necklace_v4(owner,stone_id,quantity) SELECT owner,stone_id,quantity FROM necklace WHERE ${legacy} ON CONFLICT(owner,stone_id) DO NOTHING`),
    statement(db, `INSERT INTO favorites_v4(owner,stone_id) SELECT owner,stone_id FROM favorites WHERE ${legacy} ON CONFLICT(owner,stone_id) DO NOTHING`),
    statement(db, `INSERT INTO reviews_v4(user_id,stone_id,rating,text,updated) SELECT user_id,stone_id,rating,text,updated FROM reviews WHERE ${legacy} ON CONFLICT(user_id,stone_id) DO NOTHING`),
    statement(db, "INSERT OR IGNORE INTO app_versions(key,value) VALUES('legacy_v3_copied','1')"),
    statement(db, `UPDATE runtime SET revision=revision+1 WHERE id=1 AND ${pending}`, [signature]),
    statement(db, "INSERT INTO app_versions(key,value) VALUES('catalog',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", [signature])
  ]);
}

function verifiedOwner(request, env) {
  // These headers are trusted ONLY behind Sites' dispatcher, which strips client
  // values and inserts its verified identity. Do not expose this Worker directly.
  const configured = typeof env.SITE_OWNER_EMAIL === 'string' ? env.SITE_OWNER_EMAIL.trim().toLowerCase() : '';
  const id = request.headers.get('oai-authenticated-user-id')?.trim() || '';
  const address = request.headers.get('oai-authenticated-user-email')?.trim().toLowerCase() || '';
  return configured && id && id.length <= 256 && address === configured ? {id, email:configured} : null;
}

function isAdmin(user, identity) {
  return !!(user && user.admin === 1 && identity && user.email === identity.email && user.admin_owner_id === identity.id);
}

function requireUser(context, admin = false) {
  if (!context.user) throw new ApiError(401, 'Алдымен аккаунтқа кіріңіз.');
  if (admin && !isAdmin(context.user, context.identity)) throw new ApiError(403, 'Бұл әрекетке сайт иесінің расталған әкімші құқығы қажет.');
  return context.user;
}

function readCookie(request) {
  const values = (request.headers.get('cookie') || '').split(';').map(part => part.trim())
    .filter(part => part.startsWith(COOKIE_NAME + '='));
  if (values.length !== 1) return null;
  const value = values[0].slice(COOKIE_NAME.length + 1);
  return /^[A-Za-z0-9_-]{43}$/.test(value) ? value : null;
}

async function sessionValues(userId = null) {
  const token = randomToken();
  return {token, token_hash:await sha256(token), owner:userId ? `u:${userId}` : `g:${randomToken(24)}`,
    user_id:userId, csrf:randomToken(), expires:now() + SESSION_AGE};
}

async function findSession(db, request) {
  const token = readCookie(request);
  return token ? first(db, 'SELECT * FROM sessions WHERE token_hash=? AND expires>?', [await sha256(token), now()]) : null;
}

async function guestSession(db) {
  const session = await sessionValues();
  await db.batch([
    statement(db, 'INSERT OR IGNORE INTO runtime(id,revision) VALUES(1,0)'),
    statement(db, "INSERT OR IGNORE INTO settings(key,value) VALUES('store',?)", [JSON.stringify(DEFAULT_STORE)]),
    statement(db, 'DELETE FROM sessions WHERE expires<?', [now()]),
    statement(db, 'INSERT INTO sessions(token_hash,owner,user_id,csrf,expires) VALUES(?,?,?,?,?)', [session.token_hash, session.owner, null, session.csrf, session.expires])
  ]);
  return session;
}

async function getContext(db, request, env, session) {
  // Revision is read first. If another mutation happens during reads/crypto,
  // the guarded write batch fails without changing any data.
  const revision = (await first(db, 'SELECT revision FROM runtime WHERE id=1')).revision;
  const user = session.user_id ? await first(db, 'SELECT * FROM users WHERE id=?', [session.user_id]) : null;
  return {db, request, env, session, revision, user, identity:verifiedOwner(request, env)};
}

async function commit(context, operations) {
  const {db, session, revision} = context;
  const nonce = randomToken(24);
  const statements = [statement(db,
    'INSERT INTO mutation_guards(id) SELECT ? WHERE (SELECT revision FROM runtime WHERE id=1)=? AND EXISTS(SELECT 1 FROM sessions WHERE token_hash=? AND csrf=? AND expires>?)',
    [nonce, revision, session.token_hash, session.csrf, now()])];
  for (const operation of operations) {
    if (!operation.sql.includes('__GUARD__')) throw new Error('Mutation guard missing');
    statements.push(statement(db, operation.sql.replace('__GUARD__', guard), [...operation.args, nonce]));
  }
  statements.push(statement(db, `UPDATE runtime SET revision=revision+1 WHERE id=1 AND ${guard}`, [nonce]));
  statements.push(statement(db, 'SELECT id FROM mutation_guards WHERE id=?', [nonce]));
  statements.push(statement(db, 'DELETE FROM mutation_guards WHERE id=?', [nonce]));
  // D1 batch is one transaction: an exception rolls back every statement.
  const results = await db.batch(statements);
  if (!results.at(-2).results.length) throw new ApiError(409, 'Деректер немесе сеанс өзгерді. Бетті жаңартып, қайталаңыз.');
}

const operation = (sql, ...args) => ({sql, args});
const insertSession = session => operation(
  'INSERT INTO sessions(token_hash,owner,user_id,csrf,expires) SELECT ?,?,?,?,? WHERE __GUARD__',
  session.token_hash, session.owner, session.user_id, session.csrf, session.expires);
const deleteSession = session => operation('DELETE FROM sessions WHERE token_hash=? AND __GUARD__', session.token_hash);
const cart = (db, owner) => all(db, 'SELECT stone_id,quantity FROM necklace_v4 WHERE owner=? ORDER BY stone_id', [owner]);

async function state(db, request, env, sessionHash) {
  const identity = verifiedOwner(request, env);
  const identityId = identity?.id || '', identityEmail = identity?.email || '';
  const validSession = 'SELECT * FROM sessions WHERE token_hash=? AND expires>?';
  const ownerSql = `SELECT owner FROM (${validSession})`;
  const userSql = `SELECT user_id FROM (${validSession})`;
  const adminSql = `SELECT 1 FROM users WHERE id=(${userSql}) AND admin=1 AND admin_owner_id=? AND email=?`;
  const time = now();
  const own = [sessionHash, time], adminArgs = [...own, identityId, identityEmail];
  // One read batch gives a consistent state and checks session validity before
  // any account results are returned. Admin access always needs trusted identity.
  const results = await db.batch([
    statement(db, validSession, own),
    statement(db, `SELECT * FROM users WHERE id=(${userSql})`, own),
    statement(db, `SELECT stone_id,quantity FROM necklace_v4 WHERE owner=(${ownerSql}) ORDER BY stone_id`, own),
    statement(db, `SELECT stone_id FROM favorites_v4 WHERE owner=(${ownerSql}) ORDER BY stone_id`, own),
    statement(db, `SELECT id,name,updated,snapshot FROM collections WHERE owner=(${ownerSql}) ORDER BY id DESC`, own),
    statement(db, `SELECT o.*,u.name,u.email FROM orders o JOIN users u ON u.id=o.user_id WHERE o.user_id=(${userSql}) OR EXISTS(${adminSql}) ORDER BY o.id DESC`, [...own, ...adminArgs]),
    statement(db, "SELECT value FROM settings WHERE key='store'"),
    statement(db, 'SELECT r.stone_id,r.rating,r.text,u.name,r.user_id AS owner FROM reviews_v4 r JOIN users u ON u.id=r.user_id ORDER BY r.updated DESC,r.user_id'),
    statement(db, `SELECT m.id,m.subject,m.text,m.reply,m.created,u.name FROM messages m JOIN users u ON u.id=m.user_id WHERE m.user_id=(${userSql}) OR EXISTS(${adminSql}) ORDER BY m.id DESC`, [...own, ...adminArgs]),
    statement(db, `SELECT id,name,email,admin FROM users WHERE EXISTS(${adminSql}) ORDER BY id`, adminArgs),
    statement(db, 'SELECT id,admin_owner_id FROM users WHERE admin=1 LIMIT 1'),
    statement(db, 'SELECT record FROM catalog_data ORDER BY id'),
    statement(db, `SELECT stone_id FROM stone_comparisons WHERE owner=(${ownerSql}) ORDER BY ordinal,stone_id`, own),
    statement(db, `SELECT stone_id,viewed FROM stone_history WHERE owner=(${ownerSql}) ORDER BY ordinal DESC,stone_id DESC LIMIT 12`, own),
    statement(db, `SELECT stone_id,text,updated FROM stone_notes WHERE owner=(${ownerSql}) ORDER BY updated DESC,stone_id`, own)
  ]);
  const rows = results.map(result => result.results);
  const session = rows[0][0];
  if (!session) throw new ApiError(401, 'Сеанс аяқталды. Бетті жаңартыңыз.');
  const user = rows[1][0] || null;
  const admin = isAdmin(user, identity);
  const claimed = rows[10][0];
  const available = !!(user && identity && user.email === identity.email &&
    (!claimed || (claimed.id === user.id && claimed.admin_owner_id === identity.id)) && !admin);
  return {ok:true, csrf:session.csrf,
    user:user ? {id:user.id,name:user.name,email:user.email,phone:user.phone,address:user.address,admin} : null,
    catalog:rows[11].map(row => JSON.parse(row.record)), necklace:snapshot(rows[2]), favorites:rows[3].map(row => row.stone_id),
    collections:rows[4].map(row => ({id:row.id,name:row.name,updated:row.updated,necklace:JSON.parse(row.snapshot)})),
    orders:rows[5].map(row => ({id:row.id,status:row.status,created:row.created,delivery:row.delivery,pickup_date:row.pickup_date,
      phone:row.phone,address:row.address,note:row.note,gift:!!row.gift,name:row.name,email:row.email,...JSON.parse(row.snapshot)})),
    store:rows[6][0] ? JSON.parse(rows[6][0].value) : {...DEFAULT_STORE}, reviews:rows[7], messages:rows[8],
    users:rows[9].map(row => ({...row,admin:!!row.admin})), admin_claim_available:available,
    comparison:rows[12].map(row => row.stone_id), recent:rows[13], notes:rows[14],
    storage_scope:await sha256(session.owner)};
}

async function perform(context, data) {
  const {db, session} = context;
  const owner = session.owner;
  let nextSession = session;
  let operations = [];
  switch (data.action) {
    case 'register': {
      if (session.user_id) throw new ApiError(409, 'Жаңа аккаунт ашу үшін алдымен шығыңыз.');
      const name = text(data, 'name', 2, 100), address = email(data), secret = password(data);
      const importGuest = boolean(data, 'import_guest');
      const reservedOwner = typeof context.env.SITE_OWNER_EMAIL === 'string' ? context.env.SITE_OWNER_EMAIL.trim().toLowerCase() : '';
      if (address === reservedOwner && !context.identity) throw new ApiError(403, 'Бұл email сайт иесіне арналған. Алдымен Sites арқылы иесінің аккаунтын растаңыз.');
      if (await first(db, 'SELECT 1 FROM users WHERE email=?', [address])) throw new ApiError(409, 'Бұл email тіркелген. Аккаунтқа кіріңіз.');
      const salt = hex(randomBytes(16)), hashed = await hashPassword(secret, salt);
      nextSession = await sessionValues();
      operations.push(operation('INSERT INTO users(name,email,salt,password_hash,rounds,admin,created) SELECT ?,?,?,?,?,0,? WHERE __GUARD__', name, address, salt, hashed, PASSWORD_ROUNDS, timestamp()));
      operations.push(operation("INSERT INTO sessions(token_hash,owner,user_id,csrf,expires) SELECT ?,('u:'||id),id,?,? FROM users WHERE email=? AND __GUARD__",
        nextSession.token_hash, nextSession.csrf, nextSession.expires, address));
      if (importGuest) {
        const newOwner = "('u:'||(SELECT id FROM users WHERE email=?))";
        operations.push(operation(`INSERT INTO necklace_v4(owner,stone_id,quantity) SELECT ${newOwner},stone_id,quantity FROM necklace_v4 WHERE owner=? AND __GUARD__`, address, owner));
        operations.push(operation(`INSERT INTO collections(owner,name,updated,snapshot) SELECT ${newOwner},name,updated,snapshot FROM collections WHERE owner=? AND __GUARD__`, address, owner));
        operations.push(operation(`INSERT INTO stone_comparisons(owner,stone_id,ordinal) SELECT ${newOwner},stone_id,ordinal FROM stone_comparisons WHERE owner=? AND __GUARD__`, address, owner));
        operations.push(operation(`INSERT INTO stone_history(owner,stone_id,viewed,ordinal) SELECT ${newOwner},stone_id,viewed,ordinal FROM stone_history WHERE owner=? AND __GUARD__`, address, owner));
        operations.push(operation(`INSERT INTO stone_notes(owner,stone_id,text,updated) SELECT ${newOwner},stone_id,text,updated FROM stone_notes WHERE owner=? AND __GUARD__`, address, owner));
      }
      operations.push(deleteSession(session));
      break;
    }
    case 'login': {
      const address = email(data), secret = password(data);
      const user = await first(db, 'SELECT * FROM users WHERE email=?', [address]);
      if (user && user.locked_until > now()) throw new ApiError(429, 'Көп рет қате енгізілді. Бір минуттан кейін қайталаңыз.');
      const hashed = await hashPassword(secret, user?.salt || '00000000000000000000000000000000');
      if (!user || !constantEqual(hashed, user.password_hash)) {
        if (user) {
          const failures = user.locked_until ? 1 : user.failed + 1;
          await commit(context, [operation('UPDATE users SET failed=?,locked_until=? WHERE id=? AND __GUARD__', failures, failures >= 5 ? now() + 60 : 0, user.id)]);
        }
        throw new ApiError(401, 'Email немесе құпиясөз дұрыс емес.');
      }
      nextSession = await sessionValues(user.id);
      operations = [operation('UPDATE users SET failed=0,locked_until=0 WHERE id=? AND __GUARD__', user.id), insertSession(nextSession), deleteSession(session)];
      break;
    }
    case 'logout':
      nextSession = await sessionValues();
      operations = [insertSession(nextSession), deleteSession(session)];
      break;
    case 'admin_claim': {
      const user = requireUser(context), identity = context.identity;
      if (!identity || user.email !== identity.email) throw new ApiError(403, 'Әкімші құқығы үшін сайт иесінің расталған аккаунтымен кіріңіз.');
      const existing = await first(db, 'SELECT id,admin_owner_id FROM users WHERE admin=1 LIMIT 1');
      if (existing && (existing.id !== user.id || existing.admin_owner_id !== identity.id)) throw new ApiError(403, 'Әкімші құқығы басқа расталған аккаунтқа бекітілген.');
      operations = [operation('UPDATE users SET admin=1,admin_owner_id=? WHERE id=? AND email=? AND __GUARD__', identity.id, user.id, identity.email)];
      break;
    }
    case 'update_profile': {
      const user = requireUser(context), name = text(data, 'name', 2, 100);
      operations = [operation('UPDATE users SET name=?,phone=?,address=? WHERE id=? AND __GUARD__', name, phone(data), text(data, 'address'), user.id)];
      break;
    }
    case 'change_password': {
      const user = requireUser(context), current = password(data, 'current_password'), replacement = password(data, 'new_password');
      if (!constantEqual(await hashPassword(current, user.salt), user.password_hash)) throw new ApiError(400, 'Қазіргі құпиясөз дұрыс емес.');
      const salt = hex(randomBytes(16)), hashed = await hashPassword(replacement, salt);
      nextSession = await sessionValues(user.id);
      operations = [operation('UPDATE users SET salt=?,password_hash=?,rounds=?,failed=0,locked_until=0 WHERE id=? AND __GUARD__', salt, hashed, PASSWORD_ROUNDS, user.id),
        operation('DELETE FROM sessions WHERE user_id=? AND __GUARD__', user.id), insertSession(nextSession)];
      break;
    }
    case 'necklace_set': {
      const sid = stoneId(data), quantity = integer(data, 'quantity', 0, 99);
      operations = [quantity ? operation('INSERT INTO necklace_v4(owner,stone_id,quantity) SELECT ?,?,? WHERE __GUARD__ ON CONFLICT(owner,stone_id) DO UPDATE SET quantity=excluded.quantity', owner, sid, quantity)
        : operation('DELETE FROM necklace_v4 WHERE owner=? AND stone_id=? AND __GUARD__', owner, sid)];
      break;
    }
    case 'necklace_demo':
    case 'necklace_clear':
      operations = [operation('DELETE FROM necklace_v4 WHERE owner=? AND __GUARD__', owner)];
      if (data.action === 'necklace_demo') for (const [sid, quantity] of [[2,2],[3,1],[5,3]]) operations.push(operation('INSERT INTO necklace_v4(owner,stone_id,quantity) SELECT ?,?,? WHERE __GUARD__', owner, sid, quantity));
      break;
    case 'comparison_toggle': {
      const sid = stoneId(data);
      const current = await all(db,'SELECT stone_id FROM stone_comparisons WHERE owner=?',[owner]);
      if (current.some(row => row.stone_id === sid)) operations = [operation('DELETE FROM stone_comparisons WHERE owner=? AND stone_id=? AND __GUARD__',owner,sid)];
      else {
        if (current.length >= 3) throw new ApiError(400,'Бір уақытта үш тасқа дейін салыстыруға болады. Алдымен біреуін алып тастаңыз.');
        operations = [operation('INSERT INTO stone_comparisons(owner,stone_id,ordinal) SELECT ?,?,COALESCE((SELECT MAX(ordinal) FROM stone_comparisons WHERE owner=?),0)+1 WHERE __GUARD__',owner,sid,owner)];
      }
      break;
    }
    case 'comparison_clear':
      operations = [operation('DELETE FROM stone_comparisons WHERE owner=? AND __GUARD__',owner)];
      break;
    case 'stone_view': {
      const sid = stoneId(data);
      operations = [operation('INSERT INTO stone_history(owner,stone_id,viewed,ordinal) SELECT ?,?,?,COALESCE((SELECT MAX(ordinal) FROM stone_history WHERE owner=?),0)+1 WHERE __GUARD__ ON CONFLICT(owner,stone_id) DO UPDATE SET viewed=excluded.viewed,ordinal=excluded.ordinal',owner,sid,timestamp(),owner),
        operation('DELETE FROM stone_history WHERE owner=? AND stone_id NOT IN (SELECT stone_id FROM stone_history WHERE owner=? ORDER BY ordinal DESC,stone_id DESC LIMIT 12) AND __GUARD__',owner,owner)];
      break;
    }
    case 'history_clear':
      operations = [operation('DELETE FROM stone_history WHERE owner=? AND __GUARD__',owner)];
      break;
    case 'note_save': {
      const sid = stoneId(data), value = text(data,'text',0,2000);
      operations = [value ? operation('INSERT INTO stone_notes(owner,stone_id,text,updated) SELECT ?,?,?,? WHERE __GUARD__ ON CONFLICT(owner,stone_id) DO UPDATE SET text=excluded.text,updated=excluded.updated',owner,sid,value,timestamp())
        : operation('DELETE FROM stone_notes WHERE owner=? AND stone_id=? AND __GUARD__',owner,sid)];
      break;
    }
    case 'favorite_toggle': {
      requireUser(context);
      const sid = stoneId(data);
      const exists = await first(db, 'SELECT 1 FROM favorites_v4 WHERE owner=? AND stone_id=?', [owner, sid]);
      operations = [exists ? operation('DELETE FROM favorites_v4 WHERE owner=? AND stone_id=? AND __GUARD__', owner, sid)
        : operation('INSERT INTO favorites_v4(owner,stone_id) SELECT ?,? WHERE __GUARD__', owner, sid)];
      break;
    }
    case 'collection_save': {
      const name = text(data, 'name', 1, 100), necklace = snapshot(await cart(db, owner));
      if (!necklace.count) throw new ApiError(400, 'Сақтау үшін алқаға тас қосыңыз.');
      operations = [operation('INSERT INTO collections(owner,name,updated,snapshot) SELECT ?,?,?,? WHERE __GUARD__', owner, name, timestamp(), JSON.stringify(necklace))];
      break;
    }
    case 'collection_load':
    case 'collection_delete': {
      const id = integer(data, 'id', 1, Number.MAX_SAFE_INTEGER);
      const collection = await first(db, 'SELECT snapshot FROM collections WHERE id=? AND owner=?', [id, owner]);
      if (!collection) throw new ApiError(404, 'Жинақ табылмады.');
      if (data.action === 'collection_delete') operations = [operation('DELETE FROM collections WHERE id=? AND owner=? AND __GUARD__', id, owner)];
      else {
        operations = [operation('DELETE FROM necklace_v4 WHERE owner=? AND __GUARD__', owner)];
        operations.push(operation("INSERT INTO necklace_v4(owner,stone_id,quantity) SELECT ?,json_extract(value,'$.stone_id'),json_extract(value,'$.quantity') FROM json_each(?,'$.items') WHERE __GUARD__", owner, collection.snapshot));
      }
      break;
    }
    case 'order_create': {
      const user = requireUser(context), necklace = snapshot(await cart(db, owner));
      if (!necklace.count) throw new ApiError(400, 'Өтінім беру үшін алқаға тас қосыңыз.');
      const delivery = data.delivery;
      if (!['pickup', 'delivery'].includes(delivery)) throw new ApiError(400, 'Алып кету немесе жеткізуді таңдаңыз.');
      const telephone = phone(data, true), address = text(data, 'address', delivery === 'delivery' ? 3 : 0);
      const pickup = text(data, 'pickup_date', 0, 10);
      // The shop uses Kazakhstan time (UTC+05:00), independent of Worker location.
      const today = new Date(Date.now() + 5 * 3600000).toISOString().slice(0, 10);
      const parsed = new Date(pickup + 'T00:00:00Z');
      if (pickup && (!/^\d{4}-\d{2}-\d{2}$/.test(pickup) || Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0,10) !== pickup || pickup < today)) throw new ApiError(400, 'Бүгінгі немесе кейінгі күнді таңдаңыз.');
      const note = text(data, 'note', 0, 2000), gift = boolean(data, 'gift');
      operations = [operation("INSERT INTO orders(user_id,status,created,delivery,pickup_date,phone,address,note,gift,snapshot) SELECT ?,'new',?,?,?,?,?,?,?,? WHERE __GUARD__", user.id, timestamp(), delivery, pickup, telephone, address, note, gift ? 1 : 0, JSON.stringify(necklace))];
      break;
    }
    case 'order_cancel': {
      const user = requireUser(context), id = integer(data, 'id', 1, Number.MAX_SAFE_INTEGER);
      const order = await first(db, 'SELECT status FROM orders WHERE id=? AND user_id=?', [id, user.id]);
      if (!order) throw new ApiError(404, 'Өтінім табылмады.');
      if (order.status !== 'new') throw new ApiError(409, 'Тек жаңа өтінімнен бас тартуға болады.');
      operations = [operation("UPDATE orders SET status='cancelled' WHERE id=? AND user_id=? AND status='new' AND __GUARD__", id, user.id)];
      break;
    }
    case 'admin_order_status': {
      requireUser(context, true);
      const id = integer(data, 'id', 1, Number.MAX_SAFE_INTEGER);
      if (!['new','accepted','preparing','ready','cancelled'].includes(data.status)) throw new ApiError(400, 'Өтінім күйі дұрыс емес.');
      if (!await first(db, 'SELECT 1 FROM orders WHERE id=?', [id])) throw new ApiError(404, 'Өтінім табылмады.');
      operations = [operation('UPDATE orders SET status=? WHERE id=? AND __GUARD__', data.status, id)];
      break;
    }
    case 'store_update': {
      requireUser(context, true);
      const address = text(data, 'address', 3), value = {address,phone:phone(data),hours:text(data,'hours',0,200),example:address === 'Алматы, Қазақстан'};
      operations = [operation("INSERT INTO settings(key,value) SELECT 'store',? WHERE __GUARD__ ON CONFLICT(key) DO UPDATE SET value=excluded.value", JSON.stringify(value))];
      break;
    }
    case 'review_save': {
      const user = requireUser(context), sid = stoneId(data), rating = integer(data,'rating',1,5), value = text(data,'text',1,2000);
      operations = [operation('INSERT INTO reviews_v4(user_id,stone_id,rating,text,updated) SELECT ?,?,?,?,? WHERE __GUARD__ ON CONFLICT(user_id,stone_id) DO UPDATE SET rating=excluded.rating,text=excluded.text,updated=excluded.updated', user.id, sid, rating, value, timestamp())];
      break;
    }
    case 'message_send': {
      const user = requireUser(context), subject = text(data,'subject',1,150), value = text(data,'text',1,4000);
      operations = [operation('INSERT INTO messages(user_id,subject,text,created) SELECT ?,?,?,? WHERE __GUARD__', user.id, subject, value, timestamp())];
      break;
    }
    case 'message_reply': {
      requireUser(context, true);
      const id = integer(data,'id',1,Number.MAX_SAFE_INTEGER), reply = text(data,'reply',1,4000);
      if (!await first(db,'SELECT 1 FROM messages WHERE id=?',[id])) throw new ApiError(404,'Хабарлама табылмады.');
      operations = [operation('UPDATE messages SET reply=? WHERE id=? AND __GUARD__', reply, id)];
      break;
    }
    default: throw new ApiError(400, 'Белгісіз әрекет.');
  }
  await commit(context, operations);
  return nextSession;
}

function checkSource(request) {
  const url = new URL(request.url);
  if (url.protocol !== 'https:') throw new ApiError(403, 'Сайтқа HTTPS арқылы кіріңіз.');
  const host = request.headers.get('host');
  if (host && host.toLowerCase() !== url.host.toLowerCase()) throw new ApiError(403, 'Сұрау мекенжайы дұрыс емес.');
  const origin = request.headers.get('origin');
  if ((origin && origin !== url.origin) || request.headers.get('sec-fetch-site') === 'cross-site') throw new ApiError(403, 'Бұл сұрау көзіне рұқсат жоқ.');
  if (request.method === 'POST' && origin !== url.origin) throw new ApiError(403, 'Сұрау тек осы сайттан жіберілуі керек.');
}

async function body(request) {
  if ((request.headers.get('content-type') || '').split(';')[0].trim().toLowerCase() !== 'application/json') throw new ApiError(415, 'Сұрау JSON пішімінде болуы керек.');
  const contentLength = request.headers.get('content-length');
  if (contentLength && (!/^\d+$/.test(contentLength) || Number(contentLength) > MAX_BODY)) throw new ApiError(413, 'Сұрау көлемі тым үлкен.');
  if (!request.body) throw new ApiError(400, 'Сұрау бос.');
  const reader = request.body.getReader();
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const {done, value} = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_BODY) { await reader.cancel(); throw new ApiError(413, 'Сұрау көлемі тым үлкен.'); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  let value;
  try { value = JSON.parse(new TextDecoder('utf-8', {fatal:true}).decode(bytes)); }
  catch { throw new ApiError(400, 'JSON дерегін оқу мүмкін болмады.'); }
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new ApiError(400, 'Сұрау JSON объектісі болуы керек.');
  return value;
}

function response(value, status = 200, token = null) {
  const headers = new Headers({'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'same-origin'});
  if (token) headers.set('Set-Cookie', `${COOKIE_NAME}=${token}; Path=/; Max-Age=${SESSION_AGE}; Secure; HttpOnly; SameSite=Lax`);
  return new Response(JSON.stringify(value), {status, headers});
}

export async function handleApi(request, env) {
  try {
    checkSource(request);
    const path = new URL(request.url).pathname;
    if (!['/api/state','/api/action'].includes(path)) throw new ApiError(404, 'Әдіс табылмады.');
    if ((path === '/api/state' && request.method !== 'GET') || (path === '/api/action' && request.method !== 'POST')) throw new ApiError(405, 'Бұл сұрау әдісіне рұқсат жоқ.');
    if (!env?.DB) throw new ApiError(503, 'Дерекқор әзірге қолжетімсіз. Кейінірек қайталаңыз.');
    const db = typeof env.DB.withSession === 'function' ? env.DB.withSession('first-primary') : env.DB;
    await ensureCatalog(db);
    let session = await findSession(db, request), token = null;
    if (request.method === 'GET') {
      if (!session) { session = await guestSession(db); token = session.token; }
    } else {
      if (!session) throw new ApiError(403, 'Сеанс аяқталды. Бетті жаңартыңыз.');
      if (!constantEqual(request.headers.get('x-csrf-token'), session.csrf)) throw new ApiError(403, 'Қауіпсіздік коды ескірген. Бетті жаңартыңыз.');
      const data = await body(request);
      const context = await getContext(db, request, env, session);
      session = await perform(context, data);
      token = session.token || null;
    }
    return response(await state(db, request, env, session.token_hash), 200, token);
  } catch (error) {
    if (error instanceof ApiError) return response({ok:false,error:error.message},error.status);
    return response({ok:false,error:'Деректі өңдеу мүмкін болмады. Қайта көріңіз.'},500);
  }
}
