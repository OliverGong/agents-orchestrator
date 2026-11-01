<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { http } from '@/shared/api/http'
import { useControlStore } from '@/app/stores/control'
import { fmtTime, prettyJson } from '@/shared/utils/format'
import JsonDetails from '@/shared/components/JsonDetails.vue'
import DagDiagram from '@/shared/components/DagDiagram.vue'
import SwimlaneChart from '@/shared/components/SwimlaneChart.vue'
import type { DagNode } from '@/shared/types/dag'
import type { RunDetails, RunStatus, RunSummary } from '@/shared/types/api'

const route = useRoute()
const router = useRouter()
const control = useControlStore()

/* ---------------- list ---------------- */
const runs = ref<RunSummary[]>([])
const loading = ref(false)

async function refresh(): Promise<void> {
  loading.value = true
  try {
    runs.value = await http.get<RunSummary[]>('/api/trips/runs')
  } catch {
    runs.value = []
  } finally {
    loading.value = false
  }
}

/* ---------------- detail ---------------- */
const detail = ref<RunDetails | null>(null)
const view = ref<'timeline' | 'dag' | 'swimlane'>('timeline')
const dagExact = ref(false)

async function openDetail(runId: string): Promise<void> {
  detail.value = await http.get<RunDetails>(`/api/trips/runs/${runId}`)
  view.value = 'timeline'
}

function statusType(status: RunStatus): 'info' | 'success' | 'error' | 'default' {
  if (status === 'RUNNING') return 'info'
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'error'
  return 'default'
}

const finalContextPretty = computed(() =>
  detail.value ? prettyJson(detail.value.finalContext) : '',
)
const finalRequest = computed<Record<string, unknown> | null>(() => {
  try {
    return (JSON.parse(detail.value?.finalContext ?? 'null') as { request?: Record<string, unknown> })?.request ?? null
  } catch {
    return null
  }
})

/* DAG edges for a run: exact dependsOn when the run's stage set matches the
   current control-plane template, otherwise derived from wave adjacency. */
const dagNodes = computed<DagNode[]>(() =>
  (detail.value?.waves ?? []).flatMap((w) =>
    w.stages.map((s) => ({
      id: s.stageName,
      wave: w.waveIndex,
      label: s.stageName,
      sub: `${s.agentName}${s.durationMs != null ? ` · ${s.durationMs}ms` : ''}`,
      status: s.outcome === 'STAGE_COMPLETED' ? ('ok' as const) : ('fail' as const),
      retries: (s.retries ?? []).length,
    })),
  ),
)
const dagEdges = computed(() => {
  const d = detail.value
  if (!d) return []
  const stageNames = new Set(d.waves.flatMap((w) => w.stages.map((s) => s.stageName)))
  const wf = control.state?.workflow
  if (wf) {
    const names = new Set(wf.stages.map((s) => s.name))
    const same = names.size === stageNames.size && [...names].every((n) => stageNames.has(n))
    if (same) {
      dagExact.value = true
      return wf.stages.flatMap((s) =>
        s.dependsOn.filter((dep) => stageNames.has(dep)).map((dep) => ({ from: dep, to: s.name })),
      )
    }
  }
  dagExact.value = false
  const edges: { from: string; to: string }[] = []
  const byWave = new Map(d.waves.map((w) => [w.waveIndex, w.stages.map((s) => s.stageName)]))
  const maxWave = Math.max(...d.waves.map((w) => w.waveIndex))
  for (let w = 1; w <= maxWave; w++) {
    for (const to of byWave.get(w) ?? []) {
      for (const from of byWave.get(w - 1) ?? []) edges.push({ from, to })
    }
  }
  return edges
})

function gotoInstances(): void {
  if (!detail.value) return
  const query: Record<string, string> = { window: 'all', runId: detail.value.runId }
  if (detail.value.businessId) query.businessId = detail.value.businessId
  void router.push({ name: 'instances', query })
}

