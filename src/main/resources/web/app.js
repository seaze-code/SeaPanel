(() => {
  const TOKEN_KEY = 'seapanel_token';
  const THEME_KEY = 'seapanel_theme';

  let token = localStorage.getItem(TOKEN_KEY) || '';
  let activeTab = 'overview';
  let pollTimer = null;
  let eventSource = null;
  let lastLogId = 0;
  let cachedPlayers = [];
  let cachedPlugins = [];
  const scanReports = new Map();
  const tpsHistory = [];
  const cmdHistory = [];
  let cmdHistoryIdx = -1;

  // Config editor state
  let editorScope = '';
  let editorCurrentFile = '';

  const $ = (id) => document.getElementById(id);

  const MC_FALLBACK_SVG = `
    <svg viewBox="0 0 32 32" xmlns="http://www.w3.org/2000/svg" aria-label="Minecraft Icon">
      <rect width="32" height="32" fill="#593d29"/>
      <rect width="32" height="10" fill="#5b9e32"/>
      <rect x="0" y="10" width="4" height="3" fill="#5b9e32"/>
      <rect x="8" y="10" width="5" height="4" fill="#5b9e32"/>
      <rect x="18" y="10" width="6" height="3" fill="#5b9e32"/>
      <rect x="27" y="10" width="5" height="4" fill="#5b9e32"/>
      <rect x="4" y="3" width="4" height="3" fill="#70b93e"/>
      <rect x="16" y="2" width="5" height="3" fill="#70b93e"/>
      <rect x="6" y="17" width="4" height="4" fill="#462e1e"/>
      <rect x="19" y="21" width="5" height="4" fill="#462e1e"/>
      <rect x="11" y="25" width="4" height="4" fill="#6f4f37"/>
    </svg>`;

  function initServerIcons() {
    document.querySelectorAll('[data-server-icon]').forEach((box) => {
      const img = document.createElement('img');
      img.alt = 'Server Icon';
      img.src = '/api/server-icon?t=' + Date.now();
      img.onerror = () => {
        box.innerHTML = MC_FALLBACK_SVG;
      };
      box.innerHTML = '';
      box.appendChild(img);
    });
  }
  initServerIcons();

  // Theme
  function updateThemeIcon() {
    const isLight = document.documentElement.getAttribute('data-theme') === 'light';
    $('themeIcon').innerHTML = isLight
      ? '<circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.34 17.66-1.41 1.41"/><path d="m19.07 4.93-1.41 1.41"/>'
      : '<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/>';
  }

  $('themeToggle').addEventListener('click', () => {
    const cur = document.documentElement.getAttribute('data-theme') || 'dark';
    const next = cur === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    localStorage.setItem(THEME_KEY, next);
    updateThemeIcon();
  });
  updateThemeIcon();

  // Toast
  function toast(msg, isError = false) {
    const el = document.createElement('div');
    el.className = 'toast' + (isError ? ' error' : '');
    el.textContent = msg;
    $('toastContainer').appendChild(el);
    setTimeout(() => el.remove(), 3200);
  }

  // Custom sleek dialog (replaces native browser prompt/confirm)
  function showDialog({ title, message, withInput = false, defaultValue = '', placeholder = '', presets = [], confirmText = 'Confirm', danger = false }) {
    return new Promise((resolve) => {
      $('dialogTitle').textContent = title || 'Confirm';
      $('dialogMessage').textContent = message || '';

      const inputWrap = $('dialogInputWrap');
      const inputEl = $('dialogInput');
      const presetsEl = $('dialogPresets');
      presetsEl.innerHTML = '';

      if (withInput) {
        inputWrap.classList.remove('hidden');
        inputEl.value = defaultValue;
        inputEl.placeholder = placeholder;
        for (const p of presets) {
          const btn = document.createElement('button');
          btn.type = 'button';
          btn.className = 'chip-btn';
          btn.textContent = p;
          btn.addEventListener('click', () => { inputEl.value = p; inputEl.focus(); });
          presetsEl.appendChild(btn);
        }
      } else {
        inputWrap.classList.add('hidden');
      }

      const confirmBtn = $('dialogConfirmBtn');
      confirmBtn.textContent = confirmText;
      confirmBtn.className = danger ? 'btn btn-danger' : 'btn btn-primary';

      $('dialogModal').classList.remove('hidden');
      if (withInput) {
        setTimeout(() => { inputEl.focus(); inputEl.select(); }, 30);
      }

      function cleanup(result) {
        $('dialogModal').classList.add('hidden');
        $('dialogForm').removeEventListener('submit', onSubmit);
        $('dialogCancelBtn').removeEventListener('click', onCancel);
        $('dialogCloseBtn').removeEventListener('click', onCancel);
        $('dialogBackdrop').removeEventListener('click', onCancel);
        resolve(result);
      }

      function onSubmit(e) {
        e.preventDefault();
        cleanup(withInput ? inputEl.value : true);
      }

      function onCancel() {
        cleanup(null);
      }

      $('dialogForm').addEventListener('submit', onSubmit);
      $('dialogCancelBtn').addEventListener('click', onCancel);
      $('dialogCloseBtn').addEventListener('click', onCancel);
      $('dialogBackdrop').addEventListener('click', onCancel);
    });
  }

  // API helper
  async function api(path, options = {}) {
    const headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
    if (token) headers['Authorization'] = 'Bearer ' + token;

    const res = await fetch(path, Object.assign({}, options, { headers }));
    if (res.status === 401 && path !== '/api/login' && path !== '/api/auth/check') {
      handleUnauthorized();
      throw new Error('Session expired');
    }
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || ('HTTP ' + res.status));
    }
    return data;
  }

  function handleUnauthorized() {
    token = '';
    localStorage.removeItem(TOKEN_KEY);
    stopLive();
    $('appView').classList.add('hidden');
    $('loginView').classList.remove('hidden');
  }

  async function initAuth() {
    try {
      const check = await api('/api/auth/check');
      if (check.serverName) {
        $('loginServerName').textContent = check.serverName;
        $('navServerName').textContent = check.serverName;
      }
      if (check.authenticated) {
        enterApp();
      } else {
        handleUnauthorized();
      }
    } catch {
      handleUnauthorized();
    }
  }

  $('loginForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const errEl = $('loginError');
    errEl.classList.add('hidden');
    try {
      const res = await api('/api/login', {
        method: 'POST',
        body: JSON.stringify({ password: $('loginPassword').value })
      });
      token = res.token;
      localStorage.setItem(TOKEN_KEY, token);
      if (res.serverName) $('navServerName').textContent = res.serverName;
      $('loginPassword').value = '';
      enterApp();
    } catch (err) {
      errEl.textContent = err.message || 'Invalid password';
      errEl.classList.remove('hidden');
    }
  });

  $('logoutBtn').addEventListener('click', async () => {
    try { await api('/api/logout', { method: 'POST' }); } catch {}
    handleUnauthorized();
  });

  function enterApp() {
    $('loginView').classList.add('hidden');
    $('appView').classList.remove('hidden');
    refreshAll();
    startLive();
  }

  // Tabs
  document.querySelectorAll('[data-tab]').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      const tab = btn.getAttribute('data-tab');
      activeTab = tab;
      document.querySelectorAll('.nav-link').forEach((n) => {
        n.classList.toggle('active', n.getAttribute('data-tab') === tab);
      });
      document.querySelectorAll('.tab-panel').forEach((p) => {
        p.classList.toggle('active', p.id === 'tab-' + tab);
      });
      if (tab === 'worlds') loadWorldsAndWhitelist();
      if (tab === 'players') loadPlayers();
      if (tab === 'plugins') loadPlugins();
    });
  });

  function startLive() {
    stopLive();
    pollTimer = setInterval(() => {
      loadStats();
      if (activeTab === 'players') loadPlayers();
      if (activeTab === 'worlds') loadWorldsAndWhitelist();
    }, 3000);
    connectConsole();
  }

  function stopLive() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
    if (eventSource) { eventSource.close(); eventSource = null; }
  }

  async function refreshAll() {
    await Promise.all([loadStats(), loadPlayers(), loadPlugins(), loadWorldsAndWhitelist()]);
  }

  function renderTpsSparkline() {
    const svg = $('tpsSparkline');
    if (!svg || tpsHistory.length < 2) return;
    const w = 200, h = 28;
    const pts = tpsHistory.map((v, i) => {
      const x = (i / (tpsHistory.length - 1)) * w;
      const clamped = Math.max(0, Math.min(20, v));
      const y = h - 3 - ((clamped / 20) * (h - 6));
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    }).join(' ');
    const latest = tpsHistory[tpsHistory.length - 1];
    const color = latest >= 18 ? '#22c55e' : (latest >= 15 ? '#f59e0b' : '#ef4444');
    svg.innerHTML = `<polyline fill="none" stroke="${color}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" points="${pts}"/>`;
  }

  // Stats
  function formatUptime(sec) {
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    const s = sec % 60;
    return `${h}h ${m}m ${s}s`;
  }

  async function loadStats() {
    try {
      const s = await api('/api/stats');
      $('navServerName').textContent = s.serverName || 'SeaPanel';
      $('serverVersionText').textContent = `MC ${s.mcVersion}`;
      $('motdText').textContent = s.motd || 'Minecraft Server';

      $('statTps').textContent = (s.tps || []).map((v) => v.toFixed(1)).join(' / ');
      $('statMspt').textContent = `MSPT: ${s.mspt} ms`;
      if (s.tps && s.tps.length) {
        tpsHistory.push(Number(s.tps[0]));
        if (tpsHistory.length > 24) tpsHistory.shift();
        renderTpsSparkline();
      }

      $('statPlayers').textContent = `${s.onlinePlayers} / ${s.maxPlayers}`;
      $('navPlayerCount').textContent = s.onlinePlayers;
      const pPct = s.maxPlayers > 0 ? Math.min(100, (s.onlinePlayers / s.maxPlayers) * 100) : 0;
      $('playersBar').style.width = pPct + '%';

      const used = s.memory?.usedMb || 0;
      const max = s.memory?.maxMb || 1;
      $('statMemory').textContent = `${used} / ${max} MB`;
      $('memoryBar').style.width = Math.min(100, (used / max) * 100) + '%';

      $('statCpu').textContent = s.cpuPercent >= 0 ? `${s.cpuPercent}%` : 'N/A';
      $('statUptime').textContent = `Uptime: ${formatUptime(s.uptimeSeconds || 0)}`;

      $('infoVersion').textContent = s.serverVersion;
      $('infoGamemode').textContent = s.defaultGameMode;
      $('infoWhitelist').textContent = s.whitelistEnabled ? 'Enabled' : 'Disabled';
      $('infoCounts').textContent = `${s.worldCount} worlds / ${s.pluginCount} plugins`;
    } catch {}
  }

  $('btnSaveAll').addEventListener('click', async () => {
    try {
      await api('/api/server/action', { method: 'POST', body: JSON.stringify({ action: 'save' }) });
      toast('Saved all worlds and players.');
    } catch (e) { toast(e.message, true); }
  });

  $('btnStopServer').addEventListener('click', async () => {
    const ok = await showDialog({
      title: 'Stop Server',
      message: 'Are you sure you want to shut down the Minecraft server? All online players will be disconnected.',
      confirmText: 'Stop Server',
      danger: true
    });
    if (!ok) return;
    try {
      await api('/api/server/action', { method: 'POST', body: JSON.stringify({ action: 'stop' }) });
      toast('Server is shutting down...');
    } catch (e) { toast(e.message, true); }
  });

  $('broadcastForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const msg = $('broadcastInput').value.trim();
    if (!msg) return;
    try {
      await api('/api/server/action', { method: 'POST', body: JSON.stringify({ action: 'broadcast', message: msg }) });
      $('broadcastInput').value = '';
      toast('Broadcast sent.');
    } catch (err) { toast(err.message, true); }
  });

  // Players
  $('playerSearch').addEventListener('input', renderPlayers);

  async function loadPlayers() {
    try {
      const res = await api('/api/players');
      cachedPlayers = res.players || [];
      $('navPlayerCount').textContent = cachedPlayers.length;
      renderPlayers();
    } catch {}
  }

  function renderPlayers() {
    const q = ($('playerSearch').value || '').toLowerCase();
    const list = cachedPlayers.filter((p) => p.name.toLowerCase().includes(q));
    const grid = $('playersGrid');
    grid.innerHTML = '';

    if (list.length === 0) {
      $('playersEmpty').classList.remove('hidden');
      return;
    }
    $('playersEmpty').classList.add('hidden');

    for (const p of list) {
      const card = document.createElement('div');
      card.className = 'player-card';
      card.innerHTML = `
        <div class="player-top">
          <img class="player-avatar" src="https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/64" alt="${escapeHtml(p.name)}">
          <div>
            <div class="player-name">${escapeHtml(p.name)} ${p.isOp ? '<span class="op-tag">OP</span>' : ''}</div>
            <div class="muted-sm mono">${escapeHtml(p.gameMode)} &bull; ${p.ping}ms</div>
          </div>
        </div>
        <div class="player-stats">
          <span>HP ${p.health}/${p.maxHealth}</span>
          <span>Food ${p.food}/20</span>
          <span>${escapeHtml(p.world)} (${p.x}, ${p.y}, ${p.z})</span>
        </div>
      `;
      card.addEventListener('click', () => openPlayerModal(p.uuid));
      grid.appendChild(card);
    }
  }

  async function openPlayerModal(uuid) {
    try {
      const p = await api('/api/players/' + encodeURIComponent(uuid));
      $('modalPlayerAvatar').src = `https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/64`;
      $('modalPlayerName').textContent = p.name + (p.isOp ? ' [OP]' : '');
      $('modalPlayerMeta').textContent = `${p.world} (${p.x}, ${p.y}, ${p.z}) • Ping ${p.ping}ms • Lv ${p.expLevel}`;

      const invHtml = (p.inventory || []).length
        ? p.inventory.map((it) => `
            <div class="inv-item">
              <div class="inv-item-name">${escapeHtml(it.displayName || it.material)} x${it.amount}</div>
              <div class="inv-item-sub">Slot ${it.slot}${it.enchantments ? ' • ' + escapeHtml(it.enchantments.join(', ')) : ''}</div>
            </div>`).join('')
        : '<div class="muted-sm">Inventory is empty.</div>';

      $('playerModalBody').innerHTML = `
        <div>
          <div class="field-label">Quick Actions</div>
          <div class="btn-group">
            <button class="btn btn-secondary btn-sm" data-pact="heal">Heal &amp; Feed</button>
            <button class="btn btn-secondary btn-sm" data-pact="toggle_fly">${p.allowFlight ? 'Disable Fly' : 'Enable Fly'}</button>
            <button class="btn btn-secondary btn-sm" data-pact="${p.isOp ? 'deop' : 'op'}">${p.isOp ? 'Remove OP' : 'Give OP'}</button>
            <button class="btn btn-secondary btn-sm" data-gm="SURVIVAL">Survival</button>
            <button class="btn btn-secondary btn-sm" data-gm="CREATIVE">Creative</button>
            <button class="btn btn-secondary btn-sm" data-gm="ADVENTURE">Adventure</button>
            <button class="btn btn-secondary btn-sm" data-gm="SPECTATOR">Spectator</button>
            <button class="btn btn-danger btn-sm" data-pact="clear_inv">Clear Inv</button>
            <button class="btn btn-danger btn-sm" data-pact="kill">Kill</button>
            <button class="btn btn-danger btn-sm" data-pact="kick">Kick</button>
            <button class="btn btn-danger btn-sm" data-pact="ban">Ban</button>
          </div>
        </div>

        <div>
          <div class="field-label">Send Private Message</div>
          <div class="inline-form">
            <input type="text" id="pmInput" class="input" placeholder="Hello!">
            <button class="btn btn-primary btn-sm" id="pmSendBtn">Send</button>
          </div>
        </div>

        <div>
          <div class="field-label">Teleport to Coordinates</div>
          <div class="inline-form">
            <input type="number" id="tpX" class="input" value="${p.x}" placeholder="X">
            <input type="number" id="tpY" class="input" value="${p.y}" placeholder="Y">
            <input type="number" id="tpZ" class="input" value="${p.z}" placeholder="Z">
            <button class="btn btn-secondary btn-sm" id="tpBtn">TP</button>
          </div>
        </div>

        <div>
          <div class="field-label">Inventory (${(p.inventory || []).length} stacks)</div>
          <div class="inv-grid">${invHtml}</div>
        </div>
      `;

      $('playerModalBody').querySelectorAll('[data-pact]').forEach((b) => {
        b.addEventListener('click', async () => {
          const action = b.getAttribute('data-pact');
          const payload = { action };

          if (action === 'kick' || action === 'ban') {
            const reason = await showDialog({
              title: `${action === 'ban' ? 'Ban' : 'Kick'} ${p.name}`,
              message: `Enter a reason to ${action} this player:`,
              withInput: true,
              defaultValue: `${action === 'ban' ? 'Banned' : 'Kicked'} by SeaPanel`,
              placeholder: 'Reason...',
              presets: ['Griefing', 'Cheating / Hacking', 'Spamming', 'Rule Violation'],
              confirmText: action === 'ban' ? 'Ban Player' : 'Kick Player',
              danger: true
            });
            if (reason === null) return;
            payload.reason = reason;
          } else if (action === 'clear_inv' || action === 'kill') {
            const ok = await showDialog({
              title: action === 'kill' ? `Kill ${p.name}` : `Clear ${p.name}'s Inventory`,
              message: action === 'kill'
                ? `Are you sure you want to kill ${p.name}?`
                : `Are you sure you want to wipe all items from ${p.name}'s inventory?`,
              confirmText: 'Confirm',
              danger: true
            });
            if (!ok) return;
          }

          await runPlayerAction(p.uuid, payload);
          if (action === 'kick' || action === 'ban') closeModal('playerModal');
          else openPlayerModal(p.uuid);
        });
      });

      $('playerModalBody').querySelectorAll('[data-gm]').forEach((b) => {
        b.addEventListener('click', async () => {
          await runPlayerAction(p.uuid, { action: 'gamemode', mode: b.getAttribute('data-gm') });
          openPlayerModal(p.uuid);
        });
      });

      $('pmSendBtn').addEventListener('click', async () => {
        const msg = $('pmInput').value.trim();
        if (!msg) return;
        await runPlayerAction(p.uuid, { action: 'message', message: msg });
        $('pmInput').value = '';
      });

      $('tpBtn').addEventListener('click', async () => {
        await runPlayerAction(p.uuid, {
          action: 'teleport',
          world: p.world,
          x: Number($('tpX').value),
          y: Number($('tpY').value),
          z: Number($('tpZ').value)
        });
      });

      $('playerModal').classList.remove('hidden');
    } catch (e) {
      toast(e.message, true);
    }
  }

  async function runPlayerAction(uuid, payload) {
    try {
      await api(`/api/players/${encodeURIComponent(uuid)}/action`, {
        method: 'POST',
        body: JSON.stringify(payload)
      });
      toast('Action applied.');
      loadPlayers();
    } catch (e) {
      toast(e.message, true);
    }
  }

  // Bans
  $('btnViewBans').addEventListener('click', async () => {
    try {
      const res = await api('/api/bans');
      const bans = res.bans || [];
      $('bansModalBody').innerHTML = bans.length
        ? bans.map((b) => `
            <div class="kv-row">
              <div>
                <div class="mono" style="font-weight:600;">${escapeHtml(b.target)}</div>
                <div class="muted-sm">${escapeHtml(b.reason || 'No reason')}</div>
              </div>
              <button class="btn btn-secondary btn-sm" data-unban="${escapeHtml(b.target)}">Unban</button>
            </div>`).join('')
        : '<div class="muted-sm">No banned players.</div>';

      $('bansModalBody').querySelectorAll('[data-unban]').forEach((btn) => {
        btn.addEventListener('click', async () => {
          const target = btn.getAttribute('data-unban');
          await api('/api/bans/unban', { method: 'POST', body: JSON.stringify({ target }) });
          toast(`Unbanned ${target}`);
          $('btnViewBans').click();
        });
      });
      $('bansModal').classList.remove('hidden');
    } catch (e) { toast(e.message, true); }
  });

  // Plugins & Security
  $('pluginSearch').addEventListener('input', renderPlugins);

  $('btnServerConfigs').addEventListener('click', () => {
    openConfigEditor('_server', 'Server Configs');
  });

  $('btnScanAllPlugins').addEventListener('click', async () => {
    const btn = $('btnScanAllPlugins');
    btn.disabled = true;
    btn.textContent = 'Scanning...';
    try {
      const res = await api('/api/plugins/scan-all', { method: 'POST' });
      for (const r of (res.results || [])) {
        if (r.plugin) scanReports.set(r.plugin, r);
      }
      renderPlugins();
      toast(`Scanned ${(res.results || []).length} plugins.`);
    } catch (e) {
      toast(e.message, true);
    } finally {
      btn.disabled = false;
      btn.textContent = 'Scan All Plugins';
    }
  });

  async function loadPlugins() {
    try {
      const res = await api('/api/plugins');
      cachedPlugins = res.plugins || [];
      renderPlugins();
    } catch {}
  }

  function renderPlugins() {
    const q = ($('pluginSearch').value || '').toLowerCase();
    const list = cachedPlugins.filter((p) => p.name.toLowerCase().includes(q));
    const grid = $('pluginsGrid');
    grid.innerHTML = '';

    for (const p of list) {
      const report = scanReports.get(p.name);
      const verdictHtml = report
        ? `<span class="verdict-badge verdict-${escapeHtml(report.verdict)}">${escapeHtml(report.verdict.replace('_', ' '))}</span>`
        : `<span class="verdict-badge">${p.enabled ? 'ENABLED' : 'DISABLED'}</span>`;

      const card = document.createElement('div');
      card.className = 'plugin-card';
      card.innerHTML = `
        <div>
          <div class="plugin-head">
            <div>
              <div class="plugin-title">${escapeHtml(p.name)}</div>
              <div class="plugin-ver">v${escapeHtml(p.version)} &bull; ${p.sizeKb} KB</div>
            </div>
            ${verdictHtml}
          </div>
          <p class="muted-sm" style="margin-top:8px;">${escapeHtml(p.description || 'No description provided.')}</p>
        </div>
        <div class="btn-group">
          <button class="btn btn-secondary btn-sm" data-scan="${escapeHtml(p.name)}">${report ? 'View Report' : 'Scan Malware'}</button>
          <button class="btn btn-secondary btn-sm" data-cfg="${escapeHtml(p.name)}">Edit Config</button>
          ${p.name !== 'SeaPanel' ? `<button class="btn ${p.enabled ? 'btn-danger' : 'btn-secondary'} btn-sm" data-ptoggle="${escapeHtml(p.name)}">${p.enabled ? 'Disable' : 'Enable'}</button>` : ''}
        </div>
      `;

      const toggleBtn = card.querySelector('[data-ptoggle]');
      if (toggleBtn) {
        toggleBtn.addEventListener('click', async () => {
          try {
            const res = await api(`/api/plugins/${encodeURIComponent(p.name)}/toggle`, { method: 'POST' });
            toast(`${p.name} ${res.enabled ? 'enabled' : 'disabled'}`);
            loadPlugins();
          } catch (e) { toast(e.message, true); }
        });
      }

      card.querySelector('[data-scan]').addEventListener('click', async () => {
        if (scanReports.has(p.name)) {
          openScanModal(scanReports.get(p.name));
          return;
        }
        try {
          const r = await api(`/api/plugins/${encodeURIComponent(p.name)}/scan`, { method: 'POST' });
          scanReports.set(p.name, r);
          renderPlugins();
          openScanModal(r);
        } catch (e) { toast(e.message, true); }
      });

      card.querySelector('[data-cfg]').addEventListener('click', () => {
        openConfigEditor(p.name, `${p.name} Configs`);
      });

      grid.appendChild(card);
    }
  }

  function openScanModal(r) {
    $('scanModalTitle').textContent = `${r.plugin} — Security Scan`;
    $('scanModalSub').textContent = `${r.jarName} • ${r.classCount} classes • Verdict: ${r.verdict}`;

    const findings = r.findings || [];
    $('scanModalBody').innerHTML = findings.length
      ? findings.map((f) => `
          <div class="finding-card">
            <div class="finding-head">
              <span class="mono" style="font-weight:700;">${escapeHtml(f.id)}</span>
              <span class="verdict-badge verdict-${f.severity === 'HIGH' ? 'HIGH_RISK' : (f.severity === 'MEDIUM' ? 'SUSPICIOUS' : 'LOW_RISK')}">${escapeHtml(f.severity)}</span>
            </div>
            <div class="muted-sm">${escapeHtml(f.description)}</div>
            <div class="finding-classes">Matched in: ${escapeHtml((f.classes || []).join(', '))}</div>
          </div>`).join('')
      : '<div class="empty-state">No suspicious bytecode patterns or webhooks detected in this JAR.</div>';

    $('scanModal').classList.remove('hidden');
  }

  async function openConfigEditor(scope, title) {
    editorScope = scope;
    editorCurrentFile = '';
    $('editorModalTitle').textContent = title;
    $('editorFilePath').textContent = 'Select a file from the left panel';
    $('editorTextarea').value = '';
    $('editorSaveBtn').disabled = true;
    $('editorFileList').innerHTML = '<div class="muted-sm">Loading files...</div>';
    $('editorModal').classList.remove('hidden');

    try {
      const res = await api(`/api/plugins/${encodeURIComponent(scope)}/files`);
      const files = res.files || [];
      const sidebar = $('editorFileList');
      sidebar.innerHTML = '';

      if (!files.length) {
        sidebar.innerHTML = '<div class="muted-sm">No editable config files found.</div>';
        return;
      }

      for (const f of files) {
        const btn = document.createElement('button');
        btn.className = 'editor-file-btn';
        btn.textContent = f.path;
        btn.addEventListener('click', () => loadConfigFileContent(f.path, btn));
        sidebar.appendChild(btn);
      }
      // Auto-select first file
      sidebar.firstChild.click();
    } catch (e) {
      toast(e.message, true);
    }
  }

  async function loadConfigFileContent(filePath, btnEl) {
    document.querySelectorAll('.editor-file-btn').forEach((b) => b.classList.remove('active'));
    if (btnEl) btnEl.classList.add('active');
    try {
      const res = await api(`/api/plugins/${encodeURIComponent(editorScope)}/file?path=${encodeURIComponent(filePath)}`);
      editorCurrentFile = filePath;
      $('editorFilePath').textContent = filePath;
      $('editorTextarea').value = res.content || '';
      $('editorSaveBtn').disabled = false;
    } catch (e) {
      toast(e.message, true);
    }
  }

  $('editorSaveBtn').addEventListener('click', async () => {
    if (!editorScope || !editorCurrentFile) return;
    try {
      await api(`/api/plugins/${encodeURIComponent(editorScope)}/file`, {
        method: 'POST',
        body: JSON.stringify({ path: editorCurrentFile, content: $('editorTextarea').value })
      });
      toast(`Saved ${editorCurrentFile}`);
    } catch (e) {
      toast(e.message, true);
    }
  });

  // Modals close
  document.querySelectorAll('[data-close]').forEach((el) => {
    el.addEventListener('click', () => closeModal(el.getAttribute('data-close')));
  });
  function closeModal(id) { $(id).classList.add('hidden'); }

  // Console
  async function connectConsole() {
    try {
      const res = await api(`/api/console?since=${lastLogId}`);
      for (const entry of (res.logs || [])) appendLogLine(entry);
    } catch {}

    if (eventSource) eventSource.close();
    eventSource = new EventSource(`/api/console/stream?token=${encodeURIComponent(token)}`);
    eventSource.onmessage = (ev) => {
      try {
        const entry = JSON.parse(ev.data);
        appendLogLine(entry);
      } catch {}
    };
  }

  function appendLogLine(entry) {
    if (entry.id <= lastLogId) return;
    lastLogId = entry.id;
    const out = $('consoleOutput');
    const div = document.createElement('div');
    div.className = 'log-line';
    div.innerHTML = `<span class="log-time">[${escapeHtml(entry.time)}]</span><span class="log-level ${escapeHtml(entry.level)}">${escapeHtml(entry.level)}</span><span>${escapeHtml(entry.message)}</span>`;
    out.appendChild(div);
    while (out.children.length > 500) out.removeChild(out.firstChild);
    if ($('autoScrollCheck').checked) out.scrollTop = out.scrollHeight;
  }

  $('btnClearConsole').addEventListener('click', () => { $('consoleOutput').innerHTML = ''; });

  document.querySelectorAll('.chip-btn[data-cmd]').forEach((b) => {
    b.addEventListener('click', () => runConsoleCommand(b.getAttribute('data-cmd')));
  });

  $('consoleForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const cmd = $('consoleInput').value.trim();
    if (!cmd) return;
    cmdHistory.unshift(cmd);
    cmdHistoryIdx = -1;
    $('consoleInput').value = '';
    await runConsoleCommand(cmd);
  });

  $('consoleInput').addEventListener('keydown', (e) => {
    if (e.key === 'ArrowUp' && cmdHistory.length) {
      e.preventDefault();
      cmdHistoryIdx = Math.min(cmdHistory.length - 1, cmdHistoryIdx + 1);
      $('consoleInput').value = cmdHistory[cmdHistoryIdx];
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      cmdHistoryIdx = Math.max(-1, cmdHistoryIdx - 1);
      $('consoleInput').value = cmdHistoryIdx >= 0 ? cmdHistory[cmdHistoryIdx] : '';
    }
  });

  async function runConsoleCommand(command) {
    try {
      await api('/api/console/command', { method: 'POST', body: JSON.stringify({ command }) });
    } catch (e) { toast(e.message, true); }
  }

  // Worlds & Whitelist
  async function loadWorldsAndWhitelist() {
    try {
      const [wRes, wlRes] = await Promise.all([api('/api/worlds'), api('/api/whitelist')]);
      renderWorlds(wRes.worlds || []);
      renderWhitelist(wlRes);
    } catch {}
  }

  function renderWorlds(worlds) {
    const grid = $('worldsGrid');
    grid.innerHTML = '';
    for (const w of worlds) {
      const weatherText = w.isThundering ? 'Thunder' : (w.hasStorm ? 'Rain' : 'Clear');
      const card = document.createElement('div');
      card.className = 'card';
      card.innerHTML = `
        <div class="card-head-row">
          <h3 class="card-title">${escapeHtml(w.name)}</h3>
          <span class="badge">${escapeHtml(w.environment)}</span>
        </div>
        <div class="world-meta">
          <span class="badge">Players: ${w.players}</span>
          <span class="badge">Chunks: ${w.loadedChunks}</span>
          <span class="badge">Entities: ${w.entities}</span>
          <span class="badge">${escapeHtml(w.difficulty)}</span>
          <span class="badge">${weatherText}</span>
        </div>
        <div class="world-controls">
          <div class="control-row">
            <span class="control-label">Time</span>
            <div class="btn-group">
              <button class="btn btn-secondary btn-sm" data-wact="time" data-preset="day">Day</button>
              <button class="btn btn-secondary btn-sm" data-wact="time" data-preset="noon">Noon</button>
              <button class="btn btn-secondary btn-sm" data-wact="time" data-preset="sunset">Sunset</button>
              <button class="btn btn-secondary btn-sm" data-wact="time" data-preset="midnight">Night</button>
            </div>
          </div>
          <div class="control-row">
            <span class="control-label">Weather</span>
            <div class="btn-group">
              <button class="btn btn-secondary btn-sm" data-wact="weather" data-weather="clear">Clear</button>
              <button class="btn btn-secondary btn-sm" data-wact="weather" data-weather="rain">Rain</button>
              <button class="btn btn-secondary btn-sm" data-wact="weather" data-weather="thunder">Thunder</button>
            </div>
          </div>
          <div class="control-row">
            <span class="control-label">Difficulty</span>
            <div class="btn-group">
              <button class="btn btn-secondary btn-sm" data-wact="difficulty" data-diff="PEACEFUL">Peaceful</button>
              <button class="btn btn-secondary btn-sm" data-wact="difficulty" data-diff="EASY">Easy</button>
              <button class="btn btn-secondary btn-sm" data-wact="difficulty" data-diff="NORMAL">Normal</button>
              <button class="btn btn-secondary btn-sm" data-wact="difficulty" data-diff="HARD">Hard</button>
            </div>
          </div>
        </div>
      `;

      card.querySelectorAll('[data-wact]').forEach((btn) => {
        btn.addEventListener('click', async () => {
          const action = btn.getAttribute('data-wact');
          const body = { action };
          if (action === 'time') body.preset = btn.getAttribute('data-preset');
          if (action === 'weather') body.weather = btn.getAttribute('data-weather');
          if (action === 'difficulty') body.difficulty = btn.getAttribute('data-diff');
          try {
            await api(`/api/worlds/${encodeURIComponent(w.name)}/action`, {
              method: 'POST',
              body: JSON.stringify(body)
            });
            toast(`Updated ${w.name}`);
            loadWorldsAndWhitelist();
          } catch (e) { toast(e.message, true); }
        });
      });

      grid.appendChild(card);
    }
  }

  function renderWhitelist(wl) {
    $('whitelistToggle').checked = !!wl.enabled;
    const container = $('whitelistPlayers');
    container.innerHTML = '';
    for (const p of (wl.players || [])) {
      const chip = document.createElement('span');
      chip.className = 'wl-chip';
      chip.innerHTML = `${escapeHtml(p.name)} <button title="Remove">&times;</button>`;
      chip.querySelector('button').addEventListener('click', async () => {
        await api('/api/whitelist', { method: 'POST', body: JSON.stringify({ action: 'remove', name: p.name }) });
        toast(`Removed ${p.name} from whitelist`);
        loadWorldsAndWhitelist();
      });
      container.appendChild(chip);
    }
  }

  $('whitelistToggle').addEventListener('change', async (e) => {
    try {
      await api('/api/whitelist', { method: 'POST', body: JSON.stringify({ action: 'toggle', enabled: e.target.checked }) });
      toast(`Whitelist ${e.target.checked ? 'enabled' : 'disabled'}`);
    } catch (err) { toast(err.message, true); }
  });

  $('whitelistAddForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const name = $('whitelistNameInput').value.trim();
    if (!name) return;
    try {
      await api('/api/whitelist', { method: 'POST', body: JSON.stringify({ action: 'add', name }) });
      $('whitelistNameInput').value = '';
      toast(`Added ${name} to whitelist`);
      loadWorldsAndWhitelist();
    } catch (err) { toast(err.message, true); }
  });

  document.querySelectorAll('.gm-default-btn').forEach((btn) => {
    btn.addEventListener('click', async () => {
      const mode = btn.getAttribute('data-gm');
      try {
        await api('/api/server/action', { method: 'POST', body: JSON.stringify({ action: 'gamemode', mode }) });
        toast(`Default gamemode set to ${mode}`);
        loadStats();
      } catch (e) { toast(e.message, true); }
    });
  });

  function escapeHtml(str) {
    return String(str ?? '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  initAuth();
})();
