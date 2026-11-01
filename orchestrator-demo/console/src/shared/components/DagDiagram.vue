<script setup lang="ts">
import { computed } from 'vue'
import type { DagEdge, DagNode } from '@/shared/types/dag'

/** Layered DAG (ported from the legacy console): waves as columns,
    bezier dependency edges, optional per-stage status coloring. */

const props = withDefaults(
  defineProps<{ nodes: DagNode[]; edges: DagEdge[]; useStatus?: boolean }>(),
  { useStatus: false },
)

const STATUS_FILL: Record<string, string> = {
  ok: '#234d2c',
  fail: '#5c2320',
  planned: '#1c2333',
  running: '#1f3a5f',
}
const STATUS_STROKE: Record<string, string> = {
  ok: '#3fb950',
  fail: '#f85149',
  planned: '#8b949e',
  running: '#58a6ff',
}

interface PlacedNode extends DagNode {
  x: number
  y: number
  w: number
  h: number
  fill: string
  stroke: string
}

const layout = computed(() => {
  const byWave = new Map<number, DagNode[]>()
  for (const n of props.nodes) {
    const arr = byWave.get(n.wave) ?? []
    arr.push(n)
    byWave.set(n.wave, arr)
  }
  const waves = [...byWave.keys()].sort((a, b) => a - b)
  const colW = 190
  const rowH = 74
  const padX = 24
  const padY = 20
  const maxRows = Math.max(1, ...waves.map((w) => byWave.get(w)!.length))
  const width = padX * 2 + colW * Math.max(1, waves.length)
  const height = padY * 2 + rowH * maxRows

  const placed = new Map<string, PlacedNode>()
  waves.forEach((w, ci) => {
    byWave.get(w)!.forEach((n, ri) => {
      const status = props.useStatus ? (n.status || 'planned') : 'planned'
      placed.set(n.id, {
        ...n,
        x: padX + ci * colW + 14,
        y: padY + ri * rowH + 8,
        w: colW - 28,
        h: rowH - 22,
        fill: STATUS_FILL[status] ?? STATUS_FILL.planned!,
        stroke: STATUS_STROKE[status] ?? STATUS_STROKE.planned!,
      })
    })
  })

  const edgePaths: string[] = []
  for (const e of props.edges) {
    const a = placed.get(e.from)
    const b = placed.get(e.to)
    if (!a || !b) continue
    const x1 = a.x + a.w
    const y1 = a.y + a.h / 2
    const x2 = b.x
    const y2 = b.y + b.h / 2
    const mx = (x1 + x2) / 2
    edgePaths.push(`M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`)
  }
  return { width, height, nodes: [...placed.values()], edgePaths }
})
</script>

<template>
  <div class="viz-card">
    <svg :viewBox="`0 0 ${layout.width} ${layout.height}`" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <marker id="dag-arrow" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto">
          <path d="M0,0 L7,3 L0,6 Z" fill="#8b949e" />
        </marker>
      </defs>
      <path
        v-for="(d, i) in layout.edgePaths"
        :key="i"
        class="dag-edge"
        :d="d"
        marker-end="url(#dag-arrow)"
      />
      <g v-for="n in layout.nodes" :key="n.id">
        <rect
          :x="n.x"
          :y="n.y"
          :width="n.w"
          :height="n.h"
          rx="8"
          :fill="n.fill"
          :stroke="n.stroke"
          stroke-width="1.4"
        />
        <text :x="n.x + 10" :y="n.y + 18" class="dag-node-label">{{ n.label }}</text>
        <text :x="n.x + 10" :y="n.y + 33" class="dag-node-sub">{{ n.sub || '' }}</text>
        <text v-if="n.retries" :x="n.x + n.w - 14" :y="n.y + 13" class="retry-mark">⚠{{ n.retries }}</text>
      </g>
    </svg>
  </div>
</template>
