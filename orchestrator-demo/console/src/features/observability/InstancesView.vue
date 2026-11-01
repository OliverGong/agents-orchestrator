<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { http, qs } from '@/shared/api/http'
import { fmtDateTime } from '@/shared/utils/format'
import JsonDetails from '@/shared/components/JsonDetails.vue'
import type { InstanceDetail, InstanceStageMetric, RunStage, RunStatus } from '@/shared/types/api'

const route = useRoute()
const router = useRouter()

/* ---------------- filters ---------------- */
const filters = reactive({
  businessId: '',
  scenarioId: '',
  status: '',
  q: '',
  window: '24h',
})
const loading = ref(false)
const rows = ref<InstanceDetail['instance'][]>([])

async function refresh(): Promise<void> {
  loading.value = true
  try {
    const query = qs({
      businessId: filters.businessId.trim() || undefined,
      scenarioId: filters.scenarioId.trim() || undefined,
      status: filters.status || undefined,
      q: filters.q.trim() || undefined,
      window: filters.window,
      limit: '200',
    })
    rows.value = await http.get<InstanceDetail['instance'][]>(`/api/v1/instances${query}`)
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

/* ---------------- detail ---------------- */
const detail = ref<InstanceDetail | null>(null)
const detailError = ref('')

interface StageRow {
  waveIndex: number | string
  stage: string
  agent: string
  model: string
  outcome: string
  durationMs: number | null
  promptTokens: number | string
  completionTokens: number | string
  totalTokens: number | string
  attempt: number | string
  createdAt: string
}

const stageRows = ref<StageRow[]>([])
const finalContext = ref('')
const requestPayload = ref<Record<string, unknown> | null>(null)
const requestRaw = ref('')

/* Prefer the durable per-stage rows (obs_run_metrics, survive restarts);
   fall back to the in-memory timeline usage while the run is retained. */
function buildStageRows(d: InstanceDetail): StageRow[] {
  if (d.stages.length) {
    return d.stages.map(
      (s: InstanceStageMetric) => ({
        waveIndex: s.waveIndex ?? '—',
        stage: s.stage,
        agent: s.agent,
        model: s.model || '默认',
        outcome: s.outcome,
        durationMs: s.durationMs,
        promptTokens: s.promptTokens ?? '—',
        completionTokens: s.completionTokens ?? '—',
        totalTokens: s.totalTokens ?? '—',
        attempt: s.attempt ?? 1,
        createdAt: fmtDateTime(s.createdAt),
      }),
    )
  }
  if (d.run) {
    return d.run.waves.flatMap((w) =>
      w.stages.map(
        (s: RunStage) => ({
          waveIndex: w.waveIndex,
          stage: s.stageName,
          agent: s.agentName,
          model: s.usage?.model || '默认',
          outcome: s.outcome,
          durationMs: s.durationMs ?? null,
          promptTokens: s.usage ? s.usage.promptTokens : '—',
          completionTokens: s.usage ? s.usage.completionTokens : '—',
          totalTokens: s.usage ? s.usage.totalTokens : '—',
          attempt: s.usage ? s.usage.retries + 1 : 1,
          createdAt: fmtDateTime(s.completedAt ?? undefined),
        }),
      ),
    )
  }
  return []
}

async function openDetail(runId: string): Promise<void> {
  detailError.value = ''
  detail.value = null
  try {
    const d = await http.get<InstanceDetail>(`/api/v1/instances/${encodeURIComponent(runId)}`)
    detail.value = d
    stageRows.value = buildStageRows(d)
    try {
      finalContext.value = d.run ? JSON.stringify(JSON.parse(d.run.finalContext), null, 2) : ''
    } catch {
      finalContext.value = d.run?.finalContext ?? ''
    }
    requestRaw.value = d.instance.requestJson
    try {
      requestPayload.value = JSON.parse(d.instance.requestJson) as Record<string, unknown>
    } catch {
      requestPayload.value = null
    }
  } catch (e) {
    detailError.value = '实例详情加载失败: ' + (e as Error).message
  }
}

function gotoReplay(): void {
  if (!detail.value) return
  void router.push({ name: 'runs', query: { runId: detail.value.instance.runId } })
}

function gotoLogs(): void {
  if (!detail.value) return
  void router.push({
    name: 'logs',
    query: { runId: detail.value.instance.runId, window: 'all' },
  })
}

let refreshTimer: ReturnType<typeof setInterval> | null = null
onMounted(async () => {
  const q = route.query
  if (typeof q.businessId === 'string') filters.businessId = q.businessId
  if (typeof q.window === 'string' && q.window) filters.window = q.window
  await refresh()
  if (typeof q.runId === 'string' && q.runId) await openDetail(q.runId)
  refreshTimer = setInterval(refresh, 30000)
})
onBeforeUnmount(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})

function statusType(status: RunStatus): 'info' | 'success' | 'error' | 'default' {
  if (status === 'RUNNING') return 'info'
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'error'
  return 'default'
}
</script>

<template>
  <div>
    <n-card title="运行实例注册表">
      <template #header-extra>
        <n-button size="small" :loading="loading" @click="refresh">刷新</n-button>
      </template>
      <p class="hint">
        数据溯源的起点：每个业务触发（<code>POST /api/trips/plan</code>）在请求进入时实例化一条 RUNNING
        记录（唯一 runId + 调用方 businessId），终态由 ObservabilityActor 先落库再推送。按 businessId / 场景 / 状态 /
        关键字检索，点击行查看该次实例化的完整上下文。
      </p>
      <n-form inline size="small" label-placement="left">
        <n-form-item label="业务 ID">
          <n-input v-model:value="filters.businessId" placeholder="businessId 精确匹配" clearable style="width: 170px" />
        </n-form-item>
        <n-form-item label="场景 ID">
          <n-input v-model:value="filters.scenarioId" placeholder="scenarioId 精确匹配" clearable style="width: 150px" />
        </n-form-item>
        <n-form-item label="状态">
          <n-select
            v-model:value="filters.status"
            :options="[
              { label: '全部', value: '' },
              { label: 'RUNNING', value: 'RUNNING' },
              { label: 'COMPLETED', value: 'COMPLETED' },
              { label: 'FAILED', value: 'FAILED' },
            ]"
            style="width: 130px"
          />
        </n-form-item>
        <n-form-item label="时间窗口">
          <n-select
            v-model:value="filters.window"
            :options="[
              { label: '近 1 小时', value: '1h' },
              { label: '近 24 小时', value: '24h' },
              { label: '近 7 天', value: '7d' },
              { label: '全部', value: 'all' },
            ]"
            style="width: 130px"
          />
        </n-form-item>
        <n-form-item label="关键字">
          <n-input v-model:value="filters.q" placeholder="runId / businessId / 场景 / 错误" clearable style="width: 180px" />
        </n-form-item>
        <n-form-item>
          <n-button type="primary" @click="refresh">应用筛选</n-button>
        </n-form-item>
      </n-form>

      <table class="data-table-manual">
        <thead>
          <tr>
            <th>开始时间</th><th>Run ID</th><th>业务 ID</th><th>场景</th><th>模板</th><th>模式</th><th>状态</th><th>耗时</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="i in rows" :key="i.runId" class="clickable" @click="openDetail(i.runId)">
            <td class="mono">{{ fmtDateTime(i.startedAt) }}</td>
            <td class="mono" :title="i.runId">{{ i.runId.slice(0, 8) }}…</td>
            <td class="mono">{{ i.businessId || '—' }}</td>
            <td class="mono">{{ i.scenarioId || '默认' }}</td>
            <td>{{ i.templateName }}</td>
            <td>{{ i.mode }}</td>
            <td><n-tag size="small" :type="statusType(i.status)">{{ i.status }}</n-tag></td>
            <td class="num">
              <template v-if="i.durationMs != null">{{ i.durationMs }} ms</template>
              <span v-else class="hint" style="margin: 0">进行中</span>
            </td>
          </tr>
          <tr v-if="!rows.length">
            <td colspan="8" class="hint">当前筛选条件下暂无实例；发起一次业务请求后，其实例化过程将从这里可溯源。</td>
          </tr>
        </tbody>
      </table>
      <div class="hint" style="margin-top: 6px">共 {{ rows.length }} 条实例（最新在前）</div>
    </n-card>

    <n-card v-if="detailError" style="margin-top: 16px">
      <p class="error-message">{{ detailError }}</p>
    </n-card>

    <n-card v-if="detail" style="margin-top: 16px">
      <template #header>
        实例 {{ detail.instance.runId.slice(0, 8) }}…
        <n-tag size="small" :type="statusType(detail.instance.status)" style="margin-left: 8px">
          {{ detail.instance.status }}
        </n-tag>
        <n-tag v-if="detail.instance.businessId" size="small" style="margin-left: 6px">
          业务 {{ detail.instance.businessId }}
        </n-tag>
        <n-button size="tiny" type="primary" style="margin-left: 10px" @click="gotoReplay">运行回放 →</n-button>
        <n-button size="tiny" type="primary" style="margin-left: 6px" @click="gotoLogs">团队日志 →</n-button>
      </template>
      <p class="hint">
        唯一请求 ID <b class="mono">{{ detail.instance.runId }}</b>
        <template v-if="detail.instance.businessId"> · 业务关联 ID <b class="mono">{{ detail.instance.businessId }}</b></template>
        <template v-else> · 无业务关联 ID</template>
      </p>
      <div class="request-echo">
        <dl>
          <dt>场景</dt><dd class="mono">{{ detail.instance.scenarioId || '控制平面默认' }}</dd>
          <dt>模板 / 模式</dt><dd>{{ detail.instance.templateName }} · {{ detail.instance.mode }}</dd>
          <dt>触发方式</dt><dd>{{ detail.instance.trigger }}</dd>
          <dt>开始 → 完成</dt>
          <dd>{{ fmtDateTime(detail.instance.startedAt) }} → {{ fmtDateTime(detail.instance.completedAt) }}</dd>
          <dt>耗时</dt>
          <dd>{{ detail.instance.durationMs != null ? `${detail.instance.durationMs} ms` : '进行中' }}</dd>
          <template v-if="detail.instance.errorMessage">
            <dt>错误</dt><dd class="error-message">{{ detail.instance.errorMessage }}</dd>
          </template>
        </dl>
      </div>

      <h3 class="hint" style="margin-top: 14px">请求上下文（业务触发载荷）</h3>
      <template v-if="requestPayload">
        <div class="request-echo">
          <dl>
            <dt>目的地</dt><dd>{{ requestPayload['destination'] }}</dd>
            <dt>天数</dt><dd>{{ requestPayload['numberOfDays'] }} 天</dd>
            <dt>预算</dt><dd>CNY {{ requestPayload['budget'] }}</dd>
            <dt>偏好</dt><dd>{{ requestPayload['preferences'] || '(无)' }}</dd>
          </dl>
        </div>
        <JsonDetails title="原始请求 JSON" :json="requestRaw" />
      </template>
      <pre v-else class="json">{{ requestRaw }}</pre>

      <h3 class="hint" style="margin-top: 14px">多 Agent / 多模型调用明细</h3>
      <table v-if="stageRows.length" class="data-table-manual">
        <thead>
          <tr>
            <th>Wave</th><th>阶段</th><th>Agent</th><th>模型</th><th>结果</th><th>耗时</th>
            <th>Prompt</th><th>Completion</th><th>总 tokens</th><th>尝试</th><th>时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(s, i) in stageRows" :key="i">
            <td class="mono">{{ s.waveIndex }}</td>
            <td>{{ s.stage }}</td>
            <td>{{ s.agent }}</td>
            <td>{{ s.model }}</td>
            <td><n-tag size="small" :type="s.outcome === 'STAGE_COMPLETED' ? 'success' : 'error'">{{ s.outcome }}</n-tag></td>
            <td class="num">{{ s.durationMs != null ? `${s.durationMs} ms` : '—' }}</td>
            <td class="num">{{ s.promptTokens }}</td>
            <td class="num">{{ s.completionTokens }}</td>
            <td class="num"><b>{{ s.totalTokens }}</b></td>
            <td class="num">{{ s.attempt }}</td>
            <td class="mono">{{ s.createdAt }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="hint">
        暂无阶段调用明细（阶段尚未完成或指标后端不可达；运行回放中仍可见实时时间线）。
      </div>

      <template v-if="finalContext">
        <h3 class="hint" style="margin-top: 14px">黑板终态（最终上下文）</h3>
        <pre class="json">{{ finalContext }}</pre>
      </template>

      <h3 class="hint" style="margin-top: 14px">关联团队日志（{{ detail.logs.length }} 条）</h3>
      <table v-if="detail.logs.length" class="data-table-manual">
        <thead>
          <tr><th>时间</th><th>级别</th><th>动作</th><th>消息</th></tr>
        </thead>
        <tbody>
          <tr v-for="l in detail.logs" :key="l.id">
            <td class="mono">{{ fmtDateTime(l.ts) }}</td>
            <td>
              <n-tag size="small" :type="l.level === 'ERROR' ? 'error' : l.level === 'WARN' ? 'warning' : 'success'">
                {{ l.level }}
              </n-tag>
            </td>
            <td class="mono">{{ l.action }}</td>
            <td>{{ l.message }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="hint">该运行没有留存的团队日志（可能早于日志保留窗口）。</div>
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
</style>
