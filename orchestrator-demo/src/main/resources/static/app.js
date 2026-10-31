/**
 * Orchestrator Demo console logic — vanilla JS, same-origin against the
 * Spring Boot app. v2.1: left sidebar navigation over three domains
 * (场景 run/dry-run/config · 元数据 agents/workflow/mcp · 可观测 runs/metrics),
 * scenario CRUD + activation (dynamic instantiation onto the control plane),
 * token-free dry-run rehearsal rendered as DAG + wave/slot tables, and run
 * replay with timeline / DAG / swimlane views incl. parse-retry chains.
 * No build step, no framework.
 */
'use strict';

const $ = (sel) => document.querySelector(sel);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

/* ---------------- sidebar pages & health dot ---------------- */
document.querySelectorAll('.nav-item').forEach((btn) => btn.addEventListener('click', () => {
  document.querySelectorAll('.nav-item, .page-panel').forEach((el) => el.classList.remove('active'));
  btn.classList.add('active');
  $(`#page-${btn.dataset.page}`).classList.add('active');
  // Panels show point-in-time data: refresh on every page activation.
  if (btn.dataset.page === 'run') loadScenarioSelects();
  if (btn.dataset.page === 'dryrun') loadScenarioSelects();
  if (btn.dataset.page === 'scenarios') refreshScenarios();
  if (btn.dataset.page === 'agents' || btn.dataset.page === 'workflow' || btn.dataset.page === 'mcp') refreshControl();
  if (btn.dataset.page === 'runs') refreshRuns();
  if (btn.dataset.page === 'metrics') refreshObservability();
}));

async function refreshHealthDot() {
  const dot = $('#health-dot');
  try {
    const health = await getJson('/actuator/health');
    dot.className = 'dot ' + (health.status === 'UP' ? 'dot-up' : 'dot-down');
    dot.title = `应用健康状态: ${health.status}`;
    return health;
  } catch {
    dot.className = 'dot dot-unknown';
    dot.title = '应用健康状态: 不可达';
    return null;
  }
}

/* ---------------- scenario bar (run page) ---------------- */
let scenarioCache = [];

async function loadScenarioSelects() {
  try {
    scenarioCache = (await getJson('/api/v1/scenarios')).scenarios || [];
  } catch { scenarioCache = []; }
  for (const sel of ['#run-scenario-select', '#dryrun-scenario-select']) {
    const el = $(sel);
    if (!el) continue;
    el.innerHTML = scenarioCache.map((s) =>
      `<option value="${esc(s.id)}">${esc(s.name)}（${esc(s.id)}）</option>`).join('');
  }
}

$('#activate-btn').addEventListener('click', async () => {
  const id = $('#run-scenario-select').value;
  const msg = $('#activate-msg');
  if (!id) return;
  const btn = $('#activate-btn');
  btn.disabled = true; msg.textContent = '激活中…（工作流/Agent 覆盖热应用到控制平面）';
  try {
    const cp = await postJson(`/api/v1/scenarios/${encodeURIComponent(id)}/activate`, {});
    msg.textContent = `场景已激活：模板「${esc(cp.workflow.mode)}」已热切换（version ${cp.version}），下一次运行即按场景执行`;
    msg.className = 'hint ok';
    const scn = scenarioCache.find((s) => s.id === id);
    $('#active-scenario-badge').textContent = scn ? scn.name : id;
    $('#active-scenario-badge').className = 'badge badge-ok';
  } catch (e) {
    msg.textContent = '激活失败: ' + e.message; msg.className = 'hint';
  } finally { btn.disabled = false; }
});

/* ---------------- planning workspace ---------------- */
let pollTimer = null;
let submitStart = 0;

$('#plan-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const body = {
    destination: $('#f-destination').value.trim(),
    numberOfDays: Number($('#f-days').value),
    budget: Number($('#f-budget').value),
    preferences: $('#f-preferences').value.trim(),
  };
  setRunning(true);
  $('#error-card').hidden = true; $('#results').innerHTML = '';
  showRequest(body);
  submitStart = Date.now();
  startProgressPolling();
  try {
    const res = await fetch('/api/trips/plan', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => null);
      showError(res.status, err);
    } else {
      const context = await res.json();
      renderResults(context);
    }
  } catch (e) {
    showError(0, { error: 'Network error', message: String(e) });
  } finally {
    stopProgressPolling();
    setRunning(false);
  }
});

function setRunning(on) {
  $('#submit-btn').disabled = on;
  $('#submit-btn').textContent = on ? '工作流执行中…' : '开始规划';
}

function showRequest(req) {
  $('#echo-card').hidden = false;
  $('#echo-body').innerHTML = `
    <dl>
      <dt>目的地</dt><dd>${esc(req.destination)}</dd>
      <dt>天数</dt><dd>${esc(req.numberOfDays)} 天</dd>
      <dt>预算</dt><dd>CNY ${esc(req.budget)}</dd>
      <dt>偏好</dt><dd>${esc(req.preferences || '(无)')}</dd>
    </dl>`;
}

/* live progress: the run timeline is folded by the ObservabilityActor while
   the POST is still in flight, so polling /api/trips/runs reveals each stage
   as it completes. */
function startProgressPolling() {
  $('#progress-card').hidden = false;
  $('#progress-status').textContent = 'RUNNING';
  $('#progress-status').className = 'badge badge-running';
  $('#progress-stages').innerHTML = '<div class="hint">等待工作流启动…</div>';
  pollTimer = setInterval(pollActiveRun, 2500);
  pollActiveRun();
}

function stopProgressPolling() {
  clearInterval(pollTimer);
  pollTimer = null;
  const secs = ((Date.now() - submitStart) / 1000).toFixed(1);
  $('#progress-elapsed').textContent = `总耗时 ${secs}s`;
}

async function pollActiveRun() {
  try {
    const runs = await getJson('/api/trips/runs');
    const active = runs.find((r) => r.status === 'RUNNING')
      || (runs.length ? runs[0] : null);
    if (!active) return;
    const details = await getJson(`/api/trips/runs/${active.runId}`);
    $('#progress-status').textContent = details.status;
    $('#progress-status').className = 'badge badge-' + details.status;
    const elapsed = ((Date.now() - submitStart) / 1000).toFixed(0);
    $('#progress-elapsed').textContent = `${elapsed}s`;
    const stages = details.waves.flatMap((w) => w.stages);
    $('#progress-stages').innerHTML = stages.length ? stages.map((s) => `
      <div class="stage-line ${s.outcome === 'STAGE_COMPLETED' ? 'done' : 'fail'}">
        <span class="name">${esc(s.stageName)}</span><span class="arrow">→</span>
        <span>${esc(s.agentName)}</span>
        <span class="badge ${s.outcome === 'STAGE_COMPLETED' ? 'badge-ok' : 'badge-failed'}">
          ${s.outcome === 'STAGE_COMPLETED' ? '完成' : '失败'}</span>
        <span class="meta">${s.durationMs != null ? esc(s.durationMs) + ' ms' : ''}
          ${s.threadName ? '· ' + esc(s.threadName) : ''}</span>
      </div>`).join('')
      : '<div class="hint">第一个 Agent 正在调用模型…</div>';
  } catch { /* backend hiccup during polling — keep the previous frame */ }
}

