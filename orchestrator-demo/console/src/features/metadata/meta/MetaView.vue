<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useDialog, useMessage } from 'naive-ui'
import { http } from '@/shared/api/http'
import { useSessionStore } from '@/app/stores/session'
import { fmtDateTime } from '@/shared/utils/format'
import type { ArtifactType, MetaState, PortalConfig, ReleaseRecord } from '@/shared/types/api'

const message = useMessage()
const dialog = useDialog()
const session = useSessionStore()

const meta = ref<MetaState | null>(null)
const loading = ref(false)
const artifactTypes: ArtifactType[] = ['HTML', 'SPRING_BOOT_PROJECT', 'JAR', 'AUDIO']
const portalKinds = ['WEB_PORTAL', 'STATIC_SITE', 'DOWNLOAD', 'CUSTOM']

async function refresh(): Promise<void> {
  loading.value = true
  try {
    meta.value = await http.get<MetaState>('/api/v1/meta')
  } catch {
    message.error('元信息 API 不可达')
  } finally {
    loading.value = false
  }
  if (!session.scenarios.length) await session.refreshScenarios()
}

const versionText = computed(() => (meta.value ? `version ${meta.value.version}` : 'version —'))
const updatedText = computed(() =>
  meta.value?.updatedAt ? `更新于 ${fmtDateTime(meta.value.updatedAt)}` : '',
)

/* ---------------- runtime form ---------------- */
const runtime = reactive({
  modelBackend: '',
  requestTimeoutSeconds: null as number | null,
  maxWaveConcurrency: null as number | null,
  temperature: null as number | null,
  topP: null as number | null,
})
const savingRuntime = ref(false)

function loadRuntime(): void {
  const rt = meta.value?.runtime
  runtime.modelBackend = rt?.modelBackend ?? ''
  runtime.requestTimeoutSeconds = rt?.requestTimeoutSeconds ?? null
  runtime.maxWaveConcurrency = rt?.maxWaveConcurrency ?? null
  runtime.temperature = rt?.temperature ?? null
  runtime.topP = rt?.topP ?? null
}

async function saveRuntime(): Promise<void> {
  savingRuntime.value = true
  try {
    // Blank inputs stay null = inherit the agent / backend defaults.
    meta.value = await http.put<MetaState>('/api/v1/meta/runtime', {
      modelBackend: runtime.modelBackend.trim() || null,
      requestTimeoutSeconds: runtime.requestTimeoutSeconds,
      maxWaveConcurrency: runtime.maxWaveConcurrency,
      temperature: runtime.temperature,
      topP: runtime.topP,
    })
    loadRuntime()
    message.success('已保存')
  } catch (e) {
    message.error('保存失败: ' + (e as Error).message)
  } finally {
    savingRuntime.value = false
  }
}

/* ---------------- portals ---------------- */
const portalForm = reactive({ id: '', name: '', kind: 'WEB_PORTAL', endpoint: '', enabled: true })
const savingPortal = ref(false)

async function savePortal(): Promise<void> {
  savingPortal.value = true
  try {
    meta.value = await http.post<MetaState>('/api/v1/meta/portals', {
      id: portalForm.id.trim(),
      name: portalForm.name.trim() || portalForm.id.trim(),
      kind: portalForm.kind,
      endpoint: portalForm.endpoint.trim(),
      enabled: portalForm.enabled,
    })
    portalForm.id = ''
    portalForm.name = ''
    portalForm.endpoint = ''
    message.success('已保存')
  } catch (e) {
    message.error('保存失败: ' + (e as Error).message)
  } finally {
    savingPortal.value = false
  }
}

function confirmDeletePortal(id: string): void {
  dialog.warning({
    title: '删除门户',
    content: `删除门户 ${id}？`,
    positiveText: '删除',
    negativeText: '取消',
    async onPositiveClick() {
      try {
        meta.value = await http.del<MetaState>(`/api/v1/meta/portals/${encodeURIComponent(id)}`)
        message.success('已删除')
      } catch (e) {
        message.error('删除失败: ' + (e as Error).message)
      }
    },
  })
}

