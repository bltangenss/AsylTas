import assert from 'node:assert/strict';
import {readFile,writeFile,mkdir,mkdtemp,rm,realpath} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {createHash,pbkdf2Sync} from 'node:crypto';
import {handleApi} from '../worker/api.js';
import {D1} from './d1.mjs';

const testsRoot = fileURLToPath(new URL('.',import.meta.url));
const root = path.resolve(testsRoot,'../..');
const migration = await readFile(new URL('../drizzle/0000_initial.sql',import.meta.url),'utf8');
const upgrade = await readFile(new URL('../drizzle/0001_catalog_and_discovery.sql',import.meta.url),'utf8');
const PASSWORD = 'Test-only-password-123';
const OWNER = 'owner@example.test';
const VERIFIED_ID = 'sites-owner-test-identity';
const origin = 'https://asyltas.example.test';
const tomorrow = new Date(Date.now()+86400000).toISOString().slice(0,10);

class Client {
  constructor(env, identity = null) { this.env=env;this.cookie='';this.csrf='';this.identity=identity;this.headers=null; }
  async request(method='GET',route='/api/state',data=undefined,overrides={}) {
    const headers = new Headers({'Cookie':this.cookie});
    if (method==='POST') { headers.set('Origin',origin);headers.set('Content-Type','application/json');headers.set('X-CSRF-Token',this.csrf); }
    // Tests emulate headers already verified by the Sites dispatcher.
    if (this.identity) {
      headers.set('oai-authenticated-user-id',this.identity.id);
      headers.set('oai-authenticated-user-email',this.identity.email);
    }
    for (const [key,value] of Object.entries(overrides.headers||{})) {
      if (value===null) headers.delete(key);else headers.set(key,value);
    }
    const body = overrides.raw ?? (data===undefined ? undefined : JSON.stringify(data));
    const request = new Request((overrides.origin||origin)+route,{method,headers,body});
    const response = await handleApi(request,this.env);
    const result = await response.json();
    this.headers=response.headers;
    const cookie=response.headers.get('set-cookie');
    if (cookie) this.cookie=cookie.split(';')[0];
    if (result.ok) this.csrf=result.csrf;
    return {status:response.status,result};
  }
  async state() {
    const response=await this.request();assert.equal(response.status,200,JSON.stringify(response));return response.result;
  }
  async action(action,fields={},expected=200,overrides={}) {
    const response=await this.request('POST','/api/action',{action,...fields},overrides);
    assert.equal(response.status,expected,JSON.stringify({action,...response}));
    assert.equal(response.result.ok,expected===200);
    if (expected!==200) assert.ok(typeof response.result.error==='string' && response.result.error.length);
    return response.result;
  }
  async register(address='customer@example.test',importGuest=false) {
    await this.state();return this.action('register',{name:'Сынақ қолданушы',email:address,password:PASSWORD,import_guest:importGuest});
  }
}

const cases=[];
const test=(name,fn,options={})=>cases.push({name,fn,...options});
async function customer(env,address='second@example.test') { const c=new Client(env);await c.register(address);return c; }
async function admin(env) {
  const c=new Client(env,{id:VERIFIED_ID,email:OWNER});
  await c.register(OWNER);await c.action('admin_claim');return c;
}
async function order(client,overrides={}) {
  await client.action('necklace_demo');
  const state=await client.action('order_create',{delivery:'pickup',pickup_date:tomorrow,phone:'+7 700 000 0000',address:'',note:'Тек сынақ',gift:true,...overrides});
  return state.orders[0];
}
const equalSnapshot=n=>assert.deepEqual([n.count,n.carats,n.total],[6,'5.90','616200.00']);

