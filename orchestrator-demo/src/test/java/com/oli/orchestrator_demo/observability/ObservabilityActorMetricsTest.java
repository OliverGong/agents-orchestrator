/**
 * @ClassName ObservabilityActorMetricsTest
 * @Description Verifies the actor's write-ahead metrics sink: stage events
 *              land in the {@link RunMetricsStore} before the timeline fold,
 *              the scenario attribution recorded on {@code RUN_STARTED} rides
 *              along on every stage row, ghost stage events produce no rows
 *              and a failing store never breaks the timeline.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.observability;
import com.oli.orchestrator_demo.Await;

import static com.oli.orchestrator_demo.Await.until;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.oli.orchestrator_demo.ChatClientStubs;
import com.oli.orchestrator_demo.observability.AgentObservabilityEvent.LmUsage;
import com.oli.orchestrator_demo.observability.RunTimeline.RunStatus;

class ObservabilityActorMetricsTest {

  /** Collecting fake so assertions can run without a database. */
  private static final class CollectingStore implements RunMetricsStore {
    final List<RunMetricsStore.RunStageMetrics> rows = new CopyOnWriteArrayList<>();

    @Override
    public void record(RunMetricsStore.RunStageMetrics row) {
      rows.add(row);
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
      return "collecting";
    }
  }

  @Test
  void persistsStageRowsWithScenarioAttribution() {
    CollectingStore store = new CollectingStore();
    ObservabilityActor actor = ChatClientStubs.actor(store);

    actor.tell(AgentObservabilityEvent.runStarted("run-1", "tpl", "scn-a", null, "SEQUENTIAL", "{}"));
    actor.tell(AgentObservabilityEvent.stageCompleted("run-1", "research", "research", 0,
        List.of(), "SEQUENTIAL", "main", 100, 1, new LmUsage(120, 45, 165, "qwen3:8b", 1),
        "{\"in\":1}", "{\"out\":1}"));
    actor.tell(AgentObservabilityEvent.runCompleted("run-1", "{}"));

    until(() -> store.rows.size() == 1);
    until(() -> actor.timeline("run-1").map(t -> t.status() == RunStatus.COMPLETED).orElse(false));

    RunMetricsStore.RunStageMetrics row = store.rows.get(0);
    assertThat(row.runId()).isEqualTo("run-1");
    assertThat(row.scenarioId()).isEqualTo("scn-a");
    assertThat(row.stage()).isEqualTo("research");
    assertThat(row.agent()).isEqualTo("research");
    assertThat(row.model()).isEqualTo("qwen3:8b");
    assertThat(row.outcome()).isEqualTo("STAGE_COMPLETED");
    assertThat(row.durationMs()).isEqualTo(100);
    assertThat(row.promptTokens()).isEqualTo(120);
    assertThat(row.completionTokens()).isEqualTo(45);
    assertThat(row.totalTokens()).isEqualTo(165);
    assertThat(row.attempt()).isEqualTo(1);
  }

  @Test
  void dropsGhostStageEventsAndSurvivesFailingStores() {
    RunMetricsStore failing = new RunMetricsStore() {
      @Override
      public void record(RunStageMetrics row) {
        throw new IllegalStateException("db down");
      }

      @Override
      public List<StageMetricsSummary> aggregate(Instant since, String scenarioId) {
        return List.of();
      }

      @Override
      public List<RunMetricsStore.RunStageMetrics> byRun(String runId) {
        return List.of();
      }

      @Override
      public String name() {
        return "failing";
      }
    };
    ObservabilityActor actor = ChatClientStubs.actor(failing);

    // Ghost stage event: never folded, never recorded.
    actor.tell(AgentObservabilityEvent.stageCompleted("ghost", "research", "research", 0,
        List.of(), "SEQUENTIAL", "main", 1, null, null, "{}", "{}"));

    // Known run with a failing store: the timeline still folds.
    actor.tell(AgentObservabilityEvent.runStarted("run-1", "tpl", null, null, "SEQUENTIAL", "{}"));
    actor.tell(AgentObservabilityEvent.stageCompleted("run-1", "research", "research", 0,
        List.of(), "SEQUENTIAL", "main", 1, null, null, "{}", "{}"));

    until(() -> actor.timeline("run-1").map(t -> t.events().size() == 2).orElse(false));
    assertThat(actor.timeline("ghost")).isEmpty();
    assertThat(actor.recentRuns()).hasSize(1);
  }
}
