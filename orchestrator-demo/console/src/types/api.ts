/**
 * Typed REST contract of the Orchestrator backend (v3.0 M2a inventory).
 * Every console view consumes these DTOs only — the shape drift between the
 * SPA and Spring controllers is caught at compile time instead of runtime.
 */

/* ---------------- scenarios ---------------- */

export interface TripRequest {
  destination: string
  numberOfDays: number
  budget: number
  preferences?: string
}

export type WorkflowMode = 'SEQUENTIAL' | 'CONCURRENT'

export interface WorkflowStageSpec {
  name: string
  agent: string
  dependsOn: string[]
}

export interface WorkflowSpec {
  mode: WorkflowMode
  stages: WorkflowStageSpec[]
}

export type ArtifactType = 'HTML' | 'SPRING_BOOT_PROJECT' | 'JAR' | 'AUDIO'

export interface CalibrationRule {
  pattern: string
  replacement: string
  description?: string
}

export interface ScenarioAgentOverride {
  systemPrompt?: string
  model?: string | null
  temperature?: number | null
  calibrationRules?: CalibrationRule[]
  mcpRefs?: string[]
}

export interface ScenarioSummary {
  id: string
  name: string
  description?: string
  requestTemplate: TripRequest
  workflowOverride?: WorkflowSpec | null
  agentOverrides?: Record<string, ScenarioAgentOverride>
  mcpServerRefs?: string[]
  artifactType: ArtifactType
  active: boolean
}

export interface ScenariosState {
  scenarios: ScenarioSummary[]
  activeScenarioId: string | null
}

export interface ControlPlaneState {
  version: number | string
  agents: Record<string, AgentConfig>
  descriptors: Record<string, AgentDescriptor> | null
  workflow: WorkflowSpec
  mcpServers: McpServerConfig[]
}

/* ---------------- dry run ---------------- */

export interface DryRunStage {
  stageName: string
  agentName: string
  effectiveModel: string | null
  effectiveTemperature: number | null
  mcpServers: string[]
  inputs: string[]
  outputs: string[]
  pendingSafe: string[]
  warnings: string[]
}

export interface DryRunWave {
  waveIndex: number
  parallel: boolean
  stages: DryRunStage[]
}

export interface DryRunEdge {
  from: string
  to: string
}

export interface SlotFlow {
  slot: string
  producedBy: string[]
  consumedBy: string[]
  pendingSafe: boolean
}

export interface DryRunReport {
  scenarioId: string
  valid: boolean
  issues: string[]
  waves: DryRunWave[]
  edges: DryRunEdge[]
  slotFlows: SlotFlow[]
  resolvedMode: string
  resolvedTemplateSource: string
}

/* ---------------- trip run & replay ---------------- */

export interface ResearchResponse {
  attractions: string[]
  foodOptions: string[]
  travelTips: string[]
}

export interface BudgetResponse {
  accommodation: number
  transportCost: number
  foodCost: number
  activityCost: number
  totalCost: number
  isBudgetMet: boolean
  recommendations: string[]
}

export interface DayPlan {
  day: number
  morning: string
  afternoon: string
  evening: string
  estimatedCost: number
}

export interface ItineraryResponse {
  dayPlans: DayPlan[]
}

export interface ReviewResponse {
  isValid: boolean
  summary: string
  issues: string[]
  recommendations: string[]
}

export interface OrchestratorContext {
  request: TripRequest
  researchResponse: ResearchResponse
  budgetResponse: BudgetResponse
  itineraryResponse: ItineraryResponse
  reviewResponse: ReviewResponse
}

export type StageOutcome = 'STAGE_COMPLETED' | 'STAGE_FAILED' | string

export interface ToolCall {
  serverName: string
  toolName: string
  durationMs: number
  outcome: string
  resultSummary?: string
  errorMessage?: string
}

export interface RetryAttempt {
  attempt: number
  errorMessage?: string
  timestamp: string
}

export interface StageUsage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
  model?: string
  retries: number
}

export interface RunStage {
  stageName: string
  agentName: string
  outcome: StageOutcome
  threadName?: string
  durationMs?: number | null
  errorMessage?: string | null
  inputContext?: string | null
  outputContext?: string | null
  parallelPeers?: string[]
  toolCalls?: ToolCall[]
  retries?: RetryAttempt[]
  usage?: StageUsage | null
  completedAt?: string | null
}

