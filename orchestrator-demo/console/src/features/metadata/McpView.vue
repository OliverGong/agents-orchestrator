<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useMessage } from 'naive-ui'
import { http } from '@/shared/api/http'
import { useControlStore } from '@/app/stores/control'
import type { ControlPlaneState, McpServerConfig, McpServerStatus, McpTransport } from '@/shared/types/api'

const message = useMessage()
const control = useControlStore()
const saving = ref(false)

interface EditableServer {
  name: string
  transport: McpTransport
  endpoint: string
  command: string
  argsText: string
  envText: string
  enabled: boolean
  description: string
}

const servers = ref<EditableServer[]>([])
const statuses = ref<McpServerStatus[]>([])
let statusTimer: ReturnType<typeof setInterval> | null = null

function fromConfig(s: McpServerConfig): EditableServer {
  return {
    name: s.name,
    transport: s.transport,
    endpoint: s.endpoint ?? '',
    command: s.command ?? '',
    argsText: (s.args ?? []).join(' '),
    envText: Object.entries(s.env ?? {})
      .map(([k, v]) => `${k}=${v}`)
      .join(','),
    enabled: s.enabled,
    description: s.description ?? '',
  }
}

function toConfig(row: EditableServer): McpServerConfig {
  const env: Record<string, string> = {}
  if (row.transport === 'STDIO') {
    for (const pair of row.envText.split(',')) {
      const idx = pair.indexOf('=')
      if (idx > 0) env[pair.slice(0, idx).trim()] = pair.slice(idx + 1).trim()
    }
  }
  return {
    name: row.name.trim(),
    transport: row.transport,
    endpoint: row.transport === 'STDIO' ? '' : row.endpoint.trim(),
    command: row.transport === 'STDIO' ? row.command.trim() : '',
    args:
      row.transport === 'STDIO' && row.argsText.trim()
        ? row.argsText.trim().split(/\s+/)
        : [],
    env: row.transport === 'STDIO' ? env : {},
    enabled: row.enabled,
    description: row.description,
  }
}

function addServer(): void {
  servers.value.push({
    name: `mcp-server-${servers.value.length + 1}`,
    transport: 'STREAMABLE_HTTP',
    endpoint: 'http://localhost:3002/mcp',
    command: '',
    argsText: '',
    envText: '',
    enabled: false,
    description: '',
  })
}

async function refreshStatuses(): Promise<void> {
  try {
    statuses.value = await http.get<McpServerStatus[]>('/api/v1/control/mcp/status')
  } catch {
    /* keep the previous frame */
  }
}

function statusOf(name: string): McpServerStatus | undefined {
  return statuses.value.find((s) => s.name === name)
}

const reconnecting = reactive<Record<string, boolean>>({})
async function reconnect(name: string): Promise<void> {
  reconnecting[name] = true
  try {
    await http.post(`/api/v1/control/mcp/servers/${encodeURIComponent(name)}/reconnect`)
  } catch {
    /* the refreshed status shows the failure */
  } finally {
    reconnecting[name] = false
    await refreshStatuses()
  }
}

async function save(): Promise<void> {
  saving.value = true
  try {
    const body = servers.value.map(toConfig).filter((s) => s.name)
    const st = await http.put<ControlPlaneState>('/api/v1/control/mcp/servers', body)
    control.apply(st)
    message.success(`已保存（version ${st.version}），连接由后台自动重建`)
    servers.value = st.mcpServers.map(fromConfig)
    await refreshStatuses()
  } catch (e) {
    message.error('保存失败: ' + (e as Error).message)
  } finally {
    saving.value = false
  }
}

const reachableHint = computed(() => !control.reachable)

onMounted(async () => {
  await control.refresh()
  if (control.state) servers.value = control.state.mcpServers.map(fromConfig)
  await refreshStatuses()
  statusTimer = setInterval(refreshStatuses, 15000)
})
onBeforeUnmount(() => {
  if (statusTimer) clearInterval(statusTimer)
})
</script>

