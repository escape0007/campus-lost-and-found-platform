'use strict';

(() => {
  const $ = id => document.getElementById(id);
  const state = {
    session: null, options: null, scope: 'available', page: 0,
    filters: { keyword: '', type: '', category: '', area: '', from: '', to: '', openOnly: false, size: 20 },
    list: null,
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
    ['list', 'detail', 'editor', 'alerts', 'workflow'].forEach(name => { $(`${name}-view`).hidden = name !== view; });
    ['mine-nav', 'available-nav', 'alerts-nav', 'claims-nav', 'moderation-nav'].forEach(id => $(id).removeAttribute('aria-current'));
    if (view === 'list') $(`${state.scope}-nav`).setAttribute('aria-current', 'page');
    if (view === 'alerts') $('alerts-nav').setAttribute('aria-current', 'page');
  }

  function applySessionUi() {
    const authenticated = Boolean(state.session?.authenticated);
    $('login-view').hidden = authenticated; $('workspace').hidden = !authenticated;
    $('logout-button').hidden = !authenticated;
    $('session-status').hidden = !authenticated;
    $('account-name').textContent = authenticated ? state.session.displayName || state.session.username : '';
    $('account-name').title = authenticated ? `${state.session.username} · not campus identity verification` : '';
    $('moderation-nav').hidden = !authenticated || state.session?.role !== 'MODERATOR';
    return authenticated;
  }

  async function refreshSession() {
    state.session = await api('/api/session');
    return applySessionUi();
  }

  function markSignedOut() {
    clearPrivateWorkflow();
    state.session = { ...(state.session || {}), authenticated: false, csrfToken: null };
    applySessionUi();
  }

  function fillChoices(selectId, items, placeholder) {
    const select = $(selectId);
    const current = select.value || '';
    select.replaceChildren();
    const first = node('option', '', placeholder); first.value = ''; select.append(first);
    (items || []).forEach(item => {
      const option = node('option', '', item.label); option.value = item.value; select.append(option);
    });
    select.value = (items || []).some(item => item.value === current) ? current : '';
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
    fillChoices('filter-category', state.options.categories, 'Any category');
    fillChoices('filter-area', state.options.areas, 'Any area');
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
    return !(editorDirty() || workflowDirty) || window.confirm('Leave this page and discard the unsaved draft?');
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

  async function loadAlerts(token = state.routeToken) {
    const status = $('alert-status');
    const retry = $('alert-retry');
    if (status) status.textContent = '正在读取提醒…';
    if (retry) retry.hidden = true;
    try {
      const preference = await api('/api/alert-preference');
      if (token !== state.routeToken) return;
      const page = await api('/api/alerts');
      if (token !== state.routeToken) return;
      renderAlerts(preference, page);
    } catch (error) {
      if (token !== state.routeToken) return;
      if (await handleAuthError(error)) return;
      if (status) status.textContent = error.message || '提醒暂时读不出来。';
      if (retry) retry.hidden = false;
    }
  }

  function renderAlerts(preference, page) {
    const alertsMode = $('alert-mode-alerts');
    const manualMode = $('alert-mode-manual');
    if (alertsMode) alertsMode.checked = preference.mode === 'NEW_MATCH_ALERTS';
    if (manualMode) manualMode.checked = preference.mode === 'MANUAL_CHECKING';
    if ($('alert-version')) $('alert-version').value = preference.version ?? '';
    if ($('alert-save')) $('alert-save').disabled = false;
      if ($('alert-status')) $('alert-status').textContent = [page.configVersion ? `配置 ${page.configVersion}` : '', page.message || ''].filter(Boolean).join(' ');
    const list = $('alert-list');
    if (!list) return;
    list.replaceChildren();
    (page.items || []).forEach(item => {
      const card = node('article', 'match-card');
      const heading = node('h3');
      heading.append(alertLink(item.lostHidden, item.lostId, item.lostTitle, '丢失公告'));
      heading.append(document.createTextNode(' · '));
      heading.append(alertLink(item.foundHidden, item.foundId, item.foundTitle, '拾获公告'));
      card.append(heading);
      const lostStatus = item.lostHidden ? '不可用' : alertStatus(item.lostStatus);
      const foundStatus = item.foundHidden ? '不可用' : alertStatus(item.foundStatus);
      card.append(node('p', 'muted', `${alertConfidence(item.confidenceLabel)} · 丢失：${lostStatus} · 拾获：${foundStatus}`));
      card.append(node('p', 'muted', `实质版本 丢失 v${item.lostMaterialVersion} · 拾获 v${item.foundMaterialVersion}`));
      card.append(node('p', '', item.reason || ''));
      if (!item.read) {
        const button = node('button', 'button secondary', '标为已读');
        button.type = 'button';
        button.addEventListener('click', () => { void markAlertRead(item.id); });
        card.append(button);
      }
      list.append(card);
    });
  }

  function alertStatus(status) {
    return {
      OPEN: '开放', CLAIM_UNDER_REVIEW: '认领复核中', AWAITING_COLLECTION: '待领取',
      RETURNED: '已归还', CLOSED: '已归档'
    }[status] || status || '';
  }

  function alertConfidence(label) {
    return { High: '高', Medium: '中', Low: '低' }[label] || '高';
  }

  function alertLink(hidden, id, title, fallback) {
    if (hidden || !id) return node('span', '', '公告不可用');
    const link = node('a', '', title || fallback);
    link.href = `#/notices/${encodeURIComponent(id)}`;
    return link;
  }

  async function saveAlertPreference(event) {
    event.preventDefault();
    const alertsMode = $('alert-mode-alerts');
    const manualMode = $('alert-mode-manual');
    const selected = alertsMode && alertsMode.checked ? alertsMode.value : manualMode && manualMode.checked ? manualMode.value : '';
    const error = $('alert-save-error');
    if (error) error.textContent = '';
    if (!selected) {
      if (error) error.textContent = '请选择提醒方式。';
      return;
    }
    const button = $('alert-save');
    if (button) button.disabled = true;
    try {
      await api('/api/alert-preference', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode: selected, version: Number($('alert-version').value) })
      });
      await loadAlerts();
    } catch (caught) {
      if (await handleAuthError(caught)) return;
      if (error) error.textContent = caught.message;
    } finally {
      if (button) button.disabled = false;
    }
  }

  async function markAlertRead(id) {
    try {
      await api(`/api/alerts/${encodeURIComponent(id)}/read`, { method: 'POST' });
      await loadAlerts();
    } catch (error) {
      if (!await handleAuthError(error) && $('alert-status')) $('alert-status').textContent = error.message;
    }
  }

  async function route() {
    if (!location.hash) history.replaceState(null, '', '#/available');
    const currentHash = location.hash || '#/available';
    if (state.signingOut) {
      history.replaceState(null, '', state.lastHash || '#/available');
      return;
    }
    if (state.busy && ['editor', 'detail', 'workflow'].includes(state.view) && state.lastHash && currentHash !== state.lastHash) {
      history.replaceState(null, '', state.lastHash); message('Please wait for the current action to finish.'); return;
    }
    if (state.lastHash && currentHash !== state.lastHash && !canLeaveEditor()) {
      history.replaceState(null, '', state.lastHash); return;
    }
    if (currentHash !== state.lastHash && state.editor) state.editor.dirty = false;
    if (state.lastHash !== currentHash) workflowDirty = false;
    state.lastHash = currentHash;
    if (!state.session?.authenticated) return;
    const token = ++state.routeToken;
    try {
      await ensureOptions();
      if (token !== state.routeToken) return;
      const path = currentHash.slice(1).split('?')[0] || '/available';
      if (path === '/claims') { await loadClaims(token); return; }
      if (path === '/moderation') { await loadModeration(token); return; }
      const claimMatch = path.match(/^\/claims\/([^/]+)$/);
      if (claimMatch) { await loadClaim(decodeURIComponent(claimMatch[1]), token); return; }
      if (path === '/alerts') { setView('alerts'); await loadAlerts(token); return; }
      if (path === '/new') { openEditor(null); return; }
      const match = path.match(/^\/notices\/([^/]+)$/);
      if (match) {
        setView('detail'); state.detail = null;
        $('detail-heading').textContent = 'Loading notice…';
        ['detail-badges', 'detail-facts', 'detail-description', 'detail-photos', 'detail-id', 'detail-version', 'history-list', 'history-count', 'match-list', 'claims-panel'].forEach(id => $(id).replaceChildren());
        ['edit-notice', 'show-close', 'close-panel', 'detail-lock', 'match-retry'].forEach(id => { $(id).hidden = true; });
        $('match-status').textContent = '';
        await loadDetail(decodeURIComponent(match[1]), token); return;
      }
      const nextScope = path === '/available' ? 'available' : 'mine';
      if (nextScope !== state.scope || state.view !== 'detail') state.page = 0;
      state.scope = nextScope;
      await loadList(token);
    } catch (error) {
      if (token !== state.routeToken) return;
      if (state.view === 'detail' && !state.detail) $('detail-heading').textContent = 'Notice unavailable';
      if (!await handleAuthError(error)) message(error.message, true, true);
    }
  }

  function emptyFilters() {
    return { keyword: '', type: '', category: '', area: '', from: '', to: '', openOnly: false, size: 20 };
  }

  function hasActiveFilters(filters = state.filters) {
    return Boolean(filters.keyword || filters.type || filters.category || filters.area || filters.from || filters.to || filters.openOnly);
  }

  function readFilterForm() {
    return {
      keyword: $('filter-keyword').value.trim(),
      type: $('filter-type').value,
      category: $('filter-category').value,
      area: $('filter-area').value,
      from: $('filter-from').value,
      to: $('filter-to').value,
      openOnly: $('filter-status').value === 'open',
      size: Number($('filter-size').value) === 50 ? 50 : 20
    };
  }

  function writeFilterForm(filters) {
    $('filter-keyword').value = filters.keyword || '';
    $('filter-type').value = filters.type || '';
    $('filter-category').value = filters.category || '';
    $('filter-area').value = filters.area || '';
    $('filter-from').value = filters.from || '';
    $('filter-to').value = filters.to || '';
    $('filter-status').value = filters.openOnly ? 'open' : '';
    $('filter-size').value = filters.size === 50 ? '50' : '20';
  }

  function clearFilterErrors() {
    ['keyword', 'type', 'category', 'area', 'from', 'to', 'openOnly', 'size', 'page'].forEach(name => {
      const el = $(`error-filter-${name}`);
      if (el) el.textContent = '';
      const input = $(name === 'openOnly' ? 'filter-status' : `filter-${name}`);
      if (input) input.removeAttribute('aria-invalid');
    });
    $('filter-error').textContent = '';
  }

  function showFilterErrors(fields = {}) {
    clearFilterErrors();
    Object.entries(fields).forEach(([name, text]) => {
      const el = $(`error-filter-${name}`);
      const input = $(name === 'openOnly' ? 'filter-status' : `filter-${name}`);
      if (el) el.textContent = text;
      else $('filter-error').textContent = text;
      if (input) input.setAttribute('aria-invalid', 'true');
    });
    if (!$('filter-error').textContent) $('filter-error').textContent = 'Please check the highlighted filters.';
  }

  function validateFilterForm(filters) {
    const fields = {};
    if (codePoints(filters.keyword) > 80) fields.keyword = 'Use at most 80 characters.';
    if (filters.from && filters.to && filters.from > filters.to) fields.to = 'The end date must be on or after the start date.';
    if (!Object.keys(fields).length) { clearFilterErrors(); return true; }
    showFilterErrors(fields);
    return false;
  }

  function noticeQuery() {
    const params = new URLSearchParams({ scope: state.scope, page: String(state.page) });
    if (state.scope !== 'available') return `/api/notices?${params.toString()}`;
    const filters = state.filters;
    if (filters.keyword) params.set('keyword', filters.keyword);
    if (filters.type) params.set('type', filters.type);
    if (filters.category) params.set('category', filters.category);
    if (filters.area) params.set('area', filters.area);
    if (filters.from) params.set('from', filters.from);
    if (filters.to) params.set('to', filters.to);
    if (filters.openOnly) params.set('openOnly', 'true');
    params.set('size', String(filters.size || 20));
    return `/api/notices?${params.toString()}`;
  }

  function renderFilterSummary(total) {
    const box = $('active-filters');
    if (state.scope !== 'available') { box.hidden = true; box.replaceChildren(); return; }
    box.hidden = false; box.replaceChildren();
    const filters = state.filters;
    if (Number.isFinite(total)) box.append(node('p', 'filter-note', `${total} notice${total === 1 ? '' : 's'}`));
    const chips = [];
    if (filters.keyword) chips.push(`Keyword “${filters.keyword}”`);
    if (filters.type) chips.push(filters.type === 'FOUND' ? 'Found' : 'Lost');
    if (filters.category) chips.push(optionLabel('categories', filters.category));
    if (filters.from || filters.to) chips.push(`${filters.from || 'any start'} – ${filters.to || 'any end'}`);
    if (filters.area) chips.push(optionLabel('areas', filters.area));
    if (filters.openOnly) chips.push('Open only');
    if (filters.size === 50) chips.push('50 per page');
    if (!chips.length) box.append(node('span', 'filter-note', 'No filters applied. Showing Open and Claim under review.'));
    chips.forEach(text => box.append(node('span', 'filter-chip', text)));
  }

  function applyFilters(event) {
    if (event) event.preventDefault();
    if (state.scope !== 'available') return;
    const next = readFilterForm();
    if (!validateFilterForm(next)) return;
    state.filters = next; state.page = 0;
    void loadList();
  }

  function browseAll() {
    state.filters = emptyFilters(); state.page = 0;
    writeFilterForm(state.filters); clearFilterErrors();
    void loadList();
  }

  async function loadList(token = ++state.routeToken) {
    setView('list');
    const available = state.scope === 'available';
    $('list-heading').textContent = available ? 'Available notices' : 'My notices';
    $('list-description').textContent = available
      ? 'Browse current notices with no filter, or narrow them below. Closed means archived, not returned.'
      : 'Your current notices and archived records, in one place.';
    $('filter-form').hidden = !available;
    if (available) renderFilterSummary();
    $('filter-apply').disabled = true; $('browse-all').disabled = true;
    $('notice-list').setAttribute('aria-busy', 'true');
    $('notice-list').replaceChildren(node('div', 'loading-state', 'Loading notices…'));
    $('pagination').hidden = true;
    try {
      const result = await api(noticeQuery());
      if (token !== state.routeToken || !state.session?.authenticated) return;
      state.list = result; clearFilterErrors();
      const list = $('notice-list'); list.replaceChildren();
      const items = result.items || [];
      const total = Number(result.totalElements);
      if (available) renderFilterSummary(total);
      if (!items.length) {
        const empty = node('div', 'empty-state');
        if (available && hasActiveFilters()) {
          empty.append(node('h2', '', 'No notices match these filters'));
          empty.append(node('p', 'muted', 'This search is valid. Adjust the keyword, type, category, dates, area or status, or browse all available notices.'));
          const adjust = node('button', 'button secondary', 'Adjust filters'); adjust.type = 'button';
          adjust.addEventListener('click', () => $('filter-keyword').focus());
          const browse = node('button', 'button primary', 'Browse all available'); browse.type = 'button';
          browse.addEventListener('click', browseAll);
          const actions = node('div', 'form-actions'); actions.append(adjust, browse); empty.append(actions);
        } else {
          empty.append(node('h2', '', state.scope === 'mine' ? 'Start with your first notice' : 'No available notices yet'));
          empty.append(node('p', 'muted', state.scope === 'mine' ? 'Lost something or found an item? Add the details and keep the link current.' : 'New notices will appear here when they are available.'));
          const link = node('a', 'button primary', 'Create a notice'); link.href = '#/new'; link.dataset.route = '/new'; empty.append(link);
        }
        list.append(empty);
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
      const size = Number(result.size ?? (available ? state.filters.size : 20));
      $('pagination').hidden = page === 0 && !result.hasNext;
      $('previous-page').disabled = page === 0; $('next-page').disabled = !result.hasNext;
      const noun = total === 1 ? 'notice' : 'notices';
      $('page-label').textContent = `Page ${page + 1} · ${size} per page${Number.isFinite(total) ? ` · ${total} ${noun}` : ''}`;
    } catch (error) {
      if (token !== state.routeToken) return;
      if (error instanceof ApiError && error.status === 422) {
        showFilterErrors(error.fields);
        const empty = node('div', 'empty-state');
        empty.append(node('h2', '', 'Check these filters'));
        empty.append(node('p', 'muted', 'The selected filters are still here. Correct the highlighted fields and apply them again.'));
        $('notice-list').replaceChildren(empty);
        return;
      }
      const empty = node('div', 'empty-state');
      empty.append(node('h2', '', 'Notices could not be loaded'));
      empty.append(node('p', 'muted', 'Your filters are unchanged. Retry to look up the current notices.'));
      const retry = node('button', 'button primary', 'Retry'); retry.type = 'button';
      retry.addEventListener('click', () => { void loadList(); });
      empty.append(retry);
      $('notice-list').replaceChildren(empty);
      if (!await handleAuthError(error)) message(error.message, true, true);
    } finally {
      if (token === state.routeToken) {
        $('filter-apply').disabled = false; $('browse-all').disabled = false;
        $('notice-list').removeAttribute('aria-busy');
      }
    }
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
    const suggestionToken = state.routeToken;
    setTimeout(() => { void loadSuggestions(notice.id, suggestionToken); void loadNoticeClaims(notice, suggestionToken); }, 0);
  }

  async function loadSuggestions(id, token) {
    const list = $('match-list');
    const status = $('match-status');
    if (!list || !status) return;
    list.replaceChildren();
    status.textContent = 'Checking current suggestions…';
    $('match-retry').hidden = true;
    try {
      const page = await api(`/api/notices/${encodeURIComponent(id)}/suggestions`);
      if (token !== state.routeToken || state.detail?.id !== id) return;
      status.textContent = [page.configVersion ? `配置 ${page.configVersion}` : '', page.message || ''].filter(Boolean).join(' ');
      if (!page.items?.length) return;
      page.items.forEach(item => {
        const card = node('article', 'match-card');
        const heading = node('h3');
        const link = node('a', '', item.title);
        link.href = `#/notices/${encodeURIComponent(item.noticeId)}`;
        link.dataset.route = `/notices/${encodeURIComponent(item.noticeId)}`;
        heading.append(link);
        const facts = `${item.type === 'FOUND' ? 'Found item' : 'Lost item'} · ${optionLabel('categories', item.category)} · ${optionLabel('areas', item.area)} · ${formatDate(item.occurrenceDate)}${item.approximate ? ' · approximate' : ''} · ${statusLabels[item.status] || item.status}`;
        const reasons = node('ul', 'match-reasons');
        (item.reasons || []).forEach(reason => reasons.append(node('li', '', reason)));
        const ignore = node('button', 'button secondary', 'Ignore this pair');
        ignore.type = 'button';
        ignore.addEventListener('click', () => { void ignorePair(id, item.noticeId, ignore); });
        card.append(heading, node('p', 'muted', facts), node('p', '', `Rank ${item.rank} · ${item.confidenceLabel} confidence (${item.confidence}) · material v${item.materialVersion}`), reasons, ignore);
        list.append(card);
      });
    } catch (error) {
      if (token !== state.routeToken || state.detail?.id !== id) return;
      status.textContent = 'Suggestions are temporarily unavailable. This notice and manual search are unchanged. You can retry.';
      $('match-retry').hidden = false;
      if (error?.status === 401) await handleAuthError(error);
    }
  }

  async function ignorePair(sourceId, otherId, button) {
    button.disabled = true;
    try {
      await api(`/api/notices/${encodeURIComponent(sourceId)}/suggestions/${encodeURIComponent(otherId)}/ignore`, { method: 'POST' });
      await loadSuggestions(sourceId, state.routeToken);
    } catch (error) {
      button.disabled = false;
      if (!await handleAuthError(error)) {
        $('match-status').textContent = 'The pair could not be ignored. The suggestion is unchanged. You can retry.';
        $('match-retry').hidden = false;
      }
    }
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

  // Private workflow state stays in memory and is cleared on sign-out.
  const workflowRequests = new Map();
  let workflowDirty = false;
  const claimLabels = {PENDING: '待审核', HELD: '候补暂停', APPROVED: '待领取', REJECTED: '已拒绝', WITHDRAWN: '已撤回', ITEM_RETURNED: '物品已归还'};
  function workflowButton(text, run, style = 'secondary') {
    const b = node('button', `button ${style}`, text); b.type = 'button';
    b.addEventListener('click', () => { if (!state.busy) void run(); }); return b;
  }
  function workflowLink(text, path) {
    const link = node('a', '', text); link.href = `#${path}`; link.dataset.route = path; return link;
  }
  function workflowInput(form, label, id, textarea = true) {
    const l = node('label', '', label); l.htmlFor = id;
    const input = node(textarea ? 'textarea' : 'input'); input.id = id;
    if (textarea) input.rows = 3;
    input.addEventListener('input', () => { workflowDirty = true; });
    form.append(l, input); return input;
  }
  function workflowError(form) {
    const error = node('p', 'field-error'); error.setAttribute('role', 'alert'); form.append(error); return error;
  }
  async function workflowWrite(path, payload, error, onSaved) {
    if (state.busy) return;
    const body = JSON.stringify(payload), fingerprint = `${state.session.username}:${path}:${body}`;
    if (!workflowRequests.has(fingerprint)) workflowRequests.set(fingerprint, uuid());
    state.busy = true; $('workspace').inert = true; $('workspace').setAttribute('aria-busy', 'true'); error.textContent = '';
    let saved;
    try {
      saved = await api(path, {method: 'POST', headers: {'Content-Type': 'application/json'}, body, requestKey: workflowRequests.get(fingerprint)});
    } catch (e) {
      error.textContent = Object.values(e.fields || {}).join(' · ') || e.message;
      if (e.status === 409) error.textContent += ' 请先刷新当前记录，再检查输入和操作条件。';
      await handleAuthError(e); return;
    } finally { state.busy = false; $('workspace').inert = false; $('workspace').removeAttribute('aria-busy'); }
    workflowRequests.delete(fingerprint); workflowDirty = false;
    message('操作已保存。');
    // A read/render failure after a committed write must never invite resubmission.
    try { await onSaved(saved); }
    catch (_) { message('操作已保存，但页面刷新失败。请刷新记录查看结果。', true); }
  }
  function clearPrivateWorkflow() {
    workflowRequests.clear(); workflowDirty = false;
    $('claims-panel').replaceChildren(); $('workflow-content').replaceChildren();
  }
  function workflowHeader(title, refresh) {
    setView('workflow'); $('workflow-heading').textContent = title;
    $('claims-nav').setAttribute('aria-current', 'page');
    const box = $('workflow-content'); box.replaceChildren();
    box.append(workflowButton('刷新当前记录', () => { if (canLeaveEditor()) { workflowDirty = false; return refresh(); } }));
    return box;
  }
  function claimCard(c) {
    const card = node('article', 'match-card');
    const h = node('h3'); h.append(workflowLink(c.foundTitle || '认领记录', `/claims/${encodeURIComponent(c.id)}`));
    card.append(h, node('p', 'muted', `${claimLabels[c.status] || c.status}${c.disputed ? ' · 争议复核中' : ''} · ${formatDate(c.createdAt, true)}`));
    return card;
  }
  async function loadClaims(token = ++state.routeToken) {
    message('');
    const box = workflowHeader('我的认领与审核', () => loadClaims());
    box.append(node('p', 'muted', '这里只显示你参与或获指定复核的私密记录。不同认领人的证据和对话相互隔离。'));
    const items = await api('/api/claims');
    if (token !== state.routeToken || !state.session?.authenticated) return;
    if (!items.length) box.append(node('p', 'empty-state', '还没有认领记录。打开一条拾获公告即可提交认领。'));
    items.forEach(c => box.append(claimCard(c)));
  }
  async function loadClaim(id, token = ++state.routeToken) {
    message('');
    workflowHeader('正在读取私密记录…', () => loadClaim(id));
    let c;
    try { c = await api(`/api/claims/${encodeURIComponent(id)}`); }
    catch (error) { if (token === state.routeToken) $('workflow-heading').textContent = '记录不可用'; throw error; }
    if (token !== state.routeToken || !state.session?.authenticated) return;
    renderClaim(c);
  }
  function showClaim(c) {
    state.routeToken += 1;
    const hash = `#/claims/${encodeURIComponent(c.id)}`;
    history.pushState(null, '', hash); state.lastHash = hash; renderClaim(c);
  }
  function renderClaim(c) {
    const box = workflowHeader(c.foundTitle || '私密认领记录', () => loadClaim(c.id));
    box.append(workflowLink('查看拾获公告', `/notices/${encodeURIComponent(c.foundNoticeId)}`));
    box.append(node('p', 'info-note', `${claimLabels[c.status] || c.status} · 版本 ${c.version} · 批准轮次 ${c.approvalRound}${c.disputed ? (c.completedAt ? ' · 归还事实保留，争议审核中' : ' · 争议处理中，暂停归还确认') : ''}${c.hidden ? ' · 公告已隐藏' : ''}`));
    box.append(node('h2', '', '私密认领证据'), node('p', 'private-text', c.evidence));
    if (c.lostNoticeId) box.append(node('p', 'muted', `关联丢失公告：${c.lostTitle || c.lostNoticeId}`));
    if (c.decisionReason) box.append(node('p', 'private-text', `审核说明：${c.decisionReason}`));
    if (c.disputeReason) box.append(node('p', 'private-text', `争议说明：${c.disputeReason}`));
    const path = `/api/claims/${encodeURIComponent(c.id)}`;
    if (c.canDecide) {
      const form = node('form', 'panel workflow-form'); form.noValidate = true;
      form.append(node('h2', '', '审核认领'));
      const reason = workflowInput(form, '审核理由（必填）', 'claim-decision'); const error = workflowError(form);
      const actions = node('div', 'form-actions');
      ['approve', 'reject'].forEach(action => actions.append(workflowButton(action === 'approve' ? '批准此认领' : '拒绝此认领', () => {
        if (!reason.value.trim()) { error.textContent = '请填写审核理由。'; return; }
        if (!window.confirm(action === 'approve' ? '批准后将锁定此拾获公告及关联丢失公告，其他认领进入候补。确认批准？' : '确认拒绝此认领？')) return;
        return workflowWrite(`${path}/${action}`, {reason: reason.value.trim(), version: c.version}, error, renderClaim);
      }, action === 'approve' ? 'primary' : 'secondary')));
      form.addEventListener('submit', e => e.preventDefault()); form.append(actions); box.append(form);
    }
    const actions = node('section', 'panel workflow-form'); const error = workflowError(actions);
    if (c.canWithdraw) actions.append(workflowButton('撤回认领', () => {
      if (window.confirm('确认撤回这条认领？原有证据和历史将保留。')) return workflowWrite(`${path}/withdraw`, {version: c.version, approvalRound: c.approvalRound}, error, renderClaim);
    }));
    if (c.status === 'APPROVED' || c.completedAt) {
      actions.append(node('p', 'info-note', `拾获方：${c.finderConfirmed ? '已确认' : '未确认'} · 认领方：${c.claimantConfirmed ? '已确认' : '未确认'}。双方分别确认实际交接，系统才记为已归还。`));
    }
    if (c.canConfirm) actions.append(workflowButton('我已完成实际交接', () => {
      if (window.confirm('请仅在物品已经实际交接后确认。确认你已完成交接？')) return workflowWrite(`${path}/confirm-return`, {version: c.version, approvalRound: c.approvalRound}, error, renderClaim);
    }, 'primary'));
    box.append(actions);
    const conversation = node('section', 'panel workflow-form'); conversation.append(node('h2', '', '私密对话'));
    (c.messages || []).forEach(m => {
      const article = node('article', 'case-message');
      article.append(node('p', 'muted', `${m.senderName || m.senderId} · ${formatDate(m.createdAt, true)}`), node('p', 'private-text', m.content)); conversation.append(article);
    });
    if (!c.messages?.length) conversation.append(node('p', 'muted', '暂无对话。请在此核实独有特征和约定交接，不要在公开公告中填写证据。'));
    if (c.canMessage) {
      const form = node('form'); form.noValidate = true;
      const content = workflowInput(form, '补充说明（1–1,000 个字符）', 'claim-message'); const err = workflowError(form);
      const submit = node('button', 'button primary', '发送私密说明'); submit.type = 'submit'; form.append(submit);
      form.addEventListener('submit', e => { e.preventDefault(); if (codePoints(content.value.trim()) < 1 || codePoints(content.value.trim()) > 1000) { err.textContent = '请输入 1–1,000 个字符。'; return; }
        void workflowWrite(`${path}/messages`, {content: content.value.trim(), version: c.version}, err, renderClaim); }); conversation.append(form);
    } else conversation.append(node('p', 'muted', '当前记录为只读。历史证据与对话不能修改。'));
    box.append(conversation);
    if (c.canReportDispute) box.append(reportForm(`${path}/report-dispute`, '提交争议复核', '说明实际争议；完成前提交将暂停归还确认。归还后提交会保留已归还事实。', () => loadClaim(c.id)));
    const historyBox = node('section', 'panel workflow-form'); historyBox.append(node('h2', '', '状态历史'));
    (c.history || []).forEach(h => historyBox.append(node('p', 'muted', `${formatDate(h.occurredAt, true)} · ${h.action} · ${h.previousState || '—'} → ${h.newState || '—'}`)));
    box.append(historyBox);
  }
  function reportForm(path, title, help, saved) {
    const form = node('form', 'panel workflow-form'); form.noValidate = true;
    form.append(node('h2', '', title), node('p', 'muted', help), node('p', 'muted', '需要线下帮助时，可自行查阅学校官方渠道选择合适服务，并自行决定提供哪些信息。本平台不代传私密记录，也不承诺校方介入。'));
    const reason = workflowInput(form, '具体原因（1–1,000 个字符）', `reason-${path.replace(/[^a-zA-Z0-9]/g, '-')}`); const error = workflowError(form);
    const submit = node('button', 'button secondary', title); submit.type = 'submit'; form.append(submit);
    form.addEventListener('submit', e => { e.preventDefault(); const count = codePoints(reason.value.trim());
      if (count < 1 || count > 1000) { error.textContent = '请输入 1–1,000 个字符。'; return; }
      void workflowWrite(path, {reason: reason.value.trim()}, error, async result => { reason.value = ''; await saved(result); }); });
    return form;
  }
  async function loadNoticeClaims(notice, token) {
    if (token !== state.routeToken || state.detail?.id !== notice.id || !state.session?.authenticated) return;
    const panel = $('claims-panel'); panel.replaceChildren();
    panel.append(reportForm(`/api/notices/${encodeURIComponent(notice.id)}/report`, '举报公告', '仅向复核人员提交。若涉及认领争议，请在私密认领记录中提交。', () => { message('举报已提交，等待指定复核人员处理。'); }));
    if (notice.type !== 'FOUND') return;
    const section = node('section', 'panel workflow-form'); section.append(node('h2', '', '认领与审核'));
    panel.append(section);
    if (['OPEN', 'CLAIM_UNDER_REVIEW'].includes(notice.status) && !notice.canEdit) {
      const form = node('form'); form.noValidate = true;
      form.append(node('p', 'muted', '提供未公开的独有特征。你提交的证据只对双方及指定复核员可见。'));
      const evidence = workflowInput(form, '私密证据（10–1,000 个字符）', 'claim-evidence');
      const lost = workflowInput(form, '关联自己的开放丢失公告 ID（选填）', 'claim-lost-id', false);
      const error = workflowError(form); const submit = node('button', 'button primary', '提交认领'); submit.type = 'submit'; form.append(submit);
      form.addEventListener('submit', e => { e.preventDefault(); const count = codePoints(evidence.value.trim());
        if (count < 10 || count > 1000) { error.textContent = '私密证据需 10–1,000 个字符。'; return; }
        void workflowWrite(`/api/notices/${encodeURIComponent(notice.id)}/claims`, {lostNoticeId: lost.value.trim() || null, evidence: evidence.value.trim()}, error, showClaim); });
      section.append(form);
    }
    try {
      const cases = await api(`/api/notices/${encodeURIComponent(notice.id)}/claims/mine`);
      if (token !== state.routeToken || state.detail?.id !== notice.id || !state.session?.authenticated) return;
      if (cases.length) { section.append(node('h3', 'workflow-subtitle', '你有权查看的认领记录')); cases.forEach(c => section.append(claimCard(c))); }
      else section.append(node('p', 'muted', '暂无可查看的认领记录。'));
    } catch (e) { if (token === state.routeToken && !await handleAuthError(e)) section.append(node('p', 'field-error', e.message)); }
  }
  async function loadModeration(token = ++state.routeToken) {
    message('');
    const box = workflowHeader('举报与争议复核', () => loadModeration());
    $('claims-nav').removeAttribute('aria-current'); $('moderation-nav').setAttribute('aria-current', 'page');
    box.append(node('p', 'muted', '先领取复核任务，才可读取私密案情。涉及自己的案件不能领取。处理完成或释放后将撤销该案件的复核访问。'));
    const reports = await api('/api/moderation/reports');
    if (token !== state.routeToken || !state.session?.authenticated) return;
    if (!reports.length) box.append(node('p', 'empty-state', '暂无待处理举报。'));
    reports.forEach(r => {
      const card = node('section', 'panel workflow-form'); card.append(node('h2', '', `${r.targetType !== 'NOTICE' ? '认领争议' : '公告举报'} · ${r.status}`), node('p', 'muted', `提交于 ${formatDate(r.createdAt, true)}`));
      if (r.reason) card.append(node('p', 'private-text', r.reason));
      if (r.canResolve && r.targetType !== 'NOTICE') card.append(workflowLink('打开获指定的私密案件', `/claims/${encodeURIComponent(r.targetId)}`));
      if (r.targetTitle) card.append(node('p', 'muted', r.targetTitle));
      if (r.resolution) card.append(node('p', 'private-text', `${r.resolution}：${r.resolutionNote || ''}`));
      const form = node('form'); form.noValidate = true;
      if (r.canAssign || r.canResolve) {
        const reason = workflowInput(form, '处理说明（必填）', `moderation-note-${r.id}`); const error = workflowError(form);
        const path = `/api/moderation/reports/${encodeURIComponent(r.id)}`;
        const action = (suffix, payload) => {
          if (!reason.value.trim()) { error.textContent = '请填写处理说明。'; return; }
          return workflowWrite(`${path}/${suffix}`, payload, error, () => loadModeration());
        };
        if (r.canAssign) form.append(workflowButton('领取此复核', () => action('assign', {reason: reason.value.trim()}), 'primary'));
        if (r.canResolve) {
          const label = node('label', '', '处理结果'); const select = node('select'); select.id = `resolution-${r.id}`; label.htmlFor = select.id;
          const choices = r.targetType !== 'NOTICE' ? [['DISMISS', '不予处理 / 结束争议'], ['RESUME_COLLECTION', '恢复领取与双方确认'], ['CANCEL_APPROVAL', '撤销批准并释放预留']] : [['DISMISS', '不予处理'], ['HIDE_NOTICE', '隐藏违规公告']];
          choices.forEach(([value, text]) => { const option = node('option', '', text); option.value = value; select.append(option); });
          form.append(label, select, workflowButton('提交处理结果', () => {
            if (window.confirm('确认提交此复核决定？决定和理由将保留审计记录。')) return action('resolve', {resolution: select.value, note: reason.value.trim(), version: r.version});
          }, 'primary'), workflowButton('释放复核权限', () => action('release', {reason: reason.value.trim()})));
        }
        form.addEventListener('submit', e => e.preventDefault()); card.append(form);
      }
      box.append(card);
    });
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
  $('filter-form').addEventListener('submit', applyFilters);
  $('browse-all').addEventListener('click', browseAll);
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
  $('match-retry').addEventListener('click', () => { if (state.detail?.id) void loadSuggestions(state.detail.id, state.routeToken); });
  $('alert-form').addEventListener('submit', saveAlertPreference);
  $('alert-retry').addEventListener('click', () => { void loadAlerts(); });
  $('copy-link').addEventListener('click', async () => {
    const url = `${location.origin}/app/#/notices/${encodeURIComponent(state.detail.id)}`;
    try { await navigator.clipboard.writeText(url); message('Stable notice link copied.'); }
    catch (_) { message(`Stable link: ${url}`); }
  });
  window.addEventListener('hashchange', () => { void route(); });
  window.addEventListener('beforeunload', event => { if (editorDirty() || workflowDirty) { event.preventDefault(); event.returnValue = ''; } });

  (async () => {
    $('login-fields').disabled = true;
    try {
      if (await refreshSession()) await route();
      else $('login-username').focus();
    } catch (error) { message(error.message, true); }
    finally { $('login-fields').disabled = false; }
  })();
})();
