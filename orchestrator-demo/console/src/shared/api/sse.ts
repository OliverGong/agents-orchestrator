/**
 * SSE connector for GET /api/v1/events/stream — the observability event bus.
 * Every stage completion / retry / run lifecycle event is pushed as it is
 * folded into the run timeline (WAL order: persisted first, then emitted).
 * The Run view subscribes for its live blackboard; the connection itself is
 * a module singleton shared by the whole SPA (EventSource auto-reconnects).
 */
import type { RunStartedEvent, StageEvent } from '@/shared/types/api'

export interface OrchestratorSseHandlers {
  connected?: () => void
  runStarted?: (e: RunStartedEvent) => void
  stageCompleted?: (e: StageEvent) => void
  stageFailed?: (e: StageEvent) => void
  stageRetried?: (e: StageEvent) => void
  runCompleted?: (finalContextJson: string) => void
  runFailed?: () => void
  error?: () => void
}

export interface SseConnection {
  /** Stop receiving events (idempotent). */
  close(): void
}

export function connectOrchestratorEvents(handlers: OrchestratorSseHandlers): SseConnection {
  const sse = new EventSource('/api/v1/events/stream')
  const on = (name: string, fn: (data: string) => void) =>
    sse.addEventListener(name, (ev) => {
      try {
        fn((ev as MessageEvent<string>).data)
      } catch {
        /* a malformed event must not kill the stream */
      }
    })

  on('connected', () => handlers.connected?.())
  on('run_started', (d) => handlers.runStarted?.(JSON.parse(d) as RunStartedEvent))
  on('stage_completed', (d) => handlers.stageCompleted?.(JSON.parse(d) as StageEvent))
  on('stage_failed', (d) => handlers.stageFailed?.(JSON.parse(d) as StageEvent))
  on('stage_retried', (d) => handlers.stageRetried?.(JSON.parse(d) as StageEvent))
  on('run_completed', (d) => handlers.runCompleted?.(d))
  on('run_failed', () => handlers.runFailed?.())
  sse.onerror = () => handlers.error?.()

  return {
    close() {
      sse.close()
    },
  }
}
