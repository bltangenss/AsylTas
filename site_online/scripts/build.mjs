import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {build} from 'esbuild';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json; charset=utf-8','.webmanifest':'application/manifest+json; charset=utf-8','.txt':'text/plain; charset=utf-8','.svg':'image/svg+xml','.jpg':'image/jpeg','.png':'image/png'};
const assets={};
async function walk(folder){
  for(const entry of await fs.readdir(folder,{withFileTypes:true})){
    const absolute=path.join(folder,entry.name);
    if(entry.isDirectory())await walk(absolute);
    else if(entry.isFile()){
      const relative=path.relative(path.join(root,'public'),absolute).split(path.sep).join('/');
      const type=mime[path.extname(relative)];
      if(!type)throw new Error('Unsupported public asset: '+relative);
      const bytes=await fs.readFile(absolute);
      assets['/'+relative]={type,base64:bytes.toString('base64'),size:bytes.length,etag:'"'+createHash('sha256').update(bytes).digest('hex')+'"'};
    }else throw new Error('Public assets must be regular files');
  }
}
await walk(path.join(root,'public'));
const catalog=JSON.parse(await fs.readFile(path.join(root,'public/assets/catalog.json'),'utf8'));
if(!assets['/index.html']||catalog.length!==48||catalog.some(stone=>!assets[stone.photo])||Object.keys(assets).filter(x=>/^\/assets\/stone_\d+\.jpg$/.test(x)).length!==48)throw new Error('Missing HTML or one of the48 gemstone photos');
for(const required of ['/discovery.js','/discovery.css','/assets/campus_v4.json'])if(!assets[required])throw new Error('Missing public feature asset: '+required);
await fs.writeFile(path.join(root,'worker/assets.generated.js'),'export const ASSETS = '+JSON.stringify(assets)+';\n');
await fs.mkdir(path.join(root,'dist/server'),{recursive:true});
await fs.mkdir(path.join(root,'dist/.openai'),{recursive:true});
await build({entryPoints:[path.join(root,'worker/index.js')],bundle:true,format:'esm',platform:'browser',target:'es2022',outfile:path.join(root,'dist/server/index.js'),legalComments:'none',minify:false});
await fs.copyFile(path.join(root,'.openai/hosting.json'),path.join(root,'dist/.openai/hosting.json'));
const info={assets:Object.keys(assets).length,asset_bytes:Object.values(assets).reduce((n,x)=>n+x.size,0),worker_bytes:(await fs.stat(path.join(root,'dist/server/index.js'))).size};
console.log(JSON.stringify(info));
