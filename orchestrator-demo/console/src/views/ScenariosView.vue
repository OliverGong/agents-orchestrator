<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useDialog, useMessage } from 'naive-ui'
import { http } from '../api/http'
import { useSessionStore } from '../stores/session'
import { useControlStore } from '../stores/control'
import type { ArtifactType, ScenarioSummary } from '../types/api'

const router = useRouter()
const dialog = useDialog()
const message = useMessage()
const session = useSessionStore()
const control = useControlStore()

const artifacts: ArtifactType[] = ['HTML', 'SPRING_BOOT_PROJECT', 'JAR', 'AUDIO']
const loading = ref(false)

async function refresh(): Promise<void> {
  loading.value = true
  try {
    await session.refreshScenarios()
  } finally {
    loading.value = false
  }
}

function openDryRun(id: string): void {
  void router.push({ name: 'dryrun', query: { scenarioId: id } })
}

function confirmActivate(id: string): void {
  dialog.warning({
    title: '激活场景',
    content: `激活场景 ${id}？（工作流/Agent 覆盖热应用到控制平面）`,
    positiveText: '激活',
    negativeText: '取消',
    async onPositiveClick() {
      try {
        await http.post(`/api/v1/scenarios/${encodeURIComponent(id)}/activate`)
        session.ctxScenarioId = id
        await session.refreshScenarios()
        message.success(`场景 ${id} 已激活`)
        void router.push({ name: 'run', query: { scenarioId: id } })
      } catch (e) {
        message.error('激活失败: ' + (e as Error).message)
      }
    },
  })
}

function confirmDelete(id: string): void {
  dialog.warning({
    title: '删除场景',
    content: `删除场景 ${id}？`,
    positiveText: '删除',
    negativeText: '取消',
    async onPositiveClick() {
      try {
        await http.del(`/api/v1/scenarios/${encodeURIComponent(id)}`)
        message.success('已删除')
        await refresh()
      } catch (e) {
        message.error('删除失败: ' + (e as Error).message)
      }
    },
  })
}

/* ---------------- editor ---------------- */
const editorOpen = ref(false)
const editingId = ref<string | null>(null)
const saving = ref(false)
interface EditorForm {
  id: string
  name: string
  description: string
  artifactType: ArtifactType
  active: boolean
  destination: string
  numberOfDays: number
  budget: number
  preferences: string
  mcpServerRefs: string[]
  workflowJson: string
  agentsJson: string
}
const form = reactive<EditorForm>({
  id: '',
  name: '',
  description: '',
  artifactType: 'HTML',
  active: true,
  destination: '',
  numberOfDays: 3,
  budget: 15000,
  preferences: '',
  mcpServerRefs: [],
  workflowJson: '',
  agentsJson: '',
})

function openEditor(id: string | null): void {
  const s: ScenarioSummary | null = id ? (session.scenarioById(id) ?? null) : null
  editingId.value = id
  editorOpen.value = true
  form.id = s?.id ?? ''
  form.name = s?.name ?? ''
  form.description = s?.description ?? ''
  form.artifactType = s?.artifactType ?? 'HTML'
  form.active = s ? s.active : true
  form.destination = s?.requestTemplate?.destination ?? ''
  form.numberOfDays = s?.requestTemplate?.numberOfDays ?? 3
  form.budget = s?.requestTemplate?.budget ?? 15000
  form.preferences = s?.requestTemplate?.preferences ?? ''
  form.mcpServerRefs = [...(s?.mcpServerRefs ?? [])]
  form.workflowJson = s?.workflowOverride ? JSON.stringify(s.workflowOverride, null, 2) : ''
  form.agentsJson =
    s && Object.keys(s.agentOverrides ?? {}).length ? JSON.stringify(s.agentOverrides, null, 2) : ''
}

function copyWorkflowFromControlPlane(): void {
  if (control.state) form.workflowJson = JSON.stringify(control.state.workflow, null, 2)
}

async function saveEditor(): Promise<void> {
  let workflowOverride: unknown = null
  let agentOverrides: unknown = {}
  try {
    if (form.workflowJson.trim()) workflowOverride = JSON.parse(form.workflowJson)
    if (form.agentsJson.trim()) agentOverrides = JSON.parse(form.agentsJson)
  } catch (e) {
    message.error('JSON 解析失败: ' + (e as Error).message)
    return
  }
  const body = {
    id: form.id.trim(),
    name: form.name.trim(),
    description: form.description,
    requestTemplate: {
      destination: form.destination.trim(),
      numberOfDays: Number(form.numberOfDays),
      budget: Number(form.budget),
      preferences: form.preferences.trim(),
    },
    workflowOverride,
    agentOverrides,
    mcpServerRefs: form.mcpServerRefs,
    artifactType: form.artifactType,
    active: form.active,
  }
  saving.value = true
  try {
    if (editingId.value) {
      await http.put(`/api/v1/scenarios/${encodeURIComponent(editingId.value)}`, body)
    } else {
      await http.post('/api/v1/scenarios', body)
    }
    message.success('已保存')
    editorOpen.value = false
    await refresh()
  } catch (e) {
    message.error('保存失败: ' + (e as Error).message)
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void refresh()
  if (!control.state) void control.refresh()
})
</script>

