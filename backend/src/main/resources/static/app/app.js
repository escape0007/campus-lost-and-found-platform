'use strict';

(() => {
  const $ = id => document.getElementById(id);
  const state = {
    session: null, options: null, scope: 'available', page: 0, list: null,
    detail: null, editor: null, latest: null, busy: false, signingOut: false,
    view: '', lastHash: '', routeToken: 0, previewUrls: [], closeRequest: null
  };
  const statusLabels = {
    OPEN: 'Open', CLAIM_UNDER_REVIEW: 'Claim under review',
    AWAITING_COLLECTION: 'Awaiting collection', RETURNED: 'Returned', CLOSED: 'Closed'
  };
  const fieldLabels = {
    type: 'Notice type', title: 'Title', category: 'Category', occurrenceDate: 'Date',
    approximate: 'Approximate date', area: 'Campus area', description: 'Description', photos: 'Photos'
  };
  const REQUEST_TIMEOUT_MS = 15000;

  class ApiError extends Error {
    constructor(status, data) {
      super(data.message || (status === 401 ? 'Please sign in to continue.' : 'The request could not be completed.'));
      this.status = status; this.code = data.code; this.fields = data.fieldErrors || {};
    }
  }

  function node(tag, className, content) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (content !== undefined && content !== null) element.textContent = String(content);
    return element;
  }

  function uuid() {
    if (crypto.randomUUID) return crypto.randomUUID();
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128;
    const hex = Array.from(bytes, x => x.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }

  async function api(path, options = {}) {
    const method = options.method || 'GET';
    const headers = new Headers(options.headers || {});
    headers.set('Accept', 'application/json');
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
      if (state.session?.csrfToken && state.session?.csrfHeaderName) {
        headers.set(state.session.csrfHeaderName, state.session.csrfToken);
      }
      headers.set('Idempotency-Key', options.requestKey || uuid());
    }
    const controller = new AbortController();
    let timedOut = false;
    const timer = setTimeout(() => { timedOut = true; controller.abort(); }, REQUEST_TIMEOUT_MS);
    let response, text;
    try {
      response = await fetch(path, { credentials: 'same-origin', ...options, method, headers, signal: controller.signal });
      text = await response.text();
    } catch (_) {
      if (timedOut) throw new Error('The request timed out. Your input has been kept. Check the current record or retry the same action when the connection is available.');
      throw new Error('Cannot reach the local application. Your input has been kept. Check that the server is running, then retry.');
    } finally { clearTimeout(timer); }
    let data = {};
    if (text) {
      try { data = JSON.parse(text); }
      catch (_) {
        if (response.ok) throw new Error('The server returned an unexpected response. Your input has been kept; please retry.');
        data = { message: `The server returned an unexpected response (${response.status}). Please retry.` };
      }
    }
    if (!response.ok) throw new ApiError(response.status, data);
    return data;
  }

  function message(text, isError = false, focus = false) {
    const box = $('app-message');
    box.textContent = text || ''; box.hidden = !text;
    box.classList.toggle('error', isError);
    box.setAttribute('role', isError ? 'alert' : 'status');
    if (focus && text) box.focus({ preventScroll: true });
  }

  function formatDate(value, withTime = false) {
    if (!value) return '—';
    if (!withTime && /^\d{4}-\d{2}-\d{2}$/.test(value)) return value;
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return String(value);
    return new Intl.DateTimeFormat('en-GB', {
      timeZone: 'Asia/Macau', day: '2-digit', month: 'short', year: 'numeric',
      ...(withTime ? { hour: '2-digit', minute: '2-digit' } : {})
    }).format(date);
  }

  function optionLabel(kind, value) {
    return state.options?.[kind]?.find(item => item.value === value)?.label || value || '—';
  }

  function photoUrl(photo) {
    if (typeof photo.url !== 'string' || !photo.url) return null;
    try {
      const url = new URL(photo.url, location.origin);
      return url.origin === location.origin && ['http:', 'https:'].includes(url.protocol) ? url.href : null;
    } catch (_) { return null; }
  }

  function badges(notice) {
    const group = node('div', 'badges');
    const type = notice.type === 'FOUND' ? 'Found item' : 'Lost item';
    group.append(node('span', `badge ${notice.type === 'FOUND' ? 'found' : 'lost'}`, type));
    group.append(node('span', `badge status-${String(notice.status || 'OPEN').toLowerCase()}`, statusLabels[notice.status] || notice.status));
    return group;
  }

  function renderPhotos(container, photos, title = 'Item photo') {
    container.replaceChildren();
    (photos || []).forEach((photo, index) => {
      const url = photoUrl(photo);
      if (!url) return;
      const link = node('a'); link.href = url; link.target = '_blank'; link.rel = 'noopener noreferrer';
      link.setAttribute('aria-label', `Open photo ${index + 1} in a new tab`);
      const img = node('img'); img.src = url; img.alt = `${title}, photo ${index + 1}`; img.loading = 'lazy';
      link.append(img); container.append(link);
    });
    container.hidden = !container.childElementCount;
  }

  function setView(view) {
    state.view = view;
    ['list', 'detail', 'editor'].forEach(name => { $(`${name}-view`).hidden = name !== view; });
    $('mine-nav').removeAttribute('aria-current'); $('available-nav').removeAttribute('aria-current');
    if (view === 'list') $(`${state.scope}-nav`).setAttribute('aria-current', 'page');
  }

  function applySessionUi() {
    const authenticated = Boolean(state.session?.authenticated);
    $('login-view').hidden = authenticated; $('workspace').hidden = !authenticated;
    $('logout-button').hidden = !authenticated;
    $('session-status').hidden = !authenticated;
    $('account-name').textContent = authenticated ? state.session.displayName || state.session.username : '';
    $('account-name').title = authenticated ? `${state.session.username} · not campus identity verification` : '';
    return authenticated;
  }

  async function refreshSession() {
    state.session = await api('/api/session');
    return applySessionUi();
  }

  function markSignedOut() {
    state.session = { ...(state.session || {}), authenticated: false, csrfToken: null };
    applySessionUi();
  }

  async function ensureOptions() {
    if (state.options) return;
    state.options = await api('/api/options');
    ['category', 'area'].forEach(kind => {
      const select = $(`notice-${kind}`);
      select.replaceChildren();
      const placeholder = node('option', '', `Select ${kind === 'area' ? 'an area' : 'a category'}`);
      placeholder.value = ''; select.append(placeholder);
      (state.options[kind === 'area' ? 'areas' : 'categories'] || []).forEach(item => {
        const option = node('option', '', item.label); option.value = item.value; select.append(option);
      });
    });
    if (state.options.today) $('notice-date').max = state.options.today;
  }

  async function handleAuthError(error) {
    if (!(error instanceof ApiError) || ![401, 403].includes(error.status)) return false;
    const sessionExpired = error.status === 401;
    if (sessionExpired) {
      markSignedOut();
      state.routeToken += 1;
    }
    let authenticated;
    try {
      // A stale CSRF token can reject a write before session authentication runs.
      // Refresh only the session/token; never replay the failed write automatically.
      authenticated = await refreshSession();
    } catch (_) {
      if (!sessionExpired) return false;
      message('Your session expired. Sign in again; your draft is still here.', true, true);
      $('login-username').focus();
      return true;
    }
    // Keep a genuine permission error while retaining the fresh token for a manual retry.
    if (authenticated) return false;
    if (!sessionExpired) state.routeToken += 1;
    message('Your session expired. Sign in again; your draft is still here.', true, true);
    $('login-username').focus();
    return true;
  }

  function editorDirty() { return state.view === 'editor' && Boolean(state.editor?.dirty); }

  function canLeaveEditor() {
    return !editorDirty() || window.confirm('Leave this page and discard the unsaved draft?');
  }

  function navigate(path) {
    if (state.busy) { message('Please wait for the current action to finish.'); return; }
    const nextHash = `#${path}`;
    if (nextHash === location.hash) {
      if (!canLeaveEditor()) return;
      if (state.editor) state.editor.dirty = false;
      void route(); return;
    }
    location.hash = path;
  }

  function codePoints(value) { return [...value].length; }
  function utf8Bytes(value) { return new TextEncoder().encode(value).length; }
  function clearRegisterErrors() {
    ['username', 'displayName', 'password'].forEach(name => { $(`error-reg-${name}`).textContent = ''; });
    $('register-error').textContent = '';
  }
  function showAuth(mode) {
    const register = mode === 'register';
    $('auth-signin').hidden = register;
    $('auth-register').hidden = !register;
    $('login-error').textContent = '';
    clearRegisterErrors();
    (register ? $('register-username') : $('login-username')).focus();
  }
  function registerProblems(username, displayName, password) {
    const fields = {};
    if (!/^[A-Za-z0-9_]{3,32}$/.test(username)) fields.username = 'Use 3–32 letters, digits or underscores.';
    const displayLength = codePoints(displayName);
    if (displayLength < 2 || displayLength > 40) fields.displayName = 'Use 2–40 Unicode characters.';
    const passwordChars = codePoints(password);
    if (passwordChars < 10 || passwordChars > 72 || utf8Bytes(password) > 72) fields.password = 'Use 10–72 characters, at most 72 bytes. The password is stored without truncation.';
    return fields;
  }
  function applyRegisterErrors(fields) {
    clearRegisterErrors();
    Object.entries(fields).forEach(([name, text]) => {
      const el = $(`error-reg-${name}`);
      if (el) el.textContent = text;
      else $('register-error').textContent = text;
    });
  }

  async function route() {
    if (!location.hash) history.replaceState(null, '', '#/available');
    const currentHash = location.hash || '#/available';
    if (state.signingOut) {
      history.replaceState(null, '', state.lastHash || '#/available');
      return;
    }
    if (state.busy && ['editor', 'detail'].includes(state.view) && state.lastHash && currentHash !== state.lastHash) {
      history.replaceState(null, '', state.lastHash); message('Please wait for the current action to finish.'); return;
    }
    if (state.lastHash && currentHash !== state.lastHash && !canLeaveEditor()) {
      history.replaceState(null, '', state.lastHash); return;
    }
    if (currentHash !== state.lastHash && state.editor) state.editor.dirty = false;
    state.lastHash = currentHash;
    if (!state.session?.authenticated) return;
    const token = ++state.routeToken;
    try {
      await ensureOptions();
      if (token !== state.routeToken) return;
      const path = currentHash.slice(1).split('?')[0] || '/available';
      if (path === '/new') { openEditor(null); return; }
      const match = path.match(/^\/notices\/([^/]+)$/);
      if (match) {
        setView('detail'); state.detail = null;
        $('detail-heading').textContent = 'Loading notice…';
        ['detail-badges', 'detail-facts', 'detail-description', 'detail-photos', 'detail-id', 'detail-version', 'history-list', 'history-count'].forEach(id => $(id).replaceChildren());
        ['edit-notice', 'show-close', 'close-panel', 'detail-lock'].forEach(id => { $(id).hidden = true; });
        await loadDetail(decodeURIComponent(match[1]), token); return;
      }
      state.scope = path === '/available' ? 'available' : 'mine'; state.page = 0;
      await loadList(token);
    } catch (error) {
      if (token !== state.routeToken) return;
      if (state.view === 'detail' && !state.detail) $('detail-heading').textContent = 'Notice unavailable';
      if (!await handleAuthError(error)) message(error.message, true, true);
    }
  }

  async function loadList(token = ++state.routeToken) {
    setView('list');
    $('list-heading').textContent = state.scope === 'mine' ? 'My notices' : 'Available notices';
    $('list-description').textContent = state.scope === 'mine' ? 'Your current notices and archived records, in one place.' : 'Current lost and found notices visible after sign-in. Closed means archived, not returned.';
    $('notice-list').setAttribute('aria-busy', 'true');
    $('notice-list').replaceChildren(node('div', 'loading-state', 'Loading notices…'));
    $('pagination').hidden = true;
    try {
      const result = await api(`/api/notices?scope=${state.scope}&page=${state.page}`);
      if (token !== state.routeToken || !state.session?.authenticated) return;
      state.list = result;
      const list = $('notice-list'); list.replaceChildren();
      const items = result.items || [];
      if (!items.length) {
        const empty = node('div', 'empty-state');
        empty.append(node('h2', '', state.scope === 'mine' ? 'Start with your first notice' : 'No available notices yet'));
        empty.append(node('p', 'muted', state.scope === 'mine' ? 'Lost something or found an item? Add the details and keep the link current.' : 'New notices will appear here when they are available.'));
        const link = node('a', 'button primary', 'Create a notice'); link.href = '#/new'; link.dataset.route = '/new'; empty.append(link); list.append(empty);
      }
      items.forEach(notice => {
        const card = node('article', 'notice-card'); card.append(badges(notice));
        const heading = node('h2'); const link = node('a', '', notice.title);
        link.href = `#/notices/${encodeURIComponent(notice.id)}`; link.dataset.route = `/notices/${encodeURIComponent(notice.id)}`;
        heading.append(link); card.append(heading);
        const facts = node('div', 'card-facts');
        facts.append(node('span', '', optionLabel('categories', notice.category)), node('span', '', optionLabel('areas', notice.area)), node('span', '', `${formatDate(notice.occurrenceDate)}${notice.approximate ? ' · approximate' : ''}`));
        card.append(facts);
        if (notice.description) card.append(node('p', 'card-description', notice.description.length > 140 ? `${notice.description.slice(0, 140)}…` : notice.description));
        const meta = node('div', 'card-meta');
        meta.append(node('span', '', `Updated ${formatDate(notice.updatedAt || notice.createdAt)}`), node('span', '', 'View details →')); card.append(meta); list.append(card);
      });
      const page = Number(result.page ?? state.page);
      $('pagination').hidden = page === 0 && !result.hasNext;
      $('previous-page').disabled = page === 0; $('next-page').disabled = !result.hasNext;
      $('page-label').textContent = `Page ${page + 1}${Number.isFinite(Number(result.totalElements)) ? ` · ${result.totalElements} notices` : ''}`;
    } catch (error) {
      if (token !== state.routeToken) return;
      $('notice-list').replaceChildren(node('div', 'empty-state', 'Notices could not be loaded. Use Refresh to try again.'));
      if (!await handleAuthError(error)) message(error.message, true, true);
    } finally { if (token === state.routeToken) $('notice-list').removeAttribute('aria-busy'); }
  }

  async function loadDetail(id, token = ++state.routeToken) {
    const notice = await api(`/api/notices/${encodeURIComponent(id)}`);
    if (token !== state.routeToken || !state.session?.authenticated) return;
    state.detail = notice; renderDetail();
    $('detail-heading').scrollIntoView({ block: 'start' });
  }

  function showSavedNotice(notice, successText) {
    // Successful writes already return the committed detail/history. Do not turn a
    // failed follow-up read into a failed-write message or issue another mutation.
    state.routeToken += 1;
    state.detail = notice;
    const nextHash = `#/notices/${encodeURIComponent(notice.id)}`;
    if (location.hash !== nextHash) history.pushState(null, '', nextHash);
    state.lastHash = nextHash;
    renderDetail();
    $('detail-heading').scrollIntoView({ block: 'start' });
    message(successText);
  }

  function renderDetail() {
    const notice = state.detail;
    setView('detail');
    $('detail-heading').textContent = notice.title;
    $('detail-badges').replaceChildren(...badges(notice).childNodes);
    const facts = $('detail-facts'); facts.replaceChildren();
    [['Category', optionLabel('categories', notice.category)], ['Campus area', optionLabel('areas', notice.area)], ['Occurrence date', `${formatDate(notice.occurrenceDate)}${notice.approximate ? ' (approximate)' : ''}`]].forEach(([label, value]) => {
      const fact = node('div', '', label); fact.append(node('strong', '', value)); facts.append(fact);
    });
    $('detail-description').textContent = notice.description;
    renderPhotos($('detail-photos'), notice.photos, notice.title);
    $('edit-notice').hidden = !notice.canEdit; $('show-close').hidden = !notice.canClose;
    const lock = notice.lockReason || (!notice.canEdit && ['RETURNED', 'CLOSED'].includes(notice.status) ? 'This record is read-only. Its stable link and change history are preserved.' : '');
    $('detail-lock').textContent = lock; $('detail-lock').hidden = !lock;
    $('detail-id').textContent = `Notice ${notice.id}`;
    $('detail-version').textContent = `Version ${notice.version} · Updated ${formatDate(notice.updatedAt, true)}`;
    $('close-panel').hidden = true;
    const entries = [...(notice.history || [])].sort((a, b) => Number(b.version) - Number(a.version));
    $('history-count').textContent = `${entries.length} ${entries.length === 1 ? 'entry' : 'entries'}`;
    $('history-list').replaceChildren();
    entries.forEach(entry => {
      const item = node('li', 'history-entry');
      const title = node('div', 'history-title');
      title.append(node('span', '', actionLabel(entry.action)), node('span', 'muted', `v${entry.version}`));
      item.append(title, node('p', '', `${entry.actor || 'Account'} · ${formatDate(entry.occurredAt, true)}`));
      const fields = Array.isArray(entry.changedFields) ? entry.changedFields.map(field => fieldLabels[field] || field).join(', ') : entry.changedFields;
      if (fields) item.append(node('p', '', `Changed: ${fields}`));
      if (entry.reason) item.append(node('p', 'history-reason', `Reason: ${entry.reason}`));
      if (entry.status) item.append(node('p', '', `Status: ${statusLabels[entry.status] || entry.status}`));
      $('history-list').append(item);
    });
    if (!entries.length) $('history-list').append(node('li', 'muted', 'No history entries are available.'));
  }

  function actionLabel(action) {
    const labels = { CREATE: 'Notice published', CREATED: 'Notice published', UPDATE: 'Notice updated', UPDATED: 'Notice updated', CLOSE: 'Notice archived', CLOSED: 'Notice archived', ARCHIVE: 'Notice archived', ARCHIVED: 'Notice archived' };
    return labels[action] || String(action || 'Notice changed').replaceAll('_', ' ');
  }

  function clearFieldErrors() {
    document.querySelectorAll('#notice-form .field-error').forEach(element => { element.textContent = ''; });
    document.querySelectorAll('#notice-form [aria-invalid]').forEach(element => { element.removeAttribute('aria-invalid'); });
  }

  function fieldError(field, text) {
    if (['retainedPhotoIds', 'files', 'photoIds'].includes(field) || field.startsWith('photos')) field = 'photos';
    const output = $(`error-${field}`);
    if (!output) return false;
    output.textContent = Array.isArray(text) ? text.join(' ') : String(text);
    const input = field === 'occurrenceDate' ? $('notice-date') : $(`notice-${field}`);
    input?.setAttribute('aria-invalid', 'true');
    return true;
  }

  function updateCounters() {
    $('title-count').textContent = `${Array.from($('notice-title').value).length} / 80`;
    $('description-count').textContent = `${Array.from($('notice-description').value).length} / 1,000`;
  }

  function revokePreviews() {
    state.previewUrls.forEach(url => URL.revokeObjectURL(url)); state.previewUrls = [];
  }

  function openEditor(notice) {
    revokePreviews();
    state.editor = {
      id: notice?.id || null, original: notice, version: notice?.version,
      existing: [...(notice?.photos || [])], retained: new Set((notice?.photos || []).map(photo => String(photo.id))),
      files: [], dirty: false, request: null
    };
    state.latest = null; clearFieldErrors();
    $('conflict-panel').hidden = true; $('latest-version').hidden = true; $('use-latest-version').hidden = true;
    $('notice-form').reset();
    $('notice-type').value = notice?.type || 'LOST'; $('notice-title').value = notice?.title || '';
    $('notice-category').value = notice?.category || ''; $('notice-date').value = notice?.occurrenceDate || state.options?.today || '';
    $('notice-approximate').checked = Boolean(notice?.approximate); $('notice-area').value = notice?.area || '';
    $('notice-description').value = notice?.description || '';
    $('editor-heading').textContent = notice ? 'Edit notice' : 'New notice';
    $('editor-eyebrow').textContent = notice ? `Update version ${notice.version}` : 'Share a notice';
    $('notice-submit').textContent = notice ? 'Save changes' : 'Publish notice';
    $('editor-progress').textContent = ''; $('notice-fields').disabled = false;
    updateCounters(); renderEditorPhotos(); setView('editor');
    $('editor-heading').scrollIntoView({ block: 'start', behavior: 'smooth' });
  }

  function renderEditorPhotos() {
    const editor = state.editor; if (!editor) return;
    revokePreviews();
    const existing = $('existing-photos'); existing.replaceChildren();
    editor.existing.forEach((photo, index) => {
      const choice = node('div', `photo-choice${editor.retained.has(String(photo.id)) ? '' : ' removed'}`);
      const url = photoUrl(photo);
      if (url) { const img = node('img'); img.src = url; img.alt = `Existing item photo ${index + 1}`; choice.append(img); }
      const label = node('label', 'checkbox-label');
      const checkbox = node('input'); checkbox.type = 'checkbox'; checkbox.checked = editor.retained.has(String(photo.id));
      checkbox.addEventListener('change', () => {
        if (checkbox.checked) editor.retained.add(String(photo.id)); else editor.retained.delete(String(photo.id));
        markDirty(); renderEditorPhotos(); validatePhotoCount();
      });
      label.append(checkbox, document.createTextNode('Keep this photo')); choice.append(label); existing.append(choice);
    });
    const additions = $('new-photos'); additions.replaceChildren();
    editor.files.forEach((file, index) => {
      const choice = node('div', 'photo-choice');
      if (['image/jpeg', 'image/png'].includes(file.type)) {
        const url = URL.createObjectURL(file); state.previewUrls.push(url);
        const img = node('img'); img.src = url; img.alt = `New item photo ${index + 1}`; choice.append(img);
      }
      choice.append(node('p', 'photo-name', `${file.name} · ${(file.size / 1024 / 1024).toFixed(2)} MB`));
      const remove = node('button', 'text-button', 'Remove new photo'); remove.type = 'button';
      remove.addEventListener('click', () => { editor.files.splice(index, 1); markDirty(); renderEditorPhotos(); validatePhotoCount(); });
      choice.append(remove); additions.append(choice);
    });
  }

  function markDirty() {
    if (!state.editor) return;
    state.editor.dirty = true; state.editor.request = null;
  }

  function validatePhotoCount() {
    const editor = state.editor; const errors = [];
    if (editor.retained.size + editor.files.length > 3) errors.push('Keep a maximum of 3 existing and new photos combined.');
    editor.files.forEach(file => {
      if (file.size > 5 * 1024 * 1024) errors.push(`${file.name}: each photo must be 5 MB or smaller.`);
      if (!['image/jpeg', 'image/png'].includes(file.type)) errors.push(`${file.name}: choose a JPEG or PNG image.`);
    });
    $('error-photos').textContent = errors.join(' ');
    if (!errors.length) $('notice-photos').removeAttribute('aria-invalid');
    else $('notice-photos').setAttribute('aria-invalid', 'true');
    return errors.length === 0;
  }

  function editorPayload() {
    const editor = state.editor;
    const result = {
      type: $('notice-type').value, title: $('notice-title').value.trim(), category: $('notice-category').value,
      occurrenceDate: $('notice-date').value, approximate: $('notice-approximate').checked, area: $('notice-area').value,
      description: $('notice-description').value.trim(), retainedPhotoIds: [...editor.retained]
    };
    if (editor.id) result.version = editor.version;
    return result;
  }

  function validatePayload(payload) {
    let valid = true;
    const check = (condition, field, text) => { if (!condition) { fieldError(field, text); valid = false; } };
    const titleLength = Array.from(payload.title).length, descriptionLength = Array.from(payload.description).length;
    check(['LOST', 'FOUND'].includes(payload.type), 'type', 'Choose lost or found.');
    check(titleLength >= 5 && titleLength <= 80, 'title', 'Use 5–80 Unicode characters for the title.');
    check(descriptionLength >= 20 && descriptionLength <= 1000, 'description', 'Use 20–1,000 Unicode characters for the description.');
    check(Boolean(payload.category), 'category', 'Choose a category.');
    check(Boolean(payload.area), 'area', 'Choose a campus area.');
    check(Boolean(payload.occurrenceDate) && (!state.options?.today || payload.occurrenceDate <= state.options.today), 'occurrenceDate', 'Choose a valid date that is not later than today in Macau.');
    return validatePhotoCount() && valid;
  }

  async function submitNotice(event) {
    event.preventDefault(); if (state.busy || !state.editor) return;
    clearFieldErrors();
    const payload = editorPayload();
    if (!validatePayload(payload)) {
      $('editor-error').textContent = 'Please check the highlighted fields. Your input has been kept.';
      $('notice-form').querySelector('[aria-invalid=true]')?.focus(); return;
    }
    const editor = state.editor;
    const fingerprint = JSON.stringify({ payload, files: editor.files.map(file => [file.name, file.size, file.type, file.lastModified]) });
    if (!editor.request || editor.request.fingerprint !== fingerprint) editor.request = { fingerprint, key: uuid() };
    const form = new FormData();
    form.append('notice', new Blob([JSON.stringify(payload)], { type: 'application/json' }));
    editor.files.forEach(file => form.append('photos', file, file.name));
    state.busy = true; $('notice-fields').disabled = true;
    $('notice-submit').textContent = 'Saving…'; $('editor-progress').textContent = 'Saving your notice…';
    try {
      const result = await api(editor.id ? `/api/notices/${encodeURIComponent(editor.id)}` : '/api/notices', {
        method: editor.id ? 'PUT' : 'POST', body: form, requestKey: editor.request.key
      });
      const saved = result.notice || result;
      if (!saved.id) throw new Error('The server returned no saved notice link. Your input has been kept; check My notices before submitting again.');
      editor.dirty = false; editor.request = null;
      showSavedNotice(saved, editor.id ? 'Changes saved. The notice link remains the same.' : 'Notice published. Keep its stable link for later updates.');
    } catch (error) {
      Object.entries(error.fields || {}).forEach(([field, text]) => fieldError(field, text));
      $('editor-error').textContent = error.message;
      if (error instanceof ApiError && error.status === 409) {
        $('conflict-panel').hidden = false; $('latest-version').hidden = true; $('use-latest-version').hidden = true;
        $('review-latest').hidden = !editor.id;
      }
      if (!await handleAuthError(error)) $('editor-error').focus({ preventScroll: true });
    } finally {
      state.busy = false; $('notice-fields').disabled = false;
      $('notice-submit').textContent = editor.id ? 'Save changes' : 'Publish notice'; $('editor-progress').textContent = '';
    }
  }

  async function reviewLatest() {
    if (!state.editor?.id) return;
    const editor = state.editor;
    $('review-latest').disabled = true;
    try {
      const latest = await api(`/api/notices/${encodeURIComponent(state.editor.id)}`);
      if (state.editor !== editor || state.view !== 'editor') return;
      state.latest = latest;
      const container = $('latest-version'); container.replaceChildren(); container.className = 'latest-summary';
      container.append(node('h3', '', `Latest saved version: ${latest.version}`));
      const values = [['Title', latest.title], ['Type', latest.type === 'FOUND' ? 'Found item' : 'Lost item'], ['Category', optionLabel('categories', latest.category)], ['Date', `${latest.occurrenceDate}${latest.approximate ? ' (approximate)' : ''}`], ['Area', optionLabel('areas', latest.area)], ['Description', latest.description], ['Status', statusLabels[latest.status] || latest.status]];
      const list = node('dl'); values.forEach(([label, text]) => list.append(node('dt', '', label), node('dd', '', text))); container.append(list);
      const gallery = node('div', 'photo-gallery'); renderPhotos(gallery, latest.photos, latest.title); container.append(gallery);
      container.append(node('p', 'muted', latest.canEdit ? 'Your text and new photos will stay unchanged. Continuing updates the saved version and refreshes existing-photo choices; review them before saving.' : latest.lockReason || 'This record is no longer editable. Your draft is kept for reference.'));
      container.hidden = false; $('use-latest-version').hidden = !latest.canEdit;
    } catch (error) { if (!await handleAuthError(error)) message(error.message, true, true); }
    finally { $('review-latest').disabled = false; }
  }

  function useLatestVersion() {
    const editor = state.editor, latest = state.latest;
    if (!editor || !latest?.canEdit || editor.id !== latest.id) return;
    const oldPhotos = new Set(editor.existing.map(photo => String(photo.id)));
    const keep = new Set(editor.retained);
    editor.existing = [...(latest.photos || [])];
    editor.retained = new Set(editor.existing.filter(photo => keep.has(String(photo.id)) || !oldPhotos.has(String(photo.id))).map(photo => String(photo.id)));
    editor.original = latest; editor.version = latest.version; markDirty();
    $('editor-eyebrow').textContent = `Update version ${latest.version}`;
    $('conflict-panel').hidden = true; $('editor-error').textContent = '';
    renderEditorPhotos(); validatePhotoCount();
    message('Latest version selected. Your text and new photos are unchanged; check the existing photos before saving.');
  }

  function showClosePanel() {
    if (state.busy || !state.detail?.canClose) return;
    state.closeRequest = null;
    $('close-panel').hidden = false; $('close-reason').value = ''; $('close-error').textContent = ''; $('close-refresh').hidden = true; $('close-submit').disabled = false;
    $('close-panel').scrollIntoView({ behavior: 'smooth', block: 'center' }); $('close-reason').focus({ preventScroll: true });
  }

  async function closeNotice(event) {
    event.preventDefault(); if (state.busy || !state.detail) return;
    const reason = $('close-reason').value.trim();
    if (!reason) { $('close-error').textContent = 'Give a reason for archiving this notice.'; $('close-reason').focus(); return; }
    const payload = { version: state.detail.version, reason };
    const fingerprint = JSON.stringify(payload);
    if (!state.closeRequest || state.closeRequest.fingerprint !== fingerprint) state.closeRequest = { fingerprint, key: uuid() };
    state.busy = true; $('close-fields').disabled = true; $('close-submit').textContent = 'Archiving…'; $('close-error').textContent = '';
    try {
      const saved = await api(`/api/notices/${encodeURIComponent(state.detail.id)}/close`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: fingerprint, requestKey: state.closeRequest.key });
      state.closeRequest = null;
      showSavedNotice(saved, 'Notice archived as Closed. This does not confirm physical return.');
    } catch (error) {
      $('close-error').textContent = error.fields?.reason || error.message;
      $('close-refresh').hidden = !(error instanceof ApiError && error.status === 409);
      await handleAuthError(error);
    } finally { state.busy = false; $('close-fields').disabled = false; $('close-submit').textContent = 'Confirm archive'; }
  }

  async function refreshClose() {
    const reason = $('close-reason').value;
    const id = state.detail.id;
    try {
      const latest = await api(`/api/notices/${encodeURIComponent(state.detail.id)}`);
      if (state.detail?.id !== id || state.view !== 'detail') return;
      state.detail = latest; renderDetail();
      $('close-reason').value = reason; $('close-panel').hidden = false;
      $('close-submit').disabled = !latest.canClose;
      $('close-error').textContent = latest.canClose ? 'Current status loaded. Your reason is kept; review it before confirming.' : latest.lockReason || 'This notice cannot currently be archived. Your reason has been kept.';
      $('close-refresh').hidden = true; state.closeRequest = null;
    } catch (error) { if (!await handleAuthError(error)) $('close-error').textContent = error.message; }
  }

  document.addEventListener('click', event => {
    const link = event.target.closest('a[data-route]');
    if (link && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) { event.preventDefault(); navigate(link.dataset.route); }
  });
  $('show-register').addEventListener('click', () => showAuth('register'));
  $('show-signin').addEventListener('click', () => showAuth('signin'));
  $('register-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (state.busy) return;
    const username = $('register-username').value.trim();
    const displayName = $('register-display').value.trim();
    const password = $('register-password').value;
    const fields = registerProblems(username, displayName, password);
    if (Object.keys(fields).length) { applyRegisterErrors(fields); return; }
    state.busy = true; $('register-fields').disabled = true; $('register-submit').textContent = 'Creating account…';
    clearRegisterErrors();
    try {
      if (!state.session?.csrfToken) await refreshSession();
      await api('/api/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, displayName, password })
      });
      $('register-password').value = '';
      $('login-username').value = username;
      $('login-password').value = '';
      showAuth('signin');
      message('Account created. Sign in to browse notices. This account does not verify campus identity.');
    } catch (error) {
      if (error.fields && Object.keys(error.fields).length) applyRegisterErrors(error.fields);
      else $('register-error').textContent = error.message;
      if (error instanceof ApiError && [401, 403].includes(error.status)) {
        // Refresh a stale anonymous CSRF token without clearing or replaying the form.
        try { await refreshSession(); } catch (_) { /* Preserve the original error and input. */ }
      }
    } finally {
      state.busy = false; $('register-fields').disabled = false; $('register-submit').textContent = 'Create account';
    }
  });
  $('login-form').addEventListener('submit', async event => {
    event.preventDefault(); if (state.busy) return;
    const username = $('login-username').value.trim(), password = $('login-password').value;
    if (!username || !password) { $('login-error').textContent = 'Enter a username and password.'; return; }
    state.busy = true; $('login-fields').disabled = true; $('login-submit').textContent = 'Signing in…'; $('login-error').textContent = '';
    try {
      if (!state.session?.csrfToken) await refreshSession();
      await api('/api/login', { method: 'POST', body: new URLSearchParams({ username, password }) });
      if (!await refreshSession()) throw new Error('Sign-in could not be confirmed. Please try again.');
      $('login-password').value = ''; message('');
      if (state.editor?.dirty && state.view === 'editor') { $('workspace').hidden = false; }
      else await route();
    } catch (error) {
      $('login-error').textContent = error.message;
      try { await refreshSession(); } catch (_) { /* Allow a manual retry. */ }
    } finally { state.busy = false; $('login-fields').disabled = false; $('login-submit').textContent = 'Sign in'; }
  });
  $('logout-button').addEventListener('click', async () => {
    if (state.busy || !canLeaveEditor()) return;
    state.busy = true; state.signingOut = true;
    $('workspace').inert = true; $('workspace').setAttribute('aria-busy', 'true');
    $('logout-button').disabled = true; $('logout-button').textContent = 'Signing out…';
    try {
      await api('/api/logout', { method: 'POST' });
      state.routeToken += 1; state.editor = null; state.detail = null; state.options = null; revokePreviews();
      markSignedOut(); showAuth('signin');
      try {
        await refreshSession();
        message('Signed out.');
      } catch (_) {
        message('Signed out. The connection could not be refreshed; retry signing in when the server is available.');
      }
    } catch (error) { if (!await handleAuthError(error)) message(error.message, true, true); }
    finally {
      state.busy = false; state.signingOut = false;
      $('workspace').inert = false; $('workspace').removeAttribute('aria-busy');
      $('logout-button').disabled = false; $('logout-button').textContent = 'Sign out';
    }
  });
  $('refresh-list').addEventListener('click', () => { void loadList(); });
  $('previous-page').addEventListener('click', () => { if (state.page > 0) { state.page -= 1; void loadList(); } });
  $('next-page').addEventListener('click', () => { if (state.list?.hasNext) { state.page += 1; void loadList(); } });
  $('detail-back').addEventListener('click', () => navigate(`/${state.scope}`));
  $('edit-notice').addEventListener('click', () => { if (!state.busy && state.detail?.canEdit) openEditor(state.detail); });
  $('show-close').addEventListener('click', showClosePanel);
  $('close-cancel').addEventListener('click', () => { $('close-panel').hidden = true; });
  $('close-form').addEventListener('submit', closeNotice);
  $('close-refresh').addEventListener('click', () => { void refreshClose(); });
  $('close-reason').addEventListener('input', () => { state.closeRequest = null; });
  $('notice-form').addEventListener('submit', submitNotice);
  $('notice-form').addEventListener('input', event => {
    if (event.target.id === 'notice-photos') return;
    markDirty(); updateCounters();
  });
  $('notice-form').addEventListener('change', event => { if (event.target.id !== 'notice-photos') markDirty(); });
  $('notice-photos').addEventListener('change', event => {
    if (!state.editor) return;
    state.editor.files.push(...Array.from(event.target.files || [])); event.target.value = '';
    markDirty(); renderEditorPhotos(); validatePhotoCount();
  });
  $('editor-cancel').addEventListener('click', () => {
    if (!canLeaveEditor()) return;
    const id = state.editor?.id; state.editor.dirty = false;
    if (id) { void loadDetail(id).catch(error => message(error.message, true, true)); }
    else navigate(`/${state.scope}`);
  });
  $('review-latest').addEventListener('click', () => { void reviewLatest(); });
  $('use-latest-version').addEventListener('click', useLatestVersion);
  $('copy-link').addEventListener('click', async () => {
    const url = `${location.origin}/app/#/notices/${encodeURIComponent(state.detail.id)}`;
    try { await navigator.clipboard.writeText(url); message('Stable notice link copied.'); }
    catch (_) { message(`Stable link: ${url}`); }
  });
  window.addEventListener('hashchange', () => { void route(); });
  window.addEventListener('beforeunload', event => { if (editorDirty()) { event.preventDefault(); event.returnValue = ''; } });

  (async () => {
    $('login-fields').disabled = true;
    try {
      if (await refreshSession()) await route();
      else $('login-username').focus();
    } catch (error) { message(error.message, true); }
    finally { $('login-fields').disabled = false; }
  })();
})();