<template>
  <n-card title="MCP 服务器">
    <template #header-extra>
      <n-tag size="small" type="success">version {{ control.version }}</n-tag>
    </template>
    <p class="hint">
      连接由后端注册表动态维护：Streamable HTTP 为默认传输（SSE 已弃用、STDIO 启动本地子进程并继承当前环境变量），保存后自动按配置差异重建连接；映射到
      Agent 的服务器，其工具会注入该 Agent 的每次模型调用，调用详情（服务器/工具/耗时/结果）可在「运行回放」详情中查看。状态 15s
      自动重试，失败可手动重连。
    </p>
    <p v-if="reachableHint" class="error-message">控制平面 API 不可达。</p>

    <template v-else>
      <table class="data-table-manual">
        <thead>
          <tr>
            <th style="width: 150px">名称</th>
            <th style="width: 170px">传输</th>
            <th>端点 / 命令</th>
            <th style="width: 56px">启用</th>
            <th style="width: 180px">说明</th>
            <th style="width: 220px">连接状态</th>
            <th style="width: 44px"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, i) in servers" :key="i">
            <td><n-input v-model:value="row.name" size="small" /></td>
            <td>
              <n-select
                v-model:value="row.transport"
                size="small"
                :options="[
                  { label: 'Streamable HTTP', value: 'STREAMABLE_HTTP' },
                  { label: 'SSE（弃用）', value: 'SSE' },
                  { label: 'STDIO', value: 'STDIO' },
                ]"
              />
            </td>
            <td>
              <template v-if="row.transport === 'STDIO'">
                <n-input v-model:value="row.command" size="small" placeholder="npx" style="margin-bottom: 4px" />
                <n-input v-model:value="row.argsText" size="small" placeholder="参数（空格分隔）" style="margin-bottom: 4px" />
                <n-input v-model:value="row.envText" size="small" placeholder="KEY=VAL,KEY=VAL（可空）" />
              </template>
              <n-input v-else v-model:value="row.endpoint" size="small" placeholder="http://localhost:3002/mcp" />
            </td>
            <td style="text-align: center">
              <n-checkbox v-model:checked="row.enabled" size="small" />
            </td>
            <td><n-input v-model:value="row.description" size="small" /></td>
            <td>
              <template v-if="statusOf(row.name)">
                <n-space size="small" align="center" :wrap="true">
                  <n-tag
                    size="small"
                    :type="statusOf(row.name)!.state === 'CONNECTED' ? 'success' : statusOf(row.name)!.state === 'FAILED' ? 'error' : 'info'"
                  >
                    {{ statusOf(row.name)!.state }}
                  </n-tag>
                  <span
                    v-if="statusOf(row.name)!.state === 'CONNECTED' && statusOf(row.name)!.toolNames.length"
                    class="hint"
                    style="margin: 0"
                    :title="statusOf(row.name)!.toolNames.join(', ')"
                  >
                    工具 ×{{ statusOf(row.name)!.toolNames.length }}
                  </span>
                  <span v-if="statusOf(row.name)!.error" class="hint" style="margin: 0; color: var(--err)">
                    {{ statusOf(row.name)!.error }}
                  </span>
                  <n-button
                    v-if="statusOf(row.name)!.enabled"
                    size="tiny"
                    :loading="!!reconnecting[row.name]"
                    @click="reconnect(row.name)"
                  >
                    重连
                  </n-button>
                </n-space>
              </template>
              <span v-else class="hint" style="margin: 0">…</span>
            </td>
            <td>
              <n-button quaternary type="error" size="small" @click="servers.splice(i, 1)">✕</n-button>
            </td>
          </tr>
          <tr v-if="!servers.length">
            <td colspan="7" class="hint">尚未录入 MCP 服务器。</td>
          </tr>
        </tbody>
      </table>

      <n-space style="margin-top: 12px">
        <n-button dashed size="small" @click="addServer">+ 添加服务器</n-button>
        <n-button type="primary" :loading="saving" @click="save">保存 MCP 配置</n-button>
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
