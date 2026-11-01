<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { http } from '../api/http'
import { useSessionStore } from '../stores/session'
import DagDiagram from '../components/DagDiagram.vue'
import type { ControlPlaneState, DryRunReport } from '../types/api'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const session = useSessionStore()

const scenarioOptions = computed(() =>
  session.scenarios.map((s) => ({ label: `${s.name}（${s.id}）`, value: s.id })),
)
const selectedScenarioId = ref<string | null>(null)
const running = ref(false)
const report = ref<DryRunReport | null>(null)

async function startDryRun() {
  const id = selectedScenarioId.value
  if (!id) return
  running.value = true
  try {
    report.value = await http.post<DryRunReport>(
      `/api/v1/scenarios/${encodeURIComponent(id)}/dry-run`,
    )
  } catch (e) {
    message.error('推演失败: ' + (e as Error).message)
  } finally {
    running.value = false
  }
}

const reportScenarioName = computed(() => {
  if (!report.value) return ''
  return session.scenarioById(report.value.scenarioId)?.name ?? report.value.scenarioId
})

const dagNodes = computed(() =>
  (report.value?.waves ?? []).flatMap((w) =>
    w.stages.map((s) => ({
      id: s.stageName,
      wave: w.waveIndex,
      label: s.stageName,
      sub: s.agentName,
      status: 'planned' as const,
      retries: 0,
    })),
  ),
)
const dagEdges = computed(() =>
  (report.value?.edges ?? []).map((e) => ({ from: e.from, to: e.to })),
)

async function activateAndRun() {
  if (!report.value) return
  try {
    await http.post<ControlPlaneState>(
      `/api/v1/scenarios/${encodeURIComponent(report.value.scenarioId)}/activate`,
    )
    session.ctxScenarioId = report.value.scenarioId
    await router.push({ name: 'run', query: { scenarioId: report.value.scenarioId } })
  } catch (e) {
    message.error('激活失败: ' + (e as Error).message)
  }
}

onMounted(() => {
  const q = route.query.scenarioId
  if (typeof q === 'string' && q) selectedScenarioId.value = q
  else if (session.ctxScenarioId) selectedScenarioId.value = session.ctxScenarioId
})
</script>

<template>
  <div>
    <n-card title="场景推演（Dry Run）">
      <p class="hint">
        不调用任何真实模型：后端按控制平面实时状态做模板校验、槽位闭环检查与依赖波次展开，输出 DAG
        边、黑板槽位流与每个阶段的有效配置。推演通过再运行，不浪费一次 token。
      </p>
      <n-space align="center">
        <n-select
          v-model:value="selectedScenarioId"
          :options="scenarioOptions"
          placeholder="选择场景"
          clearable
          style="width: 340px"
        />
        <n-button type="primary" :loading="running" @click="startDryRun">开始推演</n-button>
      </n-space>
    </n-card>

    <n-card v-if="report" style="margin-top: 16px">
      <template #header>
        推演报告 — {{ reportScenarioName }}
        <n-tag size="small" :type="report.valid ? 'success' : 'error'" style="margin-left: 8px">
          {{ report.valid ? '校验通过，可以运行' : '存在问题' }}
        </n-tag>
      </template>
      <p class="hint">
        解析模板来源 <b>{{ report.resolvedTemplateSource === 'scenario-override' ? '场景覆盖' : '控制平面当前模板' }}</b> ·
        模式 <b>{{ report.resolvedMode }}</b> · {{ report.waves.length }} 个波次 · {{ report.edges.length }} 条依赖边
      </p>
      <ul class="issue-list" :class="{ 'ok-list': report.valid }">
        <li v-for="(issue, i) in report.issues.length ? report.issues : ['无校验问题 —— 模板闭环、Agent 引用与 MCP 依赖全部通过']" :key="i">
          {{ issue }}
        </li>
      </ul>

      <h3 class="hint" style="margin-top: 14px">依赖 DAG</h3>
      <DagDiagram :nodes="dagNodes" :edges="dagEdges" />

      <h3 class="hint" style="margin-top: 14px">波次展开（模拟执行顺序）</h3>
      <div v-if="report.waves.length">
        <template v-for="w in report.waves" :key="w.waveIndex">
          <div class="wave-head">
            Wave {{ w.waveIndex }}
            <n-tag v-if="w.parallel" size="small" type="info">并行</n-tag>
            <span style="margin-left: 8px">{{ w.stages.map((s) => s.stageName).join(' ∥ ') }}</span>
          </div>
          <div v-for="s in w.stages" :key="s.stageName" class="stage-line planned">
            <span class="name">{{ s.stageName }}</span>
            <span class="arrow">→</span>
            <span>{{ s.agentName }}</span>
            <n-tag size="small">
              {{ s.effectiveModel || '后端默认模型' }}{{ s.effectiveTemperature != null ? ` · T=${s.effectiveTemperature}` : '' }}
            </n-tag>
            <n-tag v-if="s.mcpServers.length" size="small">MCP ×{{ s.mcpServers.length }}</n-tag>
            <span class="meta">
              {{ s.inputs.length ? `in: ${s.inputs.join(', ')} · ` : '' }}
              {{ s.outputs.length ? `out: ${s.outputs.join(', ')}` : '' }}
              {{ s.pendingSafe.length ? ` · ⟂ ${s.pendingSafe.join(', ')}` : '' }}
            </span>
            <span v-for="(wn, i) in s.warnings" :key="i" class="meta" style="color: var(--warn)">⚠ {{ wn }}</span>
          </div>
        </template>
      </div>
      <div v-else class="hint">无法展开波次（存在校验问题）。</div>

      <h3 class="hint" style="margin-top: 14px">黑板槽位流</h3>
      <table class="data-table-manual">
        <thead>
          <tr>
            <th>黑板槽位</th><th>产出阶段</th><th>消费阶段</th><th>pending-safe</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="f in report.slotFlows" :key="f.slot">
            <td class="mono">{{ f.slot }}</td>
            <td>{{ f.producedBy.join(', ') || '—' }}</td>
            <td>{{ f.consumedBy.join(', ') || '—' }}</td>
            <td>{{ f.pendingSafe ? '⟂ 是' : '否' }}</td>
          </tr>
          <tr v-if="!report.slotFlows.length">
            <td colspan="4" class="hint">无槽位契约数据。</td>
          </tr>
        </tbody>
      </table>

      <div v-if="report.valid" style="margin-top: 14px">
        <n-button type="primary" @click="activateAndRun">下一步：激活并运行 →</n-button>
        <span class="hint" style="margin-left: 10px">
          推演通过后激活：工作流 / Agent 覆盖热应用到控制平面，随后进入运行页直接执行。
        </span>
      </div>
    </n-card>
  </div>
</template>

<style scoped>
.issue-list { margin: 6px 0; padding-left: 20px; }
.issue-list li { color: var(--err); font-family: var(--mono); font-size: 12px; margin: 3px 0; }
.issue-list.ok-list li { color: var(--ok); }
.data-table-manual { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table-manual th,
.data-table-manual td { padding: 7px 10px; border-bottom: 1px solid var(--border); text-align: left; }
.data-table-manual th { color: var(--muted); font-weight: 500; font-size: 12px; }
.stage-line.planned { border-color: var(--border); }
</style>
