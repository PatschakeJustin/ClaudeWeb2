'use strict';
const test = require('node:test');
const assert = require('node:assert');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createApp } = require('../server.js');

async function startHub(extra = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'hub-'));
  const notes = [];
  const handler = createApp({ dataDir: dir, registration: 'first-user-only', ...extra },
    { notify: async (t, l) => { notes.push([t, l]); return true; } });
  const server = http.createServer(handler);
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const call = async (method, p, body, token) => {
    const res = await fetch(base + p, {
      method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    return { status: res.status, json: await res.json() };
  };
  return { server, call, notes };
}

const key = (c) => c.repeat(64);
const salt = 'ab'.repeat(16);

test('register, login, vault sync with version conflicts', async () => {
  const { server, call } = await startHub();
  try {
    assert.equal((await call('GET', '/api/info')).json.registration, 'open');
    const reg = await call('POST', '/api/register', { email: 'Anna@Example.com', authKey: key('a'), salt });
    assert.equal(reg.status, 200); assert.ok(reg.json.admin);
    // first-user-only: second registration is refused
    assert.equal((await call('POST', '/api/register', { email: 'b@x.de', authKey: key('b'), salt })).status, 403);
    assert.equal((await call('GET', '/api/info')).json.registration, 'closed');

    assert.equal((await call('POST', '/api/prelogin', { email: 'anna@example.com' })).json.salt, salt);
    const fake = (await call('POST', '/api/prelogin', { email: 'nobody@x.de' })).json.salt;
    assert.match(fake, /^[0-9a-f]{32}$/);
    assert.equal((await call('POST', '/api/prelogin', { email: 'nobody@x.de' })).json.salt, fake); // stable

    assert.equal((await call('POST', '/api/login', { email: 'anna@example.com', authKey: key('c') })).status, 401);
    const login = await call('POST', '/api/login', { email: 'anna@example.com', authKey: key('a') });
    assert.equal(login.status, 200);
    const t = login.json.token;

    assert.equal((await call('GET', '/api/vault')).status, 401);
    assert.deepEqual((await call('GET', '/api/vault', null, t)).json, { version: 0, data: null, updatedAt: null });
    const put1 = await call('PUT', '/api/vault', { baseVersion: 0, data: 'Y2lwaGVy' }, t);
    assert.equal(put1.json.version, 1);
    const conflict = await call('PUT', '/api/vault', { baseVersion: 0, data: 'eA==' }, t);
    assert.equal(conflict.status, 409); assert.equal(conflict.json.version, 1);
    assert.equal((await call('GET', '/api/vault', null, t)).json.data, 'Y2lwaGVy');
    assert.equal((await call('GET', '/api/vault', null, t + 'x')).status, 401);
  } finally { server.close(); }
});

test('widget store and notifications', async () => {
  const { server, call, notes } = await startHub({ registration: 'open' });
  try {
    const a = (await call('POST', '/api/register', { email: 'a@x.de', authKey: key('a'), salt })).json.token;
    const b = (await call('POST', '/api/register', { email: 'b@x.de', authKey: key('b'), salt })).json.token;
    assert.equal((await call('POST', '/api/widgets', { name: 'X', definition: {} })).status, 401);
    const w = (await call('POST', '/api/widgets', { name: 'Server-Last', description: 'Zeigt die Last', type: 'command',
      definition: { kind: 'command', command: 'uptime' } }, a)).json;
    assert.equal(w.author, 'a'); assert.equal(w.installs, 0); assert.equal(w.owner, undefined);
    await call('POST', `/api/widgets/${w.id}/install`);
    const list = (await call('GET', '/api/widgets?q=last')).json;
    assert.equal(list.length, 1); assert.equal(list[0].installs, 1);
    assert.equal((await call('DELETE', `/api/widgets/${w.id}`, null, b)).status, 403);
    assert.equal((await call('DELETE', `/api/widgets/${w.id}`, null, a)).json.deleted, true);
    assert.equal((await call('GET', '/api/widgets')).json.length, 0);

    assert.equal((await call('POST', '/api/notify', { text: 'Aufgabe fertig', level: 'success' }, a)).json.sent, true);
    assert.deepEqual(notes, [['Aufgabe fertig', 'success']]);
    assert.equal((await call('GET', '/api/nope')).status, 404);
  } finally { server.close(); }
});
