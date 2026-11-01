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
    { path: '/run', name: 'run', component: () => import('@/features/scenario/RunView.vue') },
    { path: '/dry-run', name: 'dryrun', component: () => import('@/features/scenario/DryRunView.vue') },
    { path: '/scenarios', name: 'scenarios', component: () => import('@/features/scenario/ScenariosView.vue') },
    { path: '/agents', name: 'agents', component: () => import('@/features/metadata/AgentsView.vue') },
    { path: '/workflow', name: 'workflow', component: () => import('@/features/metadata/WorkflowView.vue') },
    { path: '/mcp', name: 'mcp', component: () => import('@/features/metadata/McpView.vue') },
    { path: '/meta', name: 'meta', component: () => import('@/features/metadata/MetaView.vue') },
    { path: '/instances', name: 'instances', component: () => import('@/features/observability/InstancesView.vue') },
    { path: '/runs', name: 'runs', component: () => import('@/features/observability/RunsView.vue') },
    { path: '/logs', name: 'logs', component: () => import('@/features/observability/LogsView.vue') },
    { path: '/metrics', name: 'metrics', component: () => import('@/features/observability/MetricsView.vue') },
    { path: '/:pathMatch(.*)*', redirect: { name: 'run' } },
  ],
})

export default router
