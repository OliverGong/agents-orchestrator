<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import type { RunWave } from '../types/api'

/** Agent swimlane (ported from the legacy console): one lane per agent,
    bars per stage on a time axis when per-stage completion timestamps exist,
    sequential layout otherwise. ⚠ = parse retry, tick = MCP tool call. */
const props = defineProps<{ waves: RunWave[] }>()

const STATUS_FILL: Record<string, string> = { ok: '#234d2c', fail: '#5c2320' }
const STATUS_STROKE: Record<string, string> = { ok: '#3fb950', fail: '#f85149' }

const host = ref<HTMLElement | null>(null)
const hostWidth = ref(900)
let resizeObserver: ResizeObserver | null = null
onMounted(() => {
  if (!host.value) return
  hostWidth.value = host.value.clientWidth || 900
  resizeObserver = new ResizeObserver((entries) => {
    for (const entry of entries) {
      if (entry.contentRect.width) hostWidth.value = entry.contentRect.width
    }
  })
  resizeObserver.observe(host.value)
})
onBeforeUnmount(() => resizeObserver?.disconnect())

const layout = computed(() => {
  const stages = props.waves.flatMap((w) => w.stages.map((s) => ({ ...s, wave: w.waveIndex })))
  if (!stages.length) return null
  const lanes = [...new Set(stages.map((s) => s.agentName))]
  const timed = stages.every((s) => s.completedAt && s.durationMs != null)
  const rowH = 40
  const laneGap = 10
  const padL = 110
  const padR = 30
  const padT = 26
  const padB = 30
  const chartW = Math.max(560, hostWidth.value) - padL - padR

  const timedStages = stages.map((s) => {
    const end = timed ? Date.parse(s.completedAt!) : 0
    return { ...s, _start: timed ? end - (s.durationMs ?? 0) : 0, _end: timed ? end : 0 }
  })
  if (!timed) {
    let cursor = 0
    for (const s of timedStages) {
      s._start = cursor
      s._end = cursor + (s.durationMs || 1000)
      cursor = s._end
    }
  }
  const tMin = Math.min(...timedStages.map((s) => s._start))
  const tMax = Math.max(...timedStages.map((s) => s._end))
  const span = Math.max(1, tMax - tMin)
  const x = (t: number) => padL + ((t - tMin) / span) * chartW
  const laneY = (agent: string) => padT + lanes.indexOf(agent) * (rowH + laneGap)
  const width = padL + chartW + padR
  const height = padT + lanes.length * (rowH + laneGap) + padB
  const fmtAxis = (t: number) =>
    timed
      ? new Date(t).toLocaleTimeString('zh-CN', { hour12: false })
      : `${(t / 1000).toFixed(1)}s`

  const ticks = Array.from({ length: 6 }, (_, i) => {
    const t = tMin + (span * i) / 5
    return { t, px: x(t), label: fmtAxis(t) }
  })

  const bars = timedStages.map((s, idx) => {
    const y = laneY(s.agentName) + 6
    const h = rowH - 12
    const status = s.outcome === 'STAGE_COMPLETED' ? 'ok' : 'fail'
    const bx = x(s._start)
    const bw = Math.max(6, x(s._end) - x(s._start))
    const label = `${s.stageName}${s.durationMs != null ? ` · ${s.durationMs}ms` : ''}`
    const retryMarks = (s.retries ?? [])
      .filter((r) => {
        if (!r.timestamp || timed === false) return false
        const rt = Date.parse(r.timestamp)
        return rt >= s._start && rt <= s._end
      })
      .map((r) => ({ key: `r${idx}-${r.attempt}`, px: x(Date.parse(r.timestamp!)) - 4 }))
    const toolTicks = (s.toolCalls ?? []).map((_, i) => ({
      key: `t${idx}-${i}`,
      px: bx + 6 + (i + 0.5) * Math.max(1, (bw - 12) / Math.max(1, s.toolCalls!.length)),
    }))
    return { key: `b${idx}`, y, h, status, bx, bw, label, showLabel: bw > 70, retryMarks, toolTicks }
  })

  return {
    width,
    height,
    lanes,
    laneY,
    ticks,
    bars,
    rowH,
    laneGap,
    padL,
    padR,
    padT,
    padB,
  }
})
</script>

<template>
  <div ref="host" class="viz-card">
    <div v-if="!layout" class="hint">该运行没有阶段数据。</div>
    <svg v-else :viewBox="`0 0 ${layout.width} ${layout.height}`" xmlns="http://www.w3.org/2000/svg">
      <g v-for="tick in layout.ticks" :key="`tick-${tick.px}`">
        <line class="swim-axis" :x1="tick.px" :y1="layout.padT - 8" :x2="tick.px" :y2="layout.height - layout.padB + 4" />
        <text class="swim-axis-label" :x="tick.px" :y="layout.padT - 12" text-anchor="middle">{{ tick.label }}</text>
      </g>
      <g v-for="agent in layout.lanes" :key="agent">
        <text class="swim-lane-label" x="10" :y="layout.laneY(agent) + layout.rowH / 2 + 4">{{ agent }}</text>
        <line
          class="swim-axis"
          :x1="layout.padL"
          :y1="layout.laneY(agent) + layout.rowH + layout.laneGap / 2"
          :x2="layout.width - layout.padR"
          :y2="layout.laneY(agent) + layout.rowH + layout.laneGap / 2"
        />
      </g>
      <g v-for="bar in layout.bars" :key="bar.key">
        <rect
          :x="bar.bx"
          :y="bar.y"
          :width="bar.bw"
          :height="bar.h"
          rx="6"
          :fill="STATUS_FILL[bar.status]"
          :stroke="STATUS_STROKE[bar.status]"
          stroke-width="1.2"
        />
        <text v-if="bar.showLabel" class="dag-node-sub" :x="bar.bx + 8" :y="bar.y + bar.h / 2 + 4">{{ bar.label }}</text>
        <text v-for="m in bar.retryMarks" :key="m.key" class="retry-mark" :x="m.px" :y="bar.y + bar.h + 10">⚠</text>
        <rect
          v-for="t in bar.toolTicks"
          :key="t.key"
          class="tool-tick"
          :x="t.px"
          :y="bar.y + bar.h - 5"
          width="3"
          height="5"
          rx="1"
        />
      </g>
    </svg>
  </div>
</template>
