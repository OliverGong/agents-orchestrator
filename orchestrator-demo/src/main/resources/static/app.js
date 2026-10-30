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
  if (btn.dataset.tab === 'control') refreshControl();
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

/* ---------------- live blackboard (SSE, control-plane v1) ----------------
   GET /api/v1/events/stream pushes every observability event as it is folded
   into the run timeline: per-stage input/output snapshots and the final
   context arrive without any polling latency. */
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

/* ---------------- control plane tab ---------------- */
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
  mcpServers = cpState.mcpServers.map((s) => ({ ...s }));
  drawMcp();
}

function drawMcp() {
  $('#mcp-panel').innerHTML = `
    <table class="data-table" id="mcp-table">
      <thead><tr><th>名称</th><th>传输</th><th>端点 / 命令</th><th>启用</th><th>说明</th><th></th></tr></thead>
      <tbody>
        ${mcpServers.map((s, i) => `
          <tr>
            <td><input class="mcp-name" value="${esc(s.name)}"></td>
            <td><select class="mcp-transport">
              <option value="SSE" ${s.transport === 'SSE' ? 'selected' : ''}>SSE</option>
              <option value="STDIO" ${s.transport === 'STDIO' ? 'selected' : ''}>STDIO</option>
            </select></td>
            <td><input class="mcp-endpoint" value="${esc(s.endpoint)}"></td>
            <td><input type="checkbox" class="mcp-enabled" ${s.enabled ? 'checked' : ''}></td>
            <td><input class="mcp-desc" value="${esc(s.description)}"></td>
            <td><button class="del mcp-del" data-i="${i}" type="button">✕</button></td>
          </tr>`).join('') || '<tr><td colspan="6" class="hint">尚未录入 MCP 服务器。</td></tr>'}
      </tbody>
    </table>
    <button class="mcp-add" type="button" id="mcp-add">+ 添加服务器</button>
    <button class="mcp-save" type="button" id="mcp-save">保存 MCP 配置</button>
    <span id="mcp-msg" class="hint"></span>`;
  $('#mcp-panel').querySelectorAll('.mcp-del').forEach((btn) =>
    btn.addEventListener('click', () => { mcpServers.splice(Number(btn.dataset.i), 1); drawMcp(); }));
  $('#mcp-add').addEventListener('click', () => {
    mcpServers.push({ name: 'mcp-server-' + (mcpServers.length + 1), transport: 'SSE',
      endpoint: 'http://localhost:3001/sse', enabled: false, description: '' });
    drawMcp();
  });
  $('#mcp-save').addEventListener('click', saveMcp);
}

async function saveMcp() {
  const msg = $('#mcp-msg');
  msg.textContent = '保存中…'; msg.className = 'hint';
  const body = [...$('#mcp-panel').querySelectorAll('#mcp-table tbody tr')].map((tr) => ({
    name: tr.querySelector('.mcp-name').value.trim(),
    transport: tr.querySelector('.mcp-transport').value,
    endpoint: tr.querySelector('.mcp-endpoint').value.trim(),
    enabled: tr.querySelector('.mcp-enabled').checked,
    description: tr.querySelector('.mcp-desc').value,
  })).filter((s) => s.name);
  try {
    const st = await putJson('/api/v1/control/mcp/servers', body);
    cpState = st;
    $('#cp-version').textContent = 'version ' + st.version;
    msg.textContent = `已保存（version ${st.version}）`; msg.className = 'hint ok';
  } catch (e) {
    msg.textContent = '保存失败: ' + e.message; msg.className = 'hint';
  }
}

$('#cp-refresh').addEventListener('click', refreshControl);

/* ---------------- helpers ---------------- */
async function getJson(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${url} -> HTTP ${res.status}`);
  return res.json();
}

async function putJson(url, body) {
  const res = await fetch(url, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
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

/* ---------------- boot ---------------- */
refreshHealthDot();
refreshRuns();
refreshObservability();
connectEvents();