/* ---------------- result cards ---------------- */
function renderResults(ctx) {
  const r = ctx.request || {};
  const research = ctx.researchResponse;
  const budget = ctx.budgetResponse;
  const itinerary = ctx.itineraryResponse;
  const review = ctx.reviewResponse;
  $('#results').innerHTML = `
    <div class="card agent-card"><h2>行程结论 — ReviewAgent
      <span class="badge ${review && review.isValid ? 'badge-ok' : 'badge-failed'}">
        ${review && review.isValid ? '校验通过' : '存在问题'}</span></h2>
      <p>${esc(review && review.summary)}</p>
      ${review && review.issues && review.issues.length ? `<h3>发现的问题</h3><ul>${review.issues.map((i) => `<li>${esc(i)}</li>`).join('')}</ul>` : ''}
      ${review && review.recommendations && review.recommendations.length ? `<h3>改进建议</h3><ul>${review.recommendations.map((i) => `<li>${esc(i)}</li>`).join('')}</ul>` : ''}
    </div>
    <div class="grid-2">
      <div class="card agent-card"><h2>逐日行程 — ItineraryAgent</h2>
        <div class="day-cards">${(itinerary && itinerary.dayPlans || []).map((d) => `
          <div class="day-card"><h4>Day ${esc(d.day)}</h4>
            <p><b>上午</b> ${esc(d.morning)}</p>
            <p><b>下午</b> ${esc(d.afternoon)}</p>
            <p><b>晚上</b> ${esc(d.evening)}</p>
            <p class="cost">预计花费 CNY ${esc(d.estimatedCost)}</p>
          </div>`).join('')}
        </div>
      </div>
      <div>
        <div class="card agent-card"><h2>预算核算 — BudgetAgent
          <span class="badge ${budget && budget.isBudgetMet ? 'badge-ok' : 'badge-failed'}">
            ${budget && budget.isBudgetMet ? '预算达标' : '超出预算'}</span></h2>
          <table>
            <tr><th>住宿</th><td class="num">${esc(budget && budget.accommodation)}</td></tr>
            <tr><th>交通</th><td class="num">${esc(budget && budget.transportCost)}</td></tr>
            <tr><th>餐饮</th><td class="num">${esc(budget && budget.foodCost)}</td></tr>
            <tr><th>活动</th><td class="num">${esc(budget && budget.activityCost)}</td></tr>
            <tr><th><b>总计</b>（用户预算 CNY ${esc(r.budget)}）</th>
                <td class="num"><b>${esc(budget && budget.totalCost)}</b></td></tr>
          </table>
          ${budget && budget.recommendations && budget.recommendations.length ? `<h3>建议</h3><ul>${budget.recommendations.map((i) => `<li>${esc(i)}</li>`).join('')}</ul>` : ''}
        </div>
        <div class="card agent-card"><h2>景点调研 — ResearchAgent</h2>
          <h3>景点</h3><ul>${(research && research.attractions || []).map((i) => `<li>${esc(i)}</li>`).join('')}</ul>
          <h3>美食</h3><ul>${(research && research.foodOptions || []).map((i) => `<li>${esc(i)}</li>`).join('')}</ul>
          <h3>旅行贴士</h3><ul>${(research && research.travelTips || []).map((i) => `<li>${esc(i)}</li>`).join('')}</ul>
        </div>
      </div>
    </div>`;
}

function showError(status, err) {
  $('#error-card').hidden = false;
  $('#error-badge').textContent = status ? `HTTP ${status}` : 'NETWORK';
  $('#error-message').textContent =
    err ? `[${err.error}] ${err.message}` : '未知错误（响应体不是结构化错误）';
}

/* ---------------- runs page ---------------- */
$('#runs-refresh').addEventListener('click', refreshRuns);

async function refreshRuns() {
  let runs = [];
  try { runs = await getJson('/api/trips/runs'); } catch { /* panel shows empty */ }
  const tbody = $('#runs-table tbody');
  tbody.innerHTML = runs.map((r) => `
    <tr data-run="${esc(r.runId)}">
      <td>${esc(fmtTime(r.startedAt))}</td>
      <td>${esc(r.templateName)}</td>
      <td>${esc(r.mode)}</td>
      <td><span class="badge badge-${esc(r.status)}">${esc(r.status)}</span></td>
      <td class="num">${r.durationMs != null ? esc(r.durationMs) + ' ms' : '—'}</td>
      <td class="num">${esc(r.stageCount)}</td>
      <td class="num">${esc(r.failedStages)}</td>
    </tr>`).join('') || '<tr><td colspan="7" class="hint">暂无运行记录，去「场景运行」发起一次请求。</td></tr>';
  tbody.querySelectorAll('tr[data-run]').forEach((tr) =>
    tr.addEventListener('click', () => showRunDetail(tr.dataset.run)));
}

let runDetailData = null;
let runDetailView = 'timeline';

async function showRunDetail(runId) {
  runDetailData = await getJson(`/api/trips/runs/${runId}`);
  runDetailView = 'timeline';
  drawRunDetail();
}

function drawRunDetail() {
  const d = runDetailData;
  let final = null;
  try { final = JSON.parse(d.finalContext); } catch { /* keep raw */ }
  const req = final && final.request;
  const toggle = `
    <span class="view-toggle">
      <button data-view="timeline" class="${runDetailView === 'timeline' ? 'on' : ''}">时间线</button>
      <button data-view="dag" class="${runDetailView === 'dag' ? 'on' : ''}">DAG</button>
      <button data-view="swimlane" class="${runDetailView === 'swimlane' ? 'on' : ''}">泳道</button>
    </span>`;
  $('#run-detail').innerHTML = `
    <div class="card">
      <h2>运行 ${esc(d.runId.slice(0, 8))}…
        <span class="badge badge-${esc(d.status)}">${esc(d.status)}</span>${toggle}</h2>
      <p class="hint">模板 <b>${esc(d.templateName)}</b> · 模式 <b>${esc(d.mode)}</b> ·
        ${esc(fmtTime(d.startedAt))} → ${esc(fmtTime(d.completedAt))}</p>
      <div id="run-view-body"></div>
    </div>`;
  document.querySelectorAll('.view-toggle button').forEach((btn) =>
    btn.addEventListener('click', () => { runDetailView = btn.dataset.view; drawRunDetail(); }));
  const body = $('#run-view-body');
  if (runDetailView === 'dag') {
    body.innerHTML = `<h3>依赖 DAG</h3><div class="viz-card" id="run-dag"></div>`;
    dagEdgesFromRun(d).then((edges) => {
      if (runDetailView !== 'dag') return; // user switched views meanwhile
      const heading = body.querySelector('h3');
      if (heading) heading.textContent = `依赖 DAG（${dagEdgesExact ? '模板精确依赖' : '按波次推导'}）`;
      renderDag('#run-dag', dagNodesFromRun(d), edges, true);
    });
    return;
  }
  if (runDetailView === 'swimlane') {
    body.innerHTML = `<h3>Agent 泳道（时间 × 阶段，⚠ = 解析重试，▲ = MCP 工具调用）</h3><div class="viz-card" id="run-swim"></div>`;
    renderSwimlane('#run-swim', d);
    return;
  }
  body.innerHTML = `
    ${req ? `<h3>用户最初提出的请求</h3>
      <div class="request-echo"><dl>
        <dt>目的地</dt><dd>${esc(req.destination)}</dd>
        <dt>天数</dt><dd>${esc(req.numberOfDays)} 天</dd>
        <dt>预算</dt><dd>CNY ${esc(req.budget)}</dd>
        <dt>偏好</dt><dd>${esc(req.preferences || '(无)')}</dd>
      </dl></div>` : ''}
    <h3>依赖波次与阶段</h3>
    ${d.waves.map((w) => `
      <div class="wave-head">Wave ${esc(w.waveIndex)}
        ${w.parallel ? '<span class="badge badge-running">并行</span>' : ''}
        <span style="margin-left:8px">${w.stages.map((s) => esc(s.stageName)).join(' ∥ ')}</span></div>
      ${w.stages.map((s) => `
        <div class="stage-line ${s.outcome === 'STAGE_COMPLETED' ? 'done' : 'fail'}">
          <span class="name">${esc(s.stageName)}</span><span class="arrow">→</span>
          <span>${esc(s.agentName)}</span>
          <span class="badge ${s.outcome === 'STAGE_COMPLETED' ? 'badge-ok' : 'badge-failed'}">
            ${s.outcome === 'STAGE_COMPLETED' ? '完成' : '失败'}</span>
          <span class="meta">${s.durationMs != null ? esc(s.durationMs) + ' ms' : ''}
            ${s.threadName ? '· ' + esc(s.threadName) : ''}
            ${s.parallelPeers && s.parallelPeers.length ? '· 同波: ' + esc(s.parallelPeers.join(', ')) : ''}</span>
          ${s.errorMessage ? `<span class="meta" style="color:var(--err)">${esc(s.errorMessage)}</span>` : ''}
        </div>
        ${(s.retries || []).map((r) => `
          <div class="tool-line retry-line">
            <span class="badge" style="color:var(--warn);border-color:var(--warn)">↻ retry</span>
            <span>第 ${esc(r.attempt)} 次尝试解析失败 → 重新采样</span>
            <span class="meta">${esc(fmtTime(r.timestamp))}</span>
            ${r.errorMessage ? `<span class="meta" style="color:var(--warn)">${esc(r.errorMessage)}</span>` : ''}
          </div>`).join('')}
        ${(s.toolCalls || []).map((t) => `
          <div class="tool-line">
            <span class="badge ${t.outcome === 'OK' ? 'badge-ok' : 'badge-failed'}">🔧</span>
            <span>MCP <b>${esc(t.serverName)}</b> · ${esc(t.toolName)}</span>
            <span class="meta">${esc(t.durationMs)} ms</span>
            ${t.errorMessage ? `<span class="meta" style="color:var(--err)">${esc(t.errorMessage)}</span>` : ''}
            ${t.resultSummary ? jsonDetails('工具结果', t.resultSummary) : ''}
          </div>`).join('')}
        ${jsonDetails('输入黑板快照', s.inputContext)}
        ${jsonDetails('输出黑板快照', s.outputContext)}`).join('')}`).join('')}
    ${final ? `<h3>最终上下文（黑板终态）</h3>
      <pre class="json">${esc(JSON.stringify(final, null, 2))}</pre>` : ''}`;
}

