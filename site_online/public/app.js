'use strict';
let state = null;
let currentPage = 'home';
let authMode = 'login';
let busy = false;
let mutationIdle = Promise.resolve();
let toastTimer;
let mediaUrls = [];
let chainSequence = 0;
const mobileQuery = window.matchMedia('(max-width:760px), (max-width:1000px) and (pointer:coarse)');
const tabPages = ['home','catalog','necklace','orders','profile'];
let advancedFiltersOpen = false;
const pageScroll = new Map();
const filterDefaults = {query:'',category:'all',sort:'default',min:0,max:100,minCarats:'',maxCarats:'',budget:''};
const filters = {...filterDefaults};
const discovery = window.AsylDiscovery;
let campusPlaces = null;
let campusLoading = false;
let campusError = '';
let selectedPlace = 'pushkin9';
let travelMode = 'walking';
let gps = {status:'idle',position:null,message:''};
let gpsRequest = 0;
let gpsExpiryTimer;
let detailViewQueue = Promise.resolve();
const pageNames = {home:'Басты бет',catalog:'Тастар каталогы',necklace:'Менің алқам',saved:'Менің жинақтарым',orders:'Өтінімдер',favorites:'Таңдаулылар',profile:'Жеке кабинет',location:'ЕҰУ маңы · GPS',compare:'Тастарды салыстыру',recent:'Соңғы қаралған',notes:'Жеке жазбалар',messages:'Хабарламалар',media:'Медиа кітапхана',guide:'Қолдану нұсқаулығы',credits:'Фото авторлары'};
const statusNames = {new:'Жаңа өтінім',accepted:'Қабылданды',preparing:'Дайындалуда',ready:'Дайын',cancelled:'Бас тартылды'};
const main = document.querySelector('#main');
const dialog = document.querySelector('#dialog');
const $ = selector => document.querySelector(selector);
const esc = value => String(value == null ? '' : value).replace(/[&<>"']/g, x => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[x]));
const icon = name => '<svg class="icon" aria-hidden="true"><use href="#i-'+name+'"/></svg>';
const money = value => {const [whole,part]=Number(value || 0).toFixed(2).split('.');return whole.replace(/\B(?=(\d{3})+(?!\d))/g,' ')+(part==='00'?'':','+part)+' ₸';};
const weight = value => Number(value || 0).toFixed(2);
const stone = id => state.catalog.find(x => x.id === Number(id));
const quantity = id => Number(state.necklace.items.find(x => x.stone_id === Number(id))?.quantity || 0);
const photo = s => '<img src="'+esc(s.photo)+'" alt="'+esc(s.name)+' тасының фотосуреті" loading="'+(currentPage==='home'&&s.id===3?'eager':'lazy')+'">';
const field = (label,name,value='',type='text',extra='') => '<label class="field"><span>'+label+'</span><input type="'+type+'" name="'+name+'" value="'+esc(value)+'" '+extra+'></label>';
const textArea = (label,name,value='',extra='') => '<label class="field"><span>'+label+'</span><textarea name="'+name+'" '+extra+'>'+esc(value)+'</textarea></label>';
const button = (label,action,extra='',style='') => '<button type="button" class="button '+style+'" data-do="'+action+'" '+extra+'>'+label+'</button>';
const pageHead = (title,description,actions='') => '<div class="page-head"><div><div class="eyebrow">АСЫЛ ТАС / '+esc(pageNames[currentPage] || '')+'</div><h1>'+title+'</h1><p>'+description+'</p></div>'+actions+'</div>';
const empty = (title,text,action='',name='gem') => '<div class="empty"><span class="empty-icon">'+icon(name)+'</span><h2>'+title+'</h2><p>'+text+'</p>'+action+'</div>';
const badge = status => '<span class="badge '+esc(status)+'">'+esc(statusNames[status] || status)+'</span>';
const dateText = value => {
  if (!value) return '';
  const d = new Date(typeof value === 'number' && value < 1e12 ? value*1000 : value);
  return Number.isNaN(d.getTime()) ? String(value) : new Intl.DateTimeFormat('kk-KZ',{day:'numeric',month:'long',year:'numeric'}).format(d);
};
function toast(text) {
  clearTimeout(toastTimer); $('#toast').textContent=text; $('#toast').classList.add('visible');
  toastTimer=setTimeout(()=>$('#toast').classList.remove('visible'),3200);
}
function showModal(html,wide=false) {
  $('#dialog-content').innerHTML=html; dialog.classList.toggle('wide',wide);
  if (!dialog.open) dialog.showModal();
  dialog.scrollTop=0;
}
function closeModal(){dialog.close();delete dialog.dataset.stoneId;}
function formError(form,message) {
  let error=form.querySelector('.form-error');
  if (!error) {error=document.createElement('div');error.className='form-error';error.setAttribute('role','alert');form.prepend(error);}
  error.textContent=message;error.scrollIntoView({block:'nearest'});
}
function setMenu(open) {
  $('.sidebar').classList.toggle('open',open);$('#nav-overlay').hidden=!open;
  $('.sidebar').inert=mobileQuery.matches&&!open;
  document.body.classList.toggle('menu-open',open);
  $('#menu-toggle').setAttribute('aria-expanded',String(open));
}
async function refresh() {
  const response=await fetch('/api/state',{credentials:'same-origin',cache:'no-store'});
  const result=await response.json();
  if (!response.ok || !result.ok) throw new Error(result.error || 'Серверге қосылу мүмкін болмады.');
  if(state&&state.storage_scope!==result.storage_scope)clearGPS();
  state=result;$('#connection-error').hidden=true;render();
}
async function perform(action,values={},options={}) {
  const requestedScope=state.storage_scope;
  while(busy) {
    await mutationIdle;
    if(state.storage_scope!==requestedScope){if(!options.silent)toast('Аккаунт өзгерді. Әрекетті жаңа сеанста қайталаңыз.');return false;}
  }
  busy=true;
  let release;mutationIdle=new Promise(resolve=>{release=resolve;});
  const submit=options.form?.querySelector('[type=submit]');
  if (submit) submit.disabled=true;
  try {
    const response=await fetch('/api/action',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':state.csrf},body:JSON.stringify({action,...values})});
    const result=await response.json();
    if (!response.ok || !result.ok) throw new Error(result.error || 'Әрекет орындалмады. Қайта көріңіз.');
    if(state.storage_scope!==result.storage_scope)clearGPS();
    state=result;$('#connection-error').hidden=true;
    if (options.close) closeModal();
    if(options.render!==false)render();else renderNavigation();
    if (options.message) toast(options.message);
    return true;
  } catch(error) {
    if (options.form) formError(options.form,error.message);
    else if(!options.silent)toast(error.message);
    return false;
  } finally {
    busy=false;
    release();
    if (submit?.isConnected) submit.disabled=false;
  }
}
function requireUser() {
  if (state.user) return true;
  showAuth('login');return false;
}
function navigate(page) {
  if (location.hash==='#'+page) {currentPage=page;render();}
  else location.hash=page;
}
function renderNavigation() {
  $('#breadcrumb').textContent=pageNames[currentPage];
  document.body.dataset.page=currentPage;
  const primary=tabPages.includes(currentPage)?currentPage:'profile';
  document.querySelectorAll('nav a').forEach(a=>{
    const active=a.dataset.page===(a.closest('.bottom-nav')?primary:currentPage);
    a.classList.toggle('active',active);
    if (active) a.setAttribute('aria-current','page');else a.removeAttribute('aria-current');
  });
  $('#necklace-count').textContent=state.necklace.count;
  $('#catalog-count').textContent=state.catalog.length;
  $('#compare-count').textContent=(state.comparison||[]).length;
  $('#tab-necklace-count').textContent=state.necklace.count>99?'99+':state.necklace.count;
  $('#tab-necklace-count').hidden=state.necklace.count===0;
  $('#mobile-back').setAttribute('aria-label',primary==='profile'&&currentPage!=='profile'?'Профильге қайту':'Басты бетке қайту');
  const user=state.user;
  $('#account-control').innerHTML=user?'<a href="#profile" class="button secondary small" aria-label="Жеке кабинет"><span class="account-avatar">'+esc(user.name.charAt(0).toUpperCase())+'</span><span class="account-name">'+esc(user.name.split(' ')[0])+'</span></a>':button('<span class="account-avatar">'+icon('user')+'</span><span class="account-name">Кіру / тіркелу</span>','auth','aria-label="Кіру / тіркелу"','small');
  $('#sidebar-profile').innerHTML='<span class="avatar">'+esc(user?user.name.charAt(0).toUpperCase():'Қ')+'</span><span>'+esc(user?user.name:'Қонақ режимі')+'<small>'+(user?(user.admin?'Дүкен әкімшісі':'Жеке кабинет'):'Аккаунтқа кіру')+'</small></span>';
  document.title=pageNames[currentPage]+' · Асыл тас';
}
function render() {
  if (!state) return;
  currentPage=location.hash.slice(1) || 'home';
  if (!pageNames[currentPage]) currentPage='home';
  renderNavigation();
  mediaUrls.forEach(URL.revokeObjectURL);mediaUrls=[];
  const views={home:homeView,catalog:catalogView,favorites:catalogView,necklace:necklaceView,saved:savedView,orders:ordersView,profile:profileView,location:locationView,compare:compareView,recent:recentView,notes:notesView,messages:messagesView,media:mediaView,guide:guideView,credits:creditsView};
  main.innerHTML=views[currentPage]();
  if (currentPage==='catalog' || currentPage==='favorites') renderProducts();
  if (currentPage==='credits') loadCredits();
  if (currentPage==='media') loadMedia();
  if (currentPage==='location') {if(campusPlaces)renderLocationStatus();else if(!campusError)loadCampus();}
}
function product(s) {
  const fav=state.favorites.includes(s.id),compared=(state.comparison||[]).includes(s.id);
  return '<article class="product"><div class="product-image"><button class="photo-open" data-do="detail" data-id="'+s.id+'" aria-label="'+esc(s.name)+' туралы">'+photo(s)+'</button><button class="icon-button favorite '+(fav?'selected':'')+'" data-do="favorite" data-id="'+s.id+'" aria-label="'+esc(s.name)+(fav?' — таңдаулыдан алу':' — таңдаулыға қосу')+'" aria-pressed="'+fav+'">'+icon('heart')+'</button></div><div class="product-body"><div class="product-category">'+esc(s.category)+'</div><button class="product-title" data-do="detail" data-id="'+s.id+'">'+esc(s.name)+'</button><p class="product-meta">'+weight(s.carats)+' карат · '+s.transparency+'% мөлдірлік</p><div class="product-bottom"><span class="price">'+money(s.cost)+'</span><button class="add-mini" data-do="add" data-id="'+s.id+'" aria-label="'+esc(s.name)+' тасын алқаға қосу">'+icon('plus')+'</button></div><button class="compare-card '+(compared?'selected':'')+'" type="button" data-do="compare-stone" data-id="'+s.id+'" aria-pressed="'+compared+'">'+icon(compared?'check':'tune')+(compared?'Салыстыруда':'Салыстыру')+'</button></div></article>';
}
function homeView() {
  const n=state.necklace;
  const welcome=state.user?esc(state.user.name.split(' ')[0])+', қош келдіңіз':'Жеке коллекцияңыз осы жерден басталады';
  return mobileHome()+pageHead('Тастар әлеміне қош келдіңіз',welcome)+
    '<section class="hero"><div class="hero-copy"><div class="eyebrow">ТАЛҒАМЫҢЫЗДЫ ТОЛЫҚТЫРЫҢЫЗ</div><h2>Әр тастың<br>өз сұлулығы бар.</h2><p>Табиғи тастарды танып, өз алқаңызды жинаңыз. Салмағы мен құнын бірден көріңіз.</p><a href="#catalog" class="button light">'+icon('gem')+'Каталогты қарау</a></div><div class="hero-art"><span class="hero-tag">КОЛЛЕКЦИЯДАН</span>'+photo(stone(3))+'<div class="hero-stone-label"><div><strong>Зүбәржат</strong><span>1.10 карат · Асыл тас</span></div><span>165 000 ₸</span></div></div></section>'+
    '<div class="summary-strip"><div class="stat"><div class="stat-icon">'+icon('necklace')+'</div><div><span class="label">Менің алқам</span><strong>'+n.count+' тас</strong><small>'+weight(n.carats)+' карат</small></div><a href="#necklace" class="text-button">Ашу</a></div><div class="stat"><div class="stat-icon">'+icon('gem')+'</div><div><span class="label">Алқаның құны</span><strong>'+money(n.total)+'</strong><small>Таңдалған тастар бойынша</small></div></div><div class="stat"><div class="stat-icon">'+icon('bookmark')+'</div><div><span class="label">Сақталған жинақтар</span><strong>'+state.collections.length+' жинақ</strong><small>Өзіңіз таңдаған үйлесімдер</small></div></div></div>'+
    '<div class="section-head"><div><h2>Тастар топтамасы</h2><p>'+state.catalog.length+' тас түрі. Әрқайсысының түсі мен сипаты бөлек.</p></div><a href="#catalog" class="text-button">Барлығын көру</a></div><div class="product-grid home-products">'+[1,2,3,4].map(id=>product(stone(id))).join('')+'</div>'+
    '<div class="section-head"><h2>Алғашқы алқаңызды жинаңыз</h2></div><div class="panel"><div class="card-head"><div><h3>Үлгі жинақ</h3><p class="small-note">Лағыл, зүбәржат және аметист: 6 тас · 5.90 карат · 616 200 ₸.</p></div>'+button('Үлгіні ашу','demo','','soft')+'</div><p class="small-note">Үлгіні өзгертіп, тастардың өзіңізге ұнайтын үйлесімін сақтай аласыз.</p></div>';
}
function mobileHome() {
  const n=state.necklace;
  return '<div class="mobile-home"><div class="app-greeting"><p>'+esc(state.user?state.user.name.split(' ')[0]+', қош келдіңіз':'Қош келдіңіз')+'</p><h1>Өзіңізге жарасатын асыл тас</h1></div><a class="app-search" href="#catalog">'+icon('search')+'<span>Тастарды іздеу</span></a><section class="app-collection"><div class="app-collection-copy"><p>Менің алқам</p><h2>'+money(n.total)+'</h2><span>'+n.count+' тас <b>·</b> '+weight(n.carats)+' карат</span><a class="button light" href="#'+(n.count?'necklace':'catalog')+'">'+(n.count?'Алқаны ашу':'Алқа жинау')+'</a></div><div class="app-collection-photo">'+photo(stone(3))+'</div></section><div class="app-shortcuts">'+[['favorites','heart','Таңдаулылар'],['saved','bookmark','Жинақтар'],['location','pin','GPS · ЕҰУ'],['media','media','Медиа']].map(([route,i,label])=>'<a href="#'+route+'"><span>'+icon(i)+'</span><small>'+label+'</small></a>').join('')+'</div><button class="install-card" type="button" data-do="install"><span class="install-mark">'+icon('gem')+'</span><span><strong>Асыл тас әрдайым жаныңызда</strong><small>Басты экранға қосу</small></span>'+icon('plus')+'</button></div>';
}
function profileMenu() {
  return '<section class="app-profile-menu"><h2>Менің бөлімдерім</h2><div class="profile-links">'+[['saved','bookmark','Жинақтар'],['favorites','heart','Таңдаулы тастар'],['messages','message','Хабарламалар'],['compare','tune','Тастарды салыстыру'],['recent','search','Соңғы қаралған'],['notes','bookmark','Жеке жазбалар'],['location','pin','ЕҰУ маңы · GPS'],['media','media','Медиа кітапхана'],['guide','info','Қолдану нұсқаулығы'],['credits','gem','Фото авторлары']].map(([route,i,label])=>'<a href="#'+route+'"><span class="profile-link-icon">'+icon(i)+'</span><span>'+label+'</span>'+icon('chevron')+'</a>').join('')+'<button type="button" class="profile-install" data-do="install"><span class="profile-link-icon">'+icon('plus')+'</span><span>Басты экранға қосу</span>'+icon('chevron')+'</button></div></section>';
}
async function showInstall() {
  if(window.AsylInstall?.isStandalone){toast('Асыл тас қолданба режимінде ашық.');return;}
  if(window.AsylInstall?.canPrompt){const result=await window.AsylInstall.promptInstall();if(result==='accepted'){toast('Асыл тас басты экранға қосылды');return;}if(result==='dismissed')return;}
  const ios=window.AsylInstall?.isIOS||/iPhone|iPad|iPod/.test(navigator.userAgent);
  showModal('<div class="install-dialog-mark">'+icon('gem')+'</div><h2 id="dialog-title">Асыл тас басты экраныңызда</h2><p class="dialog-sub">Белгішеден ашқанда қолданба жеке терезеде жұмыс істейді.</p><ol class="install-steps">'+(ios?'<li><span>1</span><p>Осы сілтемені <strong>Safari</strong> арқылы ашыңыз.</p></li><li><span>2</span><p>'+icon('share')+' <strong>Бөлісу / Поделиться</strong> мәзірін ашыңыз.</p></li><li><span>3</span><p><strong>Басты экранға / На экран «Домой»</strong> тармағын таңдаңыз.</p></li><li><span>4</span><p><strong>Веб-қолданба ретінде ашу</strong> тетігі көрсетілсе, оны қосып, <strong>Қосу / Добавить</strong> басыңыз.</p></li>':'<li><span>1</span><p>Браузер мәзірін ашыңыз.</p></li><li><span>2</span><p><strong>Қолданбаны орнату</strong> немесе <strong>Басты экранға қосу</strong> тармағын таңдаңыз.</p></li>')+'</ol><p class="small-note">Аккаунтқа кіру және өзгерістерді сақтау үшін интернет қажет.</p><button type="button" class="button block section-space" data-do="close-modal">Түсінікті</button>');
}
function catalogView() {
  const fav=currentPage==='favorites';
  const compared=(state.comparison||[]).length;
  return pageHead(fav?'Сіз таңдаған тастар':'Тастар каталогы',fav?'Ұнаған тастарыңызды бір жерден табыңыз.':state.catalog.length+' тас түрі · атау, бюджет, салмақ және мөлдірлік бойынша таңдау.',
    '<a class="button secondary small" href="#compare">'+icon('tune')+'Салыстыру'+(compared?' ('+compared+')':'')+'</a>')+
    '<div class="catalog-tools" data-expanded="'+advancedFiltersOpen+'"><div class="search-box">'+icon('search')+'<input id="catalog-search" type="search" maxlength="100" placeholder="Қазақша, орысша немесе ағылшынша іздеу" aria-label="Тастың атауын іздеу" value="'+esc(filters.query)+'"></div>'+button(icon('tune'),'filters','aria-label="Сұрыптау мен сүзгілер" aria-controls="catalog-options" aria-expanded="'+advancedFiltersOpen+'"','filter-toggle secondary')+
    '<div class="filter-chips">'+[['all','Барлығы'],['Асыл тас','Асыл'],['Жартылай асыл тас','Жартылай асыл']].map(([v,l])=>'<button class="chip '+(filters.category===v?'active':'')+'" data-do="category" data-value="'+v+'" aria-pressed="'+(filters.category===v)+'">'+l+'</button>').join('')+'</div><div class="catalog-options" id="catalog-options"><label class="catalog-sort-label">Сұрыптау<select id="catalog-sort" aria-label="Тастарды сұрыптау">'+[['default','Каталог реті'],['asc','Карат бағасы: өсуі'],['desc','Карат бағасы: кемуі'],['totalAsc','Тас құны: өсуі'],['totalDesc','Тас құны: кемуі'],['weightAsc','Салмақ: өсуі']].map(([v,l])=>'<option value="'+v+'" '+(v===filters.sort?'selected':'')+'>'+l+'</option>').join('')+'</select></label>'+
    '<form id="range-form" class="catalog-range-grid"><div class="filter-pair"><span>Мөлдірлік, %</span><div><label><span class="sr-only">Ең төмен мөлдірлік</span><input name="min" type="number" inputmode="numeric" min="0" max="100" value="'+filters.min+'" required></label><span>–</span><label><span class="sr-only">Ең жоғары мөлдірлік</span><input name="max" type="number" inputmode="numeric" min="0" max="100" value="'+filters.max+'" required></label></div></div><div class="filter-pair"><span>Салмақ, карат</span><div><label><span class="sr-only">Ең аз салмақ</span><input name="minCarats" type="number" inputmode="decimal" min="0" step="0.01" placeholder="Ең аз" value="'+esc(filters.minCarats)+'"></label><span>–</span><label><span class="sr-only">Ең көп салмақ</span><input name="maxCarats" type="number" inputmode="decimal" min="0" step="0.01" placeholder="Ең көп" value="'+esc(filters.maxCarats)+'"></label></div></div>'+field('Бір тасқа бюджет, ₸','budget',filters.budget,'number','min="0" step="0.01" inputmode="decimal" placeholder="Шектеусіз"')+'<div class="filter-actions"><button class="button secondary small" type="submit">Қолдану</button><button type="button" class="text-button" data-do="reset-filters">Тазарту</button></div><div class="form-error" role="alert"></div></form></div></div><p class="results-info" id="results-info" aria-live="polite"></p><div class="product-grid" id="products"></div>';
}
function renderProducts() {
  const fav=currentPage==='favorites';
  const rows=discovery.filterCatalog(state.catalog,filters,fav?state.favorites:null);
  $('#results-info').textContent=rows.length+' / '+state.catalog.length+' тас түрі · бағалар бір тас үшін';
  const noFavorites=fav&&!state.favorites.length;
  $('#products').innerHTML=rows.length?rows.map(product).join(''):empty(noFavorites?'Әзірге таңдаулы тас жоқ':'Сүзгіге сай тас табылмады',noFavorites?'Каталогтағы жүрек белгісін басып, ұнаған тастарыңызды сақтаңыз.':'Бюджет, салмақ немесе мөлдірлік шектерін кеңейтіп көріңіз.',noFavorites&&!state.user?button('Аккаунтқа кіру','auth'):button('Сүзгіні тазарту','reset-filters','','secondary'),noFavorites?'heart':'search');
}
function compareView() {
  const selected=(state.comparison||[]).map(stone).filter(Boolean);
  const heading=pageHead('Тастарды салыстыру','Бір уақытта үш тасқа дейін. Таңдауыңыз осы '+(state.user?'аккаунтта':'қонақ сеансында')+' сақталады.',
    '<div class="button-row"><a class="button secondary small" href="#catalog">Тас таңдау</a>'+(selected.length?button('Тазарту','comparison-clear','','secondary small'):'')+'</div>');
  if(!selected.length)return heading+empty('Салыстыратын тас таңдаңыз','Каталогтағы «Салыстыру» батырмасы арқылы баға, салмақ және мөлдірлікті қатар қараңыз.','<a class="button" href="#catalog">Каталогты ашу</a>','tune');
  const rows=[['Санаты',s=>esc(s.category)],['Бір тас құны',s=>'<strong>'+money(s.cost)+'</strong>'],['Салмағы',s=>weight(s.carats)+' карат'],['Карат бағасы',s=>money(s.price_per_carat)],['Мөлдірлігі',s=>s.transparency+'%'],['Тас туралы',s=>'<span class="care-cell">'+esc(s.description||'')+'</span>'],['Күтімі',s=>'<span class="care-cell">'+esc(s.care||'')+'</span>'],['Алқаға қосу',s=>button('Қосу','add','data-id="'+s.id+'"','small')]];
  return heading+'<p class="small-note comparison-intro">Баға, салмақ және мөлдірлік — оқу деректері. Телефонда кестені көлденең сырғытуға болады.</p><div class="comparison-scroll" tabindex="0" role="region" aria-label="Тастарды салыстыру кестесі"><table class="comparison-table"><thead><tr><th scope="col">Қасиет</th>'+selected.map(s=>'<th scope="col"><button class="photo-open compare-photo" data-do="detail" data-id="'+s.id+'" aria-label="'+esc(s.name)+' туралы">'+photo(s)+'</button><button class="product-title" data-do="detail" data-id="'+s.id+'">'+esc(s.name)+'</button><br><button class="text-button compare-remove" data-do="compare-stone" data-id="'+s.id+'" aria-label="'+esc(s.name)+' салыстырудан алу">Алып тастау</button></th>').join('')+'</tr></thead><tbody>'+rows.map(([label,value])=>'<tr><th scope="row">'+label+'</th>'+selected.map(s=>'<td>'+value(s)+'</td>').join('')+'</tr>').join('')+'</tbody></table></div>';
}
function recentView() {
  const rows=(state.recent||[]).filter(r=>stone(r.stone_id));
  return pageHead('Соңғы қаралған тастар','Соңғы 12 тас. '+(state.user?'Тарих осы аккаунтта сақталады.':'Тарих осы қонақ сеансына ғана тиесілі.'),rows.length?button('Тарихты тазарту','history-clear','','secondary small'):'')+
    (rows.length?'<div class="product-grid">'+rows.map(r=>'<div><p class="recent-stamp">'+esc(dateText(r.viewed))+'</p>'+product(stone(r.stone_id))+'</div>').join('')+'</div>':empty('Әзірге қаралған тас жоқ','Тастың фотосын немесе атауын бассаңыз, ол осы тізімге қосылады.','<a class="button" href="#catalog">Каталогты ашу</a>','search'));
}
function notesView() {
  const rows=(state.notes||[]).filter(r=>stone(r.stone_id));
  return pageHead('Менің жеке жазбаларым',state.user?'Жазбалар тек сіздің аккаунтыңызға көрінеді.':'Жазбалар осы қонақ сеансында сақталады. Тіркелгенде оларды аккаунтқа көшіруге болады.')+
    (rows.length?'<div class="private-notes">'+rows.map(r=>{const s=stone(r.stone_id);return '<article class="panel private-note"><div class="card-head">'+photo(s)+'<div><h2>'+esc(s.name)+'</h2><small>'+esc(dateText(r.updated))+'</small></div></div><p class="note-text">'+esc(r.text)+'</p><div class="button-row">'+button('Өңдеу','detail','data-id="'+s.id+'"','secondary small')+button('Жою','delete-note','data-id="'+s.id+'"','secondary small')+'</div></article>';}).join('')+'</div>':empty('Жазба қосыңыз','Кез келген тасты ашып, «Менің жеке жазбам» өрісіне таңдауыңыздың себебін немесе еске салуды жазыңыз.','<a class="button" href="#catalog">Тас таңдау</a>','bookmark'));
}
function openStone(id) {
  if(!stone(id))return;
  showStone(id);
  const scope=state.storage_scope;
  detailViewQueue=detailViewQueue.catch(()=>{}).then(async()=>{
    while(busy)await mutationIdle;
    if(state.storage_scope!==scope)return;
    const saved=await perform('stone_view',{stone_id:id},{render:false,silent:true});
    if(saved&&currentPage==='recent')render();
  });
}
function chain(necklace) {
  const previewId='chain'+(++chainSequence);
  const ids=[];
  (necklace?.items || []).forEach(item=>{for(let j=0;j<item.quantity&&ids.length<24;j++) ids.push(item.stone_id);});
  if (!ids.length) return '<div class="chain-preview" style="display:grid;place-items:center;color:#a3b09f">'+icon('necklace')+'</div>';
  let svg='<svg class="chain-preview" viewBox="0 0 320 195" role="img" aria-label="Таңдалған тастардың алқадағы көрінісі"><path d="M55 15 C55 195 265 195 265 15" fill="none" stroke="#b89d6d" stroke-width="2"/>';
  ids.forEach((id,i)=>{
    const t=.2+.6*(ids.length===1?.5:i/(ids.length-1)),u=1-t;
    const x=u*u*u*55+3*u*u*t*55+3*u*t*t*265+t*t*t*265;
    const y=u*u*u*15+3*u*u*t*195+3*u*t*t*195+t*t*t*15;
    svg+='<circle cx="'+x+'" cy="'+y+'" r="13" fill="white" stroke="#b89d6d"/><clipPath id="clip-'+previewId+'-'+i+'"><circle cx="'+x+'" cy="'+y+'" r="11"/></clipPath><image href="'+esc(stone(id).photo)+'" x="'+(x-11)+'" y="'+(y-11)+'" width="22" height="22" preserveAspectRatio="xMidYMid slice" clip-path="url(#clip-'+previewId+'-'+i+')"/>';
  });
  return svg+'</svg>';
}
function necklaceView() {
  const n=state.necklace;
  const actions='<div class="button-row">'+button('Үлгі алқа','demo','','secondary')+'<a class="button" href="#catalog">'+icon('plus')+'Тас қосу</a></div>';
  let result=pageHead('Менің алқам','Тастарды үйлестіріңіз. Саны өзгерсе, есеп бірден жаңарады.',actions);
  if (!n.items.length) return result+empty('Алқаңыз әзірге бос','Каталогтан ұнаған тасты қосыңыз немесе дайын үлгіден бастаңыз.','<a class="button" href="#catalog">Тас таңдау</a>','necklace');
  result+='<div class="two-columns"><div><div class="necklace-list">'+n.items.map(item=>{
    const s=stone(item.stone_id);
    return '<div class="necklace-row">'+photo(s)+'<div><h3>'+esc(s.name)+'</h3><p class="meta">'+weight(s.carats)+' карат / тас · '+s.transparency+'% мөлдірлік</p><div class="quantity"><button data-do="quantity" data-id="'+s.id+'" data-value="'+(item.quantity-1)+'" aria-label="'+esc(s.name)+' санын азайту">−</button><span>'+item.quantity+'</span><button data-do="quantity" data-id="'+s.id+'" data-value="'+(item.quantity+1)+'" '+(item.quantity>=99?'disabled':'')+' aria-label="'+esc(s.name)+' санын көбейту">+</button></div></div><div class="row-total"><strong class="price">'+money(item.total)+'</strong><button class="icon-button" data-do="remove" data-id="'+s.id+'" aria-label="'+esc(s.name)+' тасын алып тастау">'+icon('trash')+'</button></div></div>';
  }).join('')+'</div><div class="button-row section-space">'+button(icon('bookmark')+'Жинаққа сақтау','save','','secondary')+button('Алқаны тазарту','clear','','danger')+'</div></div><aside class="panel order-summary">'+chain(n)+'<div class="summary-line"><span>Тастар саны</span><strong>'+n.count+' дана</strong></div><div class="summary-line"><span>Жалпы салмақ</span><strong>'+weight(n.carats)+' карат</strong></div><hr class="rule"><p class="small-note">Алқаның жалпы құны</p><p class="total">'+money(n.total)+'</p>'+button('Өтінім рәсімдеу','checkout','','block')+'<p class="small-note section-space">Өтінімді сақтағаннан кейін оның күйін жеке кабинеттен қарай аласыз.</p></aside></div>';
  return result;
}
function savedView() {
  return pageHead('Менің жинақтарым','Өзіңіз жасаған алқаларды сақтап, кейін қайта ашыңыз.','<a class="button" href="#necklace">Алқаны ашу</a>')+
    (state.collections.length?'<div class="saved-grid">'+state.collections.map(c=>'<article class="panel saved-card">'+chain(c.necklace)+'<h3>'+esc(c.name)+'</h3><p class="meta">'+c.necklace.count+' тас · '+weight(c.necklace.carats)+' карат</p><span class="price">'+money(c.necklace.total)+'</span><div class="button-row">'+button('Алқаға жүктеу','load-saved','data-id="'+c.id+'"','small')+button(icon('trash'),'delete-saved','data-id="'+c.id+'" aria-label="'+esc(c.name)+' жинағын жою"','secondary small')+'</div></article>').join('')+'</div>':empty('Сақталған жинақ жоқ','Алқаңызды құрастырып, «Жинаққа сақтау» батырмасын басыңыз.','<a href="#necklace" class="button">Алқаны ашу</a>','bookmark'));
}
function ordersView() {
  let result=pageHead(state.user?.admin?'Дүкен өтінімдері':'Менің өтінімдерім',state.user?.admin?'Осы сайтта сақталған өтінімдерді қарап, дайындау күйін өзгертіңіз.':'Алқаңызға қатысты мәліметтер мен өтінім күйі.');
  if (!state.user) return result+empty('Өтінімдер жеке кабинетте сақталады','Өтінім рәсімдеу үшін аккаунтыңызға кіріңіз.',button('Кіру / тіркелу','auth'),'order');
  if (!state.orders.length) return result+empty('Әзірге өтінім жоқ','Алқаңызды жинап, «Өтінім рәсімдеу» батырмасын басыңыз.','<a class="button" href="#necklace">Алқаны ашу</a>','order');
  return result+'<div class="two-equal">'+state.orders.map(order=>'<article class="panel order-card"><div class="card-head"><div><h3>Өтінім №'+order.id+'</h3><small>'+esc(dateText(order.created))+'</small></div>'+badge(order.status)+'</div><div class="photos">'+order.items.slice(0,6).map(item=>photo(stone(item.stone_id))).join('')+'</div><p class="small-note">'+order.count+' тас · '+weight(order.carats)+' карат · '+(order.delivery==='delivery'?'Жеткізу':'Өзі алып кету')+'</p>'+(state.user.admin?'<p class="small-note">'+esc(order.name || order.email || '')+'</p>':'')+'<div class="order-bottom"><strong class="price">'+money(order.total)+'</strong>'+button('Мәліметтер','order','data-id="'+order.id+'"','secondary small')+'</div></article>').join('')+'</div>';
}
function profileView() {
  if (!state.user) return pageHead('Жеке кабинет','Коллекцияңыз бен өтінімдеріңіз өз аккаунтыңызда сақталады.')+
    '<div class="two-equal guest-profile"><div class="panel"><span class="stat-icon">'+icon('user')+'</span><h2 class="section-space">Қош келдіңіз</h2><p class="muted">Аккаунт ашып, таңдаулы тастарды белгілеңіз, өтінім рәсімдеңіз және пікір қалдырыңыз.</p><div class="button-row section-space">'+button('Аккаунтқа кіру','auth')+button('Тіркелу','register','','secondary')+'</div></div><div class="panel guest-explainer"><h2>Қонақ ретінде де бастай аласыз</h2><p class="muted">Каталогты қарап, алқа жинаңыз. Тіркелген кезде алқаңызды жаңа аккаунтқа көшіруге болады.</p><a href="#catalog" class="button soft section-space">Каталогқа өту</a></div></div>'+profileMenu();
  const u=state.user;
  let html=pageHead('Жеке кабинет','Профиль деректері мен аккаунт қауіпсіздігі.',button('Аккаунттан шығу','logout','','secondary'))+
    '<div class="two-equal"><section class="panel"><div class="profile-head"><span class="avatar">'+esc(u.name.charAt(0).toUpperCase())+'</span><div><h2>'+esc(u.name)+'</h2><p>'+esc(u.email)+'</p></div></div>'+(u.admin?'<p class="badge">Дүкен әкімшісі</p>':'')+'<form data-form="profile" class="section-space">'+field('Аты-жөніңіз','name',u.name,'text','required maxlength="80" autocomplete="name"')+field('Телефон','phone',u.phone,'tel','maxlength="30" autocomplete="tel"')+field('Жеткізу мекенжайы','address',u.address,'text','maxlength="240" autocomplete="street-address"')+'<div class="form-error" role="alert"></div><button class="button" type="submit">Өзгерістерді сақтау</button></form></section><section class="panel"><h2>Құпиясөзді өзгерту</h2><form data-form="password">'+field('Қазіргі құпиясөз','current_password','','password','required autocomplete="current-password"')+field('Жаңа құпиясөз','new_password','','password','required minlength="8" maxlength="128" autocomplete="new-password"')+field('Жаңа құпиясөзді қайталау','confirm','','password','required minlength="8" maxlength="128" autocomplete="new-password"')+'<p class="small-note">Кемінде 8 таңба қолданыңыз.</p><div class="form-error" role="alert"></div><button type="submit" class="button secondary section-space">Құпиясөзді жаңарту</button></form></section></div>';
  if(state.admin_claim_available&&!u.admin) html+='<section class="panel section-space"><h2>Дүкенді басқару</h2><p class="small-note">Сайт иесі ретінде расталдыңыз. Осы аккаунтқа әкімші құқығын қосуға болады.</p><div class="section-space">'+button('Әкімші құқығын қосу','claim-admin')+'</div></section>';
  if (u.admin) html+='<section class="panel section-space"><div class="card-head"><div><h2>Дүкенді басқару</h2><p class="small-note">'+state.users.length+' аккаунт · '+state.orders.length+' өтінім</p></div><a class="button secondary small" href="#orders">Өтінімдерге өту</a></div><div class="table-scroll"><table class="admin-table"><thead><tr><th>Аты-жөні</th><th>Email</th><th>Рөлі</th></tr></thead><tbody>'+state.users.map(x=>'<tr><td>'+esc(x.name)+'</td><td>'+esc(x.email)+'</td><td>'+(x.admin?'Әкімші':'Клиент')+'</td></tr>').join('')+'</tbody></table></div></section>';
  return html+profileMenu();
}
function locationView() {
  let html=pageHead('ЕҰУ маңында кездесейік','Кездесу орнын таңдаңыз, өз орналасуыңызды анықтап, картадан маршрут ашыңыз.');
  if(!campusPlaces) return html+'<section class="panel"><p class="'+(campusError?'form-error':'muted')+'">'+esc(campusError||'Кездесу орындары жүктелуде…')+'</p>'+(campusError?button('Қайта жүктеу','campus-retry','','secondary'):'')+'</section>';
  const place=campusPlaces.find(x=>x.id===selectedPlace)||campusPlaces[0];
  selectedPlace=place.id;
  const bbox=[place.lon-.004,place.lat-.003,place.lon+.004,place.lat+.003].join(',');
  const embed='https://www.openstreetmap.org/export/embed.html?bbox='+encodeURIComponent(bbox)+'&layer=mapnik&marker='+encodeURIComponent(place.lat+','+place.lon);
  html+='<div class="campus-grid"><section class="panel campus-main"><label class="field"><span>Кездесу орны</span><select id="campus-place">'+campusPlaces.map(p=>'<option value="'+esc(p.id)+'" '+(p.id===place.id?'selected':'')+'>'+esc(p.name)+'</option>').join('')+'</select></label><h2>'+esc(place.name)+'</h2><p class="campus-address">'+esc(place.address)+'</p><p class="small-note">'+esc(place.description)+'</p><div class="campus-map"><iframe title="'+esc(place.name)+' картада" src="'+esc(embed)+'" loading="lazy" referrerpolicy="no-referrer"></iframe></div><p class="map-attribution">Карта: © <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">OpenStreetMap қатысушылары</a>. Белгі ғимаратты көрсетеді; кіреберісті картадан нақтылаңыз.</p><div class="button-row"><a class="button secondary small" href="'+esc(discovery.mapUrl(place))+'" target="_blank" rel="noopener noreferrer">Картада ашу</a>'+button('Мекенжайды көшіру','copy-place','','secondary small')+button(icon('share')+'Бөлісу','share-place','','secondary small')+'</div><p class="small-note section-space">'+(/^https:\/\//.test(place.source)?'<a href="'+esc(place.source)+'" target="_blank" rel="noopener noreferrer">Мекенжай дереккөзі</a>':esc(place.source))+' · <a href="'+esc(place.coordinate_source)+'" target="_blank" rel="noopener noreferrer">Картадағы ғимарат</a></p></section><section class="panel gps-panel"><span class="stat-icon">'+icon('pin')+'</span><h2 class="section-space">Менің орналасуым</h2><p class="small-note">GPS тек осы батырманы басқанда сұралады. Координаталар серверге сақталмайды.</p><div id="gps-status" class="gps-status" role="status" aria-live="polite"></div><div class="gps-actions">'+button('Орналасуымды анықтау','gps-start','id="gps-start"')+button('Орналасуды өшіру','gps-clear','id="gps-clear"','secondary')+'</div><hr class="rule"><label class="field"><span>Қалай барасыз?</span><select id="travel-mode">'+[['walking','Жаяу'],['driving','Көлікпен'],['transit','Қоғамдық көлікпен']].map(([v,l])=>'<option value="'+v+'" '+(v===travelMode?'selected':'')+'>'+l+'</option>').join('')+'</select></label><a id="campus-route" class="button block" href="'+esc(discovery.routeUrl(place,gps.position,travelMode))+'" target="_blank" rel="noopener noreferrer">Маршрутты картада ашу</a><p class="small-note section-space">Нақты жол, уақыт және қоғамдық көлік деректерін карта қызметі көрсетеді. Маршрутты ашқанда таңдалған орын және рұқсат етілген өз координатаңыз сол қызметке беріледі.</p></section></div>';
  const s=state.store;
  html+='<details class="panel section-space store-details"><summary>Жоба байланысы</summary><p class="small-note section-space">Кездесу орындары — оқу жобасының бағдарлары; университетте нақты дүкен бар дегенді білдірмейді.</p><dl class="contact-list section-space"><div><dt>Байланыс мекенжайы</dt><dd>'+esc(s.address)+(s.example?' · үлгі мекенжай':'')+'</dd></div><div><dt>Телефон</dt><dd>'+ (s.phone?'<a href="tel:'+esc(s.phone.replace(/[^+0-9]/g,''))+'">'+esc(s.phone)+'</a>':'Көрсетілмеген')+'</dd></div><div><dt>Жұмыс уақыты</dt><dd>'+esc(s.hours||'Көрсетілмеген')+'</dd></div></dl><div class="button-row"><a href="#messages" class="button secondary">Хабарлама жазу</a>'+(state.user?.admin?button('Деректерді өзгерту','edit-store','','soft'):'')+'</div></details>';
  return html;
}
async function loadCampus() {
  if(campusLoading)return;
  campusLoading=true;campusError='';
  try {
    const response=await fetch('/assets/campus_v4.json',{cache:'no-cache'});
    if(!response.ok)throw new Error('Мекенжайлар жүктелмеді. Интернетті тексеріп, қайта көріңіз.');
    const places=await response.json();
    if(!Array.isArray(places)||!places.length||places.length>20||places.some(p=>!p||typeof p.id!=='string'||typeof p.name!=='string'||typeof p.address!=='string'||!discovery.validCoordinates(p.lat,p.lon)))throw new Error('Мекенжай деректерін қазір ашу мүмкін болмады.');
    campusPlaces=places;
  }catch(error){campusError=error.message;}
  finally{campusLoading=false;if(currentPage==='location')render();}
}
function currentPlace(){return campusPlaces?.find(p=>p.id===selectedPlace)||campusPlaces?.[0];}
function renderLocationStatus() {
  const box=$('#gps-status'),place=currentPlace();if(!box||!place)return;
  if(gps.position&&!discovery.freshPosition(gps.position)){gps={status:'error',position:null,message:'Орналасу дерегі ескірді. Қашықтықты жаңарту үшін қайта анықтаңыз.'};}
  box.dataset.status=gps.status;
  if(gps.status==='pending')box.textContent='Құрылғыдан орналасу сұралуда… Браузер рұқсат сұраса, өз таңдауыңызды жасаңыз.';
  else if(gps.position){
    const distance=discovery.distanceMeters(gps.position,place),label=distance<1000?Math.round(distance)+' м':new Intl.NumberFormat('kk-KZ',{maximumFractionDigits:2}).format(distance/1000)+' км';
    box.innerHTML='<strong>Шамамен '+label+'</strong><span>'+esc(place.name)+' дейінгі түзу сызық бойымен қашықтық. Бұл — жаяу жүретін жол ұзындығы емес.</span><small>Құрылғы дәлдігі: ±'+Math.ceil(gps.position.accuracy)+' м. Координаталар екі минуттан кейін өшіріледі.</small>';
  }else box.textContent=gps.message||'Орналасу анықталмаған. Мекенжайды картада GPS рұқсатынсыз да аша аласыз.';
  $('#gps-start').disabled=gps.status==='pending';
  $('#gps-start').textContent=gps.position?'Орналасуды жаңарту':'Орналасуымды анықтау';
  $('#gps-clear').hidden=gps.status==='idle';
  $('#gps-clear').textContent=gps.status==='pending'?'Сұрауды тоқтату':'Орналасуды өшіру';
  $('#campus-route').href=discovery.routeUrl(place,gps.position,travelMode);
}
function clearGPS() {
  gpsRequest++;clearTimeout(gpsExpiryTimer);gps={status:'idle',position:null,message:''};renderLocationStatus();
}
function startGPS() {
  const request=++gpsRequest;clearTimeout(gpsExpiryTimer);
  if(!window.isSecureContext||!navigator.geolocation){gps={status:'error',position:null,message:'Бұл браузерде орналасу қолжетімсіз. HTTPS арқылы ашыңыз немесе мекенжайды картадан қараңыз.'};renderLocationStatus();return;}
  gps={status:'pending',position:null,message:''};renderLocationStatus();
  const failed=error=>{if(request!==gpsRequest)return;gps={status:'error',position:null,message:typeof error==='string'?error:discovery.geolocationError(error)};renderLocationStatus();};
  try {
    navigator.geolocation.getCurrentPosition(position=>{
      if(request!==gpsRequest)return;
      try {gps={status:'success',position:discovery.fromPosition(position),message:''};}
      catch(error){failed(error.message);return;}
      renderLocationStatus();
      gpsExpiryTimer=setTimeout(()=>{if(request!==gpsRequest)return;gps={status:'error',position:null,message:'Орналасу дерегі ескірді. Қашықтықты жаңарту үшін қайта анықтаңыз.'};renderLocationStatus();},Math.max(0,120000-(Date.now()-gps.position.timestamp)));
    },failed,{enableHighAccuracy:true,timeout:15000,maximumAge:0});
  }catch(error){failed(error);}
}
async function copyPlace() {
  const place=currentPlace();if(!place)return;
  try {await navigator.clipboard.writeText(place.address);toast('Мекенжай көшірілді');}
  catch {showModal('<h2 id="dialog-title">Мекенжайды көшіру</h2><p class="dialog-sub">Браузер автоматты көшіруге рұқсат бермеді. Төмендегі мәтінді белгілеп көшіріңіз.</p>'+textArea('Мекенжай','copy_address',place.address,'readonly'));const input=dialog.querySelector('textarea');input.focus();input.select();}
}
async function sharePlace() {
  const place=currentPlace();if(!place)return;
  if(!navigator.share)return copyPlace();
  try {await navigator.share({title:place.name,text:place.address,url:discovery.mapUrl(place)});}
  catch(error){if(error.name!=='AbortError')await copyPlace();}
}
function messagesView() {
  let html=pageHead('Хабарламалар','Өтінім немесе жинақ туралы сұрағыңызды сақтаңыз.');
  if (!state.user) return html+empty('Хабарлама жіберу үшін кіріңіз','Хабарламалар мен жауаптар жеке кабинетте көрінеді.',button('Аккаунтқа кіру','auth'),'message');
  html+='<div class="two-columns"><div class="stack">';
  html+=state.messages.length?state.messages.map(m=>'<article class="panel"><div class="card-head"><div><h3>'+esc(m.subject)+'</h3><small>'+esc(m.name || '')+' · '+esc(dateText(m.created))+'</small></div></div><p class="message-text">'+esc(m.text)+'</p>'+(m.reply?'<div class="message-reply"><strong>Дүкеннің жауабы</strong><br>'+esc(m.reply)+'</div>':'<p class="small-note section-space">Жауап күтілуде</p>')+(state.user.admin?'<div class="section-space">'+button(m.reply?'Жауапты өзгерту':'Жауап жазу','reply','data-id="'+m.id+'"','secondary small')+'</div>':'')+'</article>').join(''):empty('Әзірге хабарлама жоқ','Сұрағыңызды оң жақтағы формаға жазыңыз.','','message');
  return html+'</div><section class="panel"><h2>Жаңа хабарлама</h2><form data-form="message">'+field('Тақырып','subject','','text','required maxlength="100"')+textArea('Сұрағыңыз','text','','required maxlength="2000"')+'<div class="form-error" role="alert"></div><button class="button block" type="submit">Хабарламаны сақтау</button></form><p class="small-note section-space">Хабарлама осы сайттағы дүкен әкімшісіне көрінеді.</p></section></div>';
}
function guideView() {
  return pageHead('Қолдану нұсқаулығы','Каталогтан дайын өтінімге дейін.')+
    '<div class="two-equal"><section class="panel guide-list"><div><h3>Тас таңдаңыз</h3><p>Каталогта '+state.catalog.length+' тас түрі бар. Үш тілдегі атау, бюджет, салмақ және мөлдірлікпен іздеңіз. Үш тасқа дейін салыстырып, жеке жазба сақтай аласыз.</p></div><div><h3>Алқаңызды құрастырыңыз</h3><p>Қосу белгісін басып, «Менің алқам» бөлімінде әр тастың санын 1–99 аралығында өзгертіңіз. Жалпы салмақ пен құн автоматты түрде есептеледі.</p></div><div><h3>Жинақты сақтаңыз</h3><p>Алқаға атау беріп сақтаңыз. «Жинақтар» бөлімінен оны кейін қайта жүктей аласыз.</p></div><div><h3>Өтінім рәсімдеңіз</h3><p>Аккаунтқа кіріп, телефон мен алу тәсілін көрсетіңіз. Сақталған өтінімдегі тастар кейін алқаны өзгертсеңіз де сақталады.</p></div></section><div class="stack"><section class="panel"><h2>Сайт туралы</h2><p class="muted">Бұл — «Асыл тас» жобасының онлайн нұсқасы. Аккаунттар, алқалар және өтінімдер сайттың дерекқорында сақталады. Өз аккаунтыңызға басқа құрылғыдан да кіре аласыз.</p><p class="small-note section-space">Онлайн төлем жоқ. Каталогтағы баға, салмақ және мөлдірлік — оқу жобасының деректері. Суреттер — нақты минералдардың фотолары.</p></section><section class="panel"><h2>Жоба авторлары</h2><p>Болат Тәңірберді<br>Ергаш Назым<br>Ғалымжан Шынай</p><a class="text-button section-space" href="#credits">Фотосуреттердің дереккөздері</a></section><section class="panel"><h2>Дүкен әкімшісіне</h2><p class="small-note">Сайт иесі өз құқығын растағаннан кейін өтінімдер мен дүкен деректерін басқара алады. Алдымен сайт иесі ретінде растаудан өтіп, сол email-мен осы сайтқа тіркеліңіз немесе кіріңіз.</p><a class="button secondary section-space" href="/signin-with-chatgpt?return_to=%2F%23profile" target="_top">Әкімшіні растау</a></section></div></div>';
}
function creditsView() {
  return pageHead('Нақты тастардың фотосуреттері','Фотолар Wikimedia Commons дереккөздерінен алынған. Авторлар мен лицензиялар төменде көрсетілген.')+'<div class="credits-grid" id="credits-list"><p class="muted">Деректер жүктелуде…</p></div>';
}
async function loadCredits() {
  try {
    const credits=await (await fetch('/assets/photo_credits.json')).json();
    if (currentPage!=='credits') return;
    $('#credits-list').innerHTML=credits.map(c=>'<article class="panel credit">'+photo(stone(c.id))+'<div><h3>'+esc(stone(c.id).name)+'</h3><p>'+esc(c.author || 'Қоғамдық игілік')+'</p><p>'+esc(c.license)+'</p><a href="'+esc(c.source)+'" target="_blank" rel="noopener noreferrer">Дереккөз</a>'+(c.license_url?' · <a href="'+esc(c.license_url)+'" target="_blank" rel="noopener noreferrer">Лицензия</a>':'')+'</div></article>').join('');
  } catch {if($('#credits-list')) $('#credits-list').textContent='Фото деректері жүктелмеді. Бетті жаңартыңыз.';}
}
function mediaView() {
  return pageHead('Медиа кітапхана','Өзіңіздің суреттеріңіз, аудио және бейне материалдарыңыз.')+
    '<div class="panel"><label class="field"><span>Файл қосу</span><input id="media-upload" type="file" accept="image/*,audio/*,video/*"><small>Бір файлға 20 МБ дейін. Материалдар осы браузерде сақталады.</small></label><div class="info-box">'+icon('info')+'<span>Браузер деректерін тазаласаңыз, осы кітапхана да өшеді. Бастапқы файлдарыңыз өзгермейді.</span></div></div><div class="media-grid section-space" id="media-list"><p class="muted">Материалдар жүктелуде…</p></div>';
}
function showAuth(mode='login') {
  authMode=mode;
  const register=mode==='register';
  showModal('<h2 id="dialog-title">'+(register?'Жаңа аккаунт':'Қайта қош келдіңіз')+'</h2><p class="dialog-sub">'+(register?'Коллекцияңызға жеке кеңістік ашыңыз.':'Алқаларыңыз бен өтінімдеріңізді жалғастырыңыз.')+'</p><div class="auth-tabs"><button data-do="auth-tab" data-value="login" class="'+(!register?'active':'')+'">Кіру</button><button data-do="auth-tab" data-value="register" class="'+(register?'active':'')+'">Тіркелу</button></div><form data-form="auth">'+(register?field('Аты-жөніңіз','name','','text','required maxlength="80" autocomplete="name"'):'')+field('Email','email','','email','required maxlength="120" autocomplete="email"')+field('Құпиясөз','password','','password','required minlength="8" maxlength="128" autocomplete="'+(register?'new-password':'current-password')+'"')+'<label class="check-label"><input type="checkbox" id="show-password">Құпиясөзді көрсету</label>'+(register?'<label class="check-label"><input type="checkbox" name="import_guest" checked>Қонақ алқасын, жинақтарын, салыстыруын, тарихын және жазбаларын көшіру</label>':'')+'<div class="form-error" role="alert"></div><button type="submit" class="button block">'+(register?'Аккаунт ашу':'Кіру')+'</button></form><p class="small-note section-space">'+(register?'Аккаунт арқылы жинақтарыңызды сақтап, өтінімдеріңізді қарай аласыз.':'Email мен құпиясөзді осы сайтта тіркелген нұсқада енгізіңіз.')+'</p>');
}
function showStone(id) {
  const s=stone(id);if(!s)return;dialog.dataset.stoneId=String(id);
  const reviews=state.reviews.filter(x=>x.stone_id===s.id),own=reviews.find(x=>x.owner===true || x.owner===state.user?.id);
  showModal('<div class="eyebrow">'+esc(s.category)+'</div><h2 id="dialog-title">'+esc(s.name)+'</h2><div class="detail-grid"><div class="detail-photo">'+photo(s)+'</div><div><p class="detail-price">'+money(s.cost)+'</p><p class="small-note">бір тас үшін</p><div class="detail-facts"><div class="summary-line"><span>Салмақ</span><strong>'+weight(s.carats)+' карат</strong></div><div class="summary-line"><span>Мөлдірлік</span><strong>'+s.transparency+'%</strong></div><div class="summary-line"><span>Бір карат бағасы</span><strong>'+money(s.price_per_carat)+'</strong></div></div><form data-form="add-stone" data-id="'+s.id+'" class="detail-actions">'+field('Саны','quantity','1','number','min="1" max="'+Math.max(1,99-quantity(s.id))+'" required')+'<button type="submit" class="button">Алқаға қосу</button></form><p class="small-note section-space">Фотода нақты минерал көрсетілген. Баға мен өлшем — каталогтың оқу деректері.</p></div></div><div class="detail-extras">'+(s.description?'<h3>Тас туралы</h3><p class="muted">'+esc(s.description)+'</p>':'')+(s.care?'<p class="care-note"><strong>Күтім:</strong> '+esc(s.care)+'</p>':'')+'<div class="button-row section-space">'+button((state.comparison||[]).includes(s.id)?'Салыстырудан алу':'Салыстыруға қосу','compare-stone','data-id="'+s.id+'" id="detail-compare"','secondary')+'<a href="#compare" class="text-button">Салыстыруды ашу</a></div><form class="section-space" data-form="note" data-id="'+s.id+'">'+textArea('Менің жеке жазбам','text',(state.notes||[]).find(n=>n.stone_id===s.id)?.text||'','maxlength="2000" placeholder="Таңдау себебі, үйлесімі немесе еске салу…"')+'<p class="small-note">'+(state.user?'Жазба тек сіздің аккаунтыңызда көрінеді.':'Жазба осы қонақ сеансында сақталады; тіркелгенде көшіруге болады.')+' Бос жазбаны сақтау оны жояды.</p><div class="form-error" role="alert"></div><button type="submit" class="button secondary section-space">Жазбаны сақтау</button></form></div><hr class="rule"><h3>Пікірлер ('+reviews.length+')</h3>'+(reviews.length?reviews.map(r=>'<div class="review"><span class="stars" aria-label="'+r.rating+' / 5">'+'★'.repeat(r.rating)+'☆'.repeat(5-r.rating)+'</span> <small>'+esc(r.name)+'</small><p>'+esc(r.text)+'</p></div>').join(''):'<p class="small-note section-space">Бұл тасқа әзірге пікір қалдырылмаған.</p>')+(state.user?'<form class="review-form" data-form="review" data-id="'+s.id+'"><h3>'+(own?'Пікіріңізді жаңарту':'Пікір қалдыру')+'</h3><label class="field"><span>Бағаңыз</span><select name="rating" aria-label="Бағаңыз">'+[5,4,3,2,1].map(n=>'<option value="'+n+'" '+(Number(own?.rating || 5)===n?'selected':'')+'>'+n+' / 5</option>').join('')+'</select></label>'+textArea('Пікіріңіз','text',own?.text || '','required maxlength="1000"')+'<div class="form-error" role="alert"></div><button type="submit" class="button secondary">Пікірді сақтау</button></form>':'<div class="section-space">'+button('Пікір қалдыру үшін кіру','auth','','secondary')+'</div>'),true);
}
function showCheckout() {
  if (!requireUser()) return;
  if (!state.necklace.count) {toast('Алдымен алқаға тас қосыңыз.');return;}
  const today=new Date();today.setMinutes(today.getMinutes()-today.getTimezoneOffset());
  showModal('<h2 id="dialog-title">Өтінім рәсімдеу</h2><p class="dialog-sub">'+state.necklace.count+' тас · '+weight(state.necklace.carats)+' карат · '+money(state.necklace.total)+'</p><form data-form="checkout"><label class="field"><span>Алу тәсілі</span><select name="delivery" id="delivery-method" aria-label="Алу тәсілі"><option value="pickup">Дүкеннен өзім алып кетемін</option><option value="delivery">Жеткізу</option></select></label>'+field('Байланыс телефоны','phone',state.user.phone,'tel','required maxlength="30" autocomplete="tel"')+'<div id="delivery-address" hidden>'+field('Жеткізу мекенжайы','address',state.user.address,'text','maxlength="240" autocomplete="street-address"')+'</div>'+field('Қалаған алу күні','pickup_date','','date','min="'+today.toISOString().slice(0,10)+'"')+textArea('Қосымша ескерту','note','','maxlength="1000"')+'<label class="check-label"><input type="checkbox" name="gift">Сыйлық ретінде дайындау</label><label class="check-label"><input type="checkbox" name="confirmed" required>Байланыс деректері мен алқа құрамын тексердім</label><div class="info-box">'+icon('info')+'<span>Өтінім осы сайтта сақталады. Қазір төлем алынбайды.</span></div><div class="form-error" role="alert"></div><button type="submit" class="button block section-space">Өтінімді сақтау</button></form>');
}
function showOrder(id) {
  const o=state.orders.find(x=>x.id===Number(id));if(!o)return;
  showModal('<h2 id="dialog-title">Өтінім №'+o.id+'</h2><p class="dialog-sub">'+esc(dateText(o.created))+' · '+badge(o.status)+'</p><div class="panel">'+o.items.map(i=>'<div class="summary-line"><span>'+esc(stone(i.stone_id).name)+' × '+i.quantity+'</span><strong>'+money(i.total)+'</strong></div>').join('')+'<hr class="rule"><div class="summary-line"><span>'+o.count+' тас · '+weight(o.carats)+' карат</span><strong>'+money(o.total)+'</strong></div></div><dl class="contact-list section-space"><div><dt>Алу тәсілі</dt><dd>'+(o.delivery==='delivery'?'Жеткізу':'Өзім алып кетемін')+'</dd></div><div><dt>Байланыс телефоны</dt><dd>'+esc(o.phone)+'</dd></div>'+(o.address?'<div><dt>Мекенжай</dt><dd>'+esc(o.address)+'</dd></div>':'')+(o.pickup_date?'<div><dt>Қалаған күні</dt><dd>'+esc(o.pickup_date)+'</dd></div>':'')+(o.note?'<div><dt>Ескерту</dt><dd class="message-text">'+esc(o.note)+'</dd></div>':'')+'</dl>'+(o.gift?'<p class="badge">Сыйлық қаптамасы сұралған</p>':'')+
    (state.user.admin?'<form data-form="order-status" data-id="'+o.id+'" class="section-space"><label class="field"><span>Өтінім күйі</span><select name="status" aria-label="Өтінім күйі">'+Object.entries(statusNames).map(([v,l])=>'<option value="'+v+'" '+(v===o.status?'selected':'')+'>'+l+'</option>').join('')+'</select></label><div class="form-error" role="alert"></div><button type="submit" class="button">Күйін сақтау</button></form>':(o.status==='new'?'<div class="section-space">'+button('Өтінімнен бас тарту','cancel-order','data-id="'+o.id+'"','danger')+'</div>':''))+'<div class="section-space">'+button('Мәліметтерді көшіру','copy-order','data-id="'+o.id+'"','secondary')+'</div>');
}
function showSave() {
  if (!state.necklace.count){toast('Алдымен алқаға тас қосыңыз.');return;}
  showModal('<h2 id="dialog-title">Жинақты сақтау</h2><p class="dialog-sub">Алқаңызға өзіңізге түсінікті атау беріңіз.</p><form data-form="save">'+field('Жинақ атауы','name','Менің алқам','text','required maxlength="80"')+'<div class="form-error" role="alert"></div><button class="button block" type="submit">Сақтау</button></form>');
}
function showStore() {
  const s=state.store;
  showModal('<h2 id="dialog-title">Дүкен деректері</h2><p class="dialog-sub">Бұл мәліметтер барлық келушілерге көрінеді.</p><form data-form="store">'+field('Мекенжай','address',s.address,'text','required maxlength="240"')+field('Телефон','phone',s.phone,'tel','maxlength="30"')+field('Жұмыс уақыты','hours',s.hours,'text','maxlength="100"')+'<div class="form-error" role="alert"></div><button class="button block" type="submit">Деректерді сақтау</button></form>');
}
function showReply(id) {
  const m=state.messages.find(x=>x.id===Number(id));
  showModal('<h2 id="dialog-title">Хабарламаға жауап</h2><p class="dialog-sub">'+esc(m.subject)+'</p><form data-form="reply" data-id="'+m.id+'">'+textArea('Жауабыңыз','reply',m.reply || '','required maxlength="2000"')+'<div class="form-error" role="alert"></div><button class="button block" type="submit">Жауапты сақтау</button></form>');
}

document.addEventListener('click',async event=>{
  const target=event.target.closest('[data-do]');
  if (!target || !state) return;
  const action=target.dataset.do,id=Number(target.dataset.id),value=target.dataset.value;
  if (target.disabled) return;
  if (action==='install') return showInstall();
  if (action==='close-modal') return closeModal();
  if (action==='back') return navigate(tabPages.includes(currentPage)?'home':'profile');
  if (action==='filters') {advancedFiltersOpen=!advancedFiltersOpen;$('.catalog-tools').dataset.expanded=String(advancedFiltersOpen);target.setAttribute('aria-expanded',String(advancedFiltersOpen));return;}
  if (action==='claim-admin') return perform('admin_claim',{}, {message:'Әкімші құқығы қосылды'});
  if (action==='auth') return showAuth();
  if (action==='register') return showAuth('register');
  if (action==='auth-tab') return showAuth(value);
  if (action==='detail') return openStone(id);
  if (action==='order') return showOrder(id);
  if (action==='checkout') return showCheckout();
  if (action==='save') return showSave();
  if (action==='edit-store') return showStore();
  if (action==='reply') return showReply(id);
  if (action==='retry') return start();
  if (action==='category') {filters.category=value;return render();}
  if (action==='reset-filters') {Object.assign(filters,filterDefaults);return render();}
  if (action==='campus-retry')return loadCampus();
  if (action==='gps-start')return startGPS();
  if (action==='gps-clear')return clearGPS();
  if (action==='copy-place')return copyPlace();
  if (action==='share-place')return sharePlace();
  if (action==='compare-stone') {
    if(await perform('comparison_toggle',{stone_id:id},{message:(state.comparison||[]).includes(id)?'Салыстырудан алынды':'Салыстыруға қосылды'})) {
      const detail=$('#detail-compare');if(detail&&dialog.open)detail.textContent=(state.comparison||[]).includes(Number(dialog.dataset.stoneId))?'Салыстырудан алу':'Салыстыруға қосу';
    }
    return;
  }
  if (action==='comparison-clear')return perform('comparison_clear',{}, {message:'Салыстыру тізімі тазартылды'});
  if (action==='history-clear')return perform('history_clear',{}, {message:'Қарау тарихы тазартылды'});
  if (action==='delete-note') {
    if(window.confirm('Осы жеке жазбаны жоясыз ба?'))await perform('note_save',{stone_id:id,text:''},{message:'Жеке жазба жойылды'});
    return;
  }
  if (action==='add') return perform('necklace_set',{stone_id:id,quantity:quantity(id)+1},{message:stone(id).name+' алқаға қосылды'});
  if (action==='quantity') return perform('necklace_set',{stone_id:id,quantity:Number(value)});
  if (action==='remove') return perform('necklace_set',{stone_id:id,quantity:0},{message:'Тас алқадан алынды'});
  if (action==='favorite') {
    if (!requireUser()) return;
    return perform('favorite_toggle',{stone_id:id},{message:state.favorites.includes(id)?'Таңдаулыдан алынды':'Таңдаулыға сақталды'});
  }
  if (action==='demo') {
    if (state.necklace.count && !window.confirm('Қазіргі алқаны үлгі алқамен алмастырасыз ба? Сақталмаған өзгерістер ауысады.')) return;
    if(await perform('necklace_demo',{}, {message:'Үлгі алқа дайын: 6 тас, 5.90 карат'}))navigate('necklace');
    return;
  }
  if (action==='clear') {
    if(window.confirm('Алқадағы барлық тасты алып тастайсыз ба?'))await perform('necklace_clear',{}, {message:'Алқа тазартылды'});
    return;
  }
  if (action==='load-saved') {
    if(state.necklace.count && !window.confirm('Қазіргі алқаны осы жинақпен алмастырасыз ба?'))return;
    if(await perform('collection_load',{id},{message:'Жинақ алқаға жүктелді'}))navigate('necklace');
    return;
  }
  if (action==='delete-saved') {
    if(window.confirm('Осы сақталған жинақты жоясыз ба?'))await perform('collection_delete',{id},{message:'Жинақ жойылды'});
    return;
  }
  if (action==='cancel-order') {
    if(window.confirm('Осы өтінімнен бас тартасыз ба?'))await perform('order_cancel',{id},{close:true,message:'Өтінімнен бас тартылды'});
    return;
  }
  if (action==='logout') {
    if(window.confirm('Аккаунттан шығасыз ба? Сақталған деректеріңіз аккаунтта қалады.'))await perform('logout',{}, {close:true,message:'Аккаунттан шықтыңыз'});
    return;
  }
  if (action==='copy-order') {
    const o=state.orders.find(x=>x.id===id);
    const text='Асыл тас · Өтінім №'+o.id+'\n'+statusNames[o.status]+'\n'+o.items.map(i=>stone(i.stone_id).name+' × '+i.quantity+' — '+money(i.total)).join('\n')+'\nБарлығы: '+money(o.total)+'\nТелефон: '+o.phone+'\n'+(o.address || '');
    try {await navigator.clipboard.writeText(text);toast('Өтінім мәліметтері көшірілді');}catch{toast('Көшіруге браузер рұқсат бермеді.');}
    return;
  }
  if (action==='delete-media') {
    if(!window.confirm('Материалды кітапханадан жоясыз ба? Бастапқы файл өзгермейді.'))return;
    try {await mediaOperation('readwrite',store=>store.delete(target.dataset.key));await loadMedia();toast('Материал кітапханадан жойылды');}catch{toast('Материалды жою мүмкін болмады.');}
  }
});
document.addEventListener('submit',async event=>{
  const form=event.target;
  if(form.id==='global-search') {
    event.preventDefault();filters.query=form.querySelector('input').value.trim();filters.min=0;filters.max=100;filters.category='all';filters.budget='';filters.minCarats='';filters.maxCarats='';navigate('catalog');return;
  }
  if(form.id==='range-form') {
    event.preventDefault();const data=new FormData(form),next={...filters,min:Number(data.get('min')),max:Number(data.get('max')),minCarats:String(data.get('minCarats')||''),maxCarats:String(data.get('maxCarats')||''),budget:String(data.get('budget')||'')};
    try{discovery.validateFilters(next);Object.assign(filters,next);form.querySelector('.form-error')?.replaceChildren();renderProducts();}
    catch(error){formError(form,error.message);}return;
  }
  if(!form.dataset.form)return;
  event.preventDefault();
  const data=Object.fromEntries(new FormData(form)),type=form.dataset.form;
  form.querySelector('.form-error')?.replaceChildren();
  if(type==='auth') {
    const ok=await perform(authMode,{...data,import_guest:data.import_guest==='on'},{form,close:true,message:authMode==='register'?'Аккаунт ашылды':'Қош келдіңіз!'});
    if(ok)navigate('home');return;
  }
  if(type==='note')return perform('note_save',{stone_id:Number(form.dataset.id),text:data.text},{form,message:data.text.trim()?'Жеке жазба сақталды':'Жеке жазба жойылды'});
  if(type==='profile')return perform('update_profile',data,{form,message:'Профиль жаңартылды'});
  if(type==='password') {
    if(data.new_password!==data.confirm){formError(form,'Жаңа құпиясөздер сәйкес емес.');return;}
    return perform('change_password',{current_password:data.current_password,new_password:data.new_password},{form,message:'Құпиясөз жаңартылды'});
  }
  if(type==='add-stone') {
    const id=Number(form.dataset.id);
    return perform('necklace_set',{stone_id:id,quantity:quantity(id)+Number(data.quantity)},{form,close:true,message:stone(id).name+' алқаға қосылды'});
  }
  if(type==='save')return perform('collection_save',data,{form,close:true,message:'Жинақ сақталды'});
  if(type==='checkout') {
    const ok=await perform('order_create',{...data,gift:data.gift==='on'},{form,close:true,message:'Өтінім сақталды'});
    if(ok)navigate('orders');return;
  }
  if(type==='order-status')return perform('admin_order_status',{id:Number(form.dataset.id),status:data.status},{form,close:true,message:'Өтінім күйі жаңартылды'});
  if(type==='store')return perform('store_update',data,{form,close:true,message:'Дүкен деректері жаңартылды'});
  if(type==='message')return perform('message_send',data,{form,message:'Хабарлама сақталды'});
  if(type==='reply')return perform('message_reply',{id:Number(form.dataset.id),reply:data.reply},{form,close:true,message:'Жауап сақталды'});
  if(type==='review') {
    const id=Number(form.dataset.id);
    if(await perform('review_save',{stone_id:id,rating:Number(data.rating),text:data.text},{form,message:'Пікір сақталды'}))showStone(id);
  }
});
document.addEventListener('input',event=>{
  if(event.target.id==='catalog-search'){filters.query=event.target.value;renderProducts();}
});
document.addEventListener('change',async event=>{
  const target=event.target;
  if(target.id==='campus-place'){selectedPlace=target.value;render();return;}
  if(target.id==='travel-mode'){travelMode=target.value;renderLocationStatus();return;}
  if(target.id==='catalog-sort'){filters.sort=target.value;renderProducts();}
  if(target.id==='show-password') {
    dialog.querySelector('[name=password]').type=target.checked?'text':'password';
  }
  if(target.id==='delivery-method') {
    const deliver=target.value==='delivery';
    $('#delivery-address').hidden=!deliver;
    $('#delivery-address input').required=deliver;
  }
  if(target.id==='media-upload') {
    const file=target.files[0];if(!file)return;
    if(!/^(image|audio|video)\//.test(file.type)){toast('Сурет, аудио немесе бейне файлын таңдаңыз.');target.value='';return;}
    if(file.size>20*1024*1024){toast('Файл көлемі 20 МБ-тан аспауы керек.');target.value='';return;}
    try {
      await mediaOperation('readwrite',store=>store.put({id:crypto.randomUUID(),scope:mediaScope(),name:file.name,type:file.type,size:file.size,blob:file,created:Date.now()}));
      await loadMedia();toast('Материал кітапханаға қосылды');
    } catch {toast('Файлды сақтау мүмкін болмады. Браузердегі бос орынды тексеріңіз.');}
    target.value='';
  }
});
function mediaScope(){return state.user?'user:'+state.user.email:(state.storage_scope||'guest');}
async function mediaDatabase() {
  return new Promise((resolve,reject)=>{
    const request=indexedDB.open('asyltas_media_v1',1);
    request.onupgradeneeded=()=>request.result.createObjectStore('files',{keyPath:'id'});
    request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);
  });
}
async function mediaOperation(mode,callback) {
  const db=await mediaDatabase();
  return new Promise((resolve,reject)=>{
    const tx=db.transaction('files',mode),request=callback(tx.objectStore('files'));
    tx.oncomplete=()=>{db.close();resolve(request.result);};
    tx.onerror=()=>{db.close();reject(tx.error);};tx.onabort=()=>{db.close();reject(tx.error);};
  });
}
async function loadMedia() {
  try {
    const rows=(await mediaOperation('readonly',store=>store.getAll())).filter(x=>x.scope===mediaScope()).sort((a,b)=>b.created-a.created);
    if(currentPage!=='media')return;
    mediaUrls.forEach(URL.revokeObjectURL);mediaUrls=[];
    $('#media-list').innerHTML=rows.length?rows.map(row=>{
      const url=URL.createObjectURL(row.blob);mediaUrls.push(url);
      const kind=row.type.split('/')[0];
      const preview=kind==='image'?'<img src="'+url+'" alt="'+esc(row.name)+'">':'<'+kind+' controls preload="metadata" src="'+url+'"></'+kind+'>';
      return '<article class="panel media-card"><h3>'+esc(row.name)+'</h3><small>'+new Intl.NumberFormat('kk-KZ',{maximumFractionDigits:1}).format(row.size/1024)+' КБ · '+esc(dateText(row.created))+'</small>'+preview+'<button class="button secondary small" data-do="delete-media" data-key="'+esc(row.id)+'">'+icon('trash')+'Кітапханадан жою</button></article>';
    }).join(''):empty('Кітапханаңыз әзірге бос','Құрылғыңыздан сурет, аудио немесе бейне қосыңыз.','','media');
  } catch {if($('#media-list'))$('#media-list').innerHTML='<p class="form-error">Браузер файл сақтауға рұқсат бермеді.</p>';}
}
$('#menu-toggle').addEventListener('click',()=>setMenu(!$('.sidebar').classList.contains('open')));
$('#nav-overlay').addEventListener('click',()=>setMenu(false));
$('.dialog-close').addEventListener('click',closeModal);
dialog.addEventListener('click',event=>{
  if(event.target!==dialog)return;
  const r=dialog.getBoundingClientRect();
  if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)closeModal();
});
mobileQuery.addEventListener('change',()=>setMenu(false));
document.addEventListener('keydown',event=>{if(event.key==='Escape'&&$('.sidebar').classList.contains('open')){setMenu(false);$('#menu-toggle').focus();}});
setMenu(false);
window.addEventListener('hashchange',()=>{pageScroll.set(currentPage,window.scrollY);setMenu(false);if(dialog.open)closeModal();render();window.scrollTo(0,pageScroll.get(currentPage)||0);});
const updateAppMode=()=>document.documentElement.classList.toggle('standalone',!!window.AsylInstall?.isStandalone);
window.addEventListener('asyl-install-ready',updateAppMode);
window.addEventListener('pagehide',clearGPS);
document.addEventListener('visibilitychange',()=>{if(!document.hidden)renderLocationStatus();});
document.addEventListener('click',event=>{if(event.target.closest('#campus-route'))renderLocationStatus();});
updateAppMode();
const updateKeyboard=()=>{
  const editing=document.activeElement?.matches('input:not([type=checkbox]):not([type=radio]),textarea,select');
  const viewport=window.visualViewport;
  const keyboard=!!(mobileQuery.matches&&editing&&viewport&&window.innerHeight-viewport.height>140&&viewport.scale===1);
  document.body.classList.toggle('keyboard-open',keyboard);
  if(viewport&&viewport.scale===1){document.documentElement.style.setProperty('--visible-height',Math.round(viewport.height)+'px');document.documentElement.style.setProperty('--keyboard-inset',keyboard?Math.max(0,Math.round(window.innerHeight-viewport.height-viewport.offsetTop))+'px':'0px');}
};
window.visualViewport?.addEventListener('resize',updateKeyboard);
window.visualViewport?.addEventListener('scroll',updateKeyboard);
document.addEventListener('focusin',updateKeyboard);
document.addEventListener('focusout',()=>setTimeout(updateKeyboard,0));
updateKeyboard();
async function start() {
  try {await refresh();}
  catch(error) {
    main.innerHTML=empty('Байланыс уақытша үзілді','Интернет байланысын тексеріп, қайта көріңіз. Сақталған деректеріңіз аккаунтыңызда қалады.',button('Қайта жүктеу','retry'),'info');
    // Retry is available even before a state response exists.
    main.querySelector('[data-do=retry]').addEventListener('click',start,{once:true});
    $('#connection-error').textContent=error.message;$('#connection-error').hidden=false;
  }
}
start();
