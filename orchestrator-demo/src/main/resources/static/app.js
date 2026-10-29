/**
 * Orchestrator Demo console logic — vanilla JS, same-origin against the
 * Spring Boot app: trip planning form -> live wave/stage progress (polling the
 * observability actor) -> agent result cards; plus run replay and Actuator
 * process metrics. No build step, no framework.
 */
'use strict';

const $ = (sel) => document.querySelector(sel);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

/* ---------------- tabs & health dot ---------------- */
document.querySelectorAll('.tab').forEach((btn) => btn.addEventListener('click', () => {
  document.querySelectorAll('.tab, .tab-panel').forEach((el) => el.classList.remove('active'));
  btn.classList.add('active');
  $(`#tab-${btn.dataset.tab}`).classList.add('active');
  // Panels show point-in-time data: refresh on every tab activation so the
  // run replay list and the metrics panel reflect what just happened.
  if (btn.dataset.tab === 'runs') refreshRuns();
  if (btn.dataset.tab === 'obs') refreshObservability();
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

/* ---------------- runs tab ---------------- */
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
    </tr>`).join('') || '<tr><td colspan="7" class="hint">暂无运行记录，去「规划工作台」发起一次请求。</td></tr>';
  tbody.querySelectorAll('tr[data-run]').forEach((tr) =>
    tr.addEventListener('click', () => showRunDetail(tr.dataset.run)));
}

async function showRunDetail(runId) {
  const d = await getJson(`/api/trips/runs/${runId}`);
  let final = null;
  try { final = JSON.parse(d.finalContext); } catch { /* keep raw */ }
  const req = final && final.request;
  $('#run-detail').innerHTML = `
    <div class="card">
      <h2>运行 ${esc(d.runId.slice(0, 8))}…
        <span class="badge badge-${esc(d.status)}">${esc(d.status)}</span></h2>
      <p class="hint">模板 <b>${esc(d.templateName)}</b> · 模式 <b>${esc(d.mode)}</b> ·
        ${esc(fmtTime(d.startedAt))} → ${esc(fmtTime(d.completedAt))}</p>
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
          ${jsonDetails('输入黑板快照', s.inputContext)}
          ${jsonDetails('输出黑板快照', s.outputContext)}`).join('')}`).join('')}
      ${final ? `<h3>最终上下文（黑板终态）</h3>
        <pre class="json">${esc(JSON.stringify(final, null, 2))}</pre>` : ''}
    </div>`;
}

function jsonDetails(title, jsonStr) {
  if (!jsonStr) return '';
  let pretty = jsonStr;
  try { pretty = JSON.stringify(JSON.parse(jsonStr), null, 2); } catch { /* raw */ }
  return `<details class="json-details"><summary>${esc(title)}</summary>
    <pre class="json">${esc(pretty)}</pre></details>`;
}

/* ---------------- observability tab ---------------- */
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

/* ---------------- helpers ---------------- */
async function getJson(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${url} -> HTTP ${res.status}`);
  return res.json();
}

function fmtTime(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleTimeString('zh-CN', { hour12: false })
    + '.' + String(d.getMilliseconds()).padStart(3, '0');
}

/* ---------------- boot ---------------- */
refreshHealthDot();
refreshRuns();
refreshObservability();