/* DAG edges for a run: exact dependsOn when the run's stage set matches the
   current control-plane template, otherwise derived from wave adjacency. */
let dagEdgesExact = false;

function dagNodesFromRun(d) {
  return d.waves.flatMap((w) => w.stages.map((s) => ({
    id: s.stageName, wave: w.waveIndex, label: s.stageName,
    sub: `${s.agentName}${s.durationMs != null ? ' · ' + s.durationMs + 'ms' : ''}`,
    status: s.outcome === 'STAGE_COMPLETED' ? 'ok' : 'fail',
    retries: (s.retries || []).length,
  })));
}

async function dagEdgesFromRun(d) {
  const stageNames = new Set(d.waves.flatMap((w) => w.stages.map((s) => s.stageName)));
  try {
    const wf = await getJson('/api/v1/control/workflow');
    const names = new Set(wf.stages.map((s) => s.name));
    const same = names.size === stageNames.size && [...names].every((n) => stageNames.has(n));
    if (same) {
      dagEdgesExact = true;
      return wf.stages.flatMap((s) => s.dependsOn
        .filter((dep) => stageNames.has(dep))
        .map((dep) => ({ from: dep, to: s.name })));
    }
  } catch { /* fall through to wave derivation */ }
  dagEdgesExact = false;
  const edges = [];
  const byWave = new Map(d.waves.map((w) => [w.waveIndex, w.stages.map((s) => s.stageName)]));
  const maxWave = Math.max(...d.waves.map((w) => w.waveIndex));
  for (let w = 1; w <= maxWave; w++) {
    for (const to of byWave.get(w) || []) {
      for (const from of byWave.get(w - 1) || []) edges.push({ from, to });
    }
  }
  return edges;
}

/* ---------------- SVG renderers (DAG & swimlane), no external deps ---------------- */

const STATUS_FILL = { ok: '#234d2c', fail: '#5c2320', planned: '#1c2333', running: '#1f3a5f' };
const STATUS_STROKE = { ok: '#3fb950', fail: '#f85149', planned: '#8b949e', running: '#58a6ff' };

/** Layered DAG: waves as columns, bezier dependency edges. */
function renderDag(sel, nodes, edges, useStatus) {
  const el = $(sel);
  if (!el) return;
  const byWave = new Map();
  nodes.forEach((n) => {
    if (!byWave.has(n.wave)) byWave.set(n.wave, []);
    byWave.get(n.wave).push(n);
  });
  const waves = [...byWave.keys()].sort((a, b) => a - b);
  const colW = 190, rowH = 74, padX = 24, padY = 20;
  const maxRows = Math.max(1, ...waves.map((w) => byWave.get(w).length));
  const width = padX * 2 + colW * waves.length;
  const height = padY * 2 + rowH * maxRows;
  const pos = new Map();
  waves.forEach((w, ci) => {
    byWave.get(w).forEach((n, ri) => {
      pos.set(n.id, {
        x: padX + ci * colW + 14, y: padY + ri * rowH + 8,
        w: colW - 28, h: rowH - 22, n,
      });
    });
  });
  const svg = [`<svg viewBox="0 0 ${width} ${height}" xmlns="http://www.w3.org/2000/svg">`,
    `<defs><marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto">
       <path d="M0,0 L7,3 L0,6 Z" fill="#8b949e"/></marker></defs>`];
  edges.forEach((e) => {
    const a = pos.get(e.from), b = pos.get(e.to);
    if (!a || !b) return;
    const x1 = a.x + a.w, y1 = a.y + a.h / 2, x2 = b.x, y2 = b.y + b.h / 2;
    const mx = (x1 + x2) / 2;
    svg.push(`<path class="dag-edge" d="M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}"/>`);
  });
  pos.forEach((p) => {
    const status = useStatus ? (p.n.status || 'planned') : 'planned';
    const fill = p.n.wave != null && useStatus ? (STATUS_FILL[status] || STATUS_FILL.planned) : STATUS_FILL.planned;
    const stroke = p.n.wave != null && useStatus ? (STATUS_STROKE[status] || STATUS_STROKE.planned) : STATUS_STROKE.planned;
    const retryBadge = p.n.retries ? `<text x="${p.x + p.w - 14}" y="${p.y + 13}" class="retry-mark">⚠${p.n.retries}</text>` : '';
    svg.push(`<rect x="${p.x}" y="${p.y}" width="${p.w}" height="${p.h}" rx="8"
        fill="${fill}" stroke="${stroke}" stroke-width="1.4"/>
      <text x="${p.x + 10}" y="${p.y + 18}" class="dag-node-label">${esc(p.n.label)}</text>
      <text x="${p.x + 10}" y="${p.y + 33}" class="dag-node-sub">${esc(p.n.sub || '')}</text>${retryBadge}`);
  });
  svg.push('</svg>');
  el.innerHTML = svg.join('');
}

/** Swimlane: one lane per agent, bars per stage; time axis when the run has
    per-stage completion timestamps, sequential layout otherwise. */
function renderSwimlane(sel, d) {
  const el = $(sel);
  if (!el) return;
  const stages = d.waves.flatMap((w) => w.stages.map((s) => ({ ...s, wave: w.waveIndex })));
  if (!stages.length) { el.innerHTML = '<div class="hint">该运行没有阶段数据。</div>'; return; }
  const lanes = [...new Set(stages.map((s) => s.agentName))];
  const timed = stages.every((s) => s.completedAt && s.durationMs != null);
  const rowH = 40, laneGap = 10, padL = 110, padR = 30, padT = 26, padB = 30;
  const chartW = Math.max(560, el.clientWidth || 900) - padL - padR;

  // timeline range (timed) or cumulative fake timeline (sequential)
  let tMin = Infinity, tMax = -Infinity;
  stages.forEach((s) => {
    const end = Date.parse(s.completedAt);
    const start = end - s.durationMs;
    tMin = Math.min(tMin, start); tMax = Math.max(tMax, end);
    s._start = start; s._end = end;
  });
  if (!timed) {
    let cursor = 0;
    stages.forEach((s) => {
      s._start = cursor;
      s._end = cursor + (s.durationMs || 1000);
      cursor = s._end;
    });
    tMin = 0; tMax = cursor;
  }
  const span = Math.max(1, tMax - tMin);
  const x = (t) => padL + ((t - tMin) / span) * chartW;
  const laneY = (agent) => padT + lanes.indexOf(agent) * (rowH + laneGap);
  const width = padL + chartW + padR;
  const height = padT + lanes.length * (rowH + laneGap) + padB;
  const fmtAxis = (t) => (timed ? new Date(t).toLocaleTimeString('zh-CN', { hour12: false }) : (t / 1000).toFixed(1) + 's');

  const svg = [`<svg viewBox="0 0 ${width} ${height}" xmlns="http://www.w3.org/2000/svg">`];
  // axis ticks (5)
  for (let i = 0; i <= 5; i++) {
    const t = tMin + (span * i) / 5, px = x(t);
    svg.push(`<line class="swim-axis" x1="${px}" y1="${padT - 8}" x2="${px}" y2="${height - padB + 4}"/>`,
      `<text class="swim-axis-label" x="${px}" y="${padT - 12}" text-anchor="middle">${esc(fmtAxis(t))}</text>`);
  }
  lanes.forEach((agent) => {
    const y = laneY(agent);
    svg.push(`<text class="swim-lane-label" x="10" y="${y + rowH / 2 + 4}">${esc(agent)}</text>`,
      `<line class="swim-axis" x1="${padL}" y1="${y + rowH + laneGap / 2}" x2="${width - padR}" y2="${y + rowH + laneGap / 2}"/>`);
  });
  stages.forEach((s) => {
    const y = laneY(s.agentName) + 6, h = rowH - 12;
    const status = s.outcome === 'STAGE_COMPLETED' ? 'ok' : 'fail';
    const bx = x(s._start), bw = Math.max(6, x(s._end) - x(s._start));
    const label = `${s.stageName} · ${s.durationMs != null ? s.durationMs + 'ms' : ''}`;
    svg.push(`<rect x="${bx}" y="${y}" width="${bw}" height="${h}" rx="6"
        fill="${STATUS_FILL[status]}" stroke="${STATUS_STROKE[status]}" stroke-width="1.2">
      </rect>`);
    if (bw > 70) {
      svg.push(`<text class="dag-node-sub" x="${bx + 8}" y="${y + h / 2 + 4}">${esc(label)}</text>`);
    }
    // retry marks at their timestamps
    (s.retries || []).forEach((r) => {
      if (!r.timestamp) return;
      const rt = Date.parse(r.timestamp);
      if (rt >= s._start && rt <= s._end) {
        svg.push(`<text class="retry-mark" x="${x(rt) - 4}" y="${y + h + 10}">⚠</text>`);
      }
    });
    // tool ticks distributed inside the bar
    (s.toolCalls || []).forEach((t, i) => {
      const tx = bx + 6 + (i + 0.5) * Math.max(1, (bw - 12) / Math.max(1, s.toolCalls.length));
      svg.push(`<rect class="tool-tick" x="${tx}" y="${y + h - 5}" width="3" height="5" rx="1"></rect>`);
    });
  });
  svg.push('</svg>');
  el.innerHTML = svg.join('');
}