/* ---------------- per-scenario release config ---------------- */
const releaseForm = reactive({
  scenarioId: null as string | null,
  portalId: null as string | null,
  version: '',
  artifactType: 'HTML' as ArtifactType,
  changelog: '',
})
const savingRelease = ref(false)

const scenarioOptions = computed(() =>
  session.scenarios.map((s) => ({ label: `${s.name}（${s.id}）`, value: s.id })),
)
const enabledPortalOptions = computed(() =>
  (meta.value?.portals ?? [])
    .filter((p) => p.enabled)
    .map((p) => ({ label: `${p.name}（${p.id}）`, value: p.id })),
)

async function saveRelease(): Promise<void> {
  if (!releaseForm.scenarioId) {
    message.warning('尚无场景，请先在「场景配置」创建')
    return
  }
  savingRelease.value = true
  try {
    meta.value = await http.put<MetaState>(
      `/api/v1/meta/releases/${encodeURIComponent(releaseForm.scenarioId)}`,
      {
        portalId: releaseForm.portalId || null,
        version: releaseForm.version.trim(),
        changelog: releaseForm.changelog.trim(),
        artifactType: releaseForm.artifactType,
      },
    )
    message.success('已保存')
    await refresh()
  } catch (e) {
    message.error('保存失败: ' + (e as Error).message)
  } finally {
    savingRelease.value = false
  }
}

function confirmDeleteRelease(scenarioId: string): void {
  dialog.warning({
    title: '删除发布配置',
    content: `删除场景 ${scenarioId} 的发布配置？`,
    positiveText: '删除',
    negativeText: '取消',
    async onPositiveClick() {
      try {
        meta.value = await http.del<MetaState>(`/api/v1/meta/releases/${encodeURIComponent(scenarioId)}`)
        message.success('已删除')
      } catch (e) {
        message.error('删除失败: ' + (e as Error).message)
      }
    },
  })
}

/* ---------------- publish + registry ---------------- */
function publish(scenarioId: string): void {
  dialog.info({
    title: '发布场景',
    content: `发布场景 ${scenarioId} 到其配置的门户？`,
    positiveText: '发布',
    negativeText: '取消',
    async onPositiveClick() {
      try {
        const record = await http.post<ReleaseRecord>(
          `/api/v1/releases/${encodeURIComponent(scenarioId)}/publish`,
        )
        message.success(`已发布：注册表记录 #${record.id}（版本 ${record.releaseVersion ?? '—'}）`)
        await refresh()
      } catch (e) {
        message.error('发布失败: ' + (e as Error).message)
      }
    },
  })
}

const registry = ref<ReleaseRecord[]>([])

async function refreshRegistry(): Promise<void> {
  try {
    registry.value = (await http.get<ReleaseRecord[]>('/api/v1/releases')) ?? []
  } catch {
    registry.value = []
  }
}

function portalName(portalId: string | null | undefined): string {
  if (!portalId) return '—'
  const p: PortalConfig | undefined = meta.value?.portals.find((x) => x.id === portalId)
  return p ? `${p.name}（${p.id}）` : portalId
}

onMounted(async () => {
  await refresh()
  loadRuntime()
  await refreshRegistry()
})
</script>

