'use strict';

// Run with bundled Node. Optional first argument: repository directory.
// Executes the real app.js in a VM with DOM/fetch doubles; this is not a browser
// layout, backend, database, or complete SRS acceptance test.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');

const repository = path.resolve(process.argv[2] || path.join(__dirname, '..'));
const sourcePath = path.join(repository, 'backend/src/main/resources/static/app/app.js');
const source = fs.readFileSync(sourcePath, 'utf8');
const bootstrapOffset = source.lastIndexOf('  (async () => {');
assert.ok(bootstrapOffset > 0, 'Expected the final application bootstrap to exist.');
const executable = source.slice(0, bootstrapOffset)
  + '  globalThis.testHooks = {state, api, submitNotice, closeNotice, handleAuthError, navigate, route, workflowWrite, clearPrivateWorkflow, loadClaim, loadNoticeClaims, applySessionUi};\n})();';

function harness() {
  const elements = new Map();
  const timers = [];
  const element = id => {
    if (!elements.has(id)) {
      elements.set(id, {
        id, value: '', hidden: false, disabled: false, inert: false,
        textContent: '', title: '', children: [], events: {}, attributes: {},
        classList: { toggle() {} },
        focus() { this.focused = true; }, scrollIntoView() {},
        setAttribute(key, value) { this.attributes[key] = value; },
        removeAttribute(key) { delete this.attributes[key]; },
        addEventListener(type, listener) { this.events[type] = listener; },
        append(...children) { this.children.push(...children); },
        replaceChildren(...children) { this.children = [...children]; },
        querySelector() { return null; }, reset() {},
        get childNodes() { return this.children; },
        get childElementCount() { return this.children.length; }
      });
    }
    return elements.get(id);
  };
  const location = { hash: '#/notices/n1', origin: 'http://127.0.0.1:8080' };
  const context = {
    console, URL, Headers, FormData, Blob, AbortController, TextEncoder, Intl, Date,
    crypto: crypto.webcrypto, location,
    history: {
      pushState(_state, _title, hash) { location.hash = hash; },
      replaceState(_state, _title, hash) { location.hash = hash; }
    },
    document: {
      getElementById: element,
      createElement: tag => element(`generated-${elements.size}-${tag}`),
      createTextNode: text => ({ textContent: text }),
      querySelectorAll: () => [], addEventListener() {}
    },
    window: { confirm: () => true, addEventListener() {} },
    navigator: { clipboard: { writeText: async () => {} } },
    setTimeout(callback, milliseconds) {
      const timer = { callback, milliseconds, cleared: false };
      timers.push(timer);
      return timer;
    },
    clearTimeout(timer) { timer.cleared = true; },
    fetch: async () => { throw new Error('No fetch double configured.'); }
  };
  vm.createContext(context);
  vm.runInContext(executable, context, { filename: sourcePath });
  const state = context.testHooks.state;
  state.session = {
    authenticated: true, username: 'alice', displayName: 'Alice',
    csrfToken: 'old-token', csrfHeaderName: 'X-CSRF-TOKEN'
  };
  state.options = { today: '2026-10-25', categories: [], areas: [] };
  state.view = 'editor';
  state.lastHash = location.hash;
  state.editor = {
    id: 'n1', version: 1, existing: [], retained: new Set(), files: [],
    dirty: true, request: null
  };
  for (const [id, value] of Object.entries({
    'notice-type': 'LOST', 'notice-title': 'Blue bottle near library',
    'notice-category': 'BOTTLE', 'notice-date': '2026-10-25',
    'notice-area': 'LIBRARY',
    'notice-description': 'A blue water bottle lost near the library entrance.'
  })) element(id).value = value;

  const response = (status, data) => ({
    status, ok: status >= 200 && status < 300,
    text: async () => data === null ? '' : JSON.stringify(data)
  });
  const detail = (status = 'OPEN', version = 2) => ({
    id: 'n1', title: 'Blue bottle near library', type: 'LOST',
    category: 'BOTTLE', area: 'LIBRARY', occurrenceDate: '2026-10-25',
    description: 'A blue water bottle lost near the library entrance.',
    status, version, materialVersion: version,
    canEdit: status === 'OPEN', canClose: status === 'OPEN', photos: [], history: [],
    createdAt: '2026-10-25T02:00:00Z', updatedAt: '2026-10-25T02:00:00Z'
  });
  return { context, state, element, timers, response, detail };
}