function jsonDetails(title, jsonStr) {
  if (!jsonStr) return '';
  let pretty = jsonStr;
  try { pretty = JSON.stringify(JSON.parse(jsonStr), null, 2); } catch { /* raw */ }
  return `<details class="json-details"><summary>${esc(title)}</summary>
    <pre class="json">${esc(pretty)}</pre></details>`;
}

/* ---------------- observability (metrics) page ---------------- */
$('#obs-refresh').addEventListener('click', refreshObservability);
let obsTimer = null;
$('#obs-auto').addEventListener('change', (ev) => {
  clearInterval(obsTimer);
  if (ev.target.checked) obsTimer = setInterval(refreshObservability, 10000);
});

const METRIC_CARDS = [
  { name: 'orchestrator.runs.started', label: '运行发起' },
  { name: 'orchestrator.runs.completed', label: '运行成功' },
  { name: 'orchestrator.runs.failed', label: '运行失败' },
  { name: 'orchestrator.stage.completed', label: '阶段成功' },
  { name: 'orchestrator.stage.failed', label: '阶段失败' },
  { name: 'orchestrator.observability.retained.runs', label: '保留时间线' },
];

async function refreshObservability() {
  const health = await refreshHealthDot();
  /* metric cards */
  const cards = await Promise.all(METRIC_CARDS.map(async (m) => {
    try {
      const data = await getJson(`/actuator/metrics/${m.name}`);
      const count = data.measurements.find((x) => x.statistic === 'COUNT' || x.statistic === 'VALUE');
      return `<div class="metric-card"><div class="label">${esc(m.label)}</div>
        <div class="value">${count ? count.value : '—'}</div>
        <div class="sub">${esc(m.name)}</div></div>`;
    } catch { return `<div class="metric-card"><div class="label">${esc(m.label)}</div><div class="value">—</div></div>`; }
  }));
  $('#metric-cards').innerHTML = cards.join('');
  try {
    const dur = await getJson('/actuator/metrics/orchestrator.run.duration');
    const by = (s) => { const m = dur.measurements.find((x) => x.statistic === s); return m ? m.value : 0; };
    $('#metric-cards').insertAdjacentHTML('beforeend', `
      <div class="metric-card"><div class="label">运行总时长 (s)</div>
        <div class="value">${by('TOTAL_TIME').toFixed(1)}</div>
        <div class="sub">count=${by('COUNT')} · max=${by('MAX').toFixed(1)}s</div></div>`);
  } catch { /* duration card omitted when absent */ }

  /* per-agent table */
  const agents = (health && health.components && health.components.orchestrator
    && health.components.orchestrator.details && health.components.orchestrator.details.agents) || [];
  const rows = await Promise.all(agents.map(async (agent) => {
    const stages = await metricCount('orchestrator.stage.completed', `agent:${agent}`);
    const fails = await metricCount('orchestrator.stage.failed', `agent:${agent}`);
    let exec = { count: '—', total: '—', max: '—' };
    try {
      const d = await getJson(`/actuator/metrics/agent.execution.duration?tag=agent:${agent}`);
      const by = (s) => { const m = d.measurements.find((x) => x.statistic === s); return m ? m.value : null; };
      exec = { count: by('COUNT'), total: by('TOTAL_TIME') == null ? '—' : by('TOTAL_TIME').toFixed(1), max: by('MAX') == null ? '—' : by('MAX').toFixed(1) };
    } catch { /* timer absent yet */ }
    return `<tr><td><b>${esc(agent)}</b></td><td class="num">${stages}</td><td class="num">${fails}</td>
      <td class="num">${exec.count ?? '—'}</td><td class="num">${exec.total}</td><td class="num">${exec.max}</td></tr>`;
  }));
  $('#agent-table tbody').innerHTML = rows.join('')
    || '<tr><td colspan="6" class="hint">未获取到 agent 列表。</td></tr>';

  /* health & info panels */
  if (health) {
    const orch = health.components && health.components.orchestrator;
    $('#health-panel').textContent = JSON.stringify({
      status: health.status,
      orchestrator: orch && { status: orch.status, ...orch.details },
    }, null, 2);
  } else {
    $('#health-panel').textContent = '应用健康端点不可达';
  }
  try {
    const info = await getJson('/actuator/info');
    $('#info-panel').textContent = JSON.stringify(info.workflow ?? info, null, 2);
  } catch { $('#info-panel').textContent = 'info 端点不可达'; }
}

async function metricCount(name, tag) {
  try {
    const url = tag ? `/actuator/metrics/${name}?tag=${encodeURIComponent(tag)}` : `/actuator/metrics/${name}`;
    const d = await getJson(url);
    const m = d.measurements.find((x) => x.statistic === 'COUNT');
    return m ? m.value : 0;
  } catch { return 0; }
}

/* ---------------- live blackboard (SSE) ----------------
   GET /api/v1/events/stream pushes every observability event as it is folded
   into the run timeline: per-stage input/output snapshots, parse retries and
   the final context arrive without any polling latency. */
function connectEvents() {
  const sse = new EventSource('/api/v1/events/stream');
  sse.addEventListener('connected', () =>
    setSseStatus('SSE 已连接', 'badge-ok'));
  sse.addEventListener('run_started', (ev) => {
    const e = JSON.parse(ev.data);
    $('#live-card').hidden = false;
    setSseStatus('SSE 已连接', 'badge-ok');
    setLiveStatus('RUNNING', 'badge-RUNNING');
    $('#live-stages').innerHTML =
      `<div class="hint">运行 ${esc(e.runId.slice(0, 8))}… 已启动（模板 ${esc(e.templateName)} · ${esc(e.mode)}），等待阶段完成推送…</div>`;
    $('#live-context').textContent = '黑板初始化中…';
  });
  sse.addEventListener('stage_completed', (ev) => {
    const e = JSON.parse(ev.data);
    appendLiveStage(e, true);
    if (e.outputContextJson) updateLiveContext(e.outputContextJson);
  });
  sse.addEventListener('stage_failed', (ev) => {
    const e = JSON.parse(ev.data);
    appendLiveStage(e, false);
    updateLiveContext(e.inputContextJson);
  });
  sse.addEventListener('stage_retried', (ev) => {
    const e = JSON.parse(ev.data);
    $('#live-card').hidden = false;
    $('#live-stages').insertAdjacentHTML('beforeend', `
      <div class="tool-line retry-line">
        <span class="badge" style="color:var(--warn);border-color:var(--warn)">↻ retry</span>
        <span>${esc(e.stageName)} → ${esc(e.agentName)}：第 ${esc(e.attempt)} 次尝试解析失败，重新采样</span>
        <span class="meta">${esc(fmtTime(e.timestamp))}</span>
        ${e.errorMessage ? `<span class="meta" style="color:var(--warn)">${esc(e.errorMessage)}</span>` : ''}
      </div>`);
  });
  sse.addEventListener('run_completed', (ev) => {
    setLiveStatus('COMPLETED', 'badge-COMPLETED');
    updateLiveContext(ev.data);
  });
  sse.addEventListener('run_failed', () => setLiveStatus('FAILED', 'badge-FAILED'));
  sse.onerror = () => setSseStatus('SSE 重连中…', 'badge-RUNNING');
}

