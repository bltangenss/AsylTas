'use strict';
// Public application files only. Accounts, sessions, API responses and requests
// that change data are deliberately outside this service worker's cache.
const CACHE_PREFIX = 'asyl-tas-public-';
const CACHE_NAME = CACHE_PREFIX + '20261125-catalog48-gps-v4';
const PUBLIC_FILES = [
  '/', '/index.html', '/style.css', '/mobile.css', '/discovery.css', '/app.js', '/discovery.js', '/pwa.js',
  '/manifest.webmanifest', '/assets/logo.svg', '/assets/apple-touch-icon.png',
  '/assets/app-icon-192.png', '/assets/app-icon-512.png', '/assets/photo_credits.json',
  '/assets/catalog.json', '/assets/campus_v4.json',
  ...Array.from({length: 48}, (_, i) => '/assets/stone_' + (i + 1) + '.jpg')
];
const ALLOWED = new Set(PUBLIC_FILES);
const NETWORK_FIRST = new Set(['/', '/index.html', '/style.css', '/mobile.css', '/discovery.css', '/app.js', '/discovery.js', '/pwa.js', '/manifest.webmanifest', '/assets/catalog.json', '/assets/campus_v4.json', '/assets/photo_credits.json']);
function cacheable(response, pathname) {
  if (!response || !response.ok || response.redirected || !['basic', 'default'].includes(response.type)) return false;
  const type = response.headers.get('Content-Type') || '';
  if (pathname === '/' || pathname.endsWith('.html')) return type.includes('text/html');
  if (pathname.endsWith('.js')) return type.includes('javascript');
  if (pathname.endsWith('.css')) return type.includes('text/css');
  if (pathname.endsWith('.json') || pathname.endsWith('.webmanifest')) return /json|manifest/.test(type);
  return type.startsWith('image/');
}
self.addEventListener('install', event => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE_NAME);
    await Promise.all(PUBLIC_FILES.map(async pathname => {
      const response = await fetch(new Request(pathname, {cache: 'reload', credentials: 'omit'}));
      if (!cacheable(response, pathname)) throw new Error('Public asset unavailable: ' + pathname);
      await cache.put(pathname, response);
    }));
  })());
  // New versions wait for existing tabs to close; an active form is never reset.
});
self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    for (const name of await caches.keys()) {
      if (name.startsWith(CACHE_PREFIX) && name !== CACHE_NAME) await caches.delete(name);
    }
    await self.clients.claim();
  })());
});
self.addEventListener('fetch', event => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin || url.search || !ALLOWED.has(url.pathname)) return;
  event.respondWith((async () => {
    const cache = await caches.open(CACHE_NAME);
    if (!NETWORK_FIRST.has(url.pathname)) {
      const cached = await cache.match(url.pathname);
      if (cached) return cached;
    }
    try {
      const response = await fetch(request);
      if (cacheable(response, url.pathname)) {
        try { await cache.put(url.pathname, response.clone()); }
        catch { /* A full cache must not interrupt a successful online response. */ }
      }
      return response;
    } catch (error) {
      const cached = await cache.match(url.pathname);
      if (cached) return cached;
      throw error;
    }
  })());
});
