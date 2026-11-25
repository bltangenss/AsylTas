'use strict';
// Pure catalog/location helpers shared by the UI and its boundary tests.
globalThis.AsylDiscovery = (() => {
  function units(value) {
    if (value === '' || value === null || value === undefined) return null;
    const text = String(value).trim();
    if (!/^\d{1,12}(?:\.\d{1,2})?$/.test(text)) throw new Error('Оң санды енгізіңіз; үтір орнына нүкте қолданыңыз.');
    const [whole, fraction=''] = text.split('.');
    return BigInt(whole)*100n+BigInt(fraction.padEnd(2,'0'));
  }
  function validateFilters(filters) {
    if (!Number.isInteger(filters.min) || !Number.isInteger(filters.max) || filters.min < 0 || filters.max > 100 || filters.min > filters.max) throw new Error('Мөлдірлік шектері 0–100% аралығында дұрыс ретпен болсын.');
    const minimum=units(filters.minCarats), maximum=units(filters.maxCarats), budget=units(filters.budget);
    if (minimum !== null && maximum !== null && minimum > maximum) throw new Error('Ең аз салмақ ең көп салмақтан аспауы керек.');
    return {minimum,maximum,budget};
  }
  function filterCatalog(catalog,filters,favorites=null) {
    const {minimum,maximum,budget}=validateFilters(filters);
    const terms=String(filters.query||'').normalize('NFKC').toLocaleLowerCase('kk').trim().split(/\s+/).filter(Boolean);
    const rows=catalog.filter(stone=>{
      const haystack=[stone.name,stone.aliases,stone.category].join(' ').normalize('NFKC').toLocaleLowerCase('kk');
      const weight=units(stone.carats), cost=units(stone.cost);
      return terms.every(term=>haystack.includes(term)) &&
        (filters.category==='all'||stone.category===filters.category) &&
        stone.transparency>=filters.min&&stone.transparency<=filters.max &&
        (minimum===null||weight>=minimum)&&(maximum===null||weight<=maximum)&&
        (budget===null||cost<=budget)&&(!favorites||favorites.includes(stone.id));
    });
    const modes={asc:['price_per_carat',1],desc:['price_per_carat',-1],totalAsc:['cost',1],totalDesc:['cost',-1],weightAsc:['carats',1]};
    if (modes[filters.sort]) {
      const [key,direction]=modes[filters.sort];
      rows.sort((a,b)=>{const x=units(a[key]),y=units(b[key]);return (x<y?-1:x>y?1:0)*direction||a.id-b.id;});
    }
    return rows;
  }
  function validCoordinates(lat,lon) { return Number.isFinite(lat)&&Number.isFinite(lon)&&Math.abs(lat)<=90&&Math.abs(lon)<=180; }
  function distanceMeters(a,b) {
    if (!validCoordinates(a.lat,a.lon)||!validCoordinates(b.lat,b.lon)) throw new Error('Координаталар дұрыс емес.');
    const radians=n=>n*Math.PI/180, dLat=radians(b.lat-a.lat), dLon=radians(b.lon-a.lon);
    const h=Math.sin(dLat/2)**2+Math.cos(radians(a.lat))*Math.cos(radians(b.lat))*Math.sin(dLon/2)**2;
    return 6371000*2*Math.atan2(Math.sqrt(h),Math.sqrt(Math.max(0,1-h)));
  }
  function freshPosition(position,time=Date.now()) {
    return !!(position&&validCoordinates(position.lat,position.lon)&&Number.isFinite(position.accuracy)&&position.accuracy>=0&&Number.isFinite(position.timestamp)&&time-position.timestamp<=120000&&position.timestamp-time<=10000);
  }
  function fromPosition(position,time=Date.now()) {
    const value={lat:position?.coords?.latitude,lon:position?.coords?.longitude,accuracy:position?.coords?.accuracy,timestamp:position?.timestamp};
    if (!validCoordinates(value.lat,value.lon)||!Number.isFinite(value.accuracy)||value.accuracy<0) throw new Error('Құрылғы дұрыс координата бермеді. Қайта анықтаңыз.');
    if (!freshPosition(value,time)) throw new Error('Орналасу дерегі ескірген. Қайта анықтаңыз.');
    return value;
  }
  function mapUrl(place) {
    const query=validCoordinates(place.lat,place.lon)?`${place.lat},${place.lon}`:place.address;
    return 'https://www.google.com/maps/search/?api=1&query='+encodeURIComponent(query);
  }
  function routeUrl(place,position,mode='walking',time=Date.now()) {
    const query=validCoordinates(place.lat,place.lon)?`${place.lat},${place.lon}`:place.address;
    const url=new URL('https://www.google.com/maps/dir/');
    url.searchParams.set('api','1');url.searchParams.set('destination',query);
    url.searchParams.set('travelmode',['walking','driving','transit'].includes(mode)?mode:'walking');
    if (freshPosition(position,time)) url.searchParams.set('origin',`${position.lat},${position.lon}`);
    return url.href;
  }
  function geolocationError(error) {
    return error?.code===1?'Орналасуға рұқсат берілмеді. Браузердің сайт баптауынан рұқсат беріп, қайта көріңіз.':
      error?.code===2?'Құрылғы орналасуды анықтай алмады. GPS пен желіні тексеріңіз.':
      error?.code===3?'Орналасуды күту уақыты аяқталды. Ашық жерге шығып, қайта көріңіз.':'Орналасуды анықтау мүмкін болмады. Картадағы мекенжайды пайдалана аласыз.';
  }
  return Object.freeze({units,validateFilters,filterCatalog,validCoordinates,distanceMeters,freshPosition,fromPosition,mapUrl,routeUrl,geolocationError});
})();