function setSseStatus(text, cls) {
  const el = $('#sse-status');
  el.textContent = text;
  el.className = 'badge ' + cls;
}

function setLiveStatus(status, cls) {
  const el = $('#live-status');
  el.textContent = status;
  el.className = 'badge ' + cls;
}

function appendLiveStage(e, ok) {
  $('#live-stages').insertAdjacentHTML('beforeend', `
    <div class="stage-line ${ok ? 'done' : 'fail'}">
      <span class="name">${esc(e.stageName)}</span><span class="arrow">→</span>
      <span>${esc(e.agentName)}</span>
      <span class="badge ${ok ? 'badge-ok' : 'badge-failed'}">${ok ? '完成' : '失败'}</span>
      <span class="meta">wave ${esc(e.waveIndex)} · ${e.durationMs != null ? esc(e.durationMs) + ' ms' : ''}
        ${e.threadName ? '· ' + esc(e.threadName) : ''}
        ${e.parallelPeers && e.parallelPeers.length ? '· 同波: ' + esc(e.parallelPeers.join(', ')) : ''}</span>
      ${e.errorMessage ? `<span class="meta" style="color:var(--err)">${esc(e.errorMessage)}</span>` : ''}
    </div>
    ${jsonDetails('该阶段输入黑板快照', e.inputContextJson)}
    ${ok ? jsonDetails('该阶段输出黑板快照', e.outputContextJson) : ''}`);
}

function updateLiveContext(jsonStr) {
  let pretty = jsonStr;
  try { pretty = JSON.stringify(JSON.parse(jsonStr), null, 2); } catch { /* raw */ }
  $('#live-context').textContent = pretty;
}

/* ---------------- control plane pages (agents / workflow / mcp) ---------------- */
let cpState = null;
let cpModels = [];
let wfStages = [];
let mcpServers = [];

async function refreshControl() {
  try {
    cpState = await getJson('/api/v1/control/state');
  } catch {
    $('#agent-configs').innerHTML =
      '<div class="card"><p class="hint">控制平面 API 不可达。</p></div>';
    return;
  }
  try {
    cpModels = (await getJson('/api/v1/control/models')).models || [];
  } catch { cpModels = []; }
  $('#cp-version').textContent = 'version ' + cpState.version;
  renderAgentConfigs();
  renderWorkflow();
  renderMcp();
}

function renderAgentConfigs() {
  if (!$('#cp-model-list')) {
    $('#agent-configs').insertAdjacentHTML('beforebegin',
      `<datalist id="cp-model-list">${cpModels.map((m) => `<option value="${esc(m)}">`).join('')}</datalist>`);
  }
  $('#agent-configs').innerHTML = Object.values(cpState.agents).map((a) => `
    <div class="card agent-card" data-agent="${esc(a.name)}">
      <h2>Agent: ${esc(a.name)}
        <span class="badge">${a.mcpRefs.length ? 'MCP ×' + a.mcpRefs.length : '无 MCP 映射'}</span></h2>
      ${slotContractHtml(cpState.descriptors && cpState.descriptors[a.name])}
      <label>System Prompt（每次模型调用即时读取）
        <textarea class="cp-prompt" rows="8">${esc(a.systemPrompt)}</textarea></label>
      <div class="row">
        <label>模型（留空继承后端默认）
          <input class="cp-model" type="text" list="cp-model-list" value="${esc(a.model || '')}" placeholder="如 qwen:7b"></label>
        <label>温度
          <input class="cp-temp" type="number" step="0.1" min="0" max="2"
            value="${a.temperature ?? ''}" placeholder="默认"></label>
      </div>
      <h3>响应校准规则（调用后按序执行：正则 → 替换，可用 $1 引用分组）</h3>
      <div class="cp-rules">
        ${a.calibrationRules.length ? a.calibrationRules.map((r) => ruleRow(r)).join('') : '<div class="hint">无规则</div>'}
      </div>
      <button class="wf-add cp-add-rule" type="button">+ 添加规则</button>
      <h3>MCP 服务器映射</h3>
      <div class="cp-mcprefs">
        ${(cpState.mcpServers || []).length ? cpState.mcpServers.map((s) => `
          <label class="inline-check"><input type="checkbox" class="cp-mcpref" value="${esc(s.name)}"
            ${a.mcpRefs.includes(s.name) ? 'checked' : ''}> ${esc(s.name)}
            <span class="meta">${esc(s.transport)}${s.enabled ? '' : '（停用）'}</span></label>`).join('')
          : '<div class="hint">尚未录入 MCP 服务器。</div>'}
      </div>
      <button class="cp-save" type="button">保存 agent 配置</button>
      <span class="cp-msg hint"></span>
    </div>`).join('');

  document.querySelectorAll('.agent-card').forEach((card) => {
    card.querySelector('.cp-add-rule').addEventListener('click', () =>
      card.querySelector('.cp-rules').insertAdjacentHTML('beforeend', ruleRow({ pattern: '', replacement: '', description: '' })));
    card.querySelectorAll('.cp-rule-del').forEach((btn) =>
      btn.addEventListener('click', () => btn.closest('.cp-rule').remove()));
    card.querySelector('.cp-save').addEventListener('click', () => saveAgent(card));
  });
}

function ruleRow(rule) {
  return `
    <div class="cp-rule">
      <input class="cp-rule-pattern" placeholder="正则 pattern" value="${esc(rule.pattern)}">
      <input class="cp-rule-replacement" placeholder="替换为（可空=删除匹配）" value="${esc(rule.replacement)}">
      <button class="del cp-rule-del" type="button" title="删除该规则">✕</button>
    </div>`;
}

/* Slot contract of an agent, seeded from its self-declared descriptor:
   output slots are written to the blackboard, input slots are read when the
   stage starts, and pending-safe inputs may be absent (the agent then says
   so explicitly instead of failing — that is what legalizes same-wave
   parallel consumption in the workflow editor). */
function slotContractHtml(d) {
  if (!d) return '';
  const chip = (slot, cls, title) =>
    `<span class="slot-chip ${cls}"${title ? ` title="${esc(title)}"` : ''}>${esc(slot)}</span>`;
  const pendingTitle = 'pending-safe：该输入缺失时 agent 显式声明等待而非失败，因此允许同波并行消费';
  return `
    <div class="slot-contract">
      <span class="slot-label">输出</span>${d.outputSlots.map((s) => chip(s, 'out')).join('') || '<span class="hint">—</span>'}
      <span class="slot-label">输入</span>${d.inputSlots.map((s) => d.pendingSafeSlots.includes(s)
        ? chip(s + ' ⟂', 'pending', pendingTitle)
        : chip(s, 'in')).join('') || '<span class="hint">—</span>'}
      ${d.defaultModel ? `<span class="slot-label">默认模型</span>${chip(d.defaultModel, 'model')}` : ''}
    </div>`;
}

async function saveAgent(card) {
  const name = card.dataset.agent;
  const msg = card.querySelector('.cp-msg');
  const tempRaw = card.querySelector('.cp-temp').value.trim();
  const rules = [...card.querySelectorAll('.cp-rule')].map((row) => ({
    pattern: row.querySelector('.cp-rule-pattern').value,
    replacement: row.querySelector('.cp-rule-replacement').value,
    description: '',
  })).filter((r) => r.pattern.trim());
  const body = {
    name,
    systemPrompt: card.querySelector('.cp-prompt').value,
    model: card.querySelector('.cp-model').value.trim() || null,
    temperature: tempRaw === '' ? null : Number(tempRaw),
    calibrationRules: rules,
    mcpRefs: [...card.querySelectorAll('.cp-mcpref:checked')].map((c) => c.value),
  };
  msg.textContent = '保存中…'; msg.className = 'cp-msg hint';
  try {
    const st = await putJson(`/api/v1/control/agents/${encodeURIComponent(name)}`, body);
    cpState = st;
    $('#cp-version').textContent = 'version ' + st.version;
    msg.textContent = `已保存并热生效（version ${st.version}）`; msg.className = 'cp-msg hint ok';
  } catch (e) {
    msg.textContent = '保存失败: ' + e.message; msg.className = 'cp-msg hint';
  }
}

