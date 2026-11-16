'use strict';
// Humantize Hub – accounts, end-to-end encrypted vault sync, widget store, notifications.
// No third-party dependencies (the optional Discord bot lives in bot.js).
//
// Security model: the app derives two keys from the password (PBKDF2): an *auth key* that is sent here
// and stored only as a scrypt hash, and an *encryption key* that never leaves the device. The vault is
// AES-GCM encrypted on the device, so this server only ever sees ciphertext.

const fs = require('node:fs');
const path = require('node:path');
const https = require('node:https');
const http = require('node:http');
const crypto = require('node:crypto');

const VERSION = '1.0.0';
const CONFIG_PATH = process.env.HUB_CONFIG || path.join(__dirname, 'config.json');

function loadConfig() {
  const cfg = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
  cfg.port = cfg.port || 8443;
  cfg.host = cfg.host || '0.0.0.0';
  cfg.dataDir = path.resolve(path.dirname(CONFIG_PATH), cfg.dataDir || 'data');
  cfg.registration = cfg.registration || 'first-user-only'; // open | first-user-only | closed
  return cfg;
}

// ---------- storage (small JSON files, written atomically) ----------
class Store {
  constructor(dir) {
    this.dir = dir;
    fs.mkdirSync(path.join(dir, 'vaults'), { recursive: true, mode: 0o700 });
  }
  file(name) { return path.join(this.dir, name); }
  read(name, fallback) {
    try { return JSON.parse(fs.readFileSync(this.file(name), 'utf8')); } catch { return fallback; }
  }
  write(name, value) {
    const target = this.file(name);
    const tmp = target + '.' + process.pid + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(value), { mode: 0o600 });
    fs.renameSync(tmp, target);
  }
  secret() {
    let s = this.read('secret.json', null);
    if (!s) { s = { key: crypto.randomBytes(32).toString('hex') }; this.write('secret.json', s); }
    return Buffer.from(s.key, 'hex');
  }
}

// ---------- helpers ----------
const b64u = (buf) => Buffer.from(buf).toString('base64url');
const normEmail = (e) => String(e || '').trim().toLowerCase();
const validEmail = (e) => /^[^\s@]{1,64}@[^\s@]{1,255}$/.test(e);

function scryptHash(authKey, salt) {
  return crypto.scryptSync(String(authKey), salt, 64, { N: 16384, r: 8, p: 1 }).toString('hex');
}

function makeToken(secret, uid, ttlSec = 30 * 24 * 3600) {
  const payload = b64u(JSON.stringify({ uid, exp: Math.floor(Date.now() / 1000) + ttlSec }));
  const sig = b64u(crypto.createHmac('sha256', secret).update(payload).digest());
  return payload + '.' + sig;
}

function verifyToken(secret, token) {
  if (!token || !token.includes('.')) return null;
  const [payload, sig] = token.split('.');
  const expected = b64u(crypto.createHmac('sha256', secret).update(payload).digest());
  if (sig.length !== expected.length || !crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expected))) return null;
  try {
    const p = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
    return p.exp > Date.now() / 1000 ? p.uid : null;
  } catch { return null; }
}

class HttpError extends Error { constructor(status, msg) { super(msg); this.status = status; } }