<template>
  <div>
    <n-card title="元信息补录">
      <template #header-extra>
        <n-space align="center">
          <n-tag size="small" type="success">{{ versionText }}</n-tag>
          <span class="hint" style="margin: 0">{{ updatedText }}</span>
          <n-button size="small" :loading="loading" @click="refresh">刷新</n-button>
        </n-space>
      </template>
      <p class="hint">
        控制层面之外的元信息维护：运行时团队维护的全局<b>运行时配置</b>（留空 = 继承各 Agent / 后端默认值）、<b>发布门户</b>注册表与每个场景的<b>发布配置</b>。发布时由发布管道按门户整合场景产物并发布到对应门户。
      </p>
    </n-card>

    <n-card title="运行时配置（运行时团队维护）" style="margin-top: 16px">
      <p class="hint">
        全局运行时补录：默认模型后端、请求超时、波次并发上限与采样默认值。所有字段均可留空 = 继承既有默认。
      </p>
      <n-form label-placement="top" size="small">
        <n-grid :cols="4" :x-gap="12">
          <n-form-item-gi label="默认模型后端">
            <n-input v-model:value="runtime.modelBackend" placeholder="如 qwen3:8b（空 = 后端默认）" />
          </n-form-item-gi>
          <n-form-item-gi label="请求超时 (秒)">
            <n-input-number v-model:value="runtime.requestTimeoutSeconds" :min="1" clearable placeholder="空 = 默认" style="width: 100%" />
          </n-form-item-gi>
          <n-form-item-gi label="最大波次并发">
            <n-input-number v-model:value="runtime.maxWaveConcurrency" :min="1" clearable placeholder="空 = 默认" style="width: 100%" />
          </n-form-item-gi>
          <n-form-item-gi label="采样温度 / top-P">
            <n-space size="small">
              <n-input-number v-model:value="runtime.temperature" :min="0" :max="2" :step="0.05" clearable placeholder="温度" style="width: 130px" />
              <n-input-number v-model:value="runtime.topP" :min="0" :max="1" :step="0.05" clearable placeholder="topP" style="width: 130px" />
            </n-space>
          </n-form-item-gi>
        </n-grid>
        <n-button type="primary" :loading="savingRuntime" @click="saveRuntime">保存运行时配置</n-button>
      </n-form>
    </n-card>

    <div class="grid-2" style="margin-top: 16px">
      <n-card title="发布门户">
        <p class="hint">发布目标注册表：同 ID 保存即覆盖；停用的门户不可作为发布目标。</p>
        <table class="data-table-manual">
          <thead>
            <tr><th>ID</th><th>名称</th><th>类型</th><th>端点</th><th>启用</th><th></th></tr>
          </thead>
          <tbody>
            <tr v-for="p in meta?.portals ?? []" :key="p.id">
              <td class="mono">{{ p.id }}</td>
              <td>{{ p.name }}</td>
              <td><n-tag size="small">{{ p.kind }}</n-tag></td>
              <td class="mono">{{ p.endpoint || '—' }}</td>
              <td><n-tag size="small" :type="p.enabled ? 'success' : 'error'">{{ p.enabled ? '启用' : '停用' }}</n-tag></td>
              <td><n-button size="tiny" quaternary type="error" @click="confirmDeletePortal(p.id)">删除</n-button></td>
            </tr>
            <tr v-if="!meta?.portals?.length"><td colspan="6" class="hint">尚未注册发布门户。</td></tr>
          </tbody>
        </table>
        <h3 class="hint" style="margin-top: 12px">新增 / 更新门户</h3>
        <n-form label-placement="top" size="small">
          <n-grid :cols="2" :x-gap="12">
            <n-form-item-gi label="ID（slug）">
              <n-input v-model:value="portalForm.id" placeholder="如 team-portal" />
            </n-form-item-gi>
            <n-form-item-gi label="名称">
              <n-input v-model:value="portalForm.name" placeholder="团队门户" />
            </n-form-item-gi>
            <n-form-item-gi label="类型">
              <n-select v-model:value="portalForm.kind" :options="portalKinds.map((k) => ({ label: k, value: k }))" />
            </n-form-item-gi>
            <n-form-item-gi label="端点 URL">
              <n-input v-model:value="portalForm.endpoint" placeholder="https://portal.example.com/api/publish" />
            </n-form-item-gi>
          </n-grid>
          <n-checkbox v-model:checked="portalForm.enabled">启用该门户</n-checkbox>
          <div style="margin-top: 10px">
            <n-button type="primary" :loading="savingPortal" @click="savePortal">保存门户</n-button>
          </div>
        </n-form>
      </n-card>

      <n-card title="场景发布配置">
        <p class="hint">发布阶段的按场景补录：目标门户、版本号、变更说明与产物类型；每场景一条，同场景保存即覆盖。</p>
        <table class="data-table-manual">
          <thead>
            <tr><th>场景</th><th>目标门户</th><th>版本</th><th>产物</th><th>变更说明</th><th></th></tr>
          </thead>
          <tbody>
            <tr v-for="r in meta?.releases ?? []" :key="r.scenarioId">
              <td class="mono">{{ session.scenarioById(r.scenarioId)?.name ?? r.scenarioId }}</td>
              <td>{{ portalName(r.portalId) }}</td>
              <td class="mono">{{ r.version || '—' }}</td>
              <td><n-tag size="small">{{ r.artifactType || '—' }}</n-tag></td>
              <td>{{ r.changelog || '—' }}</td>
              <td style="white-space: nowrap">
                <n-button size="tiny" style="margin-right: 4px" @click="publish(r.scenarioId)">发布</n-button>
                <n-button size="tiny" quaternary type="error" @click="confirmDeleteRelease(r.scenarioId)">删除</n-button>
              </td>
            </tr>
            <tr v-if="!meta?.releases?.length"><td colspan="6" class="hint">尚无场景发布配置。</td></tr>
          </tbody>
        </table>
        <h3 class="hint" style="margin-top: 12px">新增 / 更新场景发布配置</h3>
        <n-form label-placement="top" size="small">
          <n-grid :cols="2" :x-gap="12">
            <n-form-item-gi label="场景">
              <n-select v-model:value="releaseForm.scenarioId" :options="scenarioOptions" clearable />
            </n-form-item-gi>
            <n-form-item-gi label="目标门户">
              <n-select v-model:value="releaseForm.portalId" :options="enabledPortalOptions" clearable />
            </n-form-item-gi>
            <n-form-item-gi label="版本">
              <n-input v-model:value="releaseForm.version" placeholder="如 v1.2.0" />
            </n-form-item-gi>
            <n-form-item-gi label="产物类型">
              <n-select v-model:value="releaseForm.artifactType" :options="artifactTypes.map((a) => ({ label: a, value: a }))" />
            </n-form-item-gi>
          </n-grid>
          <n-form-item label="变更说明（changelog）">
            <n-input v-model:value="releaseForm.changelog" type="textarea" :rows="2" />
          </n-form-item>
          <n-button type="primary" :loading="savingRelease" @click="saveRelease">保存发布配置</n-button>
        </n-form>
      </n-card>
    </div>

    <n-card title="发布注册表" style="margin-top: 16px">
      <template #header-extra>
        <n-button size="small" @click="refreshRegistry">刷新</n-button>
      </template>
      <p class="hint">
        每次「发布」将场景快照 + dry-run 证据 + 门户/版本元数据打包为可移植 JSON（SHA-256
        哈希）追加到持久注册表（<code>rl_release</code> 表 / <code>config/release-registry.json</code>），并写团队日志审计。包内容可随时重新导出。
      </p>
      <table class="data-table-manual">
        <thead>
          <tr><th>ID</th><th>场景</th><th>门户</th><th>版本</th><th>产物</th><th>发布时间</th><th>包哈希</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="r in registry" :key="r.id">
            <td class="mono">{{ r.id }}</td>
            <td class="mono">{{ r.scenarioId }}</td>
            <td>{{ portalName(r.portalId) }}</td>
            <td class="mono">{{ r.releaseVersion || '—' }}</td>
            <td><n-tag size="small">{{ r.artifactType || '—' }}</n-tag></td>
            <td>{{ fmtDateTime(r.publishedAt) }}</td>
            <td class="mono" :title="r.packageHash || ''">{{ (r.packageHash || '—').slice(0, 12) }}…</td>
            <td><a class="export-link" :href="`/api/v1/releases/${r.id}/package`" download>导出</a></td>
          </tr>
          <tr v-if="!registry.length"><td colspan="8" class="hint">尚无发布记录。</td></tr>
        </tbody>
      </table>
    </n-card>
  </div>
</template>

<style scoped>
.data-table-manual { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table-manual th,
.data-table-manual td { padding: 7px 10px; border-bottom: 1px solid var(--border); text-align: left; }
.data-table-manual th { color: var(--muted); font-weight: 500; font-size: 12px; }
.export-link { color: var(--accent); font-size: 12px; text-decoration: none; }
.export-link:hover { text-decoration: underline; }
</style>
