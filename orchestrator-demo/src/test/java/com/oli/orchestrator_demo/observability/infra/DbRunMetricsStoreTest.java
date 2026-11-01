/**
 * @ClassName DbRunMetricsStoreTest
 * @Description Verifies the durable stage metrics store on H2: the idempotent
 *              schema, append-only recording and the windowed aggregation
 *              grouped by scenario/stage/agent/model (counts, failures,
 *              retries, avg/max duration, token sums), including the scenario
 *              filter and the time window boundary.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.observability.infra;
import com.oli.orchestrator_demo.observability.RunMetricsStore;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class DbRunMetricsStoreTest {

  private DbRunMetricsStore store;
  private Instant now;

  @BeforeEach
  void setUp() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    store = new DbRunMetricsStore(JdbcClient.create(dataSource));
    store.initialize();
    now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
  }

  private RunMetricsStore.RunStageMetrics row(String runId, String scenarioId, String stage, String agent,
      String model, String outcome, long durationMs, long prompt, long completion) {
    return new RunMetricsStore.RunStageMetrics(runId, scenarioId, stage, agent, model, 0, 1, outcome,
        durationMs, prompt, completion, prompt + completion, now);
  }

  @Test
  void aggregatesStageRowsByScenarioStageAgentAndModel() {
    store.record(row("run-1", "scn-a", "research", "research", "qwen3:8b", "STAGE_COMPLETED", 100, 100, 50));
    store.record(row("run-2", "scn-a", "research", "research", "qwen3:8b", "STAGE_COMPLETED", 200, 120, 60));
    store.record(row("run-3", "scn-a", "research", "research", "qwen3:8b", "STAGE_FAILED", 300, 0, 0));
    store.record(row("run-4", "scn-b", "budget", "budget", "llama3.2", "STAGE_COMPLETED", 400, 10, 5));

    var summaries = store.aggregate(now.minusSeconds(60), null);

    assertThat(summaries).hasSize(2);
    RunMetricsStore.StageMetricsSummary a = summaries.get(0);
    assertThat(a.scenarioId()).isEqualTo("scn-a");
    assertThat(a.stage()).isEqualTo("research");
    assertThat(a.agent()).isEqualTo("research");
    assertThat(a.model()).isEqualTo("qwen3:8b");
    assertThat(a.stageCount()).isEqualTo(3);
    assertThat(a.failedCount()).isEqualTo(1);
    assertThat(a.retries()).isEqualTo(3);
    assertThat(a.avgDurationMs()).isEqualTo(200.0);
    assertThat(a.maxDurationMs()).isEqualTo(300L);
    assertThat(a.promptTokens()).isEqualTo(220);
    assertThat(a.completionTokens()).isEqualTo(110);
    assertThat(a.totalTokens()).isEqualTo(330);
    assertThat(a.lastRunAt()).isEqualTo(now);
    assertThat(summaries.get(1).scenarioId()).isEqualTo("scn-b");
    assertThat(summaries.get(1).totalTokens()).isEqualTo(15);
  }

  @Test
  void scenarioFilterAndWindowApply() {
    store.record(row("run-1", "scn-a", "research", "research", "qwen3:8b", "STAGE_COMPLETED", 100, 10, 5));
    RunMetricsStore.RunStageMetrics old = new RunMetricsStore.RunStageMetrics("run-2", "scn-a", "research",
        "research", "qwen3:8b", 0, 1, "STAGE_COMPLETED", 100L, 10L, 5L, 15L, now.minusSeconds(3600));
    store.record(old);
    store.record(row("run-3", "scn-b", "budget", "budget", "llama3.2", "STAGE_COMPLETED", 100, 10, 5));

    // Window excludes the hour-old row.
    assertThat(store.aggregate(now.minusSeconds(60), null)).hasSize(2);

    var onlyA = store.aggregate(now.minusSeconds(60), "scn-a");
    assertThat(onlyA).hasSize(1);
    assertThat(onlyA.get(0).scenarioId()).isEqualTo("scn-a");
    assertThat(onlyA.get(0).stageCount()).isEqualTo(1);

    var unknownScenario = store.aggregate(now.minusSeconds(60), "scn-zzz");
    assertThat(unknownScenario).isEmpty();
  }
}