export interface RunWave {
  waveIndex: number
  parallel: boolean
  stages: RunStage[]
}

export type RunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED' | string

export interface RunSummary {
  runId: string
  templateName: string
  scenarioId: string | null
  businessId: string | null
  mode: string
  status: RunStatus
  startedAt: string
  completedAt: string | null
  durationMs: number | null
  stageCount: number
  failedStages: number
}

export interface RunDetails extends RunSummary {
  finalContext: string
  waves: RunWave[]
}

/* ---------------- run instances (business correlation) ---------------- */

export interface RunInstance {
  runId: string
  businessId: string | null
  scenarioId: string | null
  templateName: string
  mode: string
  status: RunStatus
  startedAt: string
  completedAt: string | null
  durationMs: number | null
  trigger: string
  requestJson: string
  errorMessage: string | null
}

export interface InstanceStageMetric {
  waveIndex: number | null
  stage: string
  agent: string
  model: string | null
  outcome: string
  durationMs: number | null
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  attempt: number | null
  createdAt: string
}

export interface TeamLogEntry {
  id: string | number
  ts: string
  category: string
  level: string
  source: string
  runId: string | null
  scenarioId: string | null
  action: string
  message: string
  detail?: string | null
}

export interface InstanceDetail {
  instance: RunInstance
  run: RunDetails | null
  stages: InstanceStageMetric[]
  logs: TeamLogEntry[]
}

/* ---------------- meta maintenance ---------------- */

export interface RuntimeMeta {
  modelBackend?: string | null
  requestTimeoutSeconds?: number | null
  maxWaveConcurrency?: number | null
  temperature?: number | null
  topP?: number | null
}

export interface PortalConfig {
  id: string
  name: string
  kind: string
  endpoint?: string | null
  enabled: boolean
}

export interface ReleaseConfig {
  scenarioId: string
  portalId?: string | null
  version?: string | null
  artifactType?: string | null
  changelog?: string | null
}

export interface MetaState {
  version: number | string
  updatedAt?: string | null
  runtime: RuntimeMeta
  portals: PortalConfig[]
  releases: ReleaseConfig[]
}

export interface ReleaseRecord {
  id: string | number
  scenarioId: string
  portalId: string | null
  releaseVersion: string | null
  artifactType: string | null
  publishedAt: string
  packageHash: string | null
}

/* ---------------- control plane ---------------- */

export interface AgentConfig {
  name: string
  systemPrompt: string
  model: string | null
  temperature: number | null
  calibrationRules: CalibrationRule[]
  mcpRefs: string[]
}

export interface AgentDescriptor {
  outputSlots: string[]
  inputSlots: string[]
  pendingSafeSlots: string[]
  defaultModel?: string | null
}

export type McpTransport = 'STREAMABLE_HTTP' | 'SSE' | 'STDIO'

export interface McpServerConfig {
  name: string
  transport: McpTransport
  endpoint?: string | null
  command?: string | null
  args?: string[]
  env?: Record<string, string>
  enabled: boolean
  description?: string | null
}

export interface McpServerStatus {
  name: string
  state: string
  toolNames: string[]
  error?: string | null
  enabled: boolean
}

/* ---------------- actuator ---------------- */

export interface Measurement {
  statistic: string
  value: number
}

export interface MetricResponse {
  name: string
  measurements: Measurement[]
}

export interface HealthComponent {
  status: string
  details?: Record<string, unknown>
}

export interface HealthResponse {
  status: string
  components?: {
    orchestrator?: HealthComponent & { details?: { agents?: string[] } }
  }
}

export interface ActuatorInfo {
  build?: { version?: string }
  workflow?: unknown
  [key: string]: unknown
}

/* ---------------- SSE events (/api/v1/events/stream) ---------------- */

export interface RunStartedEvent {
  runId: string
  templateName: string
  mode: string
}

export interface StageEvent {
  runId: string
  stageName: string
  agentName: string
  waveIndex: number
  durationMs?: number | null
  threadName?: string | null
  parallelPeers?: string[]
  errorMessage?: string | null
  inputContextJson?: string | null
  outputContextJson?: string | null
  attempt?: number
  timestamp?: string
}
