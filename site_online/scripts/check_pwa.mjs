import fs from 'node:fs/promises';
import path from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const manifest=JSON.parse(await fs.readFile(path.join(root,'public/manifest.webmanifest'),'utf8'));
assert.equal(manifest.display,'standalone');assert.equal(manifest.start_url,'/#home');
assert.equal(manifest.scope,'/');assert.equal(manifest.id,'/');assert.equal(manifest.name,'Асыл тас');
for(const [name,size] of [['apple-touch-icon.png',180],['app-icon-192.png',192],['app-icon-512.png',512]]) {
  const bytes=await fs.readFile(path.join(root,'public/assets',name));
  assert.equal(bytes.subarray(0,8).toString('hex'),'89504e470d0a1a0a');
  assert.equal(bytes.readUInt32BE(16),size);assert.equal(bytes.readUInt32BE(20),size);
}
const origin='https://asyltas.test',listeners={},stores=new Map(),fetches=[];
class WorkerRequest extends Request {constructor(input,options){super(typeof input==='string'?new URL(input,origin):input,options);}}
const caches={
  async open(name){if(!stores.has(name))stores.set(name,new Map());const store=stores.get(name);return {async put(key,response){store.set(key,response)},async match(key){return store.get(key)}};},
  async keys(){return [...stores.keys()]},async delete(name){return stores.delete(name)}
};
let failNetwork=false;
const context={URL,Request:WorkerRequest,Set,Array,Error,caches,self:{location:{origin},clients:{async claim(){}},addEventListener(name,fn){listeners[name]=fn}},
  async fetch(request){
    const url=typeof request==='string'?new URL(request,origin):new URL(request.url);fetches.push(url.pathname);
    if(failNetwork)throw Error('offline');
    const type=url.pathname.endsWith('.css')?'text/css':url.pathname.endsWith('.js')?'text/javascript':/\.(json|webmanifest)$/.test(url.pathname)?'application/json':url.pathname==='/'||url.pathname.endsWith('.html')?'text/html':'image/png';
    return new Response('public file '+url.pathname,{headers:{'Content-Type':type}});
  }
};
vm.runInNewContext(await fs.readFile(path.join(root,'public/sw.js'),'utf8'),context);
let installing;listeners.install({waitUntil(p){installing=p}});await installing;
assert.equal(fetches.includes('/api/state'),false);
assert.equal(fetches.filter(p=>/^\/assets\/stone_\d+\.jpg$/.test(p)).length,48);
for(const required of ['/discovery.js','/discovery.css','/assets/campus_v4.json','/assets/catalog.json'])assert.ok(fetches.includes(required),required);
for(const request of [new Request(origin+'/api/state'),new Request(origin+'/api/action',{method:'POST'}),new Request(origin+'/signin-with-chatgpt'),new Request(origin+'/?return_to=%2F'),new Request('https://outside.test/app.js')]) {
  let intercepted=false;listeners.fetch({request,respondWith(){intercepted=true}});assert.equal(intercepted,false,request.url);
}
failNetwork=true;
let fallback;listeners.fetch({request:new Request(origin+'/'),respondWith(p){fallback=p}});
assert.equal((await fallback).status,200);
for(const store of stores.values())for(const key of store.keys())assert.equal(key.startsWith('/api/')||key.includes('signin')||key.includes('?'),false,key);
const report={status:'passed',checks:['standalone manifest','three PNG icon dimensions','public-file precache','48 gemstone photos','discovery and campus public files','API and auth excluded','POST excluded','query URLs excluded','cross-origin excluded','offline shell fallback'],public_cached_files:[...stores.values()][0].size};
const reportDir=path.join(root,'../reports/qa_online_v4_20261125');await fs.mkdir(reportDir,{recursive:true});await fs.writeFile(path.join(reportDir,'pwa_checks.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report));
