/**
 * Control-plane store — shared by the Agent / workflow / MCP pages (and the
 * scenario editor, which copies from the current template). Mirrors the
 * legacy console's cpState global: one /api/v1/control/state snapshot plus
 * the selectable model list.
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { http } from '../api/http'
import type { ControlPlaneState } from '../types/api'

export const useControlStore = defineStore('control', () => {
  const state = ref<ControlPlaneState | null>(null)
  const models = ref<string[]>([])
  const reachable = ref(true)

  async function refresh(): Promise<void> {
    try {
      state.value = await http.get<ControlPlaneState>('/api/v1/control/state')
      reachable.value = true
    } catch {
      reachable.value = false
      return
    }
    try {
      models.value = (await http.get<{ models: string[] }>('/api/v1/control/models')).models ?? []
    } catch {
      models.value = []
    }
  }

  /** Swap in the state returned by a mutating PUT (version bump + validators). */
  function apply(next: ControlPlaneState): void {
    state.value = next
  }

  const agentList = computed(() => (state.value ? Object.values(state.value.agents) : []))
  const agentNames = computed(() => (state.value ? Object.keys(state.value.agents) : []))
  const version = computed(() => (state.value ? String(state.value.version) : '—'))

  return { state, models, reachable, refresh, apply, agentList, agentNames, version }
})
