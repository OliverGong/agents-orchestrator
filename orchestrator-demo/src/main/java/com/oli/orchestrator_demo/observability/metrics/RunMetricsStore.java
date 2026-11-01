/**
 * @ClassName RunMetricsStore
 * @Description Durable sink for the per-stage LM metrics of workflow runs
 *              (v2.2 observability persistence): one row per executed stage,
 *              carrying the scenario attribution, the agent/model that served
 *              it, the wall duration, the parse retries and the aggregated
 *              token consumption. Written <em>before</em> the in-memory
 *              timeline fold and the SSE fan-out (write-ahead order), so the
 *              per-scenario history survives service restarts — the
 *              {@code db} backend stores rows in the {@code obs_run_metrics}
 *              table (PostgreSQL WAL / H2), the {@code file} mode keeps a
 *              no-op implementation because in-memory timelines are the only
 *              state there. The read side powers the per-scenario aggregation
 *              API ({@code GET /api/v1/scenarios/{id}/metrics}).
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.observability.metrics;

import java.time.Instant;
import java.util.List;

public interface RunMetricsStore {

  /** One executed (or failed) stage of one run. */
  record RunStageMetrics(
      String runId,
      String scenarioId,
      String stage,
      String agent,
      String model,
      Integer waveIndex,
      Integer attempt,
      String outcome,
      Long durationMs,
      Long promptTokens,
      Long completionTokens,
      Long totalTokens,
      Instant createdAt) {
  }

  /**
   * Aggregated stage metrics over a time window, grouped by scenario, stage,
   * agent and model — the shape behind the per-scenario LM history API.
   */
  record StageMetricsSummary(
      String scenarioId,
      String stage,
      String agent,
      String model,
      long stageCount,
      long failedCount,
      long retries,
      Double avgDurationMs,
      Long maxDurationMs,
      long promptTokens,
      long completionTokens,
      long totalTokens,
      Instant lastRunAt) {
  }

  /** Persists one stage row; must never throw into the observability actor. */
  void record(RunStageMetrics row);

  /** Aggregates over the window since {@code since}; scenario filter nullable. */
  List<StageMetricsSummary> aggregate(Instant since, String scenarioId);

  /**
   * All durable stage rows of one run (multi-agent / multi-model call details
   * behind the instance detail view); empty when no durable rows exist.
   */
  List<RunStageMetrics> byRun(String runId);

  String name();
}
