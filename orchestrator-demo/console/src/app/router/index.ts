import { createRouter, createWebHistory } from 'vue-router'

/**
 * One route per console page (1:1 with the legacy static pages). Cross-page
 * jumps carry their context via query params (e.g. instances?businessId=…,
 * metrics?scenarioId=…) so links stay shareable instead of relying on
 * hidden globals.
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: { name: 'run' } },
    { path: '/run', name: 'run', component: () => import('@/features/scenario/run/RunView.vue') },
    { path: '/dry-run', name: 'dryrun', component: () => import('@/features/scenario/dryrun/DryRunView.vue') },
    { path: '/scenarios', name: 'scenarios', component: () => import('@/features/scenario/scenarios/ScenariosView.vue') },
    { path: '/agents', name: 'agents', component: () => import('@/features/metadata/agents/AgentsView.vue') },
    { path: '/workflow', name: 'workflow', component: () => import('@/features/metadata/workflow/WorkflowView.vue') },
    { path: '/mcp', name: 'mcp', component: () => import('@/features/metadata/mcp/McpView.vue') },
    { path: '/meta', name: 'meta', component: () => import('@/features/metadata/meta/MetaView.vue') },
    { path: '/instances', name: 'instances', component: () => import('@/features/observability/instances/InstancesView.vue') },
    { path: '/runs', name: 'runs', component: () => import('@/features/observability/runs/RunsView.vue') },
    { path: '/logs', name: 'logs', component: () => import('@/features/observability/logs/LogsView.vue') },
    { path: '/metrics', name: 'metrics', component: () => import('@/features/observability/metrics/MetricsView.vue') },
    { path: '/:pathMatch(.*)*', redirect: { name: 'run' } },
  ],
})

export default router
