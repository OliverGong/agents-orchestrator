<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { http, qs } from '@/shared/api/http'
import { fmtDateTime, tryParseJson } from '@/shared/utils/format'
import type { TeamLogEntry } from '@/shared/types/api'

const route = useRoute()
const router = useRouter()

/* ---------------- filters ---------------- */
const filters = reactive({
  category: '',
  level: '',
  window: '24h',
  runId: '',
  scenarioId: '',
  q: '',
})
const autoRefresh = ref(false)
const loading = ref(false)
const entries = ref<TeamLogEntry[]>([])
let logsTimer: ReturnType<typeof setInterval> | null = null

async function refresh(): Promise<void> {
  loading.value = true
  try {
    const query = qs({
      category: filters.category || undefined,
      level: filters.level || undefined,
      runId: filters.runId.trim() || undefined,
      scenarioId: filters.scenarioId.trim() || undefined,
      q: filters.q.trim() || undefined,
      window: filters.window,
      limit: '300',
    })
    entries.value = await http.get<TeamLogEntry[]>(`/api/v1/logs${query}`)
  } catch {
    entries.value = []
  } finally {
    loading.value = false
  }
}

watch(autoRefresh, (on) => {
  if (logsTimer) {
    clearInterval(logsTimer)
    logsTimer = null
  }
  if (on) logsTimer = setInterval(refresh, 10000)
})

/* ---------------- row detail ---------------- */
const selected = ref<TeamLogEntry | null>(null)

const detailJson = reactive({
  id: '' as string | number,
  ts: '',
  category: '',
  level: '',
  source: '',
  runId: null as string | null,
  scenarioId: null as string | null,
  action: '',
  message: '',
  detail: null as unknown,
})

function showDetail(entry: TeamLogEntry): void {
  selected.value = entry
  detailJson.id = entry.id
  detailJson.ts = entry.ts
  detailJson.category = entry.category
  detailJson.level = entry.level
  detailJson.source = entry.source
  detailJson.runId = entry.runId
  detailJson.scenarioId = entry.scenarioId
  detailJson.action = entry.action
  detailJson.message = entry.message
  detailJson.detail = tryParseJson(entry.detail)
}

/* Closed-loop interlinks: runId → the instance detail; scenarioId → filter
   the explorer by that scenario (stops row propagation so the raw detail
   still opens for clicks outside the chips). */
function openRunInInstances(runId: string): void {
  void router.push({ name: 'instances', query: { runId } })
}

function filterByScenario(scenarioId: string): void {
  filters.scenarioId = scenarioId
  void refresh()
}

function levelType(level: string): 'error' | 'warning' | 'success' {
  if (level === 'ERROR') return 'error'
  if (level === 'WARN') return 'warning'
  return 'success'
}

onMounted(async () => {
  const q = route.query
  if (typeof q.runId === 'string') filters.runId = q.runId
  if (typeof q.scenarioId === 'string') filters.scenarioId = q.scenarioId
  if (typeof q.window === 'string' && q.window) filters.window = q.window
  await refresh()
})
onBeforeUnmount(() => {
  if (logsTimer) clearInterval(logsTimer)
})
</script>

<template>
  <div>
    <n-card title="团队日志（Log Explorer）">
      <template #header-extra>
        <n-space align="center">
          <n-checkbox v-model:checked="autoRefresh">自动刷新 (10s)</n-checkbox>
          <n-button size="small" :loading="loading" @click="refresh">刷新</n-button>
        </n-space>
      </template>
      <p class="hint">
        运行时团队的持久化日志（<code>team_log</code> 表 / <code>config/team-log.jsonl</code>），不限于业务日志：运行生命周期、MCP
        工具调用、控制/元数据/场景面的每次变更审计与系统通知，先落库再推送（WAL 顺序），重启不丢。点击行查看详情。
      </p>
      <n-form inline size="small" label-placement="left">
        <n-form-item label="类别">
          <n-select
            v-model:value="filters.category"
            @update:value="refresh"
            :options="[
              { label: '全部', value: '' },
              { label: 'RUN', value: 'RUN' },
              { label: 'AUDIT', value: 'AUDIT' },
              { label: 'SYSTEM', value: 'SYSTEM' },
            ]"
            style="width: 110px"
          />
        </n-form-item>
        <n-form-item label="级别">
          <n-select
            v-model:value="filters.level"
            @update:value="refresh"
            :options="[
              { label: '全部', value: '' },
              { label: 'INFO', value: 'INFO' },
              { label: 'WARN', value: 'WARN' },
              { label: 'ERROR', value: 'ERROR' },
            ]"
            style="width: 110px"
          />
        </n-form-item>
        <n-form-item label="时间窗口">
          <n-select
            v-model:value="filters.window"
            @update:value="refresh"
            :options="[
              { label: '近 1 小时', value: '1h' },
              { label: '近 24 小时', value: '24h' },
              { label: '近 7 天', value: '7d' },
              { label: '全部', value: 'all' },
            ]"
            style="width: 120px"
          />
        </n-form-item>
        <n-form-item label="Run ID">
          <n-input v-model:value="filters.runId" placeholder="精确匹配（可留空）" clearable style="width: 200px" />
        </n-form-item>
        <n-form-item label="场景 ID">
          <n-input v-model:value="filters.scenarioId" placeholder="精确匹配（可留空）" clearable style="width: 150px" />
        </n-form-item>
        <n-form-item label="关键字">
          <n-input v-model:value="filters.q" placeholder="消息/动作/来源/详情" clearable style="width: 170px" />
        </n-form-item>
        <n-form-item>
          <n-button type="primary" @click="refresh">应用筛选</n-button>
        </n-form-item>
      </n-form>

      <table class="data-table-manual">
        <thead>
          <tr>
            <th>时间</th><th>类别</th><th>级别</th><th>来源</th><th>Run</th><th>场景</th><th>动作</th><th>消息</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(e, i) in entries" :key="`${e.id}-${i}`" class="clickable" @click="showDetail(e)">
            <td class="mono">{{ fmtDateTime(e.ts) }}</td>
            <td><n-tag size="small">{{ e.category }}</n-tag></td>
            <td><n-tag size="small" :type="levelType(e.level)">{{ e.level }}</n-tag></td>
            <td class="mono">{{ e.source }}</td>
            <td class="mono">
              <span v-if="e.runId" class="link-chip" title="打开该运行的实例追踪" @click.stop="openRunInInstances(e.runId)">
                {{ e.runId.slice(0, 8) }}…
              </span>
              <span v-else>—</span>
            </td>
            <td class="mono">
              <span v-if="e.scenarioId" class="link-chip" title="按该场景过滤日志" @click.stop="filterByScenario(e.scenarioId)">
                {{ e.scenarioId }}
              </span>
              <span v-else>—</span>
            </td>
            <td class="mono">{{ e.action }}</td>
            <td>{{ e.message }}</td>
          </tr>
          <tr v-if="!entries.length">
            <td colspan="8" class="hint">当前筛选条件下暂无日志。</td>
          </tr>
        </tbody>
      </table>
      <div class="hint" style="margin-top: 6px">共 {{ entries.length }} 条（最新在前，最多 300 条）</div>
    </n-card>

    <n-card v-if="selected" style="margin-top: 16px">
      <template #header>
        日志详情
        <n-tag size="small" style="margin-left: 8px">{{ selected.category }}/{{ selected.level }} · {{ selected.action }}</n-tag>
      </template>
      <pre class="json">{{ detailJson }}</pre>
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