test('catalog_matches_shared_model_and_assets_exactly',async({client})=>{
  const state=await client.state();
  assert.equal(state.catalog.length,48);assert.equal(state.user,null);assert.equal(state.admin_claim_available,false);
  assert.deepEqual(state.necklace,{items:[],count:0,carats:'0.00',total:'0.00'});
  const catalog=JSON.parse(await readFile(new URL('../public/assets/catalog.json',import.meta.url),'utf8'));
  assert.deepEqual(state.catalog,catalog);
  const java=await readFile(path.join(root,'shared/src/kz/asyltas/model/Catalog.java'),'utf8');
  const rows=[...java.matchAll(/new (?:PreciousStone|SemiPreciousStone)\((\d+), "([^"]+)", "([^"]+)", "([^"]+)", (\d+),/g)];
  assert.equal(rows.length,48);
  state.catalog.forEach((item,i)=>assert.deepEqual([item.id,item.name,item.carats,item.price_per_carat,item.transparency],[Number(rows[i][1]),rows[i][2],rows[i][3],rows[i][4],Number(rows[i][5])]));
  assert.deepEqual(state.store,{address:'Алматы, Қазақстан',phone:'',hours:'',example:true});
});

test('exact_demo_quantity_and_invalid_input_boundaries',async({client})=>{
  equalSnapshot((await client.action('necklace_demo')).necklace);
  await client.action('necklace_set',{stone_id:1,quantity:99});
  for (const quantity of [-1,100,1.5,'2',true,null]) await client.action('necklace_set',{stone_id:1,quantity},400);
  assert.equal((await client.state()).necklace.items[0].quantity,99);
  await client.action('necklace_set',{stone_id:1,quantity:0});equalSnapshot((await client.state()).necklace);
  for (const stone_id of [0,49,'1',true]) await client.action('necklace_set',{stone_id,quantity:1},400);
});

test('guest_collections_are_snapshots_and_isolated',async({client,env})=>{
  await client.action('necklace_demo');
  const saved=(await client.action('collection_save',{name:'Қонақ жинағы'})).collections[0];
  await client.action('necklace_clear');assert.deepEqual((await client.state()).collections[0],saved);
  const other=new Client(env);assert.equal((await other.state()).necklace.count,0);
  await other.action('collection_load',{id:saved.id},404);await other.action('collection_delete',{id:saved.id},404);
  assert.deepEqual((await client.action('collection_load',{id:saved.id})).necklace,saved.necklace);
  await client.action('collection_delete',{id:saved.id});assert.deepEqual((await client.state()).collections,[]);
});

test('public_first_registration_never_gets_admin',async({client,env})=>{
  assert.equal((await client.register()).user.admin,false);
  const second=await customer(env);assert.equal((await second.state()).user.admin,false);
  assert.deepEqual((await client.state()).users,[]);
  await client.action('admin_claim',{},403);
});

test('password_hash_salt_crypto_and_session_rotation',async({client,db})=>{
  const oldCookie=client.cookie,oldCsrf=client.csrf;
  await client.register();assert.notEqual(client.cookie,oldCookie);assert.notEqual(client.csrf,oldCsrf);
  const user=db.sqlite.prepare('SELECT * FROM users').get();
  assert.equal(user.salt.length,32);assert.equal(user.password_hash.length,64);assert.equal(user.rounds,100000);
  assert.equal(user.password_hash,pbkdf2Sync(PASSWORD,Buffer.from(user.salt,'hex'),100000,32,'sha256').toString('hex'));
  assert.notEqual(user.password_hash,PASSWORD);
  const rawToken=client.cookie.split('=')[1];assert.equal(rawToken.length,43);
  assert.equal(db.sqlite.prepare('SELECT COUNT(*) AS n FROM sessions WHERE token_hash=?').get(rawToken).n,0);
  const stale=new Client(client.env);stale.cookie=oldCookie;stale.csrf=oldCsrf;
  await stale.action('necklace_demo',{},403);assert.equal((await stale.state()).user,null);
});

test('secure_persistent_lax_http_only_cookie',async({client})=>{
  const cookie=client.headers.get('set-cookie');
  for (const part of ['Secure','HttpOnly','SameSite=Lax','Path=/','Max-Age=2592000']) assert.ok(cookie.includes(part),part);
  assert.equal(client.headers.get('cache-control'),'no-store');
  assert.equal(client.headers.get('x-content-type-options'),'nosniff');
});

test('guest_import_is_explicit_and_copies_collections',async({client,env})=>{
  await client.action('necklace_demo');await client.action('collection_save',{name:'Қонақ'});
  const imported=await client.register('import@example.test',true);equalSnapshot(imported.necklace);assert.equal(imported.collections.length,1);
  const other=new Client(env);await other.state();await other.action('necklace_demo');await other.action('collection_save',{name:'Көшірілмейді'});
  const omitted=await other.register('empty@example.test');assert.equal(omitted.necklace.count,0);assert.deepEqual(omitted.collections,[]);
});

test('account_data_survives_logout_and_cannot_cross_users',async({client,env})=>{
  await client.register();await client.action('necklace_demo');await client.action('favorite_toggle',{stone_id:2});
  const saved=(await client.action('collection_save',{name:'Жеке'})).collections[0];
  await client.action('logout');const guest=await client.state();assert.equal(guest.user,null);assert.equal(guest.necklace.count,0);assert.deepEqual(guest.favorites,[]);
  await client.action('login',{email:'CUSTOMER@example.test',password:PASSWORD});equalSnapshot((await client.state()).necklace);
  const other=await customer(env);assert.deepEqual((await other.state()).favorites,[]);await other.action('collection_load',{id:saved.id},404);
});

test('guest_protected_actions_require_login',async({client})=>{
  for (const action of ['favorite_toggle','order_create','order_cancel','update_profile','change_password','review_save','message_send','message_reply','admin_order_status','store_update','admin_claim']) {
    await client.action(action,{stone_id:1,id:1},401);
  }
});

test('owner_claim_requires_current_account_and_both_verified_headers',async({client,env})=>{
  client.identity={id:VERIFIED_ID,email:OWNER};
  await client.register(OWNER);
  for (const identity of [null,{id:VERIFIED_ID,email:'wrong@example.test'},{id:'',email:OWNER},{id:VERIFIED_ID,email:''}]) {
    client.identity=identity;assert.equal((await client.state()).admin_claim_available,false);await client.action('admin_claim',{},403);
  }
  client.identity={id:VERIFIED_ID,email:OWNER};assert.equal((await client.state()).admin_claim_available,true);
  const state=await client.action('admin_claim');assert.equal(state.user.admin,true);assert.equal(state.admin_claim_available,false);assert.equal(state.users.length,1);
  const outsider=await customer(env,'outsider@example.test');outsider.identity=client.identity;
  assert.equal((await outsider.state()).admin_claim_available,false);await outsider.action('admin_claim',{},403);
});

test('admin_access_always_requires_matching_sites_identity',async({env})=>{
  const owner=await admin(env),buyer=await customer(env);const created=await order(buyer);
  assert.equal((await owner.state()).orders.length,1);
  owner.identity=null;const unverified=await owner.state();assert.equal(unverified.user.admin,false);assert.deepEqual(unverified.users,[]);assert.deepEqual(unverified.orders,[]);
  await owner.action('admin_order_status',{id:created.id,status:'ready'},403);
  owner.identity={id:'different-identity',email:OWNER};await owner.action('admin_claim',{},403);assert.equal((await owner.state()).user.admin,false);
  owner.identity={id:VERIFIED_ID,email:OWNER};assert.equal((await owner.state()).user.admin,true);
  await owner.action('admin_order_status',{id:created.id,status:'ready'});
  assert.equal((await buyer.state()).orders[0].status,'ready');
});

test('owner_configuration_is_required_and_not_disclosed',async({client,env})=>{
  client.identity={id:VERIFIED_ID,email:OWNER};await client.register(OWNER);
  env.SITE_OWNER_EMAIL='';await client.action('admin_claim',{},403);assert.equal((await client.state()).admin_claim_available,false);
  env.SITE_OWNER_EMAIL=OWNER;
  const output=JSON.stringify(await client.state());assert.ok(!output.includes('admin_owner_id'));assert.ok(!output.includes(VERIFIED_ID));
});

test('owner_email_is_reserved_until_sites_identity_is_verified',async({client,db})=>{
  for(const identity of [null,{id:VERIFIED_ID,email:'outsider@example.test'},{id:'',email:OWNER}]) {
    client.identity=identity;
    await client.action('register',{name:'Owner test',email:OWNER.toUpperCase(),password:PASSWORD},403);
  }
  assert.equal(db.sqlite.prepare('SELECT COUNT(*) AS n FROM users').get().n,0);
  client.identity={id:VERIFIED_ID,email:OWNER};
  const state=await client.register(OWNER);assert.equal(state.user.admin,false);assert.equal(state.admin_claim_available,true);
});

test('schema_only_migration_and_idempotent_runtime_defaults',async({client,env,db})=>{
  assert.ok(!/\b(INSERT|UPDATE|DELETE)\s+(INTO|FROM|runtime|settings)/i.test(migration));
  assert.ok(!/\b(INSERT|UPDATE|DELETE|DROP|ALTER)\b/i.test(upgrade.replace(/--[^\n]*/g,'')));
  const owner=await admin(env);await owner.action('store_update',{address:'Сақталатын мекенжай',phone:'',hours:'10:00–18:00'});
  const guest=new Client(env);assert.equal((await guest.state()).store.address,'Сақталатын мекенжай');
  assert.equal(db.sqlite.prepare('SELECT COUNT(*) AS n FROM runtime').get().n,1);
  db.sqlite.exec("DELETE FROM settings WHERE key='store'");
  assert.equal((await owner.state()).store.example,true);
  await owner.action('store_update',{address:'Қалпына келген мекенжай',phone:'',hours:''});
  assert.equal((await client.state()).store.address,'Қалпына келген мекенжай');
});

test('csrf_origin_https_and_host_checks',async({client})=>{
  for (const headers of [{'X-CSRF-Token':'bad'},{'X-CSRF-Token':null},{Origin:'https://evil.example'},{Origin:null},{Origin:'null'},{Host:'evil.example'},{'Sec-Fetch-Site':'cross-site'}]) {
    await client.action('necklace_demo',{},403,{headers});
  }
  const denied=await client.request('GET','/api/state',undefined,{origin:'http://asyltas.example.test'});assert.equal(denied.status,403);
  assert.equal((await client.state()).necklace.count,0);
  equalSnapshot((await client.action('necklace_demo')).necklace);
});

test('bad_json_content_type_and_64kb_limit',async({client})=>{
  for (const raw of ['[1,2]','null','{bad','"text"']) assert.equal((await client.request('POST','/api/action',undefined,{raw})).status,400);
  assert.equal((await client.request('POST','/api/action',undefined,{raw:new Uint8Array([255])})).status,400);
  assert.equal((await client.request('POST','/api/action',{}, {headers:{'Content-Type':'text/plain'}})).status,415);
  assert.equal((await client.request('POST','/api/action',undefined,{raw:' '.repeat(65537)})).status,413);
  assert.equal((await client.request('POST','/api/action',{}, {headers:{'Content-Length':'999999'}})).status,413);
  await client.action('unknown',{},400);
  assert.equal((await client.request('GET','/api/unknown')).status,404);
  assert.equal((await client.request('PUT','/api/state')).status,405);
});

test('profile_password_change_and_other_session_revocation',async({client,env})=>{
  await client.register();const other=new Client(env);await other.state();await other.action('login',{email:'customer@example.test',password:PASSWORD});
  const state=await client.action('update_profile',{name:'Жаңа ат',phone:'+7 700 000 0000',address:'Сынақ мекенжайы'});assert.equal(state.user.name,'Жаңа ат');
  await client.action('change_password',{current_password:'Incorrect-123',new_password:'New-password-123'},400);
  await client.action('change_password',{current_password:PASSWORD,new_password:'New-password-123'});assert.equal((await other.state()).user,null);
  await client.action('logout');await client.action('login',{email:'customer@example.test',password:PASSWORD},401);
  await client.action('login',{email:'customer@example.test',password:'New-password-123'});
});

test('order_snapshot_is_immutable_and_private',async({client,env})=>{
  await client.register();const created=await order(client,{delivery:'delivery',address:'Сынақ көшесі, 1'});equalSnapshot(created);
  await client.action('necklace_clear');assert.deepEqual((await client.state()).orders[0],created);
  const outsider=await customer(env);assert.deepEqual((await outsider.state()).orders,[]);await outsider.action('order_cancel',{id:created.id},404);
});

test('order_cancel_and_admin_status_rules',async({env})=>{
  const owner=await admin(env),buyer=await customer(env);const created=await order(buyer);
  await owner.action('admin_order_status',{id:created.id,status:'accepted'});await buyer.action('order_cancel',{id:created.id},409);
  await owner.action('admin_order_status',{id:created.id,status:'paid'},400);
  await owner.action('admin_order_status',{id:99999,status:'ready'},404);
  const second=await order(buyer);await buyer.action('order_cancel',{id:second.id});assert.equal((await buyer.state()).orders[0].status,'cancelled');
  await buyer.action('admin_order_status',{id:created.id,status:'ready'},403);
});

test('order_input_validation_does_not_insert_partial_records',async({client})=>{
  await client.register();const fields={delivery:'pickup',pickup_date:tomorrow,phone:'+7 700 000 0000',address:'',note:'',gift:false};
  await client.action('order_create',fields,400);await client.action('necklace_demo');
  for (const invalid of [{delivery:'courier'},{phone:''},{phone:'hello'},{delivery:'delivery',address:''},{pickup_date:'2026-02-31'},{pickup_date:'2000-01-01'},{gift:'true'},{note:'x'.repeat(2001)}]) await client.action('order_create',{...fields,...invalid},400);
  assert.deepEqual((await client.state()).orders,[]);
});

test('reviews_public_numeric_owner_and_one_per_user_stone',async({client,env})=>{
  const user=(await client.register()).user;
  await client.action('review_save',{stone_id:1,rating:4,text:'Жақсы тас'});
  const guest=new Client(env);const review=(await guest.state()).reviews[0];assert.equal(review.owner,user.id);assert.equal(typeof review.owner,'number');
  await client.action('review_save',{stone_id:1,rating:5,text:'Жаңартылды'});assert.equal((await guest.state()).reviews.length,1);
  const other=await customer(env);await other.action('review_save',{stone_id:1,rating:3,text:'Басқа пікір'});assert.equal((await guest.state()).reviews.length,2);
  for (const rating of [0,6,'5',true]) await other.action('review_save',{stone_id:1,rating,text:'Қате'},400);
});

test('messages_are_private_and_owner_can_reply',async({env})=>{
  const owner=await admin(env),buyer=await customer(env);await buyer.action('message_send',{subject:'Сұрақ',text:'Қашан дайын?'});
  const message=(await buyer.state()).messages[0];const outsider=await customer(env,'outsider@example.test');assert.deepEqual((await outsider.state()).messages,[]);
  assert.equal((await owner.state()).messages[0].id,message.id);
  await owner.action('message_reply',{id:message.id,reply:'Сынақ жауабы'});assert.equal((await buyer.state()).messages[0].reply,'Сынақ жауабы');
  await owner.action('message_reply',{id:999999,reply:'Жауап'},404);await buyer.action('message_reply',{id:message.id,reply:'Жауап'},403);
});

test('store_update_only_owner_and_example_label_is_accurate',async({env})=>{
  const owner=await admin(env),buyer=await customer(env);
  await buyer.action('store_update',{address:'Сынақ'},403);
  const store=(await owner.action('store_update',{address:'Сынақ мекенжайы',phone:'',hours:'10:00–18:00'})).store;assert.equal(store.example,false);assert.deepEqual((await buyer.state()).store,store);
  assert.equal((await owner.action('store_update',{address:'Алматы, Қазақстан',phone:'',hours:''})).store.example,true);
});

test('sql_parameters_and_response_secrets_are_safe',async({client})=>{
  await client.register();await client.action('necklace_demo');
  const name="Жоба'); DROP TABLE users; --";const state=await client.action('collection_save',{name});assert.equal(state.collections[0].name,name);
  const payload=JSON.stringify(state);for (const forbidden of ['password_hash','salt','token_hash','admin_owner_id','locked_until','rounds']) assert.ok(!payload.includes('"'+forbidden+'"'));
  await client.action('logout');await client.action('login',{email:"x'OR'1'='1@example.test",password:PASSWORD},401);
});

test('five_failures_lock_then_expired_lock_allows_login',async({client,db})=>{
  await client.register();await client.action('logout');
  for(let i=0;i<5;i++) await client.action('login',{email:'customer@example.test',password:'Incorrect-123'},401);
  await client.action('login',{email:'customer@example.test',password:PASSWORD},429);
  assert.equal(db.sqlite.prepare('SELECT failed FROM users').get().failed,5);
  db.sqlite.exec('UPDATE users SET locked_until=1');
  await client.action('login',{email:'customer@example.test',password:PASSWORD});
});

test('registration_validation_and_email_uniqueness',async({client,env})=>{
  for(const fields of [{name:'x'},{email:'invalid'},{password:'short'},{password:'x'.repeat(129)},{import_guest:'true'}]) await client.action('register',{name:'Сынақ',email:'customer@example.test',password:PASSWORD,import_guest:false,...fields},400);
  await client.register();const other=new Client(env);await other.state();await other.action('register',{name:'Сынақ',email:'CUSTOMER@example.test',password:PASSWORD},409);
});

test('database_reopen_preserves_account_session_and_saved_content',async({client,env,db,directory,replaceDb})=>{
  await client.register();await client.action('necklace_demo');await client.action('collection_save',{name:'Тұрақты'});await client.action('favorite_toggle',{stone_id:4});
  const before=await client.state();db.close();const reopened=new D1(path.join(directory,'data.sqlite3'));replaceDb(reopened);env.DB=reopened;
  const after=await client.state();for(const key of ['user','necklace','collections','favorites','csrf']) assert.deepEqual(after[key],before[key]);
});

test('expired_session_is_never_authorized',async({client,db})=>{
  await client.register();db.sqlite.exec('UPDATE sessions SET expires=0');await client.action('necklace_demo',{},403);
  assert.equal((await client.state()).user,null);
});

test('d1_batch_failure_rolls_back_every_mutation',async({client,db})=>{
  await client.action('necklace_set',{stone_id:1,quantity:4});const before=await client.state();const revision=db.sqlite.prepare('SELECT revision FROM runtime').get().revision;
  db.failMutationAt=3;const result=await client.action('necklace_demo',{},500);assert.ok(!JSON.stringify(result).includes('internal-secret'));
  assert.deepEqual((await client.state()).necklace,before.necklace);assert.equal(db.sqlite.prepare('SELECT revision FROM runtime').get().revision,revision);
  assert.equal(db.sqlite.prepare('SELECT COUNT(*) AS n FROM mutation_guards').get().n,0);
});

test('registration_batch_failure_leaves_no_user_or_rotated_session',async({client,db})=>{
  const cookie=client.cookie;db.failMutationAt=2;await client.action('register',{name:'Сынақ',email:'new@example.test',password:PASSWORD},500);
  assert.equal(db.sqlite.prepare('SELECT COUNT(*) AS n FROM users').get().n,0);assert.equal(client.cookie,cookie);assert.equal((await client.state()).user,null);
});

test('concurrent_cart_change_rejects_stale_snapshot_atomically',async({client,env,db})=>{
  await client.register();await client.action('necklace_demo');
  const other=new Client(env);await other.state();await other.action('login',{email:'customer@example.test',password:PASSWORD});
  db.beforeNextMutation=()=>other.action('necklace_set',{stone_id:2,quantity:5});
  await client.action('collection_save',{name:'Ескірген'},409);
  const state=await client.state();assert.deepEqual(state.collections,[]);assert.equal(state.necklace.items.find(i=>i.stone_id===2).quantity,5);
});

test('concurrent_logout_rejects_inflight_authorized_write',async({client,env,db})=>{
  await client.register();const alias=new Client(env);alias.cookie=client.cookie;alias.csrf=client.csrf;
  db.beforeNextMutation=()=>alias.action('logout');await client.action('message_send',{subject:'Ескірген',text:'Сақталмауы тиіс'},409);
  assert.equal(db.sqlite.prepare('SELECT COUNT(*) AS n FROM messages').get().n,0);
});

test('parallel_registration_cannot_create_admin_or_duplicate_email',async({env})=>{
  const one=new Client(env),two=new Client(env);await one.state();await two.state();
  const request={action:'register',name:'Қатар сынақ',email:'same@example.test',password:PASSWORD};
  const results=await Promise.all([one.request('POST','/api/action',request),two.request('POST','/api/action',request)]);
  assert.deepEqual(results.map(x=>x.status).sort(),[200,409]);
  assert.deepEqual({...env.DB.sqlite.prepare('SELECT COUNT(*) AS n,SUM(admin) AS admins FROM users').get()},{n:1,admins:0});
});

test('schema_is_migration_only_and_database_errors_do_not_leak',async({client,db,env})=>{
  await client.register();await client.action('necklace_demo');
  assert.ok(!db.trace.some(sql=>/\b(CREATE|ALTER)\b/i.test(sql)));
  const unavailable=new Client({});assert.equal((await unavailable.request()).status,503);
  const faulty=new Client({...env,DB:{prepare(){throw new Error('private-db-secret');}}});const response=await faulty.request();assert.equal(response.status,500);assert.ok(!JSON.stringify(response.result).includes('private-db-secret'));
});

test('catalog_48_persisted_once_and_first_12_values_unchanged',async({client,db})=>{
  const state=await client.state();
  assert.equal(db.sqlite.prepare('SELECT COUNT(*) AS n FROM catalog_data').get().n,48);
  assert.deepEqual(state.catalog.map(s=>s.id),Array.from({length:48},(_,i)=>i+1));
  assert.equal(new Set(state.catalog.map(s=>s.name)).size,48);
  assert.equal(state.catalog.filter(s=>s.category==='Асыл тас').length,5);
  const original=[[1,'0.75','420000',98],[2,'1.20','180000',92],[3,'1.10','150000',80],[4,'1.30','140000',90],[5,'0.80','8000',75],[6,'1.50','12000',85],[7,'2.00','6500',40],[8,'1.80','3500',20],[9,'1.60','4500',55],[10,'1.25','18000',88],[11,'1.40','5000',65],[12,'1.15','7000',70]];
  assert.deepEqual(state.catalog.slice(0,12).map(s=>[s.id,s.carats,s.price_per_carat,s.transparency]),original);
  for(const s of state.catalog) {
    assert.ok(s.description&&s.care&&s.aliases&&s.educational===true);
    assert.equal(s.photo,`/assets/stone_${s.id}.jpg`);
  }
  const revision=db.sqlite.prepare('SELECT revision FROM runtime').get().revision;
  db.trace=[];await client.state();
  assert.ok(!db.trace.some(sql=>sql.includes('INSERT INTO catalog_data')));
  assert.equal(db.sqlite.prepare('SELECT revision FROM runtime').get().revision,revision);
});

test('new_stone_cart_favorite_review_and_immutable_order',async({client,env})=>{
  await client.register();
  await client.action('necklace_set',{stone_id:48,quantity:99});
  const cart=(await client.action('necklace_set',{stone_id:24,quantity:3})).necklace;
  assert.deepEqual([cart.count,cart.carats,cart.total],[102,'229.50','1342800.00']);
  await client.action('favorite_toggle',{stone_id:48});assert.deepEqual((await client.state()).favorites,[48]);
  await client.action('review_save',{stone_id:48,rating:5,text:'Жаңа тас'});
  const orderState=await client.action('order_create',{delivery:'pickup',pickup_date:tomorrow,phone:'+7 700 000 0000',address:'',note:'48 тас каталогы',gift:false});
  const created=orderState.orders[0];assert.equal(created.total,'1342800.00');
  await client.action('necklace_demo');assert.deepEqual((await client.state()).orders[0],created);
  assert.equal((await new Client(env).state()).reviews[0].stone_id,48);
});

test('all_48_stones_collection_load_stays_below_50_d1_statements',async({client,db})=>{
  const catalog=(await client.state()).catalog;
  for(const stone of catalog) await client.action('necklace_set',{stone_id:stone.id,quantity:99});
  const before=await client.state();assert.equal(before.necklace.items.length,48);assert.equal(before.necklace.count,4752);
  const expected=catalog.reduce((sum,s)=>sum+BigInt(s.carats.replace('.',''))*99n*BigInt(s.price_per_carat),0n);
  assert.equal(before.necklace.total,`${expected/100n}.${String(expected%100n).padStart(2,'0')}`);
  const saved=(await client.action('collection_save',{name:'Барлық 48 тас'})).collections[0];
  await client.action('necklace_clear');db.trace=[];
  const loaded=await client.action('collection_load',{id:saved.id});
  assert.deepEqual(loaded.necklace,before.necklace);
  assert.ok(db.trace.length<50,`48-row collection used ${db.trace.length} D1 statements`);
});

test('comparison_limit_order_removal_clear_and_guest_isolation',async({client,env})=>{
  for(const id of [13,24,48]) await client.action('comparison_toggle',{stone_id:id});
  assert.deepEqual((await client.state()).comparison,[13,24,48]);
  await client.action('comparison_toggle',{stone_id:1},400);
  await client.action('comparison_toggle',{stone_id:24});
  assert.deepEqual((await client.action('comparison_toggle',{stone_id:1})).comparison,[13,48,1]);
  assert.deepEqual((await new Client(env).state()).comparison,[]);
  await client.action('comparison_clear');assert.deepEqual((await client.state()).comparison,[]);
});

test('concurrent_comparison_cannot_exceed_three',async({client,env,db})=>{
  await client.register();await client.action('comparison_toggle',{stone_id:1});await client.action('comparison_toggle',{stone_id:2});
  const other=new Client(env);await other.state();await other.action('login',{email:'customer@example.test',password:PASSWORD});
  db.beforeNextMutation=()=>other.action('comparison_toggle',{stone_id:48});
  await client.action('comparison_toggle',{stone_id:3},409);
  assert.deepEqual((await client.state()).comparison,[1,2,48]);
  await client.action('comparison_toggle',{stone_id:3},400);
});

test('history_keeps_12_unique_recents_reorders_and_clears',async({client,env})=>{
  for(let id=1;id<=14;id++) await client.action('stone_view',{stone_id:id});
  assert.deepEqual((await client.state()).recent.map(r=>r.stone_id),[14,13,12,11,10,9,8,7,6,5,4,3]);
  const viewed=await client.action('stone_view',{stone_id:7});
  assert.deepEqual(viewed.recent.map(r=>r.stone_id),[7,14,13,12,11,10,9,8,6,5,4,3]);
  assert.ok(viewed.recent.every(r=>Number.isFinite(Date.parse(r.viewed))));
  assert.deepEqual((await new Client(env).state()).recent,[]);
  assert.deepEqual((await client.action('history_clear')).recent,[]);
});

test('notes_are_private_persistent_updatable_and_empty_removes',async({client,env,db,directory,replaceDb})=>{
  await client.register();await client.action('note_save',{stone_id:48,text:'Менің таңдаған тасым'});
  await client.action('note_save',{stone_id:48,text:'Жеке жаңартылған жазба'});
  const before=await client.state();assert.equal(before.notes.length,1);assert.equal(before.notes[0].text,'Жеке жаңартылған жазба');
  const other=await customer(env);assert.deepEqual((await other.state()).notes,[]);
  assert.deepEqual((await new Client(env).state()).notes,[]);
  await other.action('note_save',{stone_id:48,text:'Басқа адамның жазбасы'});assert.equal((await client.state()).notes[0].text,before.notes[0].text);
  db.close();const reopened=new D1(path.join(directory,'data.sqlite3'));replaceDb(reopened);env.DB=reopened;
  assert.deepEqual((await client.state()).notes,before.notes);
  assert.deepEqual((await client.action('note_save',{stone_id:48,text:'  '})).notes,[]);
  assert.equal((await other.state()).notes[0].text,'Басқа адамның жазбасы');
});

test('new_features_validate_stone_and_note_boundaries',async({client})=>{
  for(const action of ['comparison_toggle','stone_view','note_save']) for(const stone_id of [0,49,'1',true,null]) await client.action(action,{stone_id,text:'Сынақ'},400);
  await client.action('note_save',{stone_id:13,text:'x'.repeat(2001)},400);
  await client.action('note_save',{stone_id:13,text:'a\u0000b'},400);
  await client.action('note_save',{stone_id:13,text:12},400);
  assert.equal((await client.action('note_save',{stone_id:13,text:'x'.repeat(2000)})).notes[0].text.length,2000);
});

test('explicit_guest_import_copies_discovery_and_scope_stays_per_account',async({client,env})=>{
  const guest=await client.state();
  await client.action('comparison_toggle',{stone_id:48});await client.action('stone_view',{stone_id:13});await client.action('note_save',{stone_id:24,text:'Қонақ жазбасы'});
  const imported=await client.register('import-discovery@example.test',true);
  assert.deepEqual(imported.comparison,[48]);assert.equal(imported.recent[0].stone_id,13);assert.equal(imported.notes[0].text,'Қонақ жазбасы');
  assert.notEqual(imported.storage_scope,guest.storage_scope);assert.match(imported.storage_scope,/^[a-f0-9]{64}$/);
  const other=new Client(env);await other.state();await other.action('login',{email:'import-discovery@example.test',password:PASSWORD});
  assert.equal((await other.state()).storage_scope,imported.storage_scope);
  const loggedOut=await client.action('logout');assert.notEqual(loggedOut.storage_scope,imported.storage_scope);assert.notEqual(loggedOut.storage_scope,guest.storage_scope);
  assert.deepEqual(loggedOut.notes,[]);
  await client.action('stone_view',{stone_id:48});await client.action('note_save',{stone_id:48,text:'Көшірілмесін'});await client.action('comparison_toggle',{stone_id:48});
  const blank=await client.register('empty-discovery@example.test',false);
  for(const key of ['notes','recent','comparison']) assert.deepEqual(blank[key],[]);
});

test('discovery_batch_failure_preserves_previous_rows',async({client,db})=>{
  await client.action('note_save',{stone_id:48,text:'Сақталсын'});
  db.failMutationAt=2;await client.action('note_save',{stone_id:48,text:'Сақталмасын'},500);
  assert.equal((await client.state()).notes[0].text,'Сақталсын');
  await client.action('stone_view',{stone_id:48});db.failMutationAt=2;await client.action('stone_view',{stone_id:13},500);
  assert.deepEqual((await client.state()).recent.map(r=>r.stone_id),[48]);
});

test('existing_v3_data_migrates_on_existing_session_without_loss',async({client,env,db})=>{
  const salt='11'.repeat(16),hashed=pbkdf2Sync(PASSWORD,Buffer.from(salt,'hex'),100000,32,'sha256').toString('hex');
  const created='2026-11-20T00:00:00.000Z';
  db.sqlite.prepare('INSERT INTO users(id,name,email,salt,password_hash,rounds,created) VALUES(1,?,?,?,?,100000,?)').run('Бұрынғы қолданушы','legacy@example.test',salt,hashed,created);
  const token='a'.repeat(43),sessionHash=createHash('sha256').update(token).digest('hex');
  db.sqlite.prepare('INSERT INTO sessions(token_hash,owner,user_id,csrf,expires) VALUES(?,?,?,?,?)').run(sessionHash,'u:1',1,'legacy-csrf',Math.floor(Date.now()/1000)+3600);
  client.cookie='asyltas_session='+token;client.csrf='legacy-csrf';
  db.sqlite.exec("INSERT INTO runtime VALUES(1,77); INSERT INTO necklace VALUES('u:1',2,2),('u:1',3,1),('u:1',5,3); INSERT INTO favorites VALUES('u:1',12)");
  db.sqlite.prepare('INSERT INTO reviews VALUES(1,12,4,?,?)').run('Бұрынғы пікір',created);
  const snapshot=JSON.stringify({items:[{stone_id:2,quantity:2,carats:'2.40',total:'432000.00'},{stone_id:3,quantity:1,carats:'1.10',total:'165000.00'},{stone_id:5,quantity:3,carats:'2.40',total:'19200.00'}],count:6,carats:'5.90',total:'616200.00'});
  db.sqlite.prepare('INSERT INTO collections(owner,name,updated,snapshot) VALUES(?,?,?,?)').run('u:1','Бұрынғы жинақ',created,snapshot);
  db.sqlite.prepare("INSERT INTO orders(user_id,status,created,delivery,pickup_date,phone,address,note,gift,snapshot) VALUES(1,'accepted',?,'pickup',?,'+7 700 000 0000','','Ескі өтінім',0,?)").run(created,tomorrow,snapshot);
  db.sqlite.prepare("INSERT INTO messages(user_id,subject,text,reply,created) VALUES(1,'Ескі сұрақ','Сұрақ','Жауап',?)").run(created);
  db.sqlite.prepare('INSERT INTO settings VALUES(?,?)').run('store',JSON.stringify({address:'Сақталған мекенжай',phone:'',hours:'9–18',example:false}));
  db.sqlite.exec(upgrade);db.trace=[];
  const migrated=await client.state();equalSnapshot(migrated.necklace);equalSnapshot(migrated.orders[0]);equalSnapshot(migrated.collections[0].necklace);
  assert.equal(migrated.user.email,'legacy@example.test');assert.equal(migrated.csrf,'legacy-csrf');assert.deepEqual(migrated.favorites,[12]);assert.equal(migrated.reviews[0].text,'Бұрынғы пікір');assert.equal(migrated.messages[0].reply,'Жауап');assert.equal(migrated.store.address,'Сақталған мекенжай');
  assert.ok(db.trace.length<50);assert.equal(db.sqlite.prepare('SELECT COUNT(*) AS n FROM necklace').get().n,3);
  assert.equal(db.sqlite.prepare('SELECT snapshot FROM orders').get().snapshot,snapshot);
  await client.action('necklace_clear');await client.action('favorite_toggle',{stone_id:12});
  db.sqlite.exec("DELETE FROM app_versions WHERE key='catalog'");
  const reseeded=await client.state();assert.equal(reseeded.necklace.count,0);assert.deepEqual(reseeded.favorites,[]);assert.equal(reseeded.orders[0].total,'616200.00');
  await client.action('necklace_set',{stone_id:48,quantity:1});assert.equal((await client.state()).necklace.total,'9200.00');
  const login=new Client(env);await login.state();assert.equal((await login.action('login',{email:'legacy@example.test',password:PASSWORD})).user.name,'Бұрынғы қолданушы');
},{legacy:true,bootstrap:false});

test('initial_catalog_seed_failure_rolls_back_then_recovers',async({client,db})=>{
  db.failSeedAt=5;assert.equal((await client.request()).status,500);
  assert.equal(db.sqlite.prepare('SELECT COUNT(*) AS n FROM catalog_data').get().n,0);
  assert.equal(db.sqlite.prepare('SELECT COUNT(*) AS n FROM app_versions').get().n,0);
  const recovered=await client.state();assert.equal(recovered.catalog.length,48);
  assert.equal(db.sqlite.prepare("SELECT value FROM app_versions WHERE key='legacy_v3_copied'").get().value,'1');
},{bootstrap:false});

test('parallel_initializers_seed_once_and_preserve_guest_isolation',async({client,env,db})=>{
  const other=new Client(env);
  const results=await Promise.all([client.state(),other.state()]);
  assert.ok(results.every(s=>s.catalog.length===48));assert.notEqual(results[0].storage_scope,results[1].storage_scope);
  assert.equal(db.sqlite.prepare('SELECT revision FROM runtime').get().revision,1);
  assert.equal(db.sqlite.prepare('SELECT COUNT(*) AS n FROM catalog_data').get().n,48);
},{bootstrap:false});

await mkdir(testsRoot,{recursive:true});
const results=[];
for (const item of cases) {
  const directory=await mkdtemp(path.join(testsRoot,'.backend-test-'));
  let db=new D1(path.join(directory,'data.sqlite3'));
  const env={DB:db,SITE_OWNER_EMAIL:OWNER};
  const started=Date.now();
  try {
    db.sqlite.exec(migration);
    if(!item.legacy) db.sqlite.exec(upgrade);
    const client=new Client(env);if(item.bootstrap!==false) await client.state();
    await item.fn({client,env,db,directory,replaceDb:replacement=>{db=replacement;}});
    results.push({name:item.name,passed:true,duration_ms:Date.now()-started});
    console.log('PASS '+item.name);
  } catch(error) {
    results.push({name:item.name,passed:false,error:error.stack,duration_ms:Date.now()-started});
    console.error('FAIL '+item.name+'\n'+error.stack);
  } finally {
    db.close();
    const base=await realpath(testsRoot),target=await realpath(directory);
    assert.ok(target.startsWith(base+path.sep) && path.basename(target).startsWith('.backend-test-'),'Temporary test directory must remain inside the tests workspace');
    await rm(target,{recursive:true,force:true});
  }
}
const report={status:results.every(x=>x.passed)?'passed':'failed',created_utc:new Date().toISOString(),tests:results.length,passed:results.filter(x=>x.passed).length,checks:results,
  runtime:process.version,adapter:'node:sqlite D1 prepare/bind/first/all/batch with real transaction rollback',database:'isolated temporary SQLite files; removed after each test',
  server_sha256:createHash('sha256').update(await readFile(new URL('../worker/api.js',import.meta.url))).digest('hex'),
  schema_sha256:createHash('sha256').update(migration+upgrade).digest('hex'),
  migration_hashes:{initial:createHash('sha256').update(migration).digest('hex'),upgrade:createHash('sha256').update(upgrade).digest('hex')},
  demo:{count:6,carats:'5.90',total:'616200.00'},catalog_records:48,
  admin_trust:'Tests emulate Sites dispatcher verified identity. Deployment must keep this Worker behind Sites and verify external spoofed reserved headers are stripped.',
  published:false};
const reportDir=path.join(root,'reports/qa_online_v4_20261125');await mkdir(reportDir,{recursive:true});
await writeFile(path.join(reportDir,'backend_checks.json'),JSON.stringify(report,null,2)+'\n');
console.log(`${report.passed}/${report.tests} passed`);
process.exitCode=report.status==='passed'?0:1;