/* workflow editor: declaration order is the source of truth; a dependency may
   only reference preceding stages (validated server-side as well). */
function renderWorkflow() {
  wfStages = cpState.workflow.stages.map((s) =>
    ({ name: s.name, agent: s.agent, dependsOn: [...s.dependsOn] }));
  drawWorkflow();
}

function drawWorkflow() {
  const agentNames = Object.keys(cpState.agents);
  $('#workflow-editor').innerHTML = `
    <label>执行模式（CONCURRENT 时同波阶段并行执行并合并）
      <select id="wf-mode" class="wf-mode-sel">
        <option value="SEQUENTIAL" ${cpState.workflow.mode === 'SEQUENTIAL' ? 'selected' : ''}>SEQUENTIAL（串行）</option>
        <option value="CONCURRENT" ${cpState.workflow.mode === 'CONCURRENT' ? 'selected' : ''}>CONCURRENT（并行）</option>
      </select></label>
    <table class="data-table" id="wf-table">
      <thead><tr><th>顺序</th><th>阶段名</th><th>Agent 映射</th><th>依赖（仅限前置阶段）</th><th></th></tr></thead>
      <tbody>
        ${wfStages.map((s, i) => `
          <tr>
            <td class="num">${i}</td>
            <td><input class="wf-name" value="${esc(s.name)}"></td>
            <td><select class="wf-agent">${agentNames.map((n) =>
              `<option value="${esc(n)}" ${s.agent === n ? 'selected' : ''}>${esc(n)}</option>`).join('')}</select></td>
            <td>${wfStages.filter((_, j) => j < i).map((p) => `
              <label class="inline-check"><input type="checkbox" class="wf-dep" data-stage="${esc(p.name)}"
                ${s.dependsOn.includes(p.name) ? 'checked' : ''}> ${esc(p.name)}</label>`).join('') || '—'}</td>
            <td><button class="del wf-del" data-i="${i}" type="button">✕</button></td>
          </tr>`).join('')}
      </tbody>
    </table>
    <button class="wf-add" type="button" id="wf-add">+ 添加阶段</button>
    <button class="wf-save" type="button" id="wf-save">保存并热切换模板</button>
    <span id="wf-msg" class="hint"></span>`;
  $('#workflow-editor').querySelectorAll('.wf-name').forEach((inp, i) =>
    inp.addEventListener('input', () => { wfStages[i].name = inp.value; }));
  $('#workflow-editor').querySelectorAll('.wf-agent').forEach((sel, i) =>
    sel.addEventListener('change', () => { wfStages[i].agent = sel.value; }));
  $('#workflow-editor').querySelectorAll('tr').forEach((tr, i) => {
    if (!i) return; // header row
    tr.querySelectorAll('.wf-dep').forEach((cb) => cb.addEventListener('change', () => {
      const deps = wfStages[i - 1].dependsOn;
      if (cb.checked) { if (!deps.includes(cb.dataset.stage)) deps.push(cb.dataset.stage); }
      else wfStages[i - 1].dependsOn = deps.filter((d) => d !== cb.dataset.stage);
    }));
  });
  $('#workflow-editor').querySelectorAll('.wf-del').forEach((btn) =>
    btn.addEventListener('click', () => { wfStages.splice(Number(btn.dataset.i), 1); drawWorkflow(); }));
  $('#wf-add').addEventListener('click', () => {
    wfStages.push({ name: 'stage-' + wfStages.length, agent: Object.keys(cpState.agents)[0], dependsOn: [] });
    drawWorkflow();
  });
  $('#wf-save').addEventListener('click', saveWorkflow);
}

async function saveWorkflow() {
  const msg = $('#wf-msg');
  msg.textContent = '保存中…'; msg.className = 'hint';
  const body = {
    mode: $('#wf-mode').value,
    stages: wfStages.map((s) => ({
      name: String(s.name || '').trim(), agent: s.agent, dependsOn: s.dependsOn,
    })),
  };
  try {
    const st = await putJson('/api/v1/control/workflow', body);
    cpState = st;
    $('#cp-version').textContent = 'version ' + st.version;
    renderWorkflow();
    msg.textContent = `模板已热切换，下一次请求即生效（version ${st.version}）`;
    msg.className = 'hint ok';
  } catch (e) {
    msg.textContent = '保存失败: ' + e.message; msg.className = 'hint';
  }
}

function renderMcp() {
  mcpServers = cpState.mcpServers.map((s) => ({ ...s, args: s.args || [], env: s.env || {} }));
  drawMcp();
  refreshMcpStatus();
}

function drawMcp() {
  $('#mcp-panel').innerHTML = `
    <table class="data-table" id="mcp-table">
      <thead><tr><th>名称</th><th>传输</th><th>端点 / 命令</th><th>启用</th><th>说明</th><th>连接状态</th><th></th></tr></thead>
      <tbody>
        ${mcpServers.map((s, i) => `
          <tr data-i="${i}">
            <td><input class="mcp-name" value="${esc(s.name)}"></td>
            <td><select class="mcp-transport">
              <option value="STREAMABLE_HTTP" ${s.transport === 'STREAMABLE_HTTP' ? 'selected' : ''}>Streamable HTTP</option>
              <option value="SSE" ${s.transport === 'SSE' ? 'selected' : ''}>SSE（弃用）</option>
              <option value="STDIO" ${s.transport === 'STDIO' ? 'selected' : ''}>STDIO</option>
            </select></td>
            <td class="mcp-location">${s.transport === 'STDIO'
              ? `<input class="mcp-command" placeholder="npx" value="${esc(s.command || '')}">
                 <input class="mcp-args" placeholder="参数（空格分隔）" value="${esc((s.args || []).join(' '))}">
                 <input class="mcp-env" placeholder="KEY=VAL,KEY=VAL（可空）" value="${esc(Object.entries(s.env || {}).map(([k, v]) => k + '=' + v).join(','))}">`
              : `<input class="mcp-endpoint" placeholder="http://localhost:3002/mcp" value="${esc(s.endpoint || '')}">`}</td>
            <td><input type="checkbox" class="mcp-enabled" ${s.enabled ? 'checked' : ''}></td>
            <td><input class="mcp-desc" value="${esc(s.description || '')}"></td>
            <td class="mcp-status" data-server="${esc(s.name)}"><span class="hint">…</span></td>
            <td><button class="del mcp-del" data-i="${i}" type="button">✕</button></td>
          </tr>`).join('') || '<tr><td colspan="7" class="hint">尚未录入 MCP 服务器。</td></tr>'}
      </tbody>
    </table>
    <button class="mcp-add" type="button" id="mcp-add">+ 添加服务器</button>
    <button class="mcp-save" type="button" id="mcp-save">保存 MCP 配置</button>
    <span id="mcp-msg" class="hint"></span>`;
  $('#mcp-panel').querySelectorAll('.mcp-del').forEach((btn) =>
    btn.addEventListener('click', () => { mcpServers.splice(Number(btn.dataset.i), 1); drawMcp(); }));
  $('#mcp-panel').querySelectorAll('.mcp-transport').forEach((sel) =>
    sel.addEventListener('change', () => {
      syncMcpRows();
      mcpServers[Number(sel.closest('tr').dataset.i)].transport = sel.value;
      drawMcp();
      refreshMcpStatus();
    }));
  $('#mcp-add').addEventListener('click', () => {
    mcpServers.push({ name: 'mcp-server-' + (mcpServers.length + 1), transport: 'STREAMABLE_HTTP',
      endpoint: 'http://localhost:3002/mcp', command: '', args: [], env: {}, enabled: false, description: '' });
    drawMcp();
    refreshMcpStatus();
  });
  $('#mcp-save').addEventListener('click', saveMcp);
}

/** Reads the current table edits back into mcpServers (row order = array order). */
function syncMcpRows() {
  const rows = [...$('#mcp-panel').querySelectorAll('#mcp-table tbody tr[data-i]')];
  if (!rows.length) return;
  mcpServers = rows.map((tr) => {
    const transport = tr.querySelector('.mcp-transport').value;
    const row = {
      name: tr.querySelector('.mcp-name').value.trim(),
      transport,
      enabled: tr.querySelector('.mcp-enabled').checked,
      description: tr.querySelector('.mcp-desc').value,
    };
    if (transport === 'STDIO') {
      row.endpoint = '';
      row.command = tr.querySelector('.mcp-command').value.trim();
      row.args = tr.querySelector('.mcp-args').value.trim() ? tr.querySelector('.mcp-args').value.trim().split(/\s+/) : [];
      row.env = parseEnvPairs(tr.querySelector('.mcp-env').value);
    } else {
      row.endpoint = tr.querySelector('.mcp-endpoint').value.trim();
      row.command = '';
      row.args = [];
      row.env = {};
    }
    return row;
  });
}

