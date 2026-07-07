/*
 * The console's script: vanilla, one file shared by every page, no build step and no framework.
 *
 * That is not thrift. The framework answers with a byte[] and refuses to stream (ADR-0030), so everything a browser can
 * get from it is one JSON body per request — a component system over that would solve a problem this application does
 * not have. There is no inline script either, because the page is served with a Content-Security-Policy that forbids one,
 * which is also why the per-page wiring below is keyed off the body element rather than off script tags in each file.
 *
 * The credential lives in sessionStorage: per tab, gone when the tab closes, never sent automatically by the browser
 * (so no CSRF surface), and read by this script on every page load because a separate document needs it to survive
 * navigation. What that trades away is stated in ADR-0057 rather than here: a token in a script-readable store is a
 * token a successful XSS can read, which is why the response also says no inline script and no third-party origin.
 */

(() => {
  const KEYS = { token: 'barezen.token', user: 'barezen.user', superseded: 'barezen.superseded', answers: 'barezen.answers' };
  const ANSWER_LIMIT = 50;
  const INTERESTING = ['Content-Type', 'Allow', 'ETag', 'X-Reader', 'X-Total-Count', 'X-Page-Size', 'Content-Length',
    'Cache-Control'];

  const page = document.body.dataset.page;
  const $ = id => document.getElementById(id);
  const store = {
    get(name) { return sessionStorage.getItem(KEYS[name]); },
    set(name, value) { value === null ? sessionStorage.removeItem(KEYS[name]) : sessionStorage.setItem(KEYS[name], value); },
  };

  const state = {
    get token() { return store.get('token'); },
    set token(value) { store.set('token', value); },
    get superseded() { return store.get('superseded'); },
    set superseded(value) { store.set('superseded', value); },
    q: '', limit: 20, offset: 0, total: 0,
  };

  /** What this page shows when it has a session: set by the page's own wiring, called after sign-in and on load. */
  let onPageReady = null;

  /* --- the request wrapper, and the log every page writes into ------------------------------- */

  async function call(method, path, body) {
    const headers = {};
    if (state.token) {
      headers.Authorization = 'Bearer ' + state.token;
    }
    const response = await fetch(path, { method, headers, body });
    const text = await response.text();
    record(method + ' ' + path, response.status, picked(response.headers), text);
    return { status: response.status, headers: response.headers, text };
  }

  /** The same request with a token the page stopped using — how "the old credential is really gone" gets proven. */
  async function callWith(token, method, path) {
    const response = await fetch(path, { method, headers: { Authorization: 'Bearer ' + token } });
    const text = await response.text();
    record(method + ' ' + path + ' (旧 token)', response.status, picked(response.headers), text);
    return { status: response.status, text };
  }

  function picked(headers) {
    return INTERESTING.filter(name => headers.has(name)).map(name => name + ': ' + headers.get(name)).join(' · ');
  }

  /** One answer, kept in sessionStorage so the log survives moving between pages. */
  function record(request, status, headers, body) {
    let stored = [];
    try {
      stored = JSON.parse(sessionStorage.getItem(KEYS.answers) || '[]');
    } catch (corrupt) {
      stored = [];
    }
    stored.push({ request, status, headers, body: body.slice(0, 400) });
    sessionStorage.setItem(KEYS.answers, JSON.stringify(stored.slice(-ANSWER_LIMIT)));
    if (page === 'answers') {
      renderAnswers();
    }
  }

  function renderAnswers() {
    let stored = [];
    try {
      stored = JSON.parse(sessionStorage.getItem(KEYS.answers) || '[]');
    } catch (corrupt) {
      stored = [];
    }
    const body = $('answer-log');
    body.textContent = '';
    if (stored.length === 0) {
      emptyRow(body, 4, '还没有请求。');
      return;
    }
    for (const entry of stored) {
      appendRow(body, [entry.request, String(entry.status), entry.headers, entry.body], entry.status);
    }
  }

  function appendRow(body, cells, status) {
    const row = document.createElement('tr');
    cells.forEach((value, index) => {
      const cell = document.createElement('td');
      cell.textContent = value;
      if (index === 1) {
        cell.className = 'status ' + (status < 300 ? 'ok' : status < 500 ? 'client' : 'server');
      } else if (index === 3) {
        cell.className = 'body';
      }
      row.appendChild(cell);
    });
    body.appendChild(row);
  }

  function say(message) {
    $('strip').textContent = message;
  }

  function fill(table, pairs) {
    const body = $(table);
    body.textContent = '';
    for (const [key, value] of pairs) {
      const row = document.createElement('tr');
      const name = document.createElement('th');
      name.textContent = key;
      const cell = document.createElement('td');
      cell.textContent = value;
      row.append(name, cell);
      body.appendChild(row);
    }
  }

  function parse(text) {
    try {
      return JSON.parse(text);
    } catch (notJson) {
      return null;
    }
  }

  function form(body) {
    return new URLSearchParams(body).toString();
  }

  /* --- the header every page shares --------------------------------------------------------- */

  function refreshIdentity() {
    const who = $('who');
    who.textContent = '';
    who.append(document.createTextNode(state.token ? '已登录：' : '还没有会话'));
    if (state.token) {
      const name = document.createElement('b');
      name.textContent = store.get('user') || '?';
      who.append(name);
    }
  }

  async function signIn(username, password) {
    const answer = await call('POST', '/api/sessions', form({ username, password }));
    const payload = parse(answer.text);
    if (answer.status === 200 && payload && payload.token) {
      state.token = payload.token;
      state.superseded = null;
      store.set('user', payload.user);
      refreshIdentity();
      say('已登录为 ' + payload.user + '。token 在这个标签页的 sessionStorage 里，关标签即失效。');
      if (onPageReady) {
        await onPageReady();
      }
      return true;
    }
    state.token = null;
    store.set('user', null);
    refreshIdentity();
    say('登录被拒（' + answer.status + '）。框架不说出口令错还是用户不存在 —— 两种失败是同一个应答。');
    return false;
  }

  async function signOut() {
    if (!state.token) {
      say('没有会话可退出。');
      return;
    }
    const carried = state.token;
    const answer = await call('DELETE', '/api/sessions');
    state.token = null;
    state.superseded = null;
    store.set('user', null);
    refreshIdentity();
    say('退出返回 ' + answer.status + '（204 无 body）。同一个值再请求一次就是 401。');
    if (answer.status === 204) {
      await callWith(carried, 'GET', '/api/session');
    }
  }

  /**
   * Ask the server what it thinks of the credential this tab is holding, and write that answer into the state line.
   *
   * <p>A token read back from storage is not a valid token: it can be one the server expired or rotated away while the
   * tab stayed open. A page that printed "已登录" from the mere presence of a stored value would be showing the reader
   * a fact it does not know, which is what the header used to do.
   */
  async function checkSession() {
    if (!state.token) {
      say('还没有会话 —— 点右上角任一账号登录，口令都是 hunter2。');
      return;
    }
    const answer = await call('GET', '/api/session');
    const payload = parse(answer.text);
    if (answer.status !== 200 || !payload) {
      state.token = null;
      store.set('user', null);
      refreshIdentity();
      say('这个标签页还留着 token，服务器已经不认了（' + answer.status + '）。重新登录。');
      return;
    }
    store.set('user', payload.user);
    refreshIdentity();
    say('会话属于 ' + payload.user + '，权限：' + (payload.permissions || '（无）') + '。');
    if (onPageReady) {
      await onPageReady();
    }
  }

  for (const [id, username] of [['quick-ada', 'ada'], ['quick-bob', 'bob'], ['quick-cy', 'cy']]) {
    const button = $(id);
    if (button) {
      button.addEventListener('click', () => signIn(username, 'hunter2'));
    }
  }

  if ($('signout')) {
    $('signout').addEventListener('click', signOut);
  }

  /* --- overview -------------------------------------------------------------------------------- */

  if (page === 'overview') {
    $('wrong-password').addEventListener('click', () => signIn('ada', 'wrong-one'));
    $('unknown-user').addEventListener('click', () => signIn('dave', 'hunter2'));
    $('ov-document').addEventListener('click', async () => {
      const answer = await call('GET', '/api/document');
      const payload = parse(answer.text);
      say(answer.status === 200 && payload
        ? '文档 ' + answer.text.length + ' 字节，openapi ' + payload.openapi + '，声明了 '
          + Object.keys(payload.paths || {}).length + ' 条路径。'
        : '文档取回状态 ' + answer.status + '。');
    });
  }

  /* --- reports -------------------------------------------------------------------------------- */

  async function loadReports() {
    const query = new URLSearchParams();
    if (state.q) {
      query.set('q', state.q);
    }
    query.set('limit', String(state.limit));
    query.set('offset', String(state.offset));
    const answer = await call('GET', '/api/reports?' + query.toString());
    const rows = parse(answer.text);
    if (answer.status !== 200 || !Array.isArray(rows)) {
      renderRows([], '没有列表：这条路由要 ' + (answer.status === 403 ? 'report:read，这个账号没有。' : '一个有效会话。'));
      $('reports-state').textContent = '列表没取到（' + answer.status + '）。';
      return;
    }
    renderRows(rows, '没有符合这条查询的报告。');
    state.total = Number(answer.headers.get('X-Total-Count') || rows.length);
    $('reports-state').textContent = '本页 ' + rows.length + ' 条 / 总共 ' + state.total + ' 条（偏移 ' + state.offset
      + '），读取者 ' + (answer.headers.get('X-Reader') || '?') + ' —— 三个数来自 X-Page-Size、X-Total-Count、X-Reader。';
  }

  function renderRows(rows, emptyMessage) {
    const body = $('report-rows');
    body.textContent = '';
    if (rows.length === 0) {
      emptyRow(body, 4, emptyMessage);
      return;
    }
    for (const row of rows) {
      const tr = document.createElement('tr');
      [['id', row.id], ['', row.title], ['', row.body]].forEach(([kind, value]) => {
        const td = document.createElement('td');
        if (kind) {
          td.className = kind;
        }
        td.textContent = String(value);
        tr.appendChild(td);
      });
      const actions = document.createElement('td');
      actions.className = 'ops';
      actions.append(rowButton('单读', 'small', async () => {
        const answer = await call('GET', '/api/reports/' + row.id);
        say('GET /api/reports/' + row.id + ' -> ' + answer.status);
      }), rowButton('删除', 'small', async () => {
        const answer = await call('DELETE', '/api/reports/' + row.id);
        say(answer.status === 204 ? '删掉了（204，无 body）。再删同一条就是 404。' : '删除返回 ' + answer.status + '。');
        loadReports();
      }));
      tr.appendChild(actions);
      body.appendChild(tr);
    }
  }

  /** A table action, built the same way every time: no innerHTML, and the handler is the only thing that varies. */
  function rowButton(label, className, onClick) {
    const button = document.createElement('button');
    button.className = className;
    button.type = 'button';
    button.textContent = label;
    button.addEventListener('click', onClick);
    return button;
  }

  if (page === 'reports') {
    onPageReady = loadReports;
    $('filter').addEventListener('submit', event => {
      event.preventDefault();
      state.q = $('q').value.trim();
      const requested = Number.parseInt($('limit').value, 10);
      state.limit = Number.isNaN(requested) ? 20 : requested;
      state.offset = 0;
      loadReports();
    });
    $('prev').addEventListener('click', () => {
      state.offset = Math.max(0, state.offset - state.limit);
      loadReports();
    });
    $('next').addEventListener('click', () => {
      if (state.offset + state.limit < state.total) {
        state.offset += state.limit;
        loadReports();
      } else {
        say('后面没有更多了：偏移 ' + state.offset + '，总共 ' + state.total + ' 条。');
      }
    });
    $('reload').addEventListener('click', loadReports);
    $('badlimit').addEventListener('click', async () => {
      const answer = await call('GET', '/api/reports?limit=abc');
      say(answer.status === 400 ? '服务器没替你猜：limit=abc 是 400，而不是悄悄变成 20。'
        : '期望 400，实际 ' + answer.status + '。');
    });
    $('oversize').addEventListener('click', async () => {
      const answer = await call('POST', '/api/reports',
        form({ title: 'too big', body: 'x'.repeat(1024 * 1024 + 64) }));
      say(answer.status === 413 ? '413 是适配器给的，handler 根本没跑。' : '实际状态是 ' + answer.status + '。');
    });
    $('create').addEventListener('submit', async event => {
      event.preventDefault();
      const answer = await call('POST', '/api/reports', form({ title: $('title').value, body: $('body').value }));
      const payload = parse(answer.text);
      say(answer.status === 201 ? '已新增，id = ' + (payload && payload.id ? payload.id : '?') + '（201）。'
        : '新增返回 ' + answer.status + '。');
      $('title').value = '';
      $('body').value = '';
      loadReports();
    });
  }

  /* --- session -------------------------------------------------------------------------------- */

  async function loadDetail() {
    const answer = await call('GET', '/api/session/detail');
    const payload = parse(answer.text);
    if (answer.status !== 200 || !payload) {
      fill('session-rows', [['状态', String(answer.status)], ['说明', '没有有效会话，先登录。']]);
      return;
    }
    fill('session-rows', [['user', payload.user], ['expiresAt', payload.expiresAt],
      ['secondsLeft', payload.secondsLeft], ['sessionsOpen', payload.sessionsOpen]]);
    say('这个会话还剩 ' + payload.secondsLeft + ' 秒；服务器上现在有 ' + payload.sessionsOpen + ' 个会话。');
  }

  if (page === 'session') {
    onPageReady = loadDetail;
    $('detail').addEventListener('click', loadDetail);
    $('rotate').addEventListener('click', async () => {
      if (!state.token) {
        say('先登录再轮换。');
        return;
      }
      const answer = await call('POST', '/api/session/rotate');
      const payload = parse(answer.text);
      if (answer.status !== 200 || !payload) {
        say('轮换返回 ' + answer.status + '。');
        return;
      }
      state.superseded = payload.superseded;
      state.token = payload.token;
      $('session-note').textContent = '被替换掉的值已留在这里，点「故意用旧 token 试一次」就能看到它不再被承认。';
      loadDetail();
    });
    $('retry-old').addEventListener('click', async () => {
      if (!state.superseded) {
        say('还没有被替换的 token。先点「轮换 token」。');
        return;
      }
      const answer = await callWith(state.superseded, 'GET', '/api/session');
      say(answer.status === 401 ? '旧 token 是 401 —— 是服务端把它删了，不是前端不再发送它。'
        : '期望 401，实际 ' + answer.status + '。');
    });
  }

  /* --- events ------------------------------------------------------------------------------ */

  async function loadEvents() {
    const answer = await call('GET', '/api/events');
    const rows = parse(answer.text);
    const body = $('event-rows');
    body.textContent = '';
    if (answer.status === 403) {
      emptyRow(body, 3, '403：读取审计流需要 user:read，这个账号没有。换 cy 登录。');
      say('403 在这里不是错误，是权限：这个账号没有 user:read。');
      return;
    }
    if (!Array.isArray(rows)) {
      emptyRow(body, 3, '事件流没取到（' + answer.status + '）。');
      say('事件流没取到（' + answer.status + '）。');
      return;
    }
    if (rows.length === 0) {
      emptyRow(body, 3, '还没有记录：自应用启动以来没有发生过被审计的操作。');
      return;
    }
    for (const row of rows) {
      const tr = document.createElement('tr');
      [['when', row.at], ['kind', row.kind], ['', row.subject]].forEach(([cell, value]) => {
        const td = document.createElement('td');
        if (cell) {
          td.className = cell;
        }
        td.textContent = String(value);
        tr.appendChild(td);
      });
      body.appendChild(tr);
    }
    say('读到 ' + rows.length + ' 条事件（上限 50 条由应用定，见 Audit.LIMIT）。');
  }

  /** A row that stands for "nothing here, and here is why", spanning the table so its text starts at the first column. */
  function emptyRow(body, columns, message) {
    const tr = document.createElement('tr');
    const cell = document.createElement('td');
    cell.colSpan = columns;
    cell.textContent = message;
    tr.appendChild(cell);
    body.appendChild(tr);
  }

  if (page === 'events') {
    onPageReady = loadEvents;
    $('load-events').addEventListener('click', loadEvents);
  }

  /* --- people ------------------------------------------------------------------------------- */

  if (page === 'people') {
    $('ask').addEventListener('submit', async event => {
      event.preventDefault();
      const whom = $('whom').value.trim();
      const answer = await call('GET', '/api/users/' + encodeURIComponent(whom) + '/permissions');
      const payload = parse(answer.text);
      if (answer.status === 403) {
        fill('people-rows', [['状态', '403'], ['说明', '查别人的权限需要 user:read；这个账号没有。']]);
        say('403 —— 这条路由带的是权限检查，不是"是不是本人"的判断。');
        return;
      }
      if (!payload) {
        fill('people-rows', [['状态', String(answer.status)], ['说明', '没有拿到结果。']]);
        return;
      }
      fill('people-rows', [['user', payload.user], ['askedBy', payload.askedBy],
        ['permissions', payload.permissions || '（无）'], ['count', payload.count]]);
      say('查到了 ' + payload.user + ' 的 ' + payload.count + ' 项权限。');
    });
  }

  /* --- answers ------------------------------------------------------------------------------- */

  if (page === 'answers') {
    $('clear').addEventListener('click', () => {
      sessionStorage.removeItem(KEYS.answers);
      renderAnswers();
    });
    $('reload-answers').addEventListener('click', renderAnswers);
  }

  refreshIdentity();
  if (page === 'answers') {
    renderAnswers();
  }
  // The state line is written from the server's answer, so it is never a sentence the page assumed before asking.
  checkSession();
})();
