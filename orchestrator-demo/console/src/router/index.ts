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
    { path: '/run', name: 'run', component: () => import('../views/RunView.vue') },
    { path: '/dry-run', name: 'dryrun', component: () => import('../views/DryRunView.vue') },
    { path: '/scenarios', name: 'scenarios', component: () => import('../views/ScenariosView.vue') },
    { path: '/agents', name: 'agents', component: () => import('../views/AgentsView.vue') },
    { path: '/workflow', name: 'workflow', component: () => import('../views/WorkflowView.vue') },
    { path: '/mcp', name: 'mcp', component: () => import('../views/McpView.vue') },
    { path: '/meta', name: 'meta', component: () => import('../views/MetaView.vue') },
    { path: '/instances', name: 'instances', component: () => import('../views/InstancesView.vue') },
    { path: '/runs', name: 'runs', component: () => import('../views/RunsView.vue') },
    { path: '/logs', name: 'logs', component: () => import('../views/LogsView.vue') },
    { path: '/metrics', name: 'metrics', component: () => import('../views/MetricsView.vue') },
    { path: '/:pathMatch(.*)*', redirect: { name: 'run' } },
  ],
})

export default router
