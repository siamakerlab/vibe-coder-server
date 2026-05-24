// v0.39.0 — vibe-coder-server PWA service worker.
// v0.46.0 — adds push event handler (Web Push, payload-less).
//
// Minimal "cache-first for static assets, network-first for HTML" strategy.
// Avoid caching anything under /api/* or /ws/* (real-time state) and /admin/*
// SSR pages (always fresh).
//
// Bump CACHE_VERSION on each release to invalidate old SW caches.
const CACHE_VERSION = 'vibe-coder-v0.46.0';
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

// v0.46.0 — Web Push handler. The server sends payload-less push frames so we
// just show a generic notification; click opens the dashboard.
self.addEventListener('push', (event) => {
  let title = 'Vibe Coder';
  let body = '서버에서 알림이 도착했습니다.';
  try {
    if (event.data) {
      const j = event.data.json();
      title = j.title || title;
      body = j.body || body;
    }
  } catch (_) { /* payload-less or non-JSON — ignore */ }
  event.waitUntil(self.registration.showNotification(title, {
    body: body,
    icon: '/static/icon.png',
    badge: '/static/icon.png',
    tag: 'vibe-coder',
  }));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(self.clients.matchAll({ type: 'window' }).then((wins) => {
    for (const c of wins) {
      if ('focus' in c) return c.focus();
    }
    return self.clients.openWindow('/');
  }));
});
