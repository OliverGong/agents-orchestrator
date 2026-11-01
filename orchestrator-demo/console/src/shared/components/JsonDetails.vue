<script setup lang="ts">
import { computed } from 'vue'
import { prettyJson } from '@/shared/utils/format'

/** Collapsible pretty-JSON block (blackboard snapshots, tool results…). */
const props = defineProps<{ title: string; json: string | null | undefined }>()
const pretty = computed(() => prettyJson(props.json))
</script>

<template>
  <details v-if="pretty" class="json-details">
    <summary>{{ title }}</summary>
    <pre class="json">{{ pretty }}</pre>
  </details>
</template>

<style scoped>
details.json-details { margin: 4px 0 4px 80px; }
details.json-details summary { color: var(--accent); cursor: pointer; font-size: 12px; }
</style>
