/**
 * @ClassName NoopRunMetricsStore
 * @Description The {@link RunMetricsStore} used when no database is wired
 *              ({@code orchestrator.control.store=file}): recording is a
 *              no-op and aggregation returns nothing, so the observability
 *              actor keeps its single code path without a DataSource
 *              dependency. In-memory run timelines remain the only state in
 *              this mode — restarts lose the history by design.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.observability;

import java.time.Instant;
import java.util.List;

public final class NoopRunMetricsStore implements RunMetricsStore {

  @Override
  public void record(RunStageMetrics row) {
    // No durable state in file mode.
  }

  @Override
  public List<StageMetricsSummary> aggregate(Instant since, String scenarioId) {
    return List.of();
  }

  @Override
  public List<RunStageMetrics> byRun(String runId) {
    return List.of();
  }

  @Override
  public String name() {
    return "noop";
  }
}
