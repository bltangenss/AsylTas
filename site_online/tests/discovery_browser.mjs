import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
import {createHash} from 'node:crypto';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const runtime='C:/Program Files/WindowsApps/OpenAI.CodexPrimaryRuntime.v26-1123-915-0_26.1123.915.0_x64__3k8sg7r9htsxt/dependencies/node';
const {chromium,devices}=createRequire(path.join(runtime,'package.json'))('playwright');
const base=process.argv[2]||'https://127.0.0.1:8792';
if(new URL(base).hostname!=='127.0.0.1')throw new Error('This test creates isolated QA accounts and may only run against the local preview.');
const dir=path.join(root,'../reports/qa_online_v4_20261125');await fs.mkdir(path.join(dir,'screenshots'),{recursive:true});
const report={status:'running',url:base,started:new Date().toISOString(),checks:[],screenshots:[],page_errors:[],location_testing:'Chromium Geolocation API uses an explicitly granted emulated position; denied/unavailable/timeout callbacks are controlled UI fault injection. No physical GPS claim.',database:'Separate Wrangler local persist path; no production data'};
report.worker_sha256=createHash('sha256').update(await fs.readFile(path.join(root,'dist/server/index.js'))).digest('hex');
report.app_sha256=createHash('sha256').update(await fs.readFile(path.join(root,'public/app.js'))).digest('hex');
const check=(name,passed,detail='')=>{report.checks.push({name,passed:!!passed,detail});console.log((passed?'PASS ':'FAIL ')+name);if(!passed)throw new Error(name+' '+detail);};
const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',args:['--ignore-certificate-errors']});
const context=await browser.newContext({...devices['iPhone 13'],ignoreHTTPSErrors:true,permissions:['geolocation'],geolocation:{latitude:51.15,longitude:71.46,accuracy:20}});
const page=await context.newPage();page.on('pageerror',e=>report.page_errors.push(e.message));page.on('dialog',d=>d.accept());
const api=async(p=page)=>p.evaluate(async()=>(await fetch('/api/state')).json());
async function go(route,p=page){await p.goto(base+'/#'+route);await p.waitForFunction(route=>document.body.dataset.page===route&&document.querySelector('#catalog-count')?.textContent==='48',route);}
async function clickMutation(action,callback,p=page,expected=200){const pending=p.waitForResponse(r=>r.url().endsWith('/api/action')&&r.request().postDataJSON()?.action===action);await callback();const response=await pending;const data=await response.json();if(response.status()!==expected)throw Error(action+': '+JSON.stringify(data));if(data.ok)await p.waitForFunction(expected=>typeof state!=='undefined'&&JSON.stringify(state)===JSON.stringify(expected),data);return data;}
async function open(id,p=page){await clickMutation('stone_view',()=>p.locator(`[data-do=detail][data-id="${id}"]`).first().click(),p);await p.locator('form[data-form=note]').waitFor();}
async function shot(name,p=page){await p.locator('#toast.visible').waitFor({state:'hidden',timeout:6000});await p.locator('img').evaluateAll(images=>Promise.all(images.filter(i=>{const r=i.getBoundingClientRect();return r.height&&r.y<innerHeight&&r.bottom>0;}).map(i=>i.decode().catch(()=>{}))));const file=path.join(dir,'screenshots',name+'.png');await p.screenshot({path:file,fullPage:false,animations:'disabled',scale:'css'});report.screenshots.push(file);}
async function register(p,email,name='QA қолданушы'){await go('profile',p);await p.locator('[data-do=register]').click();await p.locator('form[data-form=auth] [name=name]').fill(name);await p.locator('form[data-form=auth] [name=email]').fill(email);await p.locator('form[data-form=auth] [name=password]').fill('Test-Only-2026!');return clickMutation('register',()=>p.locator('form[data-form=auth] [type=submit]').click(),p);}
let otherContext,ownerContext;
try {
  await go('catalog');check('Catalog has48 distinct cards',await page.locator('.product').count()===48);
  check('Mobile navigation preserved',await page.locator('.bottom-nav a').count()===5&&await page.locator('.bottom-nav').isVisible());
  await page.locator('[data-do=filters]').click();await page.locator('[name=min]').fill('85');await page.locator('[name=max]').fill('88');await page.locator('[name=minCarats]').fill('1.25');await page.locator('[name=maxCarats]').fill('1.50');await page.locator('[name=budget]').fill('22500');await page.locator('#range-form [type=submit]').click();
  check('Combined exact filter shows topaz aquamarine ametrine',JSON.stringify(await page.locator('.product-title').allTextContents())===JSON.stringify(['Топаз','Аквамарин','Аметрин']));
  await page.locator('[name=minCarats]').fill('2');await page.locator('[name=maxCarats]').fill('1');await page.locator('#range-form [type=submit]').click();
  check('Inverted range displays field error',await page.locator('#range-form .form-error').isVisible());
  await page.locator('[data-do=reset-filters]').click();await page.locator('#catalog-search').fill('hematite');check('English alias reaches added stone',await page.locator('.product-title').innerText()==='Гематит');
  await page.locator('#catalog-search').fill('');
  for(const id of [13,24,48])await clickMutation('comparison_toggle',()=>page.locator(`[data-do=compare-stone][data-id="${id}"]`).click());
  await clickMutation('comparison_toggle',()=>page.locator('[data-do=compare-stone][data-id="1"]').click(),page,400);
  check('Fourth comparison blocked with user message',(await page.locator('#toast').innerText()).includes('үш тас'));
  await go('compare');check('Compare table has three stones',await page.locator('.comparison-table thead th').count()===4);
  check('Comparison fits page with internal horizontal scroll',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));await shot('v4_mobile_compare');
  await clickMutation('comparison_toggle',()=>page.locator('[data-do=compare-stone][data-id="24"]').click());check('Compare remove updates persisted selection',JSON.stringify((await api()).comparison)==='[13,48]');
  await go('catalog');await open(48);await page.locator('form[data-form=note] textarea').fill('Жеке гематит жазбасы <b>мәтін</b>');await clickMutation('note_save',()=>page.locator('form[data-form=note] [type=submit]').click());await page.locator('.dialog-close').click();
  await open(24);check('Alexandrite photo remains uncropped',await page.locator('.detail-photo img').evaluate(i=>getComputedStyle(i).objectFit==='contain'));await shot('v4_mobile_stone_note');await page.locator('.dialog-close').click();
  await go('recent');check('Recent order follows actual detail opens',JSON.stringify((await api()).recent.map(r=>r.stone_id))==='[24,48]');
  await go('notes');check('Private note survives reload and HTML is escaped',(await page.locator('.note-text').innerText()).includes('<b>мәтін</b>')&&await page.locator('.note-text b').count()===0);await shot('v4_mobile_notes');
  otherContext=await browser.newContext({...devices['iPhone 13'],ignoreHTTPSErrors:true});const other=await otherContext.newPage();await go('notes',other);check('Different guest cannot see notes comparison or history',(await api(other)).notes.length===0&&(await api(other)).comparison.length===0&&(await api(other)).recent.length===0);
  await go('location');await page.locator('#campus-place').waitFor();check('Three campus places are selectable',await page.locator('#campus-place option').count()===3);
  check('Location not requested automatically',await page.locator('#gps-status').getAttribute('data-status')==='idle');
  check('Pushkin9 is identified as a meeting place',(await page.locator('.campus-main').innerText()).includes('Университеттің ресми корпусы немесе дүкен ретінде көрсетілмейді'));
  const gpsRequests=[];const record=request=>{if(request.url().includes('/api/'))gpsRequests.push(request.postData()||'GET');};page.on('request',record);
  await page.locator('#gps-start').click();await page.waitForFunction(()=>document.querySelector('#gps-status')?.dataset.status==='success');
  check('User-triggered browser location computes distance',(await page.locator('#gps-status').innerText()).includes('түзу сызық'));
  const route=new URL(await page.locator('#campus-route').getAttribute('href'));check('Route includes explicitly permitted current position',route.searchParams.get('origin')==='51.15,71.46');
  await page.locator('#campus-place').selectOption('enu_pushkin11');check('Campus switch changes destination',new URL(await page.locator('#campus-route').getAttribute('href')).searchParams.get('destination')==='51.159897,71.465239');
  await page.locator('#travel-mode').selectOption('transit');check('Transit route mode is supported',new URL(await page.locator('#campus-route').getAttribute('href')).searchParams.get('travelmode')==='transit');
  await shot('v4_mobile_gps_success');await page.locator('#gps-clear').click();check('Clear removes GPS origin',!new URL(await page.locator('#campus-route').getAttribute('href')).searchParams.has('origin'));
  for(const [code,label] of [[1,'рұқсат'],[2,'GPS пен желіні'],[3,'уақыты аяқталды']]){
    await page.evaluate(code=>{navigator.geolocation.getCurrentPosition=(_ok,error)=>error({code});},code);await page.locator('#gps-start').click();check('GPS error'+code+' displays actionable guidance',(await page.locator('#gps-status').innerText()).includes(label));
  }
  await shot('v4_mobile_gps_denied');check('GPS actions never call application API',gpsRequests.length===0,JSON.stringify(gpsRequests));page.off('request',record);
  await page.evaluate(()=>{navigator.clipboard.writeText=async text=>{window.__copiedAddress=text;};});await page.locator('[data-do=copy-place]').click();check('Copy uses selected place address',await page.evaluate(()=>window.__copiedAddress==='Астана, Александр Пушкин көшесі, 11'));
  await page.evaluate(()=>Object.defineProperty(navigator,'share',{configurable:true,value:async data=>{window.__sharedPlace=data;}}));await page.locator('[data-do=share-place]').click();check('Share passes only public meeting address',await page.evaluate(()=>window.__sharedPlace.text.includes('Пушкин')&&!window.__sharedPlace.url.includes('origin=')));
  const customerEmail='v4-browser-'+Date.now()+'@example.invalid';const user=await register(page,customerEmail);check('First public customer remains non-admin',user.user.admin===false);check('Explicit signup imports private guest note and comparison',user.notes.length===1&&user.comparison.length===2);
  await go('necklace');await clickMutation('necklace_demo',()=>page.getByRole('button',{name:'Үлгі алқа',exact:true}).click());
  check('V3 sample total unchanged',(await api()).necklace.total==='616200.00'&&(await api()).necklace.carats==='5.90');
  await page.locator('[data-do=checkout]').click();await page.locator('form[data-form=checkout] [name=phone]').fill('+7 700 000 0000');await page.locator('form[data-form=checkout] [name=confirmed]').check();const created=await clickMutation('order_create',()=>page.locator('form[data-form=checkout] [type=submit]').click());check('Checkout persists exact immutable order',created.orders[0].count===6&&created.orders[0].total==='616200.00');
  const orderId=created.orders[0].id;await go('necklace');await clickMutation('necklace_clear',()=>page.locator('[data-do=clear]').click());check('Changing cart preserves submitted order',(await api()).orders[0].total==='616200.00');
  ownerContext=await browser.newContext({ignoreHTTPSErrors:true,viewport:{width:1440,height:1000},extraHTTPHeaders:{'oai-authenticated-user-id':'v4-local-owner','oai-authenticated-user-email':'owner-qa@asyltas.invalid'}});const owner=await ownerContext.newPage();owner.on('pageerror',e=>report.page_errors.push(e.message));
  const ownerState=await register(owner,'owner-qa@asyltas.invalid','QA әкімші');check('Owner registration still requires explicit admin claim',!ownerState.user.admin&&ownerState.admin_claim_available);
  await go('profile',owner);await clickMutation('admin_claim',()=>owner.locator('[data-do=claim-admin]').click(),owner);await go('orders',owner);await owner.locator(`[data-do=order][data-id="${orderId}"]`).click();await owner.locator('form[data-form=order-status] select').selectOption('ready');await clickMutation('admin_order_status',()=>owner.locator('form[data-form=order-status] [type=submit]').click(),owner);
  await go('orders');check('Admin update reaches customer order',(await api()).orders[0].status==='ready');
  await ownerContext.setExtraHTTPHeaders({});await owner.reload();await go('profile',owner);check('Admin privilege disappears without trusted identity',!(await api(owner)).user.admin&&(await api(owner)).users.length===0&&await owner.locator('.admin-table').count()===0);
  await go('catalog',owner);await shot('v4_desktop_catalog',owner);check('Desktop sidebar preserved',await owner.locator('.sidebar').isVisible()&&!await owner.locator('.bottom-nav').isVisible());
  for(const [width,height,label] of [[375,667,'SE'],[430,932,'ProMax'],[844,390,'landscape']]){await page.setViewportSize({width,height});for(const route of ['catalog','compare','notes','location']){await go(route);if(route==='location')await page.locator('#campus-place').waitFor();check(label+' '+route+' avoids viewport overflow',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));}}
  check('No JavaScript exceptions',report.page_errors.length===0,report.page_errors.join(';'));
  report.status='passed';
}catch(error){report.status='failed';report.failure=error.stack;process.exitCode=1;console.error(error);try{await shot('v4_failure');}catch{}}
finally{await context.close();await otherContext?.close();await ownerContext?.close();await browser.close();report.finished=new Date().toISOString();report.passed=report.checks.filter(c=>c.passed).length;await fs.writeFile(path.join(dir,'browser_checks.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({status:report.status,passed:report.passed,checks:report.checks.length}));}
