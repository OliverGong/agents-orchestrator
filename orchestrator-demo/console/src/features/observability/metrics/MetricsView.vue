<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { http, qs } from '@/shared/api/http'
import { useSessionStore } from '@/app/stores/session'
import { fmtDateTime, fmtNum } from '@/shared/utils/format'
import type { ActuatorInfo, HealthResponse, MetricResponse } from '@/shared/types/api'

const route = useRoute()
const session = useSessionStore()

const autoRefresh = ref(false)
const loading = ref(false)
let obsTimer: ReturnType<typeof setInterval> | null = null

interface MetricCard {
  name: string
  label: string
  value: number | string
  sub?: string
}
const cards = ref<MetricCard[]>([])

const METRIC_CARDS = [
  { name: 'orchestrator.runs.started', label: '运行发起' },
  { name: 'orchestrator.runs.completed', label: '运行成功' },
  { name: 'orchestrator.runs.failed', label: '运行失败' },
  { name: 'orchestrator.stage.completed', label: '阶段成功' },
  { name: 'orchestrator.stage.failed', label: '阶段失败' },
  { name: 'orchestrator.observability.retained.runs', label: '保留时间线' },
]

async function metricCount(name: string, tag?: string): Promise<number> {
  try {
    const d = await http.get<MetricResponse>(
      `/actuator/metrics/${name}${tag ? `?tag=${encodeURIComponent(tag)}` : ''}`,
    )
    const m = d.measurements.find((x) => x.statistic === 'COUNT')
    return m ? m.value : 0
  } catch {
    return 0
  }
}

/* ---------------- agent table ---------------- */
interface AgentRow {
  agent: string
  stages: number
  fails: number
  execCount: number | string
  execTotal: number | string
  execMax: number | string
}
const agentRows = ref<AgentRow[]>([])

/* ---------------- LM table (durable obs_run_metrics aggregation, 7d) ---- */
const lmScenario = ref('')
const lmRows = ref<LMRow[]>([])
const lmReachable = ref(true)
interface LMRow {
  scenarioId: string | null
  stage: string
  agent: string
  model: string | null
  stageCount: number
  failedCount: number
  retries: number
  avgDurationMs: number | null
  maxDurationMs: number | null
  promptTokens: number
  completionTokens: number
  totalTokens: number
  lastRunAt: string
}

const scenarioOptions = computed(() => [
  { label: '全部场景', value: '' },
  ...session.scenarios.map((s) => ({ label: `${s.name}（${s.id}）`, value: s.id })),
])

/* ---------------- health / info panels ---------------- */
const healthPanel = ref('加载中…')
const infoPanel = ref('加载中…')

async function refresh(): Promise<void> {
  loading.value = true
  try {
    await runRefresh()
  } finally {
    loading.value = false
  }
}

async function runRefresh(): Promise<void> {
  const health: HealthResponse | null = await session.refreshHealth()

  /* metric cards */
  const loaded: MetricCard[] = await Promise.all(
    METRIC_CARDS.map(async (m) => {
      try {
        const d = await http.get<MetricResponse>(`/actuator/metrics/${m.name}`)
        const count = d.measurements.find(
          (x) => x.statistic === 'COUNT' || x.statistic === 'VALUE',
        )
        return { ...m, value: count ? count.value : '—' }
      } catch {
        return { ...m, value: '—' as const }
      }
    }),
  )
  try {
    const dur = await http.get<MetricResponse>('/actuator/metrics/orchestrator.run.duration')
    const by = (s: string) => dur.measurements.find((x) => x.statistic === s)?.value ?? 0
    loaded.push({
      name: 'orchestrator.run.duration',
      label: '运行总时长 (s)',
      value: by('TOTAL_TIME').toFixed(1),
      sub: `count=${by('COUNT')} · max=${by('MAX').toFixed(1)}s`,
    })
  } catch {
    /* duration card omitted when absent */
  }
  cards.value = loaded

  /* per-agent table */
  const agents = health?.components?.orchestrator?.details?.agents ?? []
  agentRows.value = await Promise.all(
    agents.map(async (agent) => {
      const stages = await metricCount('orchestrator.stage.completed', `agent:${agent}`)
      const fails = await metricCount('orchestrator.stage.failed', `agent:${agent}`)
      const exec: AgentRow = {
        agent,
        stages,
        fails,
        execCount: '—',
        execTotal: '—',
        execMax: '—',
      }
      try {
        const d = await http.get<MetricResponse>(
          `/actuator/metrics/agent.execution.duration?tag=agent:${encodeURIComponent(agent)}`,
        )
        const by = (s: string) => durMeasure(d, s)
        exec.execCount = by('COUNT') ?? '—'
        exec.execTotal = by('TOTAL_TIME') == null ? '—' : by('TOTAL_TIME')!.toFixed(1)
        exec.execMax = by('MAX') == null ? '—' : by('MAX')!.toFixed(1)
      } catch {
        /* timer absent yet */
      }
      return exec
    }),
  )

  /* durable per-scenario LM metrics, scoped by the lifecycle-chain filter */
  const scoped = qs({ window: '7d', scenarioId: lmScenario.value || undefined })
  try {
    lmRows.value = await http.get<LMRow[]>(`/api/v1/metrics/lm${scoped}`)
    lmReachable.value = true
  } catch {
    lmRows.value = []
    lmReachable.value = false
  }

  /* health & info panels */
  if (health) {
    const orch = health.components?.orchestrator
    healthPanel.value = JSON.stringify(
      {
        status: health.status,
        orchestrator: orch ? { status: orch.status, ...orch.details } : undefined,
      },
      null,
      2,
    )
  } else {
    healthPanel.value = '应用健康端点不可达'
  }
  try {
    const info = await http.get<ActuatorInfo>('/actuator/info')
    infoPanel.value = JSON.stringify(info.workflow ?? info, null, 2)
  } catch {
    infoPanel.value = 'info 端点不可达'
  }
}

