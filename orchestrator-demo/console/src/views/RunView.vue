<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { http } from '../api/http'
import { connectOrchestratorEvents, type SseConnection } from '../api/sse'
import { useSessionStore } from '../stores/session'
import { fmtTime } from '../utils/format'
import JsonDetails from '../components/JsonDetails.vue'
import type {
  ControlPlaneState,
  OrchestratorContext,
  RunDetails,
  RunStage,
  RunStartedEvent,
  RunStatus,
  RunSummary,
  StageEvent,
  TripRequest,
} from '../types/api'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

/* ---------------- scenario bar ---------------- */
const scenarioOptions = computed(() =>
  session.scenarios.map((s) => ({ label: `${s.name}（${s.id}）`, value: s.id })),
)
const selectedScenarioId = ref<string | null>(null)
const activating = ref(false)
const activateMsg = ref('')

async function activateScenario() {
  const id = selectedScenarioId.value
  if (!id) return
  activating.value = true
  activateMsg.value = '激活中…（工作流/Agent 覆盖热应用到控制平面）'
  try {
    const cp = await http.post<ControlPlaneState>(
      `/api/v1/scenarios/${encodeURIComponent(id)}/activate`,
    )
    session.ctxScenarioId = id
    activateMsg.value = `场景已激活：模板「${cp.workflow.mode}」已热切换（version ${cp.version}），下一次运行即按场景执行`
    await session.refreshScenarios()
  } catch (e) {
    activateMsg.value = '激活失败: ' + (e as Error).message
  } finally {
    activating.value = false
  }
}

/* ---------------- plan form ---------------- */
const form = ref({
  destination: 'Kyoto',
  numberOfDays: 3,
  budget: 15000,
  preferences: 'temples and local food',
  businessId: '',
})
const running = ref(false)
const error = ref<{ status: number | string; message: string } | null>(null)
const submitted = ref<{ request: TripRequest; businessId: string } | null>(null)
const result = ref<OrchestratorContext | null>(null)
const resultBusinessId = ref('')

async function submitPlan() {
  const body: TripRequest = {
    destination: form.value.destination.trim(),
    numberOfDays: Number(form.value.numberOfDays),
    budget: Number(form.value.budget),
    preferences: form.value.preferences.trim(),
  }
  const businessId = form.value.businessId.trim()
  running.value = true
  error.value = null
  result.value = null
  submitted.value = { request: body, businessId }
  submitStart = Date.now()
  startProgressPolling()
  try {
    const query = businessId ? `?businessId=${encodeURIComponent(businessId)}` : ''
    const context = await http.post<OrchestratorContext>(`/api/trips/plan${query}`, body)
    result.value = context
    resultBusinessId.value = businessId
    await session.refreshScenarios()
  } catch (e) {
    error.value = {
      status: e instanceof Error && 'status' in e ? (e as { status: number }).status : 0,
      message: (e as Error).message,
    }
  } finally {
    stopProgressPolling()
    running.value = false
  }
}

/* ---------------- progress polling ----------------
   The run timeline is folded by the ObservabilityActor while the POST is
   still in flight, so polling /api/trips/runs reveals each stage as it
   completes. */
const progressStatus = ref<RunStatus | ''>('')
const progressStages = ref<RunStage[]>([])
const progressElapsed = ref('')
let pollTimer: ReturnType<typeof setInterval> | null = null
let submitStart = 0

function startProgressPolling() {
  progressStatus.value = 'RUNNING'
  progressStages.value = []
  progressElapsed.value = ''
  pollTimer = setInterval(pollActiveRun, 2500)
  void pollActiveRun()
}

function stopProgressPolling() {
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = null
  progressElapsed.value = `总耗时 ${((Date.now() - submitStart) / 1000).toFixed(1)}s`
}

async function pollActiveRun() {
  try {
    const runs = await http.get<RunSummary[]>('/api/trips/runs')
    const active = runs.find((r) => r.status === 'RUNNING') ?? (runs.length ? runs[0] : null)
    if (!active) return
    const details = await http.get<RunDetails>(`/api/trips/runs/${active.runId}`)
    progressStatus.value = details.status
    progressStages.value = details.waves.flatMap((w) => w.stages)
    progressElapsed.value = `${Math.round((Date.now() - submitStart) / 1000)}s`
  } catch {
    /* backend hiccup during polling — keep the previous frame */
  }
}

