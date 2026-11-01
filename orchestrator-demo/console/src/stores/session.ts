/**
 * Session store — the SPA-wide context that closes the lifecycle chain
 * (推演 → 激活·运行 → 回放 → 场景指标), mirroring the legacy console globals:
 * the scenario carried across page switches, pending cross-page filters and
 * the ambient backend facts (scenarios, active scenario, health, version).
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { http } from '../api/http'
import type { ActuatorInfo, HealthResponse, ScenarioSummary, ScenariosState } from '../types/api'

export type HealthState = 'UP' | 'DOWN' | 'UNKNOWN'

export const useSessionStore = defineStore('session', () => {
  /* ---- lifecycle-chain context ---- */
  /** Scenario carried across page switches (null = 控制平面默认). */
  const ctxScenarioId = ref<string | null>(null)
  /** Pending businessId filter, consumed once by the instances view. */
  const pendingBusinessFilter = ref('')
  /** Pending LM-metrics scenario filter, consumed once by the metrics view. */
  const pendingMetricsScenario = ref('')

  /* ---- ambient backend facts ---- */
  const scenarios = ref<ScenarioSummary[]>([])
  const activeScenarioId = ref<string | null>(null)
  const healthStatus = ref<HealthState>('UNKNOWN')
  const backendAgents = ref<string[]>([])
  const appVersion = ref('')

  const activeScenario = computed(
    () => scenarios.value.find((s) => s.id === activeScenarioId.value) ?? null,
  )

  function scenarioById(id: string | null | undefined): ScenarioSummary | null {
    if (!id) return null
    return scenarios.value.find((s) => s.id === id) ?? null
  }

  async function refreshScenarios(): Promise<void> {
    try {
      const state = await http.get<ScenariosState>('/api/v1/scenarios')
      scenarios.value = state.scenarios || []
      activeScenarioId.value = state.activeScenarioId
    } catch {
      /* keep the previous frame on backend hiccups */
    }
  }

  async function refreshHealth(): Promise<HealthResponse | null> {
    try {
      const health = await http.get<HealthResponse>('/actuator/health')
      healthStatus.value = health.status === 'UP' ? 'UP' : 'DOWN'
      backendAgents.value =
        health.components?.orchestrator?.details?.agents ?? []
      return health
    } catch {
      healthStatus.value = 'UNKNOWN'
      backendAgents.value = []
      return null
    }
  }

  async function fetchVersion(): Promise<void> {
    try {
      const info = await http.get<ActuatorInfo>('/actuator/info')
      if (info.build?.version) appVersion.value = `v${info.build.version}`
    } catch {
      /* keep the placeholder on old images */
    }
  }

  /** Initial boot: everything the shell and views need before first paint. */
  async function bootstrap(): Promise<void> {
    await Promise.all([refreshScenarios(), refreshHealth(), fetchVersion()])
  }

  return {
    ctxScenarioId,
    pendingBusinessFilter,
    pendingMetricsScenario,
    scenarios,
    activeScenarioId,
    activeScenario,
    scenarioById,
    healthStatus,
    backendAgents,
    appVersion,
    refreshScenarios,
    refreshHealth,
    bootstrap,
  }
})
