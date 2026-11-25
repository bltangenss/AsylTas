import {handleApi} from './api.js';
import {ASSETS} from './assets.generated.js';
const SECURITY={
  'X-Content-Type-Options':'nosniff',
  'Referrer-Policy':'strict-origin-when-cross-origin',
  'X-Frame-Options':'DENY',
  'Content-Security-Policy':"default-src 'self'; script-src 'self'; worker-src 'self'; manifest-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; frame-src https://www.openstreetmap.org; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
  'Permissions-Policy':'camera=(), microphone=(), geolocation=(self)'
};
function secure(response,request){
  const headers=new Headers(response.headers);
  for(const [name,value] of Object.entries(SECURITY))headers.set(name,value);
  if(new URL(request.url).protocol==='https:')headers.set('Strict-Transport-Security','max-age=15552000');
  return new Response(response.body,{status:response.status,headers});
}
export default {
  async fetch(request,env){
    try{
      const url=new URL(request.url);
      if(url.pathname.startsWith('/api/'))return secure(await handleApi(request,env),request);
      if(!['GET','HEAD'].includes(request.method))return secure(new Response('Әдіске рұқсат жоқ.',{status:405,headers:{Allow:'GET, HEAD'}}),request);
      const key=url.pathname==='/'?'/index.html':url.pathname;
      if(!Object.hasOwn(ASSETS,key))return secure(new Response('Бет табылмады.',{status:404,headers:{'Content-Type':'text/plain; charset=utf-8'}}),request);
      const asset=ASSETS[key];
      const headers={'Content-Type':asset.type,'ETag':asset.etag,'Cache-Control':/\.(jpg|png|svg)$/.test(key)?'public, max-age=86400':'no-cache'};
      if(key==='/sw.js')headers['Service-Worker-Allowed']='/';
      if(request.headers.get('If-None-Match')===asset.etag)return secure(new Response(null,{status:304,headers}),request);
      headers['Content-Length']=String(asset.size);
      const bytes=request.method==='HEAD'?null:Uint8Array.from(atob(asset.base64),c=>c.charCodeAt(0));
      return secure(new Response(bytes,{headers}),request);
    }catch(error){
      console.error('Asyl Tas request failed',error?.name || 'Error');
      return secure(Response.json({ok:false,error:'Сайт деректерін қазір ашу мүмкін болмады. Қайта көріңіз.'},{status:500,headers:{'Cache-Control':'no-store'}}),request);
    }
  }
};