function durMeasure(d: MetricResponse, statistic: string): number | null {
  return d.measurements.find((x) => x.statistic === statistic)?.value ?? null
}

watch(autoRefresh, (on) => {
  if (obsTimer) {
    clearInterval(obsTimer)
    obsTimer = null
  }
  if (on) obsTimer = setInterval(runRefresh, 10000)
})

onMounted(async () => {
  const q = route.query.scenarioId
  if (typeof q === 'string') lmScenario.value = q
  else if (session.pendingMetricsScenario) lmScenario.value = session.pendingMetricsScenario
  session.pendingMetricsScenario = ''
  if (!session.scenarios.length) await session.refreshScenarios()
  await runRefresh()
})
onBeforeUnmount(() => {
  if (obsTimer) clearInterval(obsTimer)
})
</script>

<template>
  <div>
    <n-card title="过程指标">
      <template #header-extra>
        <n-space align="center">
          <n-checkbox v-model:checked="autoRefresh">自动刷新 (10s)</n-checkbox>
          <n-button size="small" :loading="loading" @click="refresh">刷新</n-button>
        </n-space>
      </template>
      <div class="metric-grid">
        <div v-for="c in cards" :key="c.name" class="metric-card">
          <div class="label">{{ c.label }}</div>
          <div class="value">{{ c.value }}</div>
          <div v-if="c.sub" class="sub">{{ c.sub }}</div>
          <div class="sub">{{ c.name }}</div>
        </div>
      </div>

      <h3 class="hint" style="margin-top: 14px">Agent 维度（stage 计数 + agent.execution.duration）</h3>
      <table class="data-table-manual">
        <thead>
          <tr>
            <th>Agent</th><th>阶段成功</th><th>阶段失败</th><th>执行次数</th><th>总耗时 (s)</th><th>最大耗时 (s)</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in agentRows" :key="r.agent">
            <td><b>{{ r.agent }}</b></td>
            <td class="num">{{ r.stages }}</td>
            <td class="num">{{ r.fails }}</td>
            <td class="num">{{ r.execCount }}</td>
            <td class="num">{{ r.execTotal }}</td>
            <td class="num">{{ r.execMax }}</td>
          </tr>
          <tr v-if="!agentRows.length">
            <td colspan="6" class="hint">未获取到 agent 列表。</td>
          </tr>
        </tbody>
      </table>

      <h3 class="hint" style="margin-top: 14px">
        LM 消耗（按场景/阶段聚合，近 7 天 · obs_run_metrics）
        <n-select
          v-model:value="lmScenario"
          :options="scenarioOptions"
          size="tiny"
          style="width: 240px; margin-left: 8px"
          @update:value="runRefresh"
        />
      </h3>
      <table v-if="lmReachable" class="data-table-manual">
        <thead>
          <tr>
            <th>场景</th><th>阶段</th><th>Agent</th><th>模型</th><th>阶段次数</th><th>失败</th><th>重试</th>
            <th>平均耗时 (ms)</th><th>最大耗时 (ms)</th><th>Prompt</th><th>Completion</th><th>总 tokens</th><th>最近</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(m, i) in lmRows" :key="i">
            <td class="mono">{{ m.scenarioId ?? '—' }}</td>
            <td>{{ m.stage }}</td>
            <td>{{ m.agent }}</td>
            <td>{{ m.model ?? '—' }}</td>
            <td class="num">{{ m.stageCount }}</td>
            <td class="num">{{ m.failedCount }}</td>
            <td class="num">{{ m.retries }}</td>
            <td class="num">{{ fmtNum(m.avgDurationMs) }}</td>
            <td class="num">{{ fmtNum(m.maxDurationMs) }}</td>
            <td class="num">{{ m.promptTokens }}</td>
            <td class="num">{{ m.completionTokens }}</td>
            <td class="num"><b>{{ m.totalTokens }}</b></td>
            <td>{{ fmtDateTime(m.lastRunAt) }}</td>
          </tr>
          <tr v-if="!lmRows.length">
            <td colspan="13" class="hint">
              暂无落库指标（db 模式下运行场景后此处会出现每个阶段/模型的耗时与 token 汇总）。
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="hint">LM 指标端点不可达（file 模式无落库）。</div>
    </n-card>

    <div class="grid-2" style="margin-top: 16px">
      <n-card title="健康检查 (/actuator/health)">
        <div class="mono-panel">{{ healthPanel }}</div>
      </n-card>
      <n-card title="工作流配置 (/actuator/info)">
        <div class="mono-panel">{{ infoPanel }}</div>
      </n-card>
    </div>

    <n-card title="追踪与指标后端" style="margin-top: 16px">
      <p class="hint">
        每次运行产生 <code>orchestrator.planTrip</code> 父 span 与每阶段一个 <code>agent.execute</code> 子 span
        （OTLP/gRPC :4317 → Tempo）；指标经 <code>/actuator/prometheus</code> →
        Prometheus。点击侧栏底部链接查看瀑布图与仪表盘。
      </p>
    </n-card>
  </div>
</template>

<style scoped>
.data-table-manual { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table-manual th,
.data-table-manual td { padding: 7px 10px; border-bottom: 1px solid var(--border); text-align: left; }
.data-table-manual th { color: var(--muted); font-weight: 500; font-size: 12px; }
</style>