function gotoMetrics(): void {
  if (!detail.value?.scenarioId) return
  void router.push({ name: 'metrics', query: { scenarioId: detail.value.scenarioId } })
}

onMounted(async () => {
  await refresh()
  if (!control.state) await control.refresh()
  const q = route.query.runId
  if (typeof q === 'string' && q) await openDetail(q)
})
</script>

<template>
  <div>
    <n-card title="运行列表">
      <template #header-extra>
        <n-button size="small" :loading="loading" @click="refresh">刷新</n-button>
      </template>
      <p class="hint">
        ObservabilityActor 折叠的每次运行时间线（保留上限见系统观测）。点击行查看完整回放：时间线 / DAG 依赖图 /
        泳道图三种视图，含解析重试链与工具调用明细。
      </p>
      <table class="data-table-manual">
        <thead>
          <tr>
            <th>开始时间</th><th>模板</th><th>场景</th><th>模式</th><th>状态</th><th>耗时</th><th>阶段</th><th>失败</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in runs" :key="r.runId" class="clickable" @click="openDetail(r.runId)">
            <td>{{ fmtTime(r.startedAt) }}</td>
            <td>{{ r.templateName }}</td>
            <td class="mono">{{ r.scenarioId || '—' }}</td>
            <td>{{ r.mode }}</td>
            <td><n-tag size="small" :type="statusType(r.status)">{{ r.status }}</n-tag></td>
            <td class="num">{{ r.durationMs != null ? `${r.durationMs} ms` : '—' }}</td>
            <td class="num">{{ r.stageCount }}</td>
            <td class="num">{{ r.failedStages }}</td>
          </tr>
          <tr v-if="!runs.length">
            <td colspan="8" class="hint">暂无运行记录，去「场景运行」发起一次请求。</td>
          </tr>
        </tbody>
      </table>
    </n-card>

    <n-card v-if="detail" style="margin-top: 16px">
      <template #header>
        运行 {{ detail.runId.slice(0, 8) }}…
        <n-tag size="small" :type="statusType(detail.status)" style="margin-left: 8px">{{ detail.status }}</n-tag>
        <n-button-group size="tiny" style="margin-left: 10px">
          <n-button size="tiny" :type="view === 'timeline' ? 'primary' : 'default'" @click="view = 'timeline'">时间线</n-button>
          <n-button size="tiny" :type="view === 'dag' ? 'primary' : 'default'" @click="view = 'dag'">DAG</n-button>
          <n-button size="tiny" :type="view === 'swimlane' ? 'primary' : 'default'" @click="view = 'swimlane'">泳道</n-button>
        </n-button-group>
        <n-button size="tiny" type="primary" style="margin-left: 10px" @click="gotoInstances">实例追踪 →</n-button>
        <n-button v-if="detail.scenarioId" size="tiny" type="primary" style="margin-left: 6px" @click="gotoMetrics">
          场景指标 →
        </n-button>
      </template>
      <p class="hint">
        模板 <b>{{ detail.templateName }}</b> · 场景 <b class="mono">{{ detail.scenarioId || '控制平面默认' }}</b>
        <template v-if="detail.businessId"> · 业务 ID <b class="mono">{{ detail.businessId }}</b></template>
        · 模式 <b>{{ detail.mode }}</b> · {{ fmtTime(detail.startedAt) }} → {{ fmtTime(detail.completedAt) }}
      </p>

      <template v-if="view === 'dag'">
        <h3 class="hint">依赖 DAG（{{ dagExact ? '模板精确依赖' : '按波次推导' }}）</h3>
        <DagDiagram :nodes="dagNodes" :edges="dagEdges" use-status />
      </template>

      <template v-else-if="view === 'swimlane'">
        <h3 class="hint">Agent 泳道（时间 × 阶段，⚠ = 解析重试，▲ = MCP 工具调用）</h3>
        <SwimlaneChart :waves="detail.waves" />
      </template>

      <template v-else>
        <template v-if="finalRequest">
          <h3 class="hint">用户最初提出的请求</h3>
          <div class="request-echo">
            <dl>
              <dt>目的地</dt><dd>{{ finalRequest['destination'] }}</dd>
              <dt>天数</dt><dd>{{ finalRequest['numberOfDays'] }} 天</dd>
              <dt>预算</dt><dd>CNY {{ finalRequest['budget'] }}</dd>
              <dt>偏好</dt><dd>{{ finalRequest['preferences'] || '(无)' }}</dd>
            </dl>
          </div>
        </template>

        <h3 class="hint">依赖波次与阶段</h3>
        <template v-for="w in detail.waves" :key="w.waveIndex">
          <div class="wave-head">
            Wave {{ w.waveIndex }}
            <n-tag v-if="w.parallel" size="small" type="info">并行</n-tag>
            <span style="margin-left: 8px">{{ w.stages.map((s) => s.stageName).join(' ∥ ') }}</span>
          </div>
          <template v-for="s in w.stages" :key="`${w.waveIndex}-${s.stageName}`">
            <div class="stage-line" :class="s.outcome === 'STAGE_COMPLETED' ? 'done' : 'fail'">
              <span class="name">{{ s.stageName }}</span>
              <span class="arrow">→</span>
              <span>{{ s.agentName }}</span>
              <n-tag size="small" :type="s.outcome === 'STAGE_COMPLETED' ? 'success' : 'error'">
                {{ s.outcome === 'STAGE_COMPLETED' ? '完成' : '失败' }}
              </n-tag>
              <span class="meta">
                {{ s.durationMs != null ? `${s.durationMs} ms` : '' }}
                {{ s.threadName ? `· ${s.threadName}` : '' }}
                {{ s.parallelPeers?.length ? `· 同波: ${s.parallelPeers.join(', ')}` : '' }}
              </span>
              <span v-if="s.errorMessage" class="meta" style="color: var(--err)">{{ s.errorMessage }}</span>
            </div>
            <div v-for="r in s.retries ?? []" :key="`${s.stageName}-retry-${r.attempt}`" class="tool-line retry-line">
              <n-tag size="small" type="warning">↻ retry</n-tag>
              <span>第 {{ r.attempt }} 次尝试解析失败 → 重新采样</span>
              <span class="meta">{{ fmtTime(r.timestamp) }}</span>
              <span v-if="r.errorMessage" class="meta" style="color: var(--warn)">{{ r.errorMessage }}</span>
            </div>
            <div v-for="(t, ti) in s.toolCalls ?? []" :key="`${s.stageName}-tool-${ti}`" class="tool-line">
              <n-tag size="small" :type="t.outcome === 'OK' ? 'success' : 'error'">🔧</n-tag>
              <span>MCP <b>{{ t.serverName }}</b> · {{ t.toolName }}</span>
              <span class="meta">{{ t.durationMs }} ms</span>
              <span v-if="t.errorMessage" class="meta" style="color: var(--err)">{{ t.errorMessage }}</span>
              <JsonDetails v-if="t.resultSummary" title="工具结果" :json="t.resultSummary" class="tool-details" />
            </div>
            <JsonDetails title="输入黑板快照" :json="s.inputContext" />
            <JsonDetails title="输出黑板快照" :json="s.outputContext" />
          </template>
        </template>

        <template v-if="finalContextPretty">
          <h3 class="hint">最终上下文（黑板终态）</h3>
          <pre class="json">{{ finalContextPretty }}</pre>
        </template>
      </template>
    </n-card>
  </div>
</template>

<style scoped>
.data-table-manual { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table-manual th,
.data-table-manual td { padding: 7px 10px; border-bottom: 1px solid var(--border); text-align: left; }
.data-table-manual th { color: var(--muted); font-weight: 500; font-size: 12px; }
.data-table-manual tbody tr.clickable { cursor: pointer; }
.data-table-manual tbody tr.clickable:hover { background: var(--panel-2); }
:deep(.json-details) { margin-left: 28px; }
</style>