const submitEvent = () => ({ preventDefault() {} });
const tests = [];
const test = (name, callback) => tests.push({ name, callback });

test('Hung notice write times out; draft/key retained, controls restored, no replay', async () => {
  const h = harness(), calls = [];
  h.context.fetch = (url, options) => {
    calls.push([url, options.method]);
    return new Promise((_resolve, reject) => {
      options.signal.addEventListener('abort', () => reject(new Error('Aborted')));
    });
  };
  const pending = h.context.testHooks.submitNotice(submitEvent());
  assert.equal(h.state.busy, true);
  assert.equal(h.element('notice-fields').disabled, true);
  assert.equal(h.timers[0].milliseconds, 15000);
  h.timers[0].callback();
  await pending;
  assert.equal(h.state.busy, false);
  assert.equal(h.element('notice-fields').disabled, false);
  assert.equal(h.state.editor.dirty, true);
  assert.ok(h.state.editor.request.key);
  assert.match(h.element('editor-error').textContent, /timed out/);
  assert.equal(calls.length, 1);
});

test('Committed edit uses response detail and performs no follow-up GET', async () => {
  const h = harness(), calls = [];
  h.context.fetch = async (url, options) => {
    calls.push([url, options.method]);
    if (options.method === 'GET') throw new Error('Read unavailable');
    return h.response(200, h.detail());
  };
  await h.context.testHooks.submitNotice(submitEvent());
  assert.equal(calls.length, 1);
  assert.equal(calls[0][1], 'PUT');
  assert.equal(h.state.view, 'detail');
  assert.equal(h.state.detail.version, 2);
  assert.match(h.element('app-message').textContent, /Changes saved/);
});

test('Committed archive uses response detail and performs no follow-up GET', async () => {
  const h = harness(), calls = [];
  h.state.view = 'detail'; h.state.detail = h.detail();
  h.element('close-reason').value = 'Owner no longer needs this notice';
  h.context.fetch = async (url, options) => {
    calls.push([url, options.method]);
    if (options.method === 'GET') throw new Error('Read unavailable');
    return h.response(200, h.detail('CLOSED', 3));
  };
  await h.context.testHooks.closeNotice(submitEvent());
  assert.equal(calls.length, 1);
  assert.equal(h.state.detail.status, 'CLOSED');
  assert.match(h.element('app-message').textContent, /archived as Closed/);
});

test('Pending logout locks workspace/navigation, then releases lock on success', async () => {
  const h = harness(); let release;
  h.state.view = 'list'; const original = h.state.editor;
  h.context.fetch = (url, options) => url === '/api/logout'
    ? new Promise(resolve => { release = () => resolve(h.response(204, null)); })
    : Promise.resolve(h.response(200, {
      authenticated: false, csrfToken: 'new-token', csrfHeaderName: 'X-CSRF-TOKEN'
    }));
  const pending = h.element('logout-button').events.click();
  assert.equal(h.state.busy, true);
  assert.equal(h.state.signingOut, true);
  assert.equal(h.element('workspace').inert, true);
  h.context.testHooks.navigate('/new');
  assert.equal(h.context.location.hash, '#/notices/n1');
  assert.equal(h.state.editor, original);
  release(); await pending;
  assert.equal(h.state.busy, false);
  assert.equal(h.element('workspace').inert, false);
  assert.equal(h.state.session.authenticated, false);
  assert.equal(h.state.editor, null);
});

test('Failed logout restores interaction and preserves the original draft', async () => {
  const h = harness(), original = h.state.editor;
  h.context.fetch = async () => { throw new Error('Offline'); };
  await h.element('logout-button').events.click();
  assert.equal(h.state.editor, original);
  assert.equal(h.state.session.authenticated, true);
  assert.equal(h.state.busy, false);
  assert.equal(h.element('workspace').inert, false);
});

for (const status of [401, 403]) {
  test(`${status} plus signed-out session preserves draft and never replays the write`, async () => {
    const h = harness(), calls = [], original = h.state.editor;
    h.context.fetch = async (url, options) => {
      calls.push([url, options.method]);
      return url === '/api/session'
        ? h.response(200, { authenticated: false, csrfToken: 'fresh', csrfHeaderName: 'X-CSRF-TOKEN' })
        : h.response(status, { code: 'DENIED', message: 'Original authorization error', fieldErrors: {} });
    };
    await h.context.testHooks.submitNotice(submitEvent());
    assert.equal(calls.length, 2);
    assert.equal(h.state.editor, original);
    assert.equal(h.state.editor.dirty, true);
    assert.equal(h.state.session.authenticated, false);
    assert.equal(h.element('workspace').hidden, true);
  });
}