/* ---------------- live blackboard (SSE) ---------------- */
const sseStatus = ref('SSE 连接中…')
const liveStatus = ref('')
const liveRunInfo = ref('')
const liveContext = ref('等待运行启动…')
interface LiveStage {
  key: number
  stageName: string
  agentName: string
  ok: boolean
  waveIndex?: number
  durationMs?: number | null
  threadName?: string | null
  parallelPeers?: string[]
  errorMessage?: string | null
  inputContextJson?: string | null
  outputContextJson?: string | null
}
const liveStages = ref<LiveStage[]>([])
let liveKey = 0
let sseConn: SseConnection | null = null

function appendLiveStage(e: StageEvent, ok: boolean) {
  liveStages.value.push({
    key: ++liveKey,
    stageName: e.stageName,
    agentName: e.agentName,
    ok,
    waveIndex: e.waveIndex,
    durationMs: e.durationMs,
    threadName: e.threadName,
    parallelPeers: e.parallelPeers,
    errorMessage: e.errorMessage,
    inputContextJson: e.inputContextJson,
    outputContextJson: e.outputContextJson,
  })
}

onMounted(() => {
  const q = route.query.scenarioId
  if (typeof q === 'string' && q) selectedScenarioId.value = q
  else if (session.ctxScenarioId) selectedScenarioId.value = session.ctxScenarioId

  sseConn = connectOrchestratorEvents({
    connected: () => {
      sseStatus.value = 'SSE 已连接'
    },
    error: () => {
      sseStatus.value = 'SSE 重连中…'
    },
    runStarted: (e: RunStartedEvent) => {
      sseStatus.value = 'SSE 已连接'
      liveStatus.value = 'RUNNING'
      liveRunInfo.value = `运行 ${e.runId.slice(0, 8)}… 已启动（模板 ${e.templateName} · ${e.mode}），等待阶段完成推送…`
      liveStages.value = []
      liveContext.value = '黑板初始化中…'
    },
    stageCompleted: (e: StageEvent) => {
      appendLiveStage(e, true)
      if (e.outputContextJson) liveContext.value = e.outputContextJson
    },
    stageFailed: (e: StageEvent) => {
      appendLiveStage(e, false)
      if (e.inputContextJson) liveContext.value = e.inputContextJson
    },
    stageRetried: (e: StageEvent) => {
      liveStages.value.push({
        key: ++liveKey,
        stageName: e.stageName,
        agentName: e.agentName,
        ok: true,
        inputContextJson: e.inputContextJson,
        outputContextJson: e.outputContextJson,
        errorMessage: `第 ${e.attempt} 次尝试解析失败，重新采样${e.timestamp ? ` · ${fmtTime(e.timestamp)}` : ''}${e.errorMessage ? ` · ${e.errorMessage}` : ''}`,
      })
    },
    runCompleted: (finalContextJson: string) => {
      liveStatus.value = 'COMPLETED'
      liveContext.value = finalContextJson
    },
    runFailed: () => {
      liveStatus.value = 'FAILED'
    },
  })
})

onBeforeUnmount(() => {
  sseConn?.close()
  if (pollTimer) clearInterval(pollTimer)
})

/* ---------------- lifecycle-closure links ---------------- */
async function gotoReplay() {
  try {
    const runs = await http.get<RunSummary[]>('/api/trips/runs')
    await router.push({ name: 'runs', query: runs.length ? { runId: runs[0].runId } : {} })
  } catch {
    await router.push({ name: 'runs' })
  }
}
async function gotoInstances() {
  const query: Record<string, string> = { window: 'all' }
  if (resultBusinessId.value) query.businessId = resultBusinessId.value
  try {
    const rows = await http.get<RunSummary[]>(
      '/api/v1/instances?window=all&limit=1' +
        (resultBusinessId.value ? `&businessId=${encodeURIComponent(resultBusinessId.value)}` : ''),
    )
    if (rows.length) query.runId = rows[0].runId
  } catch {
    /* the instances panel still shows the list */
  }
  await router.push({ name: 'instances', query })
}
function gotoMetrics() {
  void router.push({
    name: 'metrics',
    query: session.ctxScenarioId ? { scenarioId: session.ctxScenarioId } : {},
  })
}
</script>