async function readJson(req, limit = 8 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0; const chunks = [];
    req.on('data', (c) => { size += c.length; if (size > limit) { reject(new HttpError(413, 'Zu groß')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => {
      if (!chunks.length) return resolve({});
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch { reject(new HttpError(400, 'Ungültiges JSON')); }
    });
    req.on('error', reject);
  });
}

// Simple per-IP limiter for login/registration.
const attempts = new Map();
function rateLimit(ip, max = 20, windowMs = 10 * 60 * 1000) {
  const now = Date.now();
  const a = (attempts.get(ip) || []).filter((t) => now - t < windowMs);
  a.push(now); attempts.set(ip, a);
  if (a.length > max) throw new HttpError(429, 'Zu viele Versuche – bitte später erneut.');
}

// ---------- application ----------
function createApp(cfg, hooks = {}) {
  const store = new Store(cfg.dataDir);
  const secret = store.secret();
  const users = () => store.read('users.json', []);
  const saveUsers = (u) => store.write('users.json', u);
  const widgets = () => store.read('widgets.json', []);
  const saveWidgets = (w) => store.write('widgets.json', w);

  function auth(req) {
    const h = req.headers.authorization || '';
    const uid = verifyToken(secret, h.startsWith('Bearer ') ? h.slice(7) : '');
    if (!uid) throw new HttpError(401, 'Nicht angemeldet');
    const user = users().find((u) => u.id === uid);
    if (!user) throw new HttpError(401, 'Konto existiert nicht mehr');
    return user;
  }

  // Unknown e-mails get a stable fake salt so that prelogin does not reveal which accounts exist.
  const fakeSalt = (email) => crypto.createHmac('sha256', secret).update('salt:' + email).digest('hex').slice(0, 32);
  const publicWidget = (w) => ({
    id: w.id, name: w.name, description: w.description, author: w.author, type: w.type,
    definition: w.definition, installs: w.installs, createdAt: w.createdAt, updatedAt: w.updatedAt,
  });

  const routes = {
    'GET /api/info': async () => ({
      name: 'Humantize Hub', version: VERSION,
      registration: cfg.registration === 'first-user-only' ? (users().length ? 'closed' : 'open') : cfg.registration,
      discord: Boolean(hooks.discordReady && hooks.discordReady()),
    }),

    'POST /api/register': async (req, body, ip) => {
      rateLimit(ip);
      const email = normEmail(body.email);
      if (!validEmail(email)) throw new HttpError(400, 'Ungültige E-Mail-Adresse');
      if (!/^[0-9a-f]{64}$/.test(body.authKey || '') || !/^[0-9a-f]{32}$/.test(body.salt || '')) throw new HttpError(400, 'Ungültige Schlüssel');
      const list = users();
      const open = cfg.registration === 'open' || (cfg.registration === 'first-user-only' && list.length === 0);
      if (!open) throw new HttpError(403, 'Registrierung ist auf diesem Hub geschlossen');
      if (list.some((u) => u.email === email)) throw new HttpError(409, 'Konto existiert bereits');
      const serverSalt = crypto.randomBytes(16).toString('hex');
      const user = {
        id: crypto.randomUUID(), email, salt: body.salt, serverSalt,
        hash: scryptHash(body.authKey, serverSalt), admin: list.length === 0, createdAt: new Date().toISOString(),
        displayName: String(body.displayName || email.split('@')[0]).slice(0, 40),
      };
      list.push(user); saveUsers(list);
      return { token: makeToken(secret, user.id), userId: user.id, admin: user.admin };
    },

    'POST /api/prelogin': async (req, body, ip) => {
      rateLimit(ip, 60);
      const email = normEmail(body.email);
      const user = users().find((u) => u.email === email);
      return { salt: user ? user.salt : fakeSalt(email) };
    },

    'POST /api/login': async (req, body, ip) => {
      rateLimit(ip);
      const email = normEmail(body.email);
      const user = users().find((u) => u.email === email);
      const ok = user && crypto.timingSafeEqual(Buffer.from(scryptHash(body.authKey || '', user.serverSalt), 'hex'), Buffer.from(user.hash, 'hex'));
      if (!ok) throw new HttpError(401, 'E-Mail oder Passwort falsch');
      return { token: makeToken(secret, user.id), userId: user.id, admin: user.admin };
    },

    'GET /api/vault': async (req) => {
      const user = auth(req);
      return store.read(`vaults/${user.id}.json`, { version: 0, data: null, updatedAt: null });
    },

    'PUT /api/vault': async (req, body) => {
      const user = auth(req);
      const cur = store.read(`vaults/${user.id}.json`, { version: 0 });
      if (typeof body.data !== 'string' || body.data.length > 6 * 1024 * 1024) throw new HttpError(400, 'Ungültige Daten');
      if (body.baseVersion !== cur.version) {
        throw Object.assign(new HttpError(409, 'Auf einem anderen Gerät wurde inzwischen gespeichert'), { extra: { version: cur.version } });
      }
      const next = { version: cur.version + 1, data: body.data, updatedAt: new Date().toISOString() };
      store.write(`vaults/${user.id}.json`, next);
      return { version: next.version, updatedAt: next.updatedAt };
    },

    'GET /api/widgets': async (req, body, ip, url) => {
      const q = (url.searchParams.get('q') || '').toLowerCase();
      return widgets()
        .filter((w) => !q || (w.name + ' ' + w.description + ' ' + w.type).toLowerCase().includes(q))
        .sort((a, b) => b.installs - a.installs || b.createdAt.localeCompare(a.createdAt))
        .slice(0, 200).map(publicWidget);
    },

    'POST /api/widgets': async (req, body) => {
      const user = auth(req);
      const name = String(body.name || '').trim().slice(0, 60);
      if (!name) throw new HttpError(400, 'Name fehlt');
      if (typeof body.definition !== 'object' || body.definition === null) throw new HttpError(400, 'Definition fehlt');
      const def = JSON.stringify(body.definition);
      if (def.length > 20000) throw new HttpError(400, 'Definition zu groß');
      const now = new Date().toISOString();
      const list = widgets();
      const w = {
        id: crypto.randomUUID(), owner: user.id, author: user.displayName, name,
        description: String(body.description || '').slice(0, 300), type: String(body.type || 'custom').slice(0, 40),
        definition: body.definition, installs: 0, createdAt: now, updatedAt: now,
      };
      list.push(w); saveWidgets(list);
      return publicWidget(w);
    },

    'POST /api/notify': async (req, body) => {
      auth(req);
      const text = String(body.text || '').slice(0, 1800);
      if (!text) throw new HttpError(400, 'Text fehlt');
      const sent = hooks.notify ? await hooks.notify(text, String(body.level || 'info')) : false;
      return { sent };
    },

    'GET /api/health': async () => ({ ok: true, uptime: process.uptime() }),
  };

  // Routes with an id in the path.
  async function dynamic(method, pathname, req) {
    let m = pathname.match(/^\/api\/widgets\/([0-9a-f-]{36})(\/install)?$/);
    if (m) {
      const list = widgets();
      const w = list.find((x) => x.id === m[1]);
      if (!w) throw new HttpError(404, 'Widget nicht gefunden');
      if (method === 'POST' && m[2]) { w.installs += 1; saveWidgets(list); return publicWidget(w); }
      if (method === 'DELETE' && !m[2]) {
        const user = auth(req);
        if (w.owner !== user.id && !user.admin) throw new HttpError(403, 'Nur der Autor kann das Widget löschen');
        saveWidgets(list.filter((x) => x.id !== w.id)); return { deleted: true };
      }
      if (method === 'GET' && !m[2]) return publicWidget(w);
    }
    return undefined;
  }

  return async function handler(req, res) {
    const url = new URL(req.url, 'https://hub');
    const ip = req.socket.remoteAddress || '?';
    const send = (status, obj) => {
      const data = JSON.stringify(obj);
      res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(data);
    };
    try {
      const key = `${req.method} ${url.pathname}`;
      const body = (req.method === 'POST' || req.method === 'PUT') ? await readJson(req) : {};
      let result;
      if (routes[key]) result = await routes[key](req, body, ip, url);
      else result = await dynamic(req.method, url.pathname, req);
      if (result === undefined) throw new HttpError(404, 'Nicht gefunden');
      send(200, result);
    } catch (e) {
      const status = e.status || 500;
      if (status === 500) console.error(e);
      send(status, Object.assign({ error: status === 500 ? 'Interner Fehler' : e.message }, e.extra || {}));
    }
  };
}

function start() {
  const cfg = loadConfig();
  const hooks = {};
  if (cfg.discord && cfg.discord.token) {
    try {
      const bot = require('./bot.js').startBot(cfg);
      hooks.notify = bot.notify; hooks.discordReady = bot.ready;
    } catch (e) {
      console.error('Discord-Bot konnte nicht starten:', e.message);
    }
  }
  const handler = createApp(cfg, hooks);
  const server = cfg.tls === false
    ? http.createServer(handler)
    : https.createServer({ key: fs.readFileSync(path.resolve(path.dirname(CONFIG_PATH), cfg.key || 'key.pem')),
        cert: fs.readFileSync(path.resolve(path.dirname(CONFIG_PATH), cfg.cert || 'cert.pem')) }, handler);
  server.listen(cfg.port, cfg.host, () => console.log(`Humantize Hub ${VERSION} läuft auf Port ${server.address().port}`));
  return server;
}

module.exports = { createApp, makeToken, verifyToken, VERSION };
if (require.main === module) start();