test('Real 403 preserves its denial while refreshing CSRF, without replay', async () => {
  const h = harness(), calls = [];
  h.context.fetch = async (url, options) => {
    calls.push([url, options.method]);
    return url === '/api/session'
      ? h.response(200, { authenticated: true, username: 'alice', displayName: 'Alice', csrfToken: 'fresh', csrfHeaderName: 'X-CSRF-TOKEN' })
      : h.response(403, { code: 'FORBIDDEN', message: 'Original ownership denial', fieldErrors: {} });
  };
  await h.context.testHooks.submitNotice(submitEvent());
  assert.equal(calls.length, 2);
  assert.equal(h.state.session.authenticated, true);
  assert.equal(h.state.session.csrfToken, 'fresh');
  assert.equal(h.element('editor-error').textContent, 'Original ownership denial');
});

test('401 plus failed session probe cannot retain stale authenticated state', async () => {
  const h = harness();
  h.context.fetch = async url => {
    if (url === '/api/session') throw new Error('Offline');
    return h.response(401, { message: 'Sign in required', fieldErrors: {} });
  };
  await h.context.testHooks.submitNotice(submitEvent());
  assert.equal(h.state.session.authenticated, false);
  assert.equal(h.element('workspace').hidden, true);
  assert.equal(h.state.editor.dirty, true);
});

function fillRegistration(h) {
  for (const [id, value] of Object.entries({
    'register-username': 'new_user', 'register-display': 'Test User',
    'register-password': 'campus-demo-2026'
  })) h.element(id).value = value;
}

test('Registration 403 refreshes CSRF and keeps input/error without replay', async () => {
  const h = harness(), calls = [];
  h.state.session = { authenticated: false, csrfToken: 'old', csrfHeaderName: 'X-CSRF-TOKEN' };
  fillRegistration(h);
  h.context.fetch = async (url, options) => {
    calls.push([url, options.method]);
    return url === '/api/session'
      ? h.response(200, { authenticated: false, csrfToken: 'fresh', csrfHeaderName: 'X-CSRF-TOKEN' })
      : h.response(403, { message: 'Original CSRF error', fieldErrors: {} });
  };
  await h.element('register-form').events.submit(submitEvent());
  assert.equal(calls.length, 2);
  assert.equal(h.state.session.csrfToken, 'fresh');
  assert.equal(h.element('register-username').value, 'new_user');
  assert.equal(h.element('register-password').value, 'campus-demo-2026');
  assert.equal(h.element('register-error').textContent, 'Original CSRF error');
  assert.equal(h.state.busy, false);
});

test('Registration without a token reads session before its first write', async () => {
  const h = harness(), calls = [];
  h.state.session = null; fillRegistration(h);
  h.context.fetch = async (url, options) => {
    calls.push([url, options.method, options.headers.get('X-CSRF-TOKEN')]);
    return url === '/api/session'
      ? h.response(200, { authenticated: false, csrfToken: 'fresh', csrfHeaderName: 'X-CSRF-TOKEN' })
      : h.response(201, { username: 'new_user' });
  };
  await h.element('register-form').events.submit(submitEvent());
  assert.equal(calls[0][0], '/api/session');
  assert.equal(calls[1][2], 'fresh');
  assert.match(h.element('app-message').textContent, /Account created/);
});

test('Request deadline also covers a hung response body', async () => {
  const h = harness(); let bodyWaiting = false;
  h.context.fetch = async (_url, options) => ({
    status: 200, ok: true,
    text: () => {
      bodyWaiting = true;
      return new Promise((_resolve, reject) => {
        options.signal.addEventListener('abort', () => reject(new Error('Body aborted')));
      });
    }
  });
  const pending = h.context.testHooks.api('/api/notices');
  // Let the actual api() advance past fetch() and enter response.text().
  await new Promise(setImmediate);
  assert.equal(bodyWaiting, true);
  h.timers[0].callback();
  await assert.rejects(pending, /timed out/);
});


