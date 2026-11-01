<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useMessage } from 'naive-ui'
import { http } from '@/shared/api/http'
import { useControlStore } from '@/app/stores/control'
import type { ControlPlaneState, WorkflowMode } from '@/shared/types/api'

const message = useMessage()
const control = useControlStore()

/* Local editable copy: declaration order is the source of truth; a dependency
   may only reference preceding stages (validated server-side as well). */
interface EditableStage {
  name: string
  agent: string
  dependsOn: string[]
}
const stages = ref<EditableStage[]>([])
const mode = ref<WorkflowMode>('SEQUENTIAL')
const saving = ref(false)

function loadFromState(): void {
  if (!control.state) return
  mode.value = control.state.workflow.mode
  stages.value = control.state.workflow.stages.map((s) => ({
    name: s.name,
    agent: s.agent,
    dependsOn: [...s.dependsOn],
  }))
}

function addStage(): void {
  stages.value.push({
    name: `stage-${stages.value.length}`,
    agent: control.agentNames[0] ?? '',
    dependsOn: [],
  })
}

function toggleDep(stage: EditableStage, dep: string, checked: boolean): void {
  if (checked) {
    if (!stage.dependsOn.includes(dep)) stage.dependsOn.push(dep)
  } else {
    stage.dependsOn = stage.dependsOn.filter((d) => d !== dep)
  }
}

async function save(): Promise<void> {
  saving.value = true
  try {
    const st = await http.put<ControlPlaneState>('/api/v1/control/workflow', {
      mode: mode.value,
      stages: stages.value.map((s) => ({
        name: String(s.name || '').trim(),
        agent: s.agent,
        dependsOn: s.dependsOn,
      })),
    })
    control.apply(st)
    loadFromState()
    message.success(`模板已热切换，下一次请求即生效（version ${st.version}）`)
  } catch (e) {
    message.error('保存失败: ' + (e as Error).message)
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await control.refresh()
  loadFromState()
})
</script>

<template>
  <n-card title="工作流编排（模板热切换）">
    <template #header-extra>
      <n-tag size="small" type="success">version {{ control.version }}</n-tag>
    </template>
    <p class="hint">
      阶段按声明顺序执行/校验（依赖必须位于其前）；同一依赖波次内的阶段在 CONCURRENT
      模式下并行执行并合并结果。保存时后端按各 Agent 的<b>槽位契约</b>校验逻辑闭环：输入槽位必须由前置阶段/更早波次产出，或被该
      Agent 声明为 pending-safe（Agent 配置卡片中 ⟂ 标注），否则返回 400。
    </p>
    <p v-if="!control.reachable" class="error-message">控制平面 API 不可达。</p>

    <template v-else>
      <n-form label-placement="top" size="small" style="max-width: 320px">
        <n-form-item label="执行模式（CONCURRENT 时同波阶段并行执行并合并）">
          <n-select
            v-model:value="mode"
            :options="[
              { label: 'SEQUENTIAL（串行）', value: 'SEQUENTIAL' },
              { label: 'CONCURRENT（并行）', value: 'CONCURRENT' },
            ]"
          />
        </n-form-item>
      </n-form>

      <table class="data-table-manual">
        <thead>
          <tr>
            <th style="width: 48px">顺序</th>
            <th style="width: 200px">阶段名</th>
            <th style="width: 220px">Agent 映射</th>
            <th>依赖（仅限前置阶段）</th>
            <th style="width: 48px"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(s, i) in stages" :key="i">
            <td class="num">{{ i }}</td>
            <td><n-input v-model:value="s.name" size="small" /></td>
            <td>
              <n-select
                v-model:value="s.agent"
                size="small"
                :options="control.agentNames.map((n) => ({ label: n, value: n }))"
              />
            </td>
            <td>
              <n-space size="small" style="gap: 4px 14px" :wrap="true" v-if="i > 0">
                <n-checkbox
                  v-for="p in stages.slice(0, i)"
                  :key="p.name"
                  size="small"
                  :checked="s.dependsOn.includes(p.name)"
                  @update:checked="(c: boolean) => toggleDep(s, p.name, c)"
                >
                  {{ p.name }}
                </n-checkbox>
              </n-space>
              <span v-else class="hint" style="margin: 0">—</span>
            </td>
            <td>
              <n-button quaternary type="error" size="small" @click="stages.splice(i, 1)">✕</n-button>
            </td>
          </tr>
        </tbody>
      </table>

      <n-space style="margin-top: 12px">
        <n-button dashed size="small" @click="addStage">+ 添加阶段</n-button>
        <n-button type="primary" :loading="saving" @click="save">保存并热切换模板</n-button>
      </n-space>
    </template>
  </n-card>
</template>

<style scoped>
.data-table-manual { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table-manual th,
.data-table-manual td { padding: 7px 10px; border-bottom: 1px solid var(--border); text-align: left; vertical-align: middle; }
.data-table-manual th { color: var(--muted); font-weight: 500; font-size: 12px; }
</style>