<template>
  <div>
    <n-card title="场景列表">
      <template #header-extra>
        <n-space>
          <n-button size="small" :loading="loading" @click="refresh">刷新</n-button>
          <n-button size="small" type="primary" @click="openEditor(null)">+ 新建场景</n-button>
        </n-space>
      </template>
      <p class="hint">
        场景 = 一套可持久化的编排逻辑组：请求模板 + 工作流覆盖 + Agent 覆盖 + MCP 映射 + 产物类型。
        空字段继承控制平面当前值；保存于 <code>cp_scenario</code> 表（db 模式）或 <code>config/scenarios.json</code>。
      </p>
      <table class="data-table-manual">
        <thead>
          <tr>
            <th>ID</th><th>名称</th><th>产物</th><th>工作流覆盖</th><th>Agent 覆盖</th>
            <th>MCP</th><th>启用</th><th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in session.scenarios" :key="s.id">
            <td class="mono">{{ s.id }}</td>
            <td>{{ s.name }}</td>
            <td><n-tag size="small">{{ s.artifactType }}</n-tag></td>
            <td>
              <n-tag v-if="s.workflowOverride" size="small" type="success">
                {{ s.workflowOverride.mode }} · {{ s.workflowOverride.stages.length }} 阶段
              </n-tag>
              <span v-else class="hint" style="margin: 0">继承</span>
            </td>
            <td class="num">{{ Object.keys(s.agentOverrides ?? {}).length || '—' }}</td>
            <td class="num">{{ (s.mcpServerRefs ?? []).length || '—' }}</td>
            <td>
              <n-tag size="small" :type="s.active ? 'success' : 'default'">{{ s.active ? '启用' : '停用' }}</n-tag>
            </td>
            <td style="white-space: nowrap">
              <n-button size="tiny" style="margin-right: 4px" @click="openDryRun(s.id)">推演</n-button>
              <n-button size="tiny" style="margin-right: 4px" @click="confirmActivate(s.id)">激活</n-button>
              <n-button size="tiny" quaternary style="margin-right: 4px" @click="openEditor(s.id)">编辑</n-button>
              <n-button size="tiny" quaternary type="error" @click="confirmDelete(s.id)">删除</n-button>
            </td>
          </tr>
          <tr v-if="!session.scenarios.length">
            <td colspan="8" class="hint">暂无场景，点击「+ 新建场景」。</td>
          </tr>
        </tbody>
      </table>
    </n-card>

    <n-card v-if="editorOpen" style="margin-top: 16px" :title="editingId ? `编辑场景：${form.name || editingId}` : '新建场景'">
      <div class="grid-2">
        <div>
          <n-form label-placement="top" size="small">
            <n-form-item label="ID（slug，创建后不可改）">
              <n-input v-model:value="form.id" :disabled="!!editingId" />
            </n-form-item>
            <n-form-item label="名称">
              <n-input v-model:value="form.name" />
            </n-form-item>
            <n-form-item label="描述">
              <n-input v-model:value="form.description" type="textarea" :rows="2" />
            </n-form-item>
            <n-form-item label="产物类型">
              <n-select v-model:value="form.artifactType" :options="artifacts.map((a) => ({ label: a, value: a }))" />
            </n-form-item>
            <n-form-item>
              <n-checkbox v-model:checked="form.active">启用该场景</n-checkbox>
            </n-form-item>
          </n-form>
        </div>
        <div>
          <h3 class="hint" style="margin-top: 0">请求模板</h3>
          <n-grid :cols="2" :x-gap="12">
            <n-form-item-gi label-placement="top" label="目的地" size="small">
              <n-input v-model:value="form.destination" />
            </n-form-item-gi>
            <n-form-item-gi label-placement="top" label="天数" size="small">
              <n-input-number v-model:value="form.numberOfDays" :min="1" style="width: 100%" />
            </n-form-item-gi>
            <n-form-item-gi label-placement="top" label="预算 (CNY)" size="small">
              <n-input-number v-model:value="form.budget" :min="1" style="width: 100%" />
            </n-form-item-gi>
            <n-form-item-gi label-placement="top" label="偏好" size="small">
              <n-input v-model:value="form.preferences" />
            </n-form-item-gi>
          </n-grid>
          <h3 class="hint">MCP 依赖（场景要求的 MCP 服务器）</h3>
          <div v-if="control.state?.mcpServers?.length">
            <n-checkbox-group v-model:value="form.mcpServerRefs">
              <n-space item-style="display:flex">
                <n-checkbox v-for="srv in control.state.mcpServers" :key="srv.name" :value="srv.name" :label="srv.name" />
              </n-space>
            </n-checkbox-group>
          </div>
          <div v-else class="hint">控制平面尚未录入 MCP 服务器。</div>
        </div>
      </div>

      <h3 class="hint" style="margin-top: 10px">工作流覆盖（JSON，留空 = 继承当前控制平面模板）</h3>
      <n-input
        v-model:value="form.workflowJson"
        type="textarea"
        :rows="6"
        placeholder='{"mode":"SEQUENTIAL","stages":[{"name":"research","agent":"research","dependsOn":[]}] }'
        class="mono"
      />
      <n-button size="small" style="margin-top: 6px" @click="copyWorkflowFromControlPlane">
        从当前控制平面模板复制
      </n-button>

      <h3 class="hint" style="margin-top: 10px">
        Agent 覆盖（JSON：agent 名 → {systemPrompt?, model?, temperature?, calibrationRules?, mcpRefs?}，留空 = 全继承）
      </h3>
      <n-input
        v-model:value="form.agentsJson"
        type="textarea"
        :rows="5"
        placeholder='{"research":{"model":"qwen:7b","temperature":0.2}}'
        class="mono"
      />

      <n-space style="margin-top: 12px">
        <n-button type="primary" :loading="saving" @click="saveEditor">
          {{ editingId ? '保存修改' : '创建场景' }}
        </n-button>
        <n-button quaternary @click="editorOpen = false">取消</n-button>
      </n-space>
    </n-card>
  </div>
</template>

<style scoped>
.data-table-manual { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table-manual th,
.data-table-manual td { padding: 7px 10px; border-bottom: 1px solid var(--border); text-align: left; }
.data-table-manual th { color: var(--muted); font-weight: 500; font-size: 12px; }
</style>
