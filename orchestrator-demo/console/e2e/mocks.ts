/**
 * Deterministic backend for the E2E suite: every /api/** and /actuator/**
 * request is answered from the fixtures below at the network level, so the
 * functional closed loop (scenarios → dry-run → run → replay → logs/metrics)
 * runs without a Spring Boot process or a real model. Shapes mirror the
 * typed REST contract in src/shared/types/api.ts.
 */
import type { Page, Route } from '@playwright/test'
import type {
  ControlPlaneState,
  DryRunReport,
  InstanceDetail,
  MetaState,
  McpServerStatus,
  OrchestratorContext,
  RunDetails,
  RunInstance,
  RunSummary,
  ScenariosState,
  TeamLogEntry,
} from '../src/shared/types/api'

export const scenariosState: ScenariosState = {
  scenarios: [
    {
      id: 'kyoto-classic',
      name: '京都经典',
      description: 'E2E fixture scenario',
      requestTemplate: { destination: '京都', numberOfDays: 3, budget: 8000, preferences: '文化' },
      artifactType: 'HTML',
      active: true,
    },
    {
      id: 'osaka-food',
      name: '大阪美食',
      requestTemplate: { destination: '大阪', numberOfDays: 2, budget: 5000 },
      artifactType: 'HTML',
      active: false,
    },
  ],
  activeScenarioId: 'kyoto-classic',
}

export const controlState: ControlPlaneState = {
  version: 7,
  agents: {
    ResearchAgent: {
      name: 'ResearchAgent',
      systemPrompt: 'You research destinations.',
      model: 'qwen3:8b',
      temperature: 0.7,
      calibrationRules: [],
      mcpRefs: [],
    },
    ItineraryAgent: {
      name: 'ItineraryAgent',
      systemPrompt: 'You plan days.',
      model: null,
      temperature: null,
      calibrationRules: [],
      mcpRefs: [],
    },
  },
  descriptors: {
    ResearchAgent: { outputSlots: ['researchResponse'], inputSlots: ['request'], pendingSafeSlots: [], defaultModel: 'qwen3:8b' },
    ItineraryAgent: { outputSlots: ['itineraryResponse'], inputSlots: ['request', 'researchResponse'], pendingSafeSlots: [] },
  },
  workflow: {
    mode: 'SEQUENTIAL',
    stages: [
      { name: 'research', agent: 'ResearchAgent', dependsOn: [] },
      { name: 'itinerary', agent: 'ItineraryAgent', dependsOn: ['research'] },
    ],
  },
  mcpServers: [
    { name: 'weather', transport: 'STREAMABLE_HTTP', endpoint: 'http://localhost:9090/mcp', enabled: true },
  ],
}

export const mcpStatuses: McpServerStatus[] = [
  { name: 'weather', state: 'CONNECTED', toolNames: ['get_weather'], enabled: true },
]

export const metaState: MetaState = {
  version: 3,
  updatedAt: '2026-11-01T05:00:00Z',
  runtime: { modelBackend: 'ollama', requestTimeoutSeconds: 60 },
  portals: [{ id: 'portal-web', name: 'Portal Web', kind: 'WEB', endpoint: 'https://portal.example', enabled: true }],
  releases: [{ scenarioId: 'kyoto-classic', portalId: 'portal-web', version: '1.0.0', artifactType: 'HTML' }],
}

export const releaseRecords = [
  {
    id: 1,
    scenarioId: 'kyoto-classic',
    portalId: 'portal-web',
    releaseVersion: '1.0.0',
    artifactType: 'HTML',
    publishedAt: '2026-11-01T05:00:00Z',
    packageHash: 'abc123',
  },
]

export const context: OrchestratorContext = {
  request: { destination: '京都', numberOfDays: 3, budget: 8000 },
  researchResponse: {
    attractions: ['清水寺', '伏见稻荷大社'],
    foodOptions: ['汤豆腐'],
    travelTips: ['购买 ICOCA 卡'],
  },
  budgetResponse: {
    accommodation: 3000,
    transportCost: 800,
    foodCost: 1600,
    activityCost: 1200,
    totalCost: 6600,
    isBudgetMet: true,
    recommendations: ['错峰出行'],
  },
  itineraryResponse: {
    dayPlans: [{ day: 1, morning: '清水寺', afternoon: '二年坂', evening: '鸭川', estimatedCost: 2200 }],
  },
  reviewResponse: { isValid: true, summary: '行程合理', issues: [], recommendations: [] },
}

