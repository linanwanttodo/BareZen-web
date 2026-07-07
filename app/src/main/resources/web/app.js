/*
 * The console's script: vanilla, one file, no build step and no framework.
 *
 * That is not thrift. The framework answers with a byte[] and refuses to stream (ADR-0030), so everything a browser
 * can get from it is one JSON body per request — a reactive layer over that would be a solution to a problem this
 * application does not have. Two rules are kept visible here rather than assumed: the session token lives in this
 * variable and nowhere else (no localStorage: a token stored there is a token any script on this origin can read), and
 * nothing built from a server response is ever written with innerHTML, because the reports table shows text the
 * application cannot vouch for.
 */

(() => {
  const state = { token: null, user: null };

  const elements = {
    login: document.getElementById('login'),
    create: document.getElementById('create'),
    refresh: document.getElementById('refresh'),
    logout: document.getElementById('logout'),
    load: document.getElementById('load'),
    forbidden: document.getElementById('forbidden'),
    oversize: document.getElementById('oversize'),
    document: document.getElementById('document'),
    sessionState: document.getElementById('session-state'),
    answers: document.getElementById('answer-log'),
    reports: document.querySelector('#report-table tbody'),
  };

  const INTERESTING = ['Content-Type', 'Allow', 'ETag', 'X-Reader', 'Content-Length', 'Cache-Control'];

  /** One request, logged as it came back, and handed to the caller as {status, headers, text}. */
  async function call(method, path, body) {
    const headers = {};
    if (state.token) {
      headers.Authorization = 'Bearer ' + state.token;
    }
    const response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : body,
    });
    const text = await response.text();
    record(method + ' ' + path, response.status, picked(response.headers), text);
    return { status: response.status, headers: response.headers, text };
  }

  function picked(headerMap) {
    return INTERESTING.filter(name => headerMap.has(name))
      .map(name => name + ': ' + headerMap.get(name))
      .join(' · ');
  }

  /** Append one row to the answer log. Built with textContent only — a response body is never markup. */
  function record(request, status, headers, body) {
    if (elements.answers.rows.length === 1 && elements.answers.rows[0].cells.length === 1) {
      elements.answers.textContent = '';
    }
    const row = document.createElement('tr');
    const cells = [request, String(status), headers, body];
    cells.forEach((value, index) => {
      const cell = document.createElement('td');
      cell.textContent = value;
      if (index === 1) {
        cell.className = 'status ' + (status < 300 ? 'ok' : status < 500 ? 'client' : 'server');
      }
      row.appendChild(cell);
    });
    elements.answers.appendChild(row);
  }

  function say(message) {
    elements.sessionState.textContent = message;
  }

  function form(body) {
    return new URLSearchParams(body).toString();
  }

  function renderReports(rows) {
    elements.reports.textContent = '';
    if (rows.length === 0) {
      const empty = document.createElement('tr');
      const cell = document.createElement('td');
      cell.colSpan = 3;
      cell.textContent = '表里没有报告。';
      empty.appendChild(cell);
      elements.reports.appendChild(empty);
      return;
    }
    for (const row of rows) {
      const tr = document.createElement('tr');
      [row.id, row.title, row.body].forEach(value => {
        const td = document.createElement('td');
        td.textContent = String(value);
        tr.appendChild(td);
      });
      elements.reports.appendChild(tr);
    }
  }

  /** The one place a body is parsed: JSON.parse, no eval, and a failure is shown rather than swallowed. */
  function parse(text) {
    try {
      return JSON.parse(text);
    } catch (notJson) {
      return null;
    }
  }

  elements.login.addEventListener('submit', async event => {
    event.preventDefault();
    const answer = await call('POST', '/api/sessions',
      form({ username: document.getElementById('username').value, password: document.getElementById('password').value }));
    const payload = parse(answer.text);
    if (answer.status === 200 && payload && payload.token) {
      state.token = payload.token;
      state.user = payload.user;
      say('已登录为 ' + payload.user + '。token 只在这个页面的内存里，刷新即失效。');
    } else {
      state.token = null;
      state.user = null;
      say('登录失败（' + answer.status + '）。框架不会说出口令错还是用户不存在。');
    }
  });

  elements.refresh.addEventListener('click', async () => {
    const answer = await call('GET', '/api/session');
    const payload = parse(answer.text);
    say(answer.status === 200 && payload
      ? '会话属于 ' + payload.user + '，权限：' + (payload.permissions || '（无）')
      : '没有有效会话（' + answer.status + '）。');
  });

  elements.logout.addEventListener('click', async () => {
    await call('DELETE', '/api/sessions');
    state.token = null;
    state.user = null;
    say('已退出。再用同一个 token 请求一次，就会看到 401。');
  });

  elements.load.addEventListener('click', async () => {
    const answer = await call('GET', '/api/reports');
    const rows = parse(answer.text);
    if (answer.status === 200 && Array.isArray(rows)) {
      renderReports(rows);
      say('读到 ' + rows.length + ' 条报告，读取者写在 X-Reader 里。');
    } else {
      renderReports([]);
      say('列表没有取到（' + answer.status + '）。');
    }
  });

  // A second seeded account with no grants: the point is to watch 403 and 401 stay different answers.
  elements.forbidden.addEventListener('click', async () => {
    const login = await call('POST', '/api/sessions', form({ username: 'bob', password: 'hunter2' }));
    const payload = parse(login.text);
    if (login.status !== 200 || !payload) {
      say('bob 登录失败，演示中断（' + login.status + '）。');
      return;
    }
    state.token = payload.token;
    state.user = payload.user;
    const answer = await call('GET', '/api/reports');
    // The session in memory is bob's now, and the page says so: what comes next will be 403 until you sign in again,
    // which is what "identity is per-request, not ambient" looks like from the client side.
    say(answer.status === 403
      ? 'bob 有有效会话但没有 report:read —— 这就是 403，不是 401。当前内存里的 token 已是 bob 的，要重新登录才拿回权限。'
      : '期望 403，实际 ' + answer.status + '。');
  });

  // 413 comes from the adapter, not from a handler: the limit is one mebibyte in both servers (ADR-0031).
  elements.oversize.addEventListener('click', async () => {
    const answer = await call('POST', '/api/reports',
      form({ title: 'too big', body: 'x'.repeat(1024 * 1024 + 64) }));
    say(answer.status === 413 ? '服务器拒了：Content Too Large（413）。' : '实际状态是 ' + answer.status + '。');
  });

  elements.document.addEventListener('click', async () => {
    const answer = await call('GET', '/api/document');
    const payload = parse(answer.text);
    say(payload && payload.openapi
      ? '文档是 ' + answer.text.length + ' 字节，openapi ' + payload.openapi + '，声明了 '
        + Object.keys(payload.paths || {}).length + ' 条路径。'
      : '文档取回状态 ' + answer.status + '。');
  });

  elements.create.addEventListener('submit', async event => {
    event.preventDefault();
    const answer = await call('POST', '/api/reports',
      form({ title: document.getElementById('title').value, body: document.getElementById('body').value }));
    say(answer.status === 201 ? '已新增（201）。' : '新增返回 ' + answer.status + '。');
    document.getElementById('title').value = '';
    document.getElementById('body').value = '';
  });
})();