<template>
  <div>
    <n-card title="场景选择">
      <template #header-extra>
        <n-tag :type="session.activeScenario ? 'success' : 'default'" size="small">
          {{ session.activeScenario ? session.activeScenario.name : '控制平面默认' }}
        </n-tag>
      </template>
      <p class="hint">
        选择一个场景并「激活」：其工作流 / Agent / MCP 覆盖将热应用到控制平面，下一次运行即按该场景的逻辑组执行。
      </p>
      <n-space align="center">
        <n-select
          v-model:value="selectedScenarioId"
          :options="scenarioOptions"
          placeholder="选择场景"
          clearable
          style="width: 340px"
        />
        <n-button type="primary" :loading="activating" @click="activateScenario">激活场景</n-button>
      </n-space>
      <p v-if="activateMsg" class="hint" style="margin-top: 8px">{{ activateMsg }}</p>
    </n-card>

    <div class="grid-2">
      <n-card title="用户请求">
        <p class="hint">
          整个工作流的入口：目的地、天数、预算与偏好将被写入黑板（OrchestratorContext），由当前激活的工作流模板驱动各
          Agent 消费与贡献。
        </p>
        <n-form label-placement="top" size="small">
          <n-form-item label="目的地">
            <n-input v-model:value="form.destination" />
          </n-form-item>
          <n-grid :cols="2" :x-gap="12">
            <n-form-item-gi label="天数">
              <n-input-number v-model:value="form.numberOfDays" :min="1" :max="7" style="width: 100%" />
            </n-form-item-gi>
            <n-form-item-gi label="预算 (CNY)">
              <n-input-number v-model:value="form.budget" :min="1" style="width: 100%" />
            </n-form-item-gi>
          </n-grid>
          <n-form-item label="偏好（自由文本，可留空）">
            <n-input v-model:value="form.preferences" placeholder="temples and local food" />
          </n-form-item>
          <n-form-item label="业务关联 ID（businessId，可留空）">
            <n-input v-model:value="form.businessId" placeholder="调用方业务键，如 order-20261101-0001" />
          </n-form-item>
          <n-button type="primary" attr-type="submit" :loading="running" @click="submitPlan">
            {{ running ? '工作流执行中…' : '开始规划' }}
          </n-button>
        </n-form>
      </n-card>

      <n-card v-if="submitted" title="本次请求">
        <div class="request-echo">
          <dl>
            <dt>目的地</dt>
            <dd>{{ submitted.request.destination }}</dd>
            <dt>天数</dt>
            <dd>{{ submitted.request.numberOfDays }} 天</dd>
            <dt>预算</dt>
            <dd>CNY {{ submitted.request.budget }}</dd>
            <dt>偏好</dt>
            <dd>{{ submitted.request.preferences || '(无)' }}</dd>
            <dt>业务关联 ID</dt>
            <dd class="mono">{{ submitted.businessId || '(未填写)' }}</dd>
          </dl>
        </div>
      </n-card>
    </div>

    <n-card v-if="progressStatus" title="执行进度" style="margin-top: 16px">
      <template #header-extra>
        <n-space align="center" size="small">
          <n-tag
            size="small"
            :type="progressStatus === 'RUNNING' ? 'info' : progressStatus === 'COMPLETED' ? 'success' : 'error'"
          >
            {{ progressStatus }}
          </n-tag>
          <span class="hint mono" style="margin: 0">{{ progressElapsed }}</span>
        </n-space>
      </template>
      <div v-if="progressStages.length" class="stages">
        <div
          v-for="(s, i) in progressStages"
          :key="i"
          class="stage-line"
          :class="s.outcome === 'STAGE_COMPLETED' ? 'done' : 'fail'"
        >
          <span class="name">{{ s.stageName }}</span>
          <span class="arrow">→</span>
          <span>{{ s.agentName }}</span>
          <n-tag size="small" :type="s.outcome === 'STAGE_COMPLETED' ? 'success' : 'error'">
            {{ s.outcome === 'STAGE_COMPLETED' ? '完成' : '失败' }}
          </n-tag>
          <span class="meta">
            {{ s.durationMs != null ? `${s.durationMs} ms` : '' }}
            {{ s.threadName ? `· ${s.threadName}` : '' }}
          </span>
        </div>
      </div>
      <div v-else class="hint">第一个 Agent 正在调用模型…</div>
    </n-card>

    <n-card style="margin-top: 16px">
      <template #header>
        实时黑板
        <n-tag size="small" style="margin-left: 8px">{{ sseStatus }}</n-tag>
        <n-tag
          v-if="liveStatus"
          size="small"
          style="margin-left: 6px"
          :type="liveStatus === 'RUNNING' ? 'info' : liveStatus === 'COMPLETED' ? 'success' : 'error'"
        >
          {{ liveStatus }}
        </n-tag>
      </template>
      <p class="hint">
        由 <code>GET /api/v1/events/stream</code>（SSE）实时推送：每个阶段完成即展示其输入/输出黑板快照，并滚动更新完整
        Context。
      </p>
      <div v-if="liveRunInfo" class="hint">{{ liveRunInfo }}</div>
      <div class="stages">
        <template v-for="s in liveStages" :key="s.key">
          <div class="stage-line" :class="s.ok ? 'done' : 'fail'">
            <span class="name">{{ s.stageName }}</span>
            <span class="arrow">→</span>
            <span>{{ s.agentName }}</span>
            <n-tag size="small" :type="s.ok ? 'success' : 'error'">{{ s.ok ? '完成' : '失败' }}</n-tag>
            <span class="meta">
              {{ s.waveIndex != null ? `wave ${s.waveIndex}` : '' }}
              {{ s.durationMs != null ? `· ${s.durationMs} ms` : '' }}
              {{ s.threadName ? `· ${s.threadName}` : '' }}
              {{ s.parallelPeers?.length ? `· 同波: ${s.parallelPeers.join(', ')}` : '' }}
            </span>
            <span v-if="s.errorMessage" class="meta" style="color: var(--err)">{{ s.errorMessage }}</span>
          </div>
          <JsonDetails v-if="s.ok" title="该阶段输出黑板快照" :json="s.outputContextJson" />
          <JsonDetails v-else title="该阶段输入黑板快照" :json="s.inputContextJson" />
        </template>
      </div>
      <h3 style="margin: 14px 0 6px; font-size: 13px; color: var(--muted)">当前完整 Context</h3>
      <pre id="live-context" class="json">{{ liveContext }}</pre>
    </n-card>

    <n-card v-if="error" style="margin-top: 16px">
      <template #header>
        <n-tag type="error" size="small">{{ error.status ? `HTTP ${error.status}` : 'NETWORK' }}</n-tag>
        调用失败
      </template>
      <p class="error-message">{{ error.message }}</p>
      <p class="hint">异常已由后端结构化映射（400/404/502/503/500）并全程日志可见，不会静默吞掉。</p>
    </n-card>

    <template v-if="result">
      <n-card style="margin-top: 16px">
        <template #header>
          运行完成
          <n-button size="tiny" type="primary" style="margin-left: 10px" @click="gotoReplay">查看运行回放 →</n-button>
          <n-button size="tiny" type="primary" style="margin-left: 6px" @click="gotoInstances">实例追踪 →</n-button>
          <n-button size="tiny" type="primary" style="margin-left: 6px" @click="gotoMetrics">场景指标 →</n-button>
        </template>
        <p class="hint" style="margin: 0">
          本次运行已由 ObservabilityActor 先落库再推送（WAL 顺序）：回放可查时间线 / DAG / 泳道，
          实例追踪可按{{ resultBusinessId ? `业务 ID ${resultBusinessId}` : '本次请求' }}溯源完整上下文，
          指标可查本场景各阶段的 LM 耗时与 token 消耗。
        </p>
      </n-card>

      <n-card title="行程结论 — ReviewAgent" style="margin-top: 16px">
        <template #header-extra>
          <n-tag size="small" :type="result.reviewResponse?.isValid ? 'success' : 'error'">
            {{ result.reviewResponse?.isValid ? '校验通过' : '存在问题' }}
          </n-tag>
        </template>
        <p>{{ result.reviewResponse?.summary }}</p>
        <template v-if="result.reviewResponse?.issues?.length">
          <h3 class="hint" style="margin-top: 10px">发现的问题</h3>
          <ul>
            <li v-for="(i, idx) in result.reviewResponse.issues" :key="idx">{{ i }}</li>
          </ul>
        </template>
        <template v-if="result.reviewResponse?.recommendations?.length">
          <h3 class="hint" style="margin-top: 10px">改进建议</h3>
          <ul>
            <li v-for="(i, idx) in result.reviewResponse.recommendations" :key="idx">{{ i }}</li>
          </ul>
        </template>
      </n-card>

      <div class="grid-2" style="margin-top: 16px">
        <n-card title="逐日行程 — ItineraryAgent">
          <div class="day-cards">
            <div v-for="d in result.itineraryResponse?.dayPlans ?? []" :key="d.day" class="day-card">
              <h4>Day {{ d.day }}</h4>
              <p><b>上午</b> {{ d.morning }}</p>
              <p><b>下午</b> {{ d.afternoon }}</p>
              <p><b>晚上</b> {{ d.evening }}</p>
              <p class="cost">预计花费 CNY {{ d.estimatedCost }}</p>
            </div>
          </div>
        </n-card>
        <div>
          <n-card title="预算核算 — BudgetAgent" style="margin-bottom: 16px">
            <template #header-extra>
              <n-tag size="small" :type="result.budgetResponse?.isBudgetMet ? 'success' : 'error'">
                {{ result.budgetResponse?.isBudgetMet ? '预算达标' : '超出预算' }}
              </n-tag>
            </template>
            <table style="width: 100%; border-collapse: collapse; font-size: 13px">
              <tbody>
                <tr><th style="text-align: left; padding: 5px 8px">住宿</th><td class="num">{{ result.budgetResponse?.accommodation }}</td></tr>
                <tr><th style="text-align: left; padding: 5px 8px">交通</th><td class="num">{{ result.budgetResponse?.transportCost }}</td></tr>
                <tr><th style="text-align: left; padding: 5px 8px">餐饮</th><td class="num">{{ result.budgetResponse?.foodCost }}</td></tr>
                <tr><th style="text-align: left; padding: 5px 8px">活动</th><td class="num">{{ result.budgetResponse?.activityCost }}</td></tr>
                <tr>
                  <th style="text-align: left; padding: 5px 8px"><b>总计</b>（用户预算 CNY {{ result.request?.budget }}）</th>
                  <td class="num"><b>{{ result.budgetResponse?.totalCost }}</b></td>
                </tr>
              </tbody>
            </table>
            <template v-if="result.budgetResponse?.recommendations?.length">
              <h3 class="hint" style="margin-top: 10px">建议</h3>
              <ul>
                <li v-for="(i, idx) in result.budgetResponse.recommendations" :key="idx">{{ i }}</li>
              </ul>
            </template>
          </n-card>
          <n-card title="景点调研 — ResearchAgent">
            <h3 class="hint">景点</h3>
            <ul><li v-for="(i, idx) in result.researchResponse?.attractions ?? []" :key="idx">{{ i }}</li></ul>
            <h3 class="hint">美食</h3>
            <ul><li v-for="(i, idx) in result.researchResponse?.foodOptions ?? []" :key="idx">{{ i }}</li></ul>
            <h3 class="hint">旅行贴士</h3>
            <ul><li v-for="(i, idx) in result.researchResponse?.travelTips ?? []" :key="idx">{{ i }}</li></ul>
          </n-card>
        </div>
      </div>
    </template>
  </div>
</template>
