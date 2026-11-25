import fs from 'node:fs/promises';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const runtime='C:/Program Files/WindowsApps/OpenAI.CodexPrimaryRuntime.v26-1123-915-0_26.1123.915.0_x64__3k8sg7r9htsxt/dependencies/node';
const require=createRequire(path.join(runtime,'package.json'));
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const finishing=process.argv.includes('--finish');
const resuming=finishing||process.argv.includes('--resume');
const base=process.env.ASYL_QA_URL || 'https://127.0.0.1:8788';
const qa=path.join(root,'test-results/browser');
const shots=path.join(root,'test-results/screenshots');
await fs.mkdir(qa,{recursive:true});await fs.mkdir(shots,{recursive:true});
let report={started:new Date().toISOString(),url:base,checks:[],screenshots:[],page_errors:[],failed_requests:[],status:'running'};
if(resuming) {report=JSON.parse(await fs.readFile(path.join(qa,'ui_checks.json'),'utf8'));report.status='running';delete report.failure;}
async function save(){await fs.writeFile(path.join(qa,'ui_checks.json'),JSON.stringify(report,null,2));}
async function check(name,pass,detail=''){report.checks.push({name,passed:!!pass,detail});await save();console.log((pass?'PASS ':'FAIL ')+name);if(!pass)throw new Error(name+' '+detail);}
const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
const context=await browser.newContext({ignoreHTTPSErrors:true,viewport:{width:1440,height:1050}});
const page=await context.newPage();
page.on('pageerror',e=>report.page_errors.push(e.message));
page.on('requestfailed',r=>{if(!r.url().startsWith('blob:'))report.failed_requests.push({url:r.url(),error:r.failure()?.errorText});});
page.on('dialog',d=>d.accept());
const api=()=>page.evaluate(async()=>await(await fetch('/api/state')).json());
const action=async(cb,predicate=()=>true)=>{
  const response=page.waitForResponse(r=>r.url().endsWith('/api/action')&&r.request().method()==='POST');
  await cb();const r=await response,data=await r.json();
  if(!r.ok()||!data.ok)throw new Error(JSON.stringify(data));
  await page.waitForTimeout(100);if(!predicate(data))throw new Error('Unexpected state');return data;
};
async function shot(name){await page.evaluate(()=>Promise.all(document.getAnimations().map(a=>a.finished.catch(()=>{}))));await page.locator('#toast.visible').waitFor({state:'hidden',timeout:5000});const p=path.join(shots,name+'.png');await page.screenshot({path:p,fullPage:true});report.screenshots.push(path.relative(root,p));await save();}
async function nav(name){await page.locator('.sidebar a[href="#'+name+'"]:not(.brand)').click();await page.waitForTimeout(80);}
async function register(p,name,email,password,importGuest=true){
  await p.locator('#account-control [data-do=auth]').click();
  await p.locator('[data-do=auth-tab][data-value=register]').click();
  await p.getByLabel('Аты-жөніңіз',{exact:true}).fill(name);
  await p.getByLabel('Email',{exact:true}).fill(email);
  await p.getByLabel('Құпиясөз',{exact:true}).fill(password);
  await p.locator('[name=import_guest]').setChecked(importGuest);
  const response=p.waitForResponse(r=>r.url().endsWith('/api/action'));
  await p.getByRole('button',{name:'Аккаунт ашу',exact:true}).click();
  const data=await(await response).json();if(!data.ok)throw new Error(JSON.stringify(data));await p.waitForTimeout(150);return data;
}
try{
  await page.goto(base);await page.getByRole('heading',{name:'Тастар әлеміне қош келдіңіз',exact:true}).waitFor();
  if(!resuming){
  await check('Home renders twelve-stone catalog with separate guest session',(await api()).catalog.length===12 && (await api()).user===null);
  await shot('web_01_home');
  await action(()=>page.getByRole('button',{name:'Үлгіні ашу',exact:true}).click());
  let state=await api();await check('Demo exact count weight and price',state.necklace.count===6&&state.necklace.carats==='5.90'&&state.necklace.total==='616200.00');
  await action(()=>page.getByRole('button',{name:'Лағыл санын көбейту',exact:true}).click());
  await check('Plus updates exact total',(await api()).necklace.total==='832200.00');
  await action(()=>page.getByRole('button',{name:'Лағыл санын азайту',exact:true}).click());
  await check('Minus restores exact total',(await api()).necklace.total==='616200.00');
  await page.getByRole('button',{name:'Жинаққа сақтау',exact:true}).click();
  await page.getByLabel('Жинақ атауы',{exact:true}).fill('Кешкі жинақ');
  await action(()=>page.locator('#dialog [type=submit]').click());
  await check('Guest collection snapshot saved',(await api()).collections[0].necklace.total==='616200.00');
  await page.reload();await page.getByRole('heading',{name:'Менің алқам',exact:true}).waitFor();
  await check('Guest necklace survives reload',(await api()).necklace.count===6);
  await nav('catalog');
  await page.locator('#catalog-search').fill('зүбә');
  await check('Kazakh catalog search',await page.locator('.product').count()===1);
  await page.locator('#catalog-search').fill('');
  await page.locator('#range-form [name=min]').fill('80');await page.locator('#range-form [name=max]').fill('80');
  await page.locator('#range-form [type=submit]').click();
  await check('Transparency boundaries are inclusive',await page.locator('.product-title').allTextContents().then(a=>a.length===1&&a[0]==='Зүбәржат'));
  await page.getByRole('button',{name:'Тазарту',exact:true}).click();
  await page.locator('#catalog-sort').selectOption('desc');
  await check('Sort uses price per carat',(await page.locator('.product-title').allTextContents())[0]==='Гауһар');
  await page.locator('[data-do=favorite][data-id="1"]').click();
  await check('Guest favorite asks for login',await page.locator('#dialog').isVisible());
  await page.locator('.dialog-close').click();
  await context.setExtraHTTPHeaders({'oai-authenticated-user-id':'qa-owner-identity','oai-authenticated-user-email':'owner-qa@asyltas.invalid'});
  state=await register(page,'Айдана','owner-qa@asyltas.invalid','LocalTest2026!',true);
  await check('Public registration imports guest data without administrator privilege',!state.user.admin&&state.necklace.count===6&&state.collections.length===1);
  await context.setExtraHTTPHeaders({'oai-authenticated-user-id':'qa-owner-identity','oai-authenticated-user-email':'owner-qa@asyltas.invalid'});
  await page.reload();await page.getByRole('heading',{name:'Тастар әлеміне қош келдіңіз'}).waitFor();
  await nav('profile');
  await action(()=>page.getByRole('button',{name:'Әкімші құқығын қосу',exact:true}).click());
  await check('Verified owner can activate administrator',(await api()).user.admin===true);
  await nav('catalog');await action(()=>page.locator('[data-do=favorite][data-id="1"]').click());
  await nav('favorites');await check('Favorites screen only shows selected stone',await page.locator('.product').count()===1);
  await nav('profile');
  await page.getByLabel('Телефон',{exact:true}).fill('+77011234567');
  await page.getByLabel('Жеткізу мекенжайы',{exact:true}).fill('Алматы, Абай даңғылы 10');
  await action(()=>page.getByRole('button',{name:'Өзгерістерді сақтау',exact:true}).click());
  await check('Profile persists contact details',(await api()).user.address==='Алматы, Абай даңғылы 10');
  await shot('web_05_profile');
  await nav('necklace');await shot('web_03_necklace');
  } else {
    await page.locator('#account-control [data-do=auth]').click();
    await page.getByLabel('Email',{exact:true}).fill('owner-qa@asyltas.invalid');
    await page.getByLabel('Құпиясөз',{exact:true}).fill('LocalTest2026!');
    await action(()=>page.locator('#dialog [type=submit]').click());
    await nav('necklace');
  }
  if(!finishing){
  let state;
  await page.getByRole('button',{name:'Өтінім рәсімдеу',exact:true}).click();
  await page.getByLabel('Алу тәсілі',{exact:true}).selectOption('delivery');
  await page.getByLabel('Қосымша ескерту',{exact:true}).fill('Сыйлыққа арналған');
  await page.getByLabel('Сыйлық ретінде дайындау',{exact:true}).check();
  await page.getByLabel('Байланыс деректері мен алқа құрамын тексердім',{exact:true}).check();
  await shot('web_04_checkout');
  await action(()=>page.getByRole('button',{name:'Өтінімді сақтау',exact:true}).click());
  state=await api();await check('Order stores exact immutable snapshot',state.orders[0].total==='616200.00'&&state.orders[0].gift&&state.orders[0].address==='Алматы, Абай даңғылы 10');
  await page.getByRole('button',{name:'Мәліметтер',exact:true}).click();
  await page.getByLabel('Өтінім күйі',{exact:true}).selectOption('accepted');
  await action(()=>page.getByRole('button',{name:'Күйін сақтау',exact:true}).click());
  await check('Administrator updates order status',(await api()).orders[0].status==='accepted');await shot('web_06_orders');
  await nav('catalog');await page.locator('#catalog-sort').selectOption('default');await shot('web_02_catalog');
  await page.locator('[data-do=detail][data-id="3"]').first().click();
  await page.getByLabel('Пікіріңіз',{exact:true}).fill('Зүбәржаттың түсі ұнады.');
  await action(()=>page.getByRole('button',{name:'Пікірді сақтау',exact:true}).click());
  await check('Review persists and remains editable',await page.getByRole('heading',{name:'Пікіріңізді жаңарту',exact:true}).count()===1);
  await shot('web_07_stone');await page.locator('.dialog-close').click();
  await nav('messages');
  await page.getByLabel('Тақырып',{exact:true}).fill('Алқаны алу');
  await page.getByLabel('Сұрағыңыз',{exact:true}).fill('Алқаны қашан алып кетуге болады?');
  await action(()=>page.getByRole('button',{name:'Хабарламаны сақтау',exact:true}).click());
  await page.getByRole('button',{name:'Жауап жазу',exact:true}).click();
  await page.getByLabel('Жауабыңыз',{exact:true}).fill('Жұмыс уақытында келіңіз.');
  await action(()=>page.getByRole('button',{name:'Жауапты сақтау',exact:true}).click());
  await check('Support message and admin reply persist',(await api()).messages[0].reply==='Жұмыс уақытында келіңіз.');
  await nav('media');await page.locator('#media-upload').setInputFiles(path.join(root,'public/assets/stone_3.jpg'));
  await page.locator('.media-card').waitFor();await page.reload();await page.locator('.media-card').waitFor();
  await check('Media persists in browser IndexedDB',await page.locator('.media-card img').count()===1);
  await shot('web_08_media');
  await nav('location');await check('Example address and live map link are clear',await page.getByText('Үлгі дүкен',{exact:true}).count()===1&&await page.getByRole('link',{name:'Картада ашу'}).getAttribute('href').then(x=>x.startsWith('https://www.google.com/maps/search/')));
  await shot('web_09_location');
  }
  await nav('guide');await check('Authors are displayed',await page.locator('main').innerText().then(x=>x.includes('Болат Тәңірберді')&&x.includes('Ергаш Назым')&&x.includes('Ғалымжан Шынай')));
  await page.goto(base+'/#credits');await page.locator('.credit').first().waitFor();
  await check('All twelve real-photo licenses available',await page.locator('.credit').count()===12);
  const customerContext=await browser.newContext({ignoreHTTPSErrors:true,viewport:{width:1440,height:900}});
  const customer=await customerContext.newPage();await customer.goto(base);await customer.getByRole('heading',{name:'Тастар әлеміне қош келдіңіз'}).waitFor();
  const second=await register(customer,'Дана','web.qa.customer@asyltas.test','Customer2026!',false);
  await check('Second account cannot see private owner data',!second.user.admin&&second.necklace.count===0&&second.favorites.length===0&&second.orders.length===0&&second.collections.length===0&&second.messages.length===0);
  await customerContext.close();
  await page.goto(base+'/#home');await page.setViewportSize({width:390,height:844});await page.waitForTimeout(100);
  await check('Mobile home has no horizontal overflow',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  await shot('web_10_mobile_home');
  await page.locator('#menu-toggle').click();await nav('catalog');
  await check('Mobile navigation opens and closes',await page.locator('#nav-overlay').isHidden());
  await check('Mobile catalog has no horizontal overflow',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await shot('web_11_mobile_catalog');
  await page.locator('[data-do=detail][data-id="3"]').first().click();await check('Mobile detail dialog fits viewport',await page.locator('#dialog').evaluate(el=>el.getBoundingClientRect().width<=innerWidth));await shot('web_12_mobile_detail');await page.locator('.dialog-close').click();
  await page.locator('#menu-toggle').click();await nav('necklace');
  await check('Mobile necklace remains editable',await page.getByRole('button',{name:'Лағыл санын көбейту'}).isVisible());await shot('web_13_mobile_necklace');
  await page.setViewportSize({width:844,height:390});await check('Landscape has no horizontal overflow',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  await page.setViewportSize({width:1440,height:1050});await page.goto(base+'/#home');await shot('web_14_account_home');
  await check('No unhandled JavaScript errors',report.page_errors.length===0,report.page_errors);
  report.status='passed';report.finished=new Date().toISOString();await save();
}catch(error){
  report.status='failed';report.failure=error.stack;await save();await page.screenshot({path:path.join(qa,'failure.png'),fullPage:true});throw error;
}finally{await browser.close();}
