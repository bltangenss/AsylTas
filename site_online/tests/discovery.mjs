import assert from 'node:assert/strict';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import '../public/discovery.js';
const helper=globalThis.AsylDiscovery;
const catalog=JSON.parse(await readFile(new URL('../public/assets/catalog.json',import.meta.url),'utf8'));
const campus=JSON.parse(await readFile(new URL('../public/assets/campus_v4.json',import.meta.url),'utf8'));
const defaults={query:'',category:'all',sort:'default',min:0,max:100,minCarats:'',maxCarats:'',budget:''};
const checks=[];
function test(name,run) {try {run();checks.push({name,passed:true});console.log('PASS '+name);}catch(error){checks.push({name,passed:false,error:error.stack});console.error('FAIL '+name+'\n'+error.stack);}}
test('decimal_input_uses_exact_hundredths',()=>{
  assert.equal(helper.units('0.01'),1n);assert.equal(helper.units('616200.00'),61620000n);assert.equal(helper.units('999999999999.99'),99999999999999n);
  assert.equal(helper.units(''),null);
  for(const value of ['1.001','-1','Infinity','NaN','1e3','2,2','1000000000000']) assert.throws(()=>helper.units(value));
});
test('inclusive_budget_carats_transparency_filters',()=>{
  const filters={...defaults,min:85,max:88,minCarats:'1.25',maxCarats:'1.50',budget:'22500.00'};
  assert.deepEqual(helper.filterCatalog(catalog,filters).map(s=>s.id),[6,10,28]);
  assert.deepEqual(helper.filterCatalog(catalog,{...filters,budget:'22499.99'}).map(s=>s.id),[6,28]);
});
test('multilingual_alias_search_normalizes_and_combines_terms',()=>{
  assert.deepEqual(helper.filterCatalog(catalog,{...defaults,query:'  SAPPHIRE  '}).map(s=>s.id),[4]);
  assert.deepEqual(helper.filterCatalog(catalog,{...defaults,query:'тигровый глаз'}).map(s=>s.id),[38]);
  assert.deepEqual(helper.filterCatalog(catalog,{...defaults,query:'ｄｉａｍｏｎｄ'}).map(s=>s.id),[1]);
  assert.equal(helper.filterCatalog(catalog,{...defaults,query:'гауһар малахит'}).length,0);
});
test('category_favorite_intersection_and_empty_results',()=>{
  assert.equal(helper.filterCatalog(catalog,{...defaults,category:'Асыл тас'}).length,5);
  assert.deepEqual(helper.filterCatalog(catalog,defaults,[24,48]).map(s=>s.id),[24,48]);
  assert.deepEqual(helper.filterCatalog(catalog,{...defaults,category:'Асыл тас'},[48]),[]);
});
test('filters_reject_inverted_or_invalid_ranges',()=>{
  for(const patch of [{min:80,max:30},{min:-1},{max:101},{min:3.2},{minCarats:'2',maxCarats:'1'},{budget:'-1'}]) assert.throws(()=>helper.validateFilters({...defaults,...patch}));
});
test('sorts_exactly_and_does_not_mutate_catalog',()=>{
  const original=catalog.map(s=>s.id),rows=helper.filterCatalog(catalog,{...defaults,sort:'totalAsc'});
  for(let i=1;i<rows.length;i++) assert.ok(helper.units(rows[i-1].cost)<=helper.units(rows[i].cost));
  assert.deepEqual(catalog.map(s=>s.id),original);
  assert.equal(helper.filterCatalog(catalog,{...defaults,sort:'desc'})[0].id,1);
});
test('campus_data_has_three_distinct_verified_places',()=>{
  assert.deepEqual(campus.map(p=>p.id),['pushkin9','enu_pushkin11','enu_main']);
  for(const p of campus) {assert.ok(helper.validCoordinates(p.lat,p.lon));assert.ok(p.address&&p.description);assert.match(p.coordinate_source,/^https:\/\//);}
  const meeting=campus[0];assert.deepEqual([meeting.lat,meeting.lon],[51.157546,71.458645]);
  assert.ok(meeting.description.includes('Университеттің ресми корпусы немесе дүкен ретінде көрсетілмейді'));
});
test('haversine_distance_zero_symmetry_and_known_equator_arc',()=>{
  assert.equal(helper.distanceMeters(campus[0],campus[0]),0);
  assert.ok(Math.abs(helper.distanceMeters({lat:0,lon:0},{lat:0,lon:1})-111194.9266)<0.01);
  assert.ok(Math.abs(helper.distanceMeters(campus[0],campus[1])-helper.distanceMeters(campus[1],campus[0]))<0.0001);
  assert.throws(()=>helper.distanceMeters({lat:91,lon:0},campus[0]));
});
test('gps_values_and_stale_positions_are_rejected',()=>{
  const now=1800000000000;
  const position={coords:{latitude:51.15,longitude:71.46,accuracy:20},timestamp:now};
  assert.deepEqual(helper.fromPosition(position,now),{lat:51.15,lon:71.46,accuracy:20,timestamp:now});
  for(const coords of [{latitude:NaN,longitude:1,accuracy:1},{latitude:1,longitude:181,accuracy:1},{latitude:1,longitude:1,accuracy:-1}]) assert.throws(()=>helper.fromPosition({...position,coords},now));
  assert.throws(()=>helper.fromPosition({...position,timestamp:now-120001},now));
  assert.throws(()=>helper.fromPosition({...position,timestamp:now+10001},now));
});
test('route_only_includes_fresh_opted_in_location',()=>{
  const now=Date.now(),position={lat:51.15,lon:71.46,accuracy:10,timestamp:now};
  const withGPS=new URL(helper.routeUrl(campus[0],position,'transit',now));
  assert.equal(withGPS.searchParams.get('origin'),'51.15,71.46');assert.equal(withGPS.searchParams.get('travelmode'),'transit');
  assert.equal(withGPS.searchParams.get('destination'),'51.157546,71.458645');
  assert.equal(new URL(helper.routeUrl(campus[0],null)).searchParams.has('origin'),false);
  assert.equal(new URL(helper.routeUrl(campus[0],{...position,timestamp:now-120001},'walking',now)).searchParams.has('origin'),false);
});
test('map_links_target_selected_place_and_escape_query',()=>{
  assert.equal(new URL(helper.mapUrl(campus[1])).searchParams.get('query'),`${campus[1].lat},${campus[1].lon}`);
  const fallback={address:'Астана & көше #1'};assert.equal(new URL(helper.mapUrl(fallback)).searchParams.get('query'),fallback.address);
  assert.equal(new URL(helper.routeUrl(campus[0],null,'unsupported')).searchParams.get('travelmode'),'walking');
});
test('geolocation_denied_unavailable_and_timeout_have_distinct_guidance',()=>{
  const messages=[1,2,3].map(code=>helper.geolocationError({code}));assert.equal(new Set(messages).size,3);
  assert.match(messages[0],/рұқсат/);assert.match(messages[1],/GPS пен желіні/);assert.match(messages[2],/уақыты аяқталды/);
});
const report={status:checks.every(c=>c.passed)?'passed':'failed',tests:checks.length,passed:checks.filter(c=>c.passed).length,checks,created_utc:new Date().toISOString(),helper_sha256:createHash('sha256').update(await readFile(new URL('../public/discovery.js',import.meta.url))).digest('hex'),coverage:'Pure input, decimal filter and geolocation calculations. Browser permission UI requires separate browser verification.'};
const directory=new URL('../../reports/qa_online_v4_20261125/',import.meta.url);await mkdir(directory,{recursive:true});await writeFile(new URL('discovery_checks.json',directory),JSON.stringify(report,null,2)+'\n');
console.log(`${report.passed}/${report.tests} passed`);process.exitCode=report.status==='passed'?0:1;
