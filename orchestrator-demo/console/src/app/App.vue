<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { darkTheme, type GlobalThemeOverrides } from 'naive-ui'
import { useSessionStore } from '@/app/stores/session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

/* Left sidebar, one group per domain (1:1 with the legacy console). */
const menuOptions = [
  {
    type: 'group' as const,
    label: '场景',
    key: 'g-scene',
    children: [
      { label: '场景运行', key: 'run' },
      { label: '场景推演', key: 'dryrun' },
      { label: '场景配置', key: 'scenarios' },
    ],
  },
  {
    type: 'group' as const,
    label: '元数据',
    key: 'g-metadata',
    children: [
      { label: 'Agent 配置', key: 'agents' },
      { label: '工作流编排', key: 'workflow' },
      { label: 'MCP 服务器', key: 'mcp' },
      { label: '元信息补录', key: 'meta' },
    ],
  },
  {
    type: 'group' as const,
    label: '可观测',
    key: 'g-observability',
    children: [
      { label: '实例追踪', key: 'instances' },
      { label: '运行回放', key: 'runs' },
      { label: '团队日志', key: 'logs' },
      { label: '系统观测', key: 'metrics' },
    ],
  },
]

const activeMenu = computed(() => (route.name as string) || 'run')
function onMenuSelect(key: string) {
  void router.push({ name: key })
}

/* Lifecycle chain: dry-run → activate+run → replay → per-scenario metrics. */
const chain = [
  { name: 'dryrun', label: '1 推演' },
  { name: 'run', label: '2 激活 · 运行' },
  { name: 'runs', label: '3 回放' },
  { name: 'metrics', label: '4 场景指标' },
]
function goChain(name: string) {
  if (name === 'metrics' && session.ctxScenarioId) {
    void router.push({ name, query: { scenarioId: session.ctxScenarioId } })
  } else {
    void router.push({ name })
  }
}

/* Observability stack links: rewrite the fixed ports to the current host so
   they stay usable when the console is opened from a remote machine. */
const obsHost = window.location.hostname || 'localhost'
const obsLinks = [
  { label: 'Grafana', port: 3000 },
  { label: 'Tempo', port: 3200 },
  { label: 'Prometheus', port: 9090 },
].map((l) => ({ ...l, href: `http://${obsHost}:${l.port}` }))

const healthTitle = computed(() => `应用健康状态: ${
  session.healthStatus === 'UP' ? 'UP' : session.healthStatus === 'DOWN' ? 'DOWN' : '不可达'
}`)

/* Naive UI dark theme tuned to the legacy console palette. */
const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#58a6ff',
    primaryColorHover: '#79b8ff',
    primaryColorPressed: '#388bfd',
    primaryColorSuppl: '#58a6ff',
    successColor: '#3fb950',
    warningColor: '#d29922',
    errorColor: '#f85149',
    bodyColor: '#0d1117',
    cardColor: '#161b22',
    modalColor: '#161b22',
    popoverColor: '#1c2333',
    tableColor: '#161b22',
    inputColor: '#0d1117',
    borderColor: '#2d333b',
    dividerColor: '#2d333b',
  },
}

onMounted(() => {
  void session.bootstrap()
})
</script>

<template>
  <n-config-provider :theme="darkTheme" :theme-overrides="themeOverrides" style="height: 100%">
    <n-message-provider>
      <n-dialog-provider>
        <div class="console-shell">
          <aside class="sidebar">
            <div class="side-brand">
              <h1>Orchestrator</h1>
              <span class="subtitle">多 Agent 编排控制台 · {{ session.appVersion || 'v?' }}</span>
            </div>
            <nav class="side-nav">
              <n-menu :value="activeMenu" :options="menuOptions" @update:value="onMenuSelect" />
            </nav>
            <div class="side-footer">
              <span class="dot" :class="`dot-${session.healthStatus.toLowerCase()}`" :title="healthTitle" />
              <nav class="links">
                <a v-for="l in obsLinks" :key="l.label" :href="l.href" target="_blank">{{ l.label }}</a>
              </nav>
            </div>
          </aside>

          <div class="console-content">
            <main class="console-main">
              <div class="ctx-bar">
                <span class="ctx-label">当前上下文</span>
                <n-tag size="small" :type="session.activeScenario ? 'success' : 'default'" :bordered="true">
                  {{ session.activeScenario ? `${session.activeScenario.name}（${session.activeScenario.id}）` : '控制平面默认' }}
                </n-tag>
                <span class="ctx-chain">
                  <template v-for="(step, i) in chain" :key="step.name">
                    <n-button
                      size="tiny"
                      :quaternary="activeMenu !== step.name"
                      :type="activeMenu === step.name ? 'primary' : 'default'"
                      @click="goChain(step.name)"
                    >
                      {{ step.label }}
                    </n-button>
                    <span v-if="i < chain.length - 1" class="ctx-arrow">→</span>
                  </template>
                </span>
              </div>

              <router-view />
            </main>
            <footer class="console-footer">
              Spring Boot 4 · Spring AI 2.0 · Micrometer/OTel — 控制台为独立 Vue 3 SPA（console/），经反代与 REST API 同源直连。
            </footer>
          </div>
        </div>
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>
