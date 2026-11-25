import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const origin=new URL(process.argv[2]).origin;
if(!origin.startsWith('https://'))throw new Error('A published HTTPS origin is required');
const output=path.resolve(process.argv[3] || path.join(root,'test-results/live.json'));
const report={url:origin,started:new Date().toISOString(),status:'running',checks:[],created_accounts:0};
let cookie='',csrf='',savedId=null;
async function check(name,ok,detail=''){
  report.checks.push({name,passed:!!ok,detail});
  console.log((ok?'PASS ':'FAIL ')+name);
  if(!ok)throw new Error(name);
}
async function state(){
  const response=await fetch(origin+'/api/state',{headers:cookie?{Cookie:cookie}:{}});
  if(!response.ok || !(response.headers.get('content-type')||'').includes('application/json'))throw new Error('Public state is unavailable: '+response.status);
  const set=response.headers.getSetCookie().find(value=>value.startsWith('asyltas_session='));
  if(set)cookie=set.split(';')[0];
  const data=await response.json();csrf=data.csrf;
  return {data,headers:response.headers,set};
}
async function action(action,values={},expected=200){
  const response=await fetch(origin+'/api/action',{method:'POST',headers:{'Content-Type':'application/json',Origin:origin,Cookie:cookie,'X-CSRF-Token':csrf},body:JSON.stringify({action,...values})});
  if(response.status!==expected)throw new Error('Unexpected status for '+action+': '+response.status);
  const set=response.headers.getSetCookie().find(value=>value.startsWith('asyltas_session='));
  if(set)cookie=set.split(';')[0];
  const data=await response.json();if(data.csrf)csrf=data.csrf;
  return data;
}
try{
  const html=await fetch(origin+'/');
  await check('Public HTML is reachable without platform login',html.status===200&&(await html.text()).includes('Асыл тас'));
  const initial=await state();
  await check('Hosted D1 initializes anonymous state',initial.data.ok&&initial.data.user===null&&initial.data.catalog.length===12);
  await check('Session cookie is Secure HttpOnly and SameSite Lax',/;\s*Secure/i.test(initial.set||'')&&/;\s*HttpOnly/i.test(initial.set||'')&&/SameSite=Lax/i.test(initial.set||''));
  await check('API is not cached',initial.headers.get('cache-control')==='no-store');
  const credits=JSON.parse(await fs.readFile(path.join(root,'public/assets/photo_credits.json'),'utf8'));
  for(const row of credits){
    const response=await fetch(origin+'/assets/'+row.resource);
    const bytes=Buffer.from(await response.arrayBuffer());
    await check('Original photograph '+row.id+' is served intact',response.status===200&&createHash('sha256').update(bytes).digest('hex')===row.sha256);
  }
  let data=await action('necklace_demo');
  await check('Hosted exact sample arithmetic',data.necklace.count===6&&data.necklace.carats==='5.90'&&data.necklace.total==='616200.00');
  await action('necklace_set',{stone_id:2,quantity:100},400);
  await check('Invalid quantity does not change data',(await state()).data.necklace.count===6);
  const name='Deployment verification '+new Date().toISOString();
  data=await action('collection_save',{name});
  savedId=data.collections.find(row=>row.name===name)?.id;
  await check('Hosted collection snapshot is persisted',!!savedId);
  await action('necklace_clear');
  data=await action('collection_load',{id:savedId});
  await check('Saved collection restores exact total',data.necklace.total==='616200.00');
  await action('admin_claim',{},401);
  await action('favorite_toggle',{stone_id:1},401);
  await check('Anonymous visitor cannot claim admin or private favorites',true);
  const rejected=await fetch(origin+'/api/action',{method:'POST',headers:{'Content-Type':'application/json',Origin:origin,Cookie:cookie},body:JSON.stringify({action:'necklace_clear'})});
  await check('CSRF is required for mutation',rejected.status===403);
  const source=await fetch(origin+'/worker/api.js');
  await check('Private server source is not served',source.status===404);
  report.status='passed';
}catch(error){
  report.status='failed';report.error=error.message;process.exitCode=1;
}finally{
  try{
    if(savedId)await action('collection_delete',{id:savedId});
    if(csrf){const cleaned=await action('necklace_clear');report.test_guest_data_removed=cleaned.necklace.count===0&&cleaned.collections.length===0;}
  }catch(error){report.cleanup_error=error.message;report.status='failed';process.exitCode=1;}
  report.finished=new Date().toISOString();
  await fs.mkdir(path.dirname(output),{recursive:true});
  await fs.writeFile(output,JSON.stringify(report,null,2));
  console.log('Live smoke result: '+report.status);
}
