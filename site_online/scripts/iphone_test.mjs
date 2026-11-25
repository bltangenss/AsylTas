import fs from 'node:fs/promises';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const runtime='C:/Program Files/WindowsApps/OpenAI.CodexPrimaryRuntime.v26-1123-915-0_26.1123.915.0_x64__3k8sg7r9htsxt/dependencies/node';
const require=createRequire(path.join(runtime,'package.json'));
const {webkit,chromium,devices}=require('playwright');
const base=process.argv[2]||'https://127.0.0.1:8788';
const live=!base.includes('127.0.0.1');
const engine=process.env.ASYL_QA_ENGINE||'webkit';
const outputRoot=process.env.ASYL_QA_OUTPUT_ROOT||path.resolve(root,'..');
const reportDir=path.join(outputRoot,'reports/qa_online_v4_20261125/iphone');
const shotDir=path.join(outputRoot,'reports/qa_online_v4_20261125/iphone/screenshots');
await fs.mkdir(reportDir,{recursive:true});await fs.mkdir(shotDir,{recursive:true});
const report={url:base,started:new Date().toISOString(),engine:engine+' (iPhone viewport), plus Chromium PWA runtime',physical_iPhone:false,checks:[],screenshots:[],errors:[],status:'running'};
const check=(name,passed,detail='')=>{report.checks.push({name,passed:!!passed,detail});console.log(`${passed?'PASS':'FAIL'} ${name}`);if(!passed)throw Error(name+' '+detail);};
const browser=engine==='chromium'?await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',args:['--ignore-certificate-errors']}):await webkit.launch({headless:true,...(process.env.ASYL_QA_WEBKIT?{executablePath:process.env.ASYL_QA_WEBKIT}:{})});
const context=await browser.newContext({...devices['iPhone 13'],ignoreHTTPSErrors:true});
const page=await context.newPage();
page.on('pageerror',error=>report.errors.push(error.message));page.on('dialog',dialog=>dialog.accept());
const api=()=>page.evaluate(async()=>(await fetch('/api/state')).json());
const fits=()=>page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1);
const tab=async route=>{await page.locator('.bottom-nav a[data-page="'+route+'"]').click();await page.waitForFunction(route=>document.body.dataset.page===route,route);};
const shot=async name=>{await page.locator('img').evaluateAll(images=>Promise.all(images.filter(img=>{const r=img.getBoundingClientRect();return r.width>0&&r.y<innerHeight&&r.bottom>0;}).map(img=>img.decode().catch(()=>{}))));const file=path.join(shotDir,(live?'live_':'')+name+'.png');await page.screenshot({path:file,animations:'disabled',fullPage:false,scale:'css'});report.screenshots.push(file);};
const action=async callback=>{const response=page.waitForResponse(r=>r.url().endsWith('/api/action')&&r.request().method()==='POST'&&r.request().postDataJSON().action!=='stone_view');await callback();const data=await(await response).json();if(!data.ok)throw Error(data.error);await page.waitForFunction(expected=>document.querySelector('#tab-necklace-count').textContent===expected,String(data.necklace.count>99?'99+':data.necklace.count));return data;};
let chromiumBrowser;
try{
  await page.goto(base);await page.locator('.app-greeting h1').waitFor();
  check('iPhone starts with dedicated app home',await page.locator('.mobile-home').isVisible()&&!await page.locator('main>.hero').isVisible());
  check('Five reachable bottom tabs',await page.locator('.bottom-nav a').count()===5&&await page.locator('.bottom-nav').isVisible());
  check('iPhone home fits viewport',await fits());
  check('No website footer on mobile',!await page.locator('.footer').isVisible());
  await shot('01_home');
  if(engine==='webkit'){
  await page.locator('.install-card').click();
  check('iPhone installation guide explains Safari and Home Screen',(await page.locator('#dialog').innerText()).includes('Safari')&&(await page.locator('#dialog').innerText()).includes('На экран «Домой»'));
  await shot('02_install');await page.locator('[data-do=close-modal]').click();
  }
  await tab('catalog');check('Catalog tab is current',await page.locator('.bottom-nav [data-page=catalog]').getAttribute('aria-current')==='page');
  check('Advanced filters start compact',!await page.locator('#catalog-options').isVisible());
  await page.locator('#catalog-search').fill('зүбә');
  check('Kazakh search works from mobile',await page.locator('.product').count()===1);
  await page.locator('#catalog-search').fill('');
  await page.locator('[data-do=filters]').click();
  check('Mobile filter control expands',await page.locator('#catalog-options').isVisible());
  await page.locator('[name=min]').fill('80');await page.locator('[name=max]').fill('80');
  await page.locator('#range-form [type=submit]').click();
  check('Mobile range boundaries still include emerald',await page.locator('.product-title').allTextContents().then(rows=>rows.length===2&&rows.includes('Зүбәржат')&&rows.includes('Апатит')));
  await page.locator('[data-do=reset-filters]').click();
  await page.locator('#catalog-sort').selectOption('desc');
  check('Price sorting is preserved',(await page.locator('.product-title').allTextContents())[0]==='Гауһар');
  check('iPhone form fields avoid focus zoom',await page.locator('#catalog-sort').evaluate(el=>parseFloat(getComputedStyle(el).fontSize)>=16));
  await page.locator('[data-do=filters]').click();
  for(const img of await page.locator('.product img').all()){
    await img.scrollIntoViewIfNeeded();await img.evaluate(image=>image.loading='eager');
    await page.waitForFunction(src=>Array.from(document.querySelectorAll('.product img')).find(img=>img.src===src)?.naturalWidth>0,await img.getAttribute('src').then(src=>new URL(src,base).href));
  }
  await page.evaluate(()=>window.scrollTo(0,0));
  check('Forty-eight real photos render',await page.locator('.product img').evaluateAll(images=>images.length===48&&images.every(i=>i.naturalWidth>0)));
  await shot('03_catalog');
  await page.locator('[data-do=detail][data-id="1"]').first().click();
  const bounds=await page.locator('#dialog').boundingBox();
  check('Stone detail is an iPhone sheet',bounds.width<=390&&bounds.x>=0&&bounds.y>=0);
  await shot('04_stone');await page.locator('.dialog-close').click();
  await action(()=>page.locator('[data-do=add][data-id="1"]').click());
  check('Bottom tab badge updates with saved necklace',(await page.locator('#tab-necklace-count').textContent())==='1');
  await tab('necklace');
  await action(()=>page.getByRole('button',{name:'Үлгі алқа',exact:true}).click());
  const sample=await api();check('Mobile sample remains exact',sample.necklace.count===6&&sample.necklace.carats==='5.90'&&sample.necklace.total==='616200.00');
  await shot('05_necklace');
  await page.reload();await page.locator('.necklace-row').first().waitFor();
  check('Necklace survives app reload',(await api()).necklace.count===6);
  await tab('profile');check('All additional sections remain reachable',await page.locator('.profile-links a').count()===10);
  await shot('06_profile');
  await page.getByRole('button',{name:'Аккаунтқа кіру',exact:true}).click();
  await page.getByLabel('Email',{exact:true}).fill('iphone-qa@example.invalid');
  check('Login sheet uses readable iPhone input size',await page.getByLabel('Email',{exact:true}).evaluate(el=>getComputedStyle(el).fontSize==='16px'));
  check('Login sheet fits visible width',await page.locator('#dialog').evaluate(el=>el.scrollWidth<=el.clientWidth+1));
  await shot('07_login');await page.locator('.dialog-close').click();
  await page.locator('.profile-links a[href="#location"]').click();
  await page.locator('.campus-map').waitFor();
  check('Location is reachable through profile',await page.locator('.bottom-nav [data-page=profile]').getAttribute('aria-current')==='page');
  await shot('08_location');await page.locator('#mobile-back').click();
  await page.waitForFunction(()=>document.body.dataset.page==='profile');
  check('Secondary-page back returns to profile',await page.locator('.app-profile-menu').isVisible());
  for(const [width,height,label] of [[375,667,'SE'],[430,932,'Pro_Max'],[844,390,'landscape']]){
    await page.setViewportSize({width,height});await tab('catalog');
    check(label+' has bottom navigation and no overflow',await page.locator('.bottom-nav').isVisible()&&await fits());
    await shot('09_'+label);
  }
  await context.addInitScript(()=>Object.defineProperty(navigator,'standalone',{get:()=>true,configurable:true}));
  await page.setViewportSize({width:390,height:844});await page.goto(base+'/#home');await page.reload();await page.locator('.app-greeting').waitFor();
  check('Home Screen mode hides installation prompts',!await page.locator('.install-card').isVisible()&&await page.evaluate(()=>document.documentElement.classList.contains('standalone')));
  await shot('10_standalone');
  await page.evaluate(async()=>{const s=await(await fetch('/api/state')).json();await fetch('/api/action',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':s.csrf},body:JSON.stringify({action:'necklace_clear'})});});
  check(engine+' has no JavaScript exceptions',report.errors.length===0,report.errors.join(';'));
  chromiumBrowser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:['--ignore-certificate-errors']});
  const chromeContext=await chromiumBrowser.newContext({ignoreHTTPSErrors:true,viewport:{width:1440,height:1000}});
  const chrome=await chromeContext.newPage();await chrome.goto(base);await chrome.getByRole('heading',{name:'Тастар әлеміне қош келдіңіз',exact:true}).waitFor();
  check('Desktop keeps original sidebar layout',await chrome.locator('.sidebar').isVisible()&&!await chrome.locator('.bottom-nav').isVisible()&&!await chrome.locator('.mobile-home').isVisible());
  await chrome.evaluate(async()=>await navigator.serviceWorker.ready);
  await chrome.waitForFunction(()=>navigator.serviceWorker.controller!==null);
  const keys=await chrome.evaluate(async()=>{const names=await caches.keys();const all=[];for(const name of names){for(const r of await(await caches.open(name)).keys())all.push(new URL(r.url).pathname);}return all;});
  check('PWA caches only public files',keys.includes('/mobile.css')&&keys.includes('/app.js')&&keys.every(key=>!key.startsWith('/api')&&!key.includes('signin')));
  await chromeContext.setOffline(true);await chrome.reload();await chrome.getByRole('heading',{name:'Байланыс уақытша үзілді'}).waitFor();
  check('Offline app shell offers retry without showing personal data',await chrome.getByRole('button',{name:'Қайта жүктеу',exact:true}).isVisible());
  await chromeContext.setOffline(false);await chrome.getByRole('button',{name:'Қайта жүктеу',exact:true}).click();await chrome.getByRole('heading',{name:'Тастар әлеміне қош келдіңіз',exact:true}).waitFor();
  check('Reconnect restores working app',await chrome.locator('#connection-error').isHidden());
  report.status='passed';
}catch(error){report.status='failed';report.failure=error.stack;console.error(error);process.exitCode=1;try{await shot('failure');}catch{}}
finally{
  try{await page.evaluate(async()=>{const s=await(await fetch('/api/state')).json();if(!s.user&&s.necklace.count)await fetch('/api/action',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':s.csrf},body:JSON.stringify({action:'necklace_clear'})});});report.test_guest_data_removed=true;}catch{report.test_guest_data_removed=false;}
  await browser.close();await chromiumBrowser?.close();report.finished=new Date().toISOString();
  await fs.writeFile(path.join(reportDir,(live?'live_':'')+engine+'_iphone_checks.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify({status:report.status,checks:report.checks.length}));
}