export const runSummaries: RunSummary[] = [
  {
    runId: 'run-e2e-0001',
    templateName: 'classic',
    scenarioId: 'kyoto-classic',
    businessId: 'BIZ-42',
    mode: 'SEQUENTIAL',
    status: 'COMPLETED',
    startedAt: '2026-11-01T05:00:00Z',
    completedAt: '2026-11-01T05:00:03Z',
    durationMs: 3210,
    stageCount: 4,
    failedStages: 0,
  },
]

const completedStage = (stageName: string, agentName: string, durationMs: number) => ({
  stageName,
  agentName,
  outcome: 'STAGE_COMPLETED',
  durationMs,
  usage: { promptTokens: 100, completionTokens: 200, totalTokens: 300, model: 'qwen3:8b', retries: 0 },
  completedAt: '2026-11-01T05:00:01Z',
})

export const runDetails: RunDetails = {
  ...runSummaries[0],
  finalContext: JSON.stringify(context),
  waves: [
    {
      waveIndex: 0,
      parallel: false,
      stages: [
        completedStage('research', 'ResearchAgent', 1200),
        completedStage('itinerary', 'ItineraryAgent', 900),
        completedStage('budget', 'BudgetAgent', 600),
        completedStage('review', 'ReviewAgent', 500),
      ],
    },
  ],
}

export const instance: RunInstance = {
  runId: 'run-e2e-0001',
  businessId: 'BIZ-42',
  scenarioId: 'kyoto-classic',
  templateName: 'classic',
  mode: 'SEQUENTIAL',
  status: 'COMPLETED',
  startedAt: '2026-11-01T05:00:00Z',
  completedAt: '2026-11-01T05:00:03Z',
  durationMs: 3210,
  trigger: 'MANUAL',
  requestJson: JSON.stringify(context.request),
  errorMessage: null,
}

export const logEntries: TeamLogEntry[] = [
  {
    id: 1,
    ts: '2026-11-01T05:00:01Z',
    category: 'RUN',
    level: 'INFO',
    source: 'orchestrator',
    runId: 'run-e2e-0001',
    scenarioId: 'kyoto-classic',
    action: 'STAGE_COMPLETED',
    message: '阶段 research 完成',
    detail: null,
  },
  {
    id: 2,
    ts: '2026-11-01T05:00:02Z',
    category: 'RUN',
    level: 'ERROR',
    source: 'orchestrator',
    runId: 'run-e2e-0001',
    scenarioId: 'kyoto-classic',
    action: 'STAGE_FAILED',
    message: '阶段 budget 校验失败',
    detail: null,
  },
]

export const instanceDetail: InstanceDetail = {
  instance,
  run: runDetails,
  stages: [
    {
      waveIndex: 0,
      stage: 'research',
      agent: 'ResearchAgent',
      model: 'qwen3:8b',
      outcome: 'STAGE_COMPLETED',
      durationMs: 1200,
      promptTokens: 100,
      completionTokens: 200,
      totalTokens: 300,
      attempt: 1,
      createdAt: '2026-11-01T05:00:01Z',
    },
  ],
  logs: logEntries,
}

export const dryRunReport: DryRunReport = {
  scenarioId: 'kyoto-classic',
  valid: true,
  issues: [],
  waves: [
    {
      waveIndex: 0,
      parallel: false,
      stages: [
        {
          stageName: 'research',
          agentName: 'ResearchAgent',
          effectiveModel: 'qwen3:8b',
          effectiveTemperature: 0.7,
          mcpServers: [],
          inputs: ['request'],
          outputs: ['researchResponse'],
          pendingSafe: [],
          warnings: [],
        },
        {
          stageName: 'itinerary',
          agentName: 'ItineraryAgent',
          effectiveModel: null,
          effectiveTemperature: null,
          mcpServers: ['weather'],
          inputs: ['request', 'researchResponse'],
          outputs: ['itineraryResponse'],
          pendingSafe: [],
          warnings: [],
        },
      ],
    },
  ],
  edges: [{ from: 'research', to: 'itinerary' }],
  slotFlows: [
    { slot: 'request', producedBy: [], consumedBy: ['research', 'itinerary'], pendingSafe: false },
    { slot: 'researchResponse', producedBy: ['research'], consumedBy: ['itinerary'], pendingSafe: false },
  ],
  resolvedMode: 'SEQUENTIAL',
  resolvedTemplateSource: 'control-plane',
}