test('Workflow timeout retries same action key without an automatic mutation replay', async () => {
  const h = harness(), calls = []; let saved = 0;
  h.context.fetch = (url, options) => {
    calls.push([url, options.headers.get('Idempotency-Key')]);
    return new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => reject(new Error('timeout'))));
  };
  const payload = {version: 3, approvalRound: 1}, error = h.element('case-error');
  const pending = h.context.testHooks.workflowWrite('/api/claims/c1/confirm-return', payload, error, () => saved++);
  h.timers.at(-1).callback(); await pending;
  assert.equal(calls.length, 1); assert.equal(saved, 0); assert.match(error.textContent, /timed out/);
  assert.equal(h.state.busy, false); assert.equal(h.element('workspace').inert, false);
  h.context.fetch = async (url, options) => { calls.push([url, options.headers.get('Idempotency-Key')]); return h.response(200, {id: 'c1'}); };
  await h.context.testHooks.workflowWrite('/api/claims/c1/confirm-return', payload, error, () => saved++);
  assert.equal(calls[0][1], calls[1][1]); assert.equal(saved, 1);
});

test('Successful workflow write followed by read failure remains explicitly saved', async () => {
  const h = harness(); let calls = 0;
  h.context.fetch = async () => { calls++; return h.response(200, {id: 'r1'}); };
  await h.context.testHooks.workflowWrite('/api/moderation/reports/r1/resolve', {version: 1}, h.element('case-error'), () => { throw new Error('read failed'); });
  assert.equal(calls, 1); assert.match(h.element('app-message').textContent, /操作已保存/);
  assert.equal(h.element('case-error').textContent, ''); assert.equal(h.state.busy, false);
});

test('Opening an unauthorized copied case link clears previous private content', async () => {
  const h = harness(); const secret = h.element('secret'); secret.textContent = 'private old case';
  h.element('workflow-content').append(secret);
  h.context.fetch = async () => h.response(404, {message: 'Not found'});
  await assert.rejects(h.context.testHooks.loadClaim('someone-elses-case'), /Not found/);
  assert.ok(!h.element('workflow-content').children.includes(secret));
  assert.equal(h.element('workflow-heading').textContent, '记录不可用');
});

test('Sign-out clears private workflow case content and cached request keys', async () => {
  const h = harness(), keys = [];
  h.context.fetch = async (_url, options) => { keys.push(options.headers.get('Idempotency-Key')); throw new Error('offline'); };
  await h.context.testHooks.workflowWrite('/api/claims/c1/messages', {content: 'secret', version: 1}, h.element('case-error'), () => {});
  h.element('workflow-content').append(h.element('secret')); h.context.testHooks.clearPrivateWorkflow();
  assert.equal(h.element('workflow-content').children.length, 0);
  await h.context.testHooks.workflowWrite('/api/claims/c1/messages', {content: 'secret', version: 1}, h.element('case-error'), () => {});
  assert.notEqual(keys[0], keys[1]);
});

test('Student sessions do not expose moderator navigation', async () => {
  const h = harness(); h.state.session.role = 'STUDENT'; h.context.testHooks.applySessionUi();
  assert.equal(h.element('moderation-nav').hidden, true);
  h.state.session.role = 'MODERATOR'; h.context.testHooks.applySessionUi(); assert.equal(h.element('moderation-nav').hidden, false);
});

test('A stale notice callback cannot reinsert private controls after navigation', async () => {
  const h = harness(); let calls = 0; h.state.routeToken = 12;
  h.element('claims-panel').append(h.element('current-record'));
  h.context.fetch = async () => { calls++; return h.response(200, []); };
  await h.context.testHooks.loadNoticeClaims({...h.detail(), type: 'FOUND'}, 11);
  assert.equal(calls, 0); assert.equal(h.element('claims-panel').children[0].id, 'current-record');
});

(async () => {
  console.log('T10 + T19 frontend VM regression');
  console.log(`Executed at: ${new Date().toISOString()}`);
  console.log(`Node: ${process.version}`);
  console.log(`Executable: ${process.execPath}`);
  console.log(`Production source: ${sourcePath}`);
  console.log(`Source SHA-256: ${crypto.createHash('sha256').update(source).digest('hex')}`);
  console.log('Scope: real app.js control flow with DOM/fetch doubles and virtual deadline advancement.');
  console.log('Excludes browser rendering, real network timing, backend/database and complete SRS acceptance.');
  let passed = 0, failed = 0;
  for (const { name, callback } of tests) {
    try { await callback(); passed += 1; console.log(`PASS ${name}`); }
    catch (error) {
      failed += 1; console.error(`FAIL ${name}`); console.error(error.stack || error);
    }
  }
  console.log(`Result: ${passed} passed, ${failed} failed, ${tests.length} total.`);
  if (failed) process.exitCode = 1;
})().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
