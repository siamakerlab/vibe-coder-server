// v0.39.0 — vibe-coder-server PWA service worker.
// v0.46.0 — adds push event handler (payload-less Web Push).
// v0.50.0 — payload-encrypted (RFC 8291) push event reads title/body/url
// from event.data.json(); falls back to generic title/body when no payload
// is attached (legacy subscriptions or fallback path).
//
// Minimal "cache-first for static assets, network-first for HTML" strategy.
// Avoid caching anything under /api/* or /ws/* (real-time state) and /admin/*
// SSR pages (always fresh).
//
// Bump CACHE_VERSION on each release to invalidate old SW caches.
// v1.86.0 — v0.50.0 → v1.86.0. 구 SW 가 console-render.js?v=1.85.0(null 버전)을
// cache-first 로 박제해 ?v 캐시버스트가 무력화되던 문제 회수(아래 fetch 의 ?v= 우회와 짝).
const CACHE_VERSION = 'vibe-coder-v1.160.1';
const STATIC_CACHE = `static-${CACHE_VERSION}`;
const STATIC_ASSETS = [
  '/static/admin.css',
  '/static/icon.png',
  '/static/keyboard.js',
  '/static/highlight.min.js',
  '/static/highlight-github-dark.min.css',
  '/static/manifest.json',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(STATIC_CACHE).then((cache) => cache.addAll(STATIC_ASSETS))
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  // Drop caches from older versions.
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys
          .filter((k) => k !== STATIC_CACHE)
          .map((k) => caches.delete(k))
      )
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;

  const url = new URL(req.url);

  // Don't cache API / WS / app data.
  if (url.pathname.startsWith('/api/') ||
      url.pathname.startsWith('/ws/') ||
      url.pathname.startsWith('/admin/')) {
    return;  // default network passthrough
  }

  // v1.86.0 — 버전 쿼리(?v=)가 붙은 자산(console-render.js, admin.css 등)은 HTML 의
  // ?v 가 캐시버스트 SSOT 이므로 SW 캐시를 우회(network)한다. cache-first 로 박제하면
  // ?v 가 바뀌어도 구버전(예: null 혼입 console-render.js)이 계속 서빙되는 문제 발생.
  if (url.pathname.startsWith('/static/') && url.search.indexOf('v=') !== -1) {
    return;  // network passthrough — 브라우저 HTTP 캐시(?v 기반)만 사용
  }

  // Static assets — cache-first.
  if (url.pathname.startsWith('/static/')) {
    event.respondWith(
      caches.match(req).then((cached) => cached || fetch(req).then((res) => {
        // Opportunistic cache fill for static assets not in STATIC_ASSETS.
        if (res.ok) {
          const clone = res.clone();
          caches.open(STATIC_CACHE).then((cache) => cache.put(req, clone));
        }
        return res;
      }))
    );
    return;
  }

  // SSR pages — let the browser handle (no cache). Online-first by default.
});

// v0.46.0 — Web Push handler. payload-less mode shows a generic notification.
// v0.50.0 — when the server attaches an RFC 8291 aes128gcm payload, the
// browser transparently decrypts it before invoking `push` and `event.data`
// is the cleartext JSON. We surface real title/body/url from it.
self.addEventListener('push', (event) => {
  let title = 'Vibe Coder';
  let body = '서버에서 알림이 도착했습니다.';
  let url = '/';
  try {
    if (event.data) {
      const j = event.data.json();
      if (j.title) title = j.title;
      if (j.body) body = j.body;
      if (j.url) url = j.url;
    }
  } catch (_) { /* payload-less or non-JSON — ignore */ }
  event.waitUntil(self.registration.showNotification(title, {
    body: body,
    icon: '/static/icon.png',
    badge: '/static/icon.png',
    tag: 'vibe-coder',
    data: { url: url },
  }));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const target = (event.notification.data && event.notification.data.url) || '/';
  event.waitUntil(self.clients.matchAll({ type: 'window' }).then((wins) => {
    for (const c of wins) {
      if ('focus' in c) {
        if (c.url.endsWith(target)) return c.focus();
      }
    }
    return self.clients.openWindow(target);
  }));
});