/** One full SSE frame sequence, replayed for every (re)connection. */
export function sseStream(): string {
  const frames: Array<[string, string]> = [
    ['connected', 'ok'],
    ['run_started', JSON.stringify({ runId: 'run-e2e-0001', templateName: 'classic', mode: 'SEQUENTIAL' })],
    [
      'stage_completed',
      JSON.stringify({ runId: 'run-e2e-0001', stageName: 'research', agentName: 'ResearchAgent', waveIndex: 0, durationMs: 1200 }),
    ],
    [
      'stage_completed',
      JSON.stringify({ runId: 'run-e2e-0001', stageName: 'itinerary', agentName: 'ItineraryAgent', waveIndex: 1, durationMs: 900 }),
    ],
    ['run_completed', JSON.stringify(context)],
  ]
  return frames.map(([event, data]) => `event: ${event}\ndata: ${data}\n\n`).join('')
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

/** Intercept every backend-origin path; let the SPA assets pass through. */
export async function installBackendMocks(page: Page): Promise<void> {
  await page.route('**/*', (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const p = url.pathname
    const method = req.method()
    if (!p.startsWith('/api/') && !p.startsWith('/actuator')) return route.continue()

    if (p === '/api/v1/events/stream') {
      return route.fulfill({ status: 200, contentType: 'text/event-stream', body: sseStream() })
    }
    if (p === '/actuator/health') return json(route, { status: 'UP', components: { orchestrator: { status: 'UP', details: { agents: ['ResearchAgent', 'ItineraryAgent'] } } } })
    if (p === '/actuator/info') return json(route, { build: { version: '3.0.0' } })
    if (p.startsWith('/actuator/metrics/')) {
      return json(route, { name: p.slice('/actuator/metrics/'.length), measurements: [{ statistic: 'COUNT', value: 3 }, { statistic: 'TOTAL_TIME', value: 1.23 }] })
    }

    if (p === '/api/v1/scenarios' && method === 'GET') return json(route, scenariosState)
    if (p === '/api/v1/scenarios' && method === 'POST') return json(route, scenariosState)
    if (/^\/api\/v1\/scenarios\/[^/]+\/dry-run$/.test(p)) return json(route, dryRunReport)
    if (/^\/api\/v1\/scenarios\/[^/]+\/activate$/.test(p)) return json(route, scenariosState)

    if (p === '/api/v1/control/models') return json(route, { models: ['qwen3:8b'] })
    if (p === '/api/v1/control/mcp/status') return json(route, mcpStatuses)
    if (p.startsWith('/api/v1/control/')) return json(route, controlState)

    if (p === '/api/v1/meta' || p === '/api/v1/meta/runtime' || p === '/api/v1/meta/portals' || p === '/api/v1/meta/releases') {
      return json(route, metaState)
    }
    if (p === '/api/v1/releases') return json(route, releaseRecords)

    if (p === '/api/trips/runs' && method === 'GET') return json(route, runSummaries)
    if (/^\/api\/trips\/runs\/[^/]+$/.test(p)) return json(route, runDetails)
    if (p === '/api/trips/plan') return json(route, context)

    if (p === '/api/v1/instances') return json(route, [instance])
    if (/^\/api\/v1\/instances\/[^/]+$/.test(p)) return json(route, instanceDetail)

    if (p === '/api/v1/logs') {
      const level = url.searchParams.get('level')
      const category = url.searchParams.get('category')
      let rows = logEntries
      if (level) rows = rows.filter((r) => r.level === level)
      if (category) rows = rows.filter((r) => r.category === category)
      return json(route, rows)
    }
    if (p === '/api/v1/metrics/lm') return json(route, [])

    return json(route, { error: 'NOT_MOCKED', message: `${method} ${p}` }, 404)
  })
}
