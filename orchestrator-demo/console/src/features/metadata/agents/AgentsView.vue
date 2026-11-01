<script setup lang="ts">
import { onMounted } from 'vue'
import { useMessage } from 'naive-ui'
import { http } from '@/shared/api/http'
import { useControlStore } from '@/app/stores/control'
import type { AgentConfig, AgentDescriptor, ControlPlaneState } from '@/shared/types/api'

const message = useMessage()
const control = useControlStore()

/* Slot contract of an agent, seeded from its self-declared descriptor:
   output slots are written to the blackboard, input slots are read when the
   stage starts, and pending-safe inputs may be absent (that is what
   legalizes same-wave parallel consumption in the workflow editor). */
function descriptor(name: string): AgentDescriptor | undefined {
  return control.state?.descriptors?.[name]
}

function pendingSafe(d: AgentDescriptor, slot: string): boolean {
  return d.pendingSafeSlots.includes(slot)
}

const modelOptions = () => control.models.map((m) => ({ label: m, value: m }))

function addRule(a: AgentConfig): void {
  a.calibrationRules.push({ pattern: '', replacement: '', description: '' })
}

async function saveAgent(a: AgentConfig): Promise<void> {
  const body = {
    name: a.name,
    systemPrompt: a.systemPrompt,
    model: a.model || null,
    temperature: a.temperature ?? null,
    calibrationRules: a.calibrationRules
      .filter((r) => r.pattern.trim())
      .map((r) => ({ pattern: r.pattern, replacement: r.replacement, description: r.description || '' })),
    mcpRefs: a.mcpRefs,
  }
  try {
    const st = await http.put<ControlPlaneState>(
      `/api/v1/control/agents/${encodeURIComponent(a.name)}`,
      body,
    )
    control.apply(st)
    message.success(`${a.name} 已保存并热生效（version ${st.version}）`)
  } catch (e) {
    message.error('保存失败: ' + (e as Error).message)
  }
}

onMounted(() => {
  void control.refresh()
})
</script>

<template>
  <div>
    <n-card title="控制平面状态">
      <template #header-extra>
        <n-space>
          <n-tag size="small" type="success">version {{ control.version }}</n-tag>
          <n-button size="small" :loading="false" @click="control.refresh()">刷新</n-button>
        </n-space>
      </template>
      <p class="hint">
        Agent 的 system prompt / 模型 / 温度 / 校准规则均可在运行时编辑并<b>立即热生效</b>（逐次调用读取）。
      </p>
      <p v-if="!control.reachable" class="error-message">控制平面 API 不可达。</p>
    </n-card>

    <div class="grid-2" style="margin-top: 16px">
      <n-card v-for="a in control.agentList" :key="a.name" :title="`Agent: ${a.name}`">
        <template #header-extra>
          <n-tag size="small">{{ a.mcpRefs.length ? `MCP ×${a.mcpRefs.length}` : '无 MCP 映射' }}</n-tag>
        </template>

        <div v-if="descriptor(a.name)" class="slot-contract">
          <span class="slot-label">输出</span>
          <template v-if="descriptor(a.name)!.outputSlots.length">
            <span v-for="s in descriptor(a.name)!.outputSlots" :key="s" class="slot-chip out">{{ s }}</span>
          </template>
          <span v-else class="hint" style="margin: 0">—</span>
          <span class="slot-label">输入</span>
          <template v-if="descriptor(a.name)!.inputSlots.length">
            <span
              v-for="s in descriptor(a.name)!.inputSlots"
              :key="s"
              class="slot-chip"
              :class="pendingSafe(descriptor(a.name)!, s) ? 'pending' : 'in'"
              :title="pendingSafe(descriptor(a.name)!, s)
                ? 'pending-safe：该输入缺失时 agent 显式声明等待而非失败，因此允许同波并行消费'
                : undefined"
            >
              {{ pendingSafe(descriptor(a.name)!, s) ? `${s} ⟂` : s }}
            </span>
          </template>
          <span v-else class="hint" style="margin: 0">—</span>
          <template v-if="descriptor(a.name)!.defaultModel">
            <span class="slot-label">默认模型</span>
            <span class="slot-chip model">{{ descriptor(a.name)!.defaultModel }}</span>
          </template>
        </div>

        <n-form label-placement="top" size="small">
          <n-form-item label="System Prompt（每次模型调用即时读取）">
            <n-input v-model:value="a.systemPrompt" type="textarea" :rows="8" class="mono" />
          </n-form-item>
          <n-grid :cols="2" :x-gap="12">
            <n-form-item-gi label="模型（留空继承后端默认）">
              <n-select
                v-model:value="a.model"
                :options="modelOptions()"
                filterable
                tag
                clearable
                placeholder="如 qwen:7b"
              />
            </n-form-item-gi>
            <n-form-item-gi label="温度">
              <n-input-number
                :value="a.temperature"
                :step="0.1"
                :min="0"
                :max="2"
                clearable
                placeholder="默认"
                style="width: 100%"
                @update:value="(v: number | null) => (a.temperature = v)"
              />
            </n-form-item-gi>
          </n-grid>

          <p class="hint">响应校准规则（调用后按序执行：正则 → 替换，可用 $1 引用分组）</p>
          <div v-if="a.calibrationRules.length">
            <n-grid
              v-for="(r, i) in a.calibrationRules"
              :key="i"
              :cols="16"
              :x-gap="8"
              style="margin-bottom: 6px"
            >
              <n-form-item-gi :span="7" :show-feedback="false">
                <n-input v-model:value="r.pattern" placeholder="正则 pattern" />
              </n-form-item-gi>
              <n-form-item-gi :span="7" :show-feedback="false">
                <n-input v-model:value="r.replacement" placeholder="替换为（可空=删除匹配）" />
              </n-form-item-gi>
              <n-form-item-gi :span="2" :show-feedback="false">
                <n-button quaternary type="error" size="small" @click="a.calibrationRules.splice(i, 1)">✕</n-button>
              </n-form-item-gi>
            </n-grid>
          </div>
          <div v-else class="hint">无规则</div>
          <n-button size="small" dashed style="margin-top: 4px" @click="addRule(a)">+ 添加规则</n-button>

          <p class="hint" style="margin-top: 12px">MCP 服务器映射</p>
          <n-checkbox-group v-model:value="a.mcpRefs">
            <n-space v-if="control.state?.mcpServers?.length" item-style="display:flex">
              <n-checkbox
                v-for="s in control.state.mcpServers"
                :key="s.name"
                :value="s.name"
                :label="`${s.name}（${s.transport}${s.enabled ? '' : '（停用）'}）`"
              />
            </n-space>
            <div v-else class="hint">尚未录入 MCP 服务器。</div>
          </n-checkbox-group>

          <n-button type="primary" style="margin-top: 14px" @click="saveAgent(a)">保存 agent 配置</n-button>
        </n-form>
      </n-card>
    </div>
  </div>
</template>