function parseEnvPairs(text) {
  const env = {};
  (text || '').split(',').forEach((pair) => {
    const idx = pair.indexOf('=');
    if (idx > 0) env[pair.slice(0, idx).trim()] = pair.slice(idx + 1).trim();
  });
  return env;
}

/** Live connection states from the MCP registry (badges + tools + reconnect). */
async function refreshMcpStatus() {
  let statuses;
  try { statuses = await getJson('/api/v1/control/mcp/status'); } catch { return; }
  statuses.forEach((st) => {
    const cell = document.querySelector(`.mcp-status[data-server="${CSS.escape(st.name)}"]`);
    if (!cell) return;
    const badgeClass = st.state === 'CONNECTED' ? 'badge-ok' : st.state === 'FAILED' ? 'badge-failed' : 'badge-running';
    cell.innerHTML = `
      <span class="badge ${badgeClass}">${esc(st.state)}</span>
      ${st.state === 'CONNECTED' && st.toolNames.length ? `<span class="hint" title="${esc(st.toolNames.join(', '))}">工具 ×${st.toolNames.length}</span>` : ''}
      ${st.error ? `<span class="hint" style="color:var(--err)">${esc(st.error)}</span>` : ''}
      ${st.enabled ? `<button class="mcp-reconnect" data-server="${esc(st.name)}" type="button">重连</button>` : ''}`;
    const btn = cell.querySelector('.mcp-reconnect');
    if (btn) btn.addEventListener('click', async () => {
      btn.disabled = true; btn.textContent = '重连中…';
      try { await postJson(`/api/v1/control/mcp/servers/${encodeURIComponent(btn.dataset.server)}/reconnect`, {}); }
      catch { /* the refreshed status shows the failure */ }
      refreshMcpStatus();
    });
  });
}

async function saveMcp() {
  const msg = $('#mcp-msg');
  msg.textContent = '保存中…'; msg.className = 'hint';
  syncMcpRows();
  const body = mcpServers.filter((s) => s.name);
  try {
    const st = await putJson('/api/v1/control/mcp/servers', body);
    cpState = st;
    $('#cp-version').textContent = 'version ' + st.version;
    msg.textContent = `已保存（version ${st.version}），连接由后台自动重建`; msg.className = 'hint ok';
    renderMcp();
  } catch (e) {
    msg.textContent = '保存失败: ' + e.message; msg.className = 'hint';
  }
}

$('#cp-refresh').addEventListener('click', refreshControl);

/* ---------------- scenario config page (CRUD) ---------------- */
$('#scn-refresh').addEventListener('click', refreshScenarios);
$('#scn-new').addEventListener('click', () => openScenarioEditor(null));

let editingScenarioId = null;

async function refreshScenarios() {
  let state = { scenarios: [] };
  try { state = await getJson('/api/v1/scenarios'); } catch { /* empty table */ }
  scenarioCache = state.scenarios || [];
  const tbody = $('#scn-table tbody');
  tbody.innerHTML = scenarioCache.map((s) => `
    <tr>
      <td class="mono">${esc(s.id)}</td>
      <td>${esc(s.name)}</td>
      <td><span class="badge">${esc(s.artifactType)}</span></td>
      <td>${s.workflowOverride ? `<span class="badge badge-ok">${esc(s.workflowOverride.mode)} · ${s.workflowOverride.stages.length} 阶段</span>` : '<span class="hint">继承</span>'}</td>
      <td class="num">${Object.keys(s.agentOverrides || {}).length || '—'}</td>
      <td class="num">${(s.mcpServerRefs || []).length || '—'}</td>
      <td><span class="badge ${s.active ? 'badge-ok' : ''}">${s.active ? '启用' : '停用'}</span></td>
      <td>
        <button class="mini-btn scn-edit" data-id="${esc(s.id)}" type="button">编辑</button>
        <button class="mini-btn scn-del" data-id="${esc(s.id)}" type="button">删除</button>
      </td>
    </tr>`).join('') || '<tr><td colspan="8" class="hint">暂无场景，点击「+ 新建场景」。</td></tr>';
  tbody.querySelectorAll('.scn-edit').forEach((btn) =>
    btn.addEventListener('click', () => openScenarioEditor(btn.dataset.id)));
  tbody.querySelectorAll('.scn-del').forEach((btn) =>
    btn.addEventListener('click', async () => {
      if (!confirm(`删除场景 ${btn.dataset.id}？`)) return;
      try {
        await delJson(`/api/v1/scenarios/${encodeURIComponent(btn.dataset.id)}`);
        refreshScenarios();
      } catch (e) { alert('删除失败: ' + e.message); }
    }));
}

function openScenarioEditor(id) {
  editingScenarioId = id;
  const s = id ? scenarioCache.find((x) => x.id === id) : null;
  $('#scn-editor-card').hidden = false;
  $('#scn-editor-title').textContent = id ? `编辑场景：${s ? s.name : id}` : '新建场景';
  const wfJson = s && s.workflowOverride ? JSON.stringify(s.workflowOverride, null, 2) : '';
  const agentJson = s && Object.keys(s.agentOverrides || {}).length
    ? JSON.stringify(s.agentOverrides, null, 2) : '';
  const rt = (s && s.requestTemplate) || {};
  $('#scn-editor').innerHTML = `
    <div class="grid-2">
      <div>
        <label>ID（slug，创建后不可改）<input id="scn-id" value="${esc(s ? s.id : '')}" ${s ? 'disabled' : ''}></label>
        <label>名称<input id="scn-name" value="${esc(s ? s.name : '')}"></label>
        <label>描述<textarea id="scn-desc" rows="2">${esc(s ? s.description : '')}</textarea></label>
        <label>产物类型
          <select id="scn-artifact">
            ${['HTML', 'SPRING_BOOT_PROJECT', 'JAR', 'AUDIO'].map((t) =>
              `<option ${s && s.artifactType === t ? 'selected' : ''}>${t}</option>`).join('')}
          </select></label>
        <label class="inline-check"><input id="scn-active" type="checkbox" ${!s || s.active ? 'checked' : ''}> 启用该场景</label>
      </div>
      <div>
        <h3>请求模板</h3>
        <div class="row">
          <label>目的地<input id="scn-dest" value="${esc(rt.destination || '')}"></label>
          <label>天数<input id="scn-days" type="number" min="1" value="${esc(rt.numberOfDays ?? 3)}"></label>
        </div>
        <div class="row">
          <label>预算 (CNY)<input id="scn-budget" type="number" min="1" step="0.01" value="${esc(rt.budget ?? 15000)}"></label>
          <label>偏好<input id="scn-pref" value="${esc(rt.preferences || '')}"></label>
        </div>
        <h3>MCP 依赖（场景要求的 MCP 服务器）</h3>
        <div id="scn-mcprefs">
          ${((cpState && cpState.mcpServers) || []).map((srv) => `
            <label class="inline-check"><input type="checkbox" class="scn-mcpref" value="${esc(srv.name)}"
              ${s && (s.mcpServerRefs || []).includes(srv.name) ? 'checked' : ''}> ${esc(srv.name)}</label>`).join('')
            || '<div class="hint">控制平面尚未录入 MCP 服务器。</div>'}
        </div>
      </div>
    </div>
    <h3>工作流覆盖（JSON，留空 = 继承当前控制平面模板）</h3>
    <textarea id="scn-workflow" class="scn-json" rows="6" placeholder='{"mode":"SEQUENTIAL","stages":[{"name":"research","agent":"research","dependsOn":[]}] }'>${esc(wfJson)}</textarea>
    <button class="wf-add" id="scn-copy-wf" type="button">从当前控制平面模板复制</button>
    <h3>Agent 覆盖（JSON：agent 名 → {systemPrompt?, model?, temperature?, calibrationRules?, mcpRefs?}，留空 = 全继承）</h3>
    <textarea id="scn-agents" class="scn-json" rows="5" placeholder='{"research":{"model":"qwen:7b","temperature":0.2}}'>${esc(agentJson)}</textarea>
    <div style="margin-top:12px">
      <button class="accent-btn" id="scn-save" type="button">${id ? '保存修改' : '创建场景'}</button>
      <button class="wf-add" id="scn-cancel" type="button">取消</button>
      <span id="scn-msg" class="hint"></span>
    </div>`;
  $('#scn-copy-wf').addEventListener('click', () => {
    if (cpState) $('#scn-workflow').value = JSON.stringify(cpState.workflow, null, 2);
  });
  $('#scn-cancel').addEventListener('click', () => { $('#scn-editor-card').hidden = true; });
  $('#scn-save').addEventListener('click', saveScenarioEditor);
}

