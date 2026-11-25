import fs from 'node:fs/promises';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const runtime=process.env.ASYL_NODE_RUNTIME || 'C:/Program Files/WindowsApps/OpenAI.CodexPrimaryRuntime.v26-1123-915-0_26.1123.915.0_x64__3k8sg7r9htsxt/dependencies/node';
const require=createRequire(path.join(runtime,'package.json'));
const {chromium}=require('playwright');
// Preserve the existing hand-drawn brand glyph. The full square background and
// centered diamond keep all foreground artwork inside the maskable safe area.
const source=(await fs.readFile(path.join(root,'public/assets/logo.svg'),'utf8'))
  .replace('viewBox="0 0 48 48"','viewBox="0 0 48 48" width="512" height="512"')
  .replace(' rx="13"','');
const browser=await chromium.launch({executablePath:process.env.ASYL_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
try {
  const page=await browser.newPage();
  for(const [size,name] of [[180,'apple-touch-icon.png'],[192,'app-icon-192.png'],[512,'app-icon-512.png']]) {
    const base64=await page.evaluate(async({source,size})=>{
      const img=new Image();img.src='data:image/svg+xml;base64,'+btoa(source);await img.decode();
      const canvas=document.createElement('canvas');canvas.width=size;canvas.height=size;
      const context=canvas.getContext('2d');context.drawImage(img,0,0,size,size);
      return canvas.toDataURL('image/png').split(',')[1];
    },{source,size});
    const bytes=Buffer.from(base64,'base64');
    await fs.writeFile(path.join(root,'public/assets',name),bytes);
    console.log(JSON.stringify({file:name,size,sha256:createHash('sha256').update(bytes).digest('hex')}));
  }
}finally{await browser.close();}