async function saveScenarioEditor() {
  const msg = $('#scn-msg');
  msg.textContent = '保存中…';
  let workflowOverride = null;
  let agentOverrides = {};
  const wfText = $('#scn-workflow').value.trim();
  const agentText = $('#scn-agents').value.trim();
  try {
    if (wfText) workflowOverride = JSON.parse(wfText);
    if (agentText) agentOverrides = JSON.parse(agentText);
  } catch (e) {
    msg.textContent = 'JSON 解析失败: ' + e.message; msg.className = 'hint';
    return;
  }
  const body = {
    id: $('#scn-id').value.trim(),
    name: $('#scn-name').value.trim(),
    description: $('#scn-desc').value,
    requestTemplate: {
      destination: $('#scn-dest').value.trim(),
      numberOfDays: Number($('#scn-days').value),
      budget: Number($('#scn-budget').value),
      preferences: $('#scn-pref').value.trim(),
    },
    workflowOverride,
    agentOverrides,
    mcpServerRefs: [...document.querySelectorAll('.scn-mcpref:checked')].map((c) => c.value),
    artifactType: $('#scn-artifact').value,
    active: $('#scn-active').checked,
  };
  try {
    if (editingScenarioId) await putJson(`/api/v1/scenarios/${encodeURIComponent(editingScenarioId)}`, body);
    else await postJson('/api/v1/scenarios', body);
    msg.textContent = '已保存'; msg.className = 'hint ok';
    $('#scn-editor-card').hidden = true;
    refreshScenarios();
  } catch (e) {
    msg.textContent = '保存失败: ' + e.message; msg.className = 'hint';
  }
}

/* ---------------- dry-run page ---------------- */
$('#dryrun-btn').addEventListener('click', async () => {
  const id = $('#dryrun-scenario-select').value;
  const reportEl = $('#dryrun-report');
  if (!id) return;
  reportEl.innerHTML = '<div class="card"><p class="hint">推演中…</p></div>';
  try {
    const report = await postJson(`/api/v1/scenarios/${encodeURIComponent(id)}/dry-run`, {});
    drawDryRunReport(report);
  } catch (e) {
    reportEl.innerHTML = `<div class="card"><p class="error-message">推演失败: ${esc(e.message)}</p></div>`;
  }
});

function drawDryRunReport(report) {
  const scn = scenarioCache.find((s) => s.id === report.scenarioId);
  const waveTable = report.waves.map((w) => `
    <div class="wave-head">Wave ${esc(w.waveIndex)} ${w.parallel ? '<span class="badge badge-running">并行</span>' : ''}</div>
    ${w.stages.map((s) => `
      <div class="stage-line planned">
        <span class="name">${esc(s.stageName)}</span><span class="arrow">→</span>
        <span>${esc(s.agentName)}</span>
        <span class="badge">${esc(s.effectiveModel || '后端默认模型')}${s.effectiveTemperature != null ? ' · T=' + esc(s.effectiveTemperature) : ''}</span>
        ${s.mcpServers.length ? `<span class="badge">MCP ×${s.mcpServers.length}</span>` : ''}
        <span class="meta">
          ${s.inputs.length ? 'in: ' + esc(s.inputs.join(', ')) + ' · ' : ''}
          ${s.outputs.length ? 'out: ' + esc(s.outputs.join(', ')) : ''}
          ${s.pendingSafe.length ? ' · ⟂ ' + esc(s.pendingSafe.join(', ')) : ''}</span>
        ${s.warnings.map((wn) => `<span class="meta" style="color:var(--warn)">⚠ ${esc(wn)}</span>`).join('')}
      </div>`).join('')}`).join('');
  const slotTable = `
    <table class="data-table">
      <thead><tr><th>黑板槽位</th><th>产出阶段</th><th>消费阶段</th><th>pending-safe</th></tr></thead>
      <tbody>${report.slotFlows.map((f) => `
        <tr><td class="mono">${esc(f.slot)}</td>
          <td>${f.producedBy.map(esc).join(', ') || '—'}</td>
          <td>${f.consumedBy.map(esc).join(', ') || '—'}</td>
          <td>${f.pendingSafe ? '⟂ 是' : '否'}</td></tr>`).join('') || '<tr><td colspan="4" class="hint">无槽位契约数据。</td></tr>'}</tbody>
    </table>`;
  $('#dryrun-report').innerHTML = `
    <div class="card">
      <h2>推演报告 — ${esc(scn ? scn.name : report.scenarioId)}
        <span class="badge ${report.valid ? 'badge-ok' : 'badge-failed'}">${report.valid ? '校验通过，可以运行' : '存在问题'}</span></h2>
      <p class="hint">解析模板来源 <b>${report.resolvedTemplateSource === 'scenario-override' ? '场景覆盖' : '控制平面当前模板'}</b> ·
        模式 <b>${esc(report.resolvedMode)}</b> · ${report.waves.length} 个波次 · ${report.edges.length} 条依赖边</p>
      <ul class="issue-list ${report.valid ? 'ok-list' : ''}">
        ${(report.issues.length ? report.issues : ['无校验问题 —— 模板闭环、Agent 引用与 MCP 依赖全部通过']).map((i) => `<li>${esc(i)}</li>`).join('')}
      </ul>
      <h3>依赖 DAG</h3>
      <div class="viz-card" id="dryrun-dag"></div>
      <h3>波次展开（模拟执行顺序）</h3>
      ${waveTable || '<div class="hint">无法展开波次（存在校验问题）。</div>'}
      <h3>黑板槽位流</h3>
      ${slotTable}
    </div>`;
  renderDag('#dryrun-dag',
    report.waves.flatMap((w) => w.stages.map((s) => ({
      id: s.stageName, wave: w.waveIndex, label: s.stageName,
      sub: s.agentName, status: 'planned', retries: 0,
    }))),
    report.edges.map((e) => ({ from: e.from, to: e.to })), false);
}

/* ---------------- helpers ---------------- */
async function getJson(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${url} -> HTTP ${res.status}`);
  return res.json();
}

async function putJson(url, body) {
  return sendJson('PUT', url, body);
}

async function postJson(url, body) {
  return sendJson('POST', url, body);
}

async function delJson(url) {
  const res = await fetch(url, { method: 'DELETE' });
  if (!res.ok) {
    const err = await res.json().catch(() => null);
    throw new Error(err && err.message ? err.message : `HTTP ${res.status}`);
  }
  return res.json();
}

async function sendJson(method, url, body) {
  const res = await fetch(url, {
    method, headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => null);
    throw new Error(err && err.message ? err.message : `HTTP ${res.status}`);
  }
  return res.json();
}

function fmtTime(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleTimeString('zh-CN', { hour12: false })
    + '.' + String(d.getMilliseconds()).padStart(3, '0');
}

/* ---------------- observability stack links ----------------
   The console may be opened from a remote machine (http://<host>:8080);
   rewrite the fixed-port Grafana/Tempo/Prometheus links to that host so
   they stay usable instead of pointing at the browser's own localhost. */
document.querySelectorAll('.links a[data-fixed-port]').forEach((a) => {
  a.href = `http://${window.location.hostname || 'localhost'}:${a.dataset.fixedPort}`;
});

/* ---------------- app version ----------------
   The build-info actuator endpoint carries the maven project version; show
   it in the sidebar so what is running is always visible. */
fetch('/actuator/info').then((r) => r.json()).then((info) => {
  const v = info?.build?.version;
  if (v) $('#app-version').textContent = `v${v}`;
}).catch(() => { /* keep the placeholder on old images */ });

/* ---------------- boot ---------------- */
refreshHealthDot();
refreshRuns();
refreshObservability();
refreshControl();
loadScenarioSelects();
connectEvents();
