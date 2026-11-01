/**
 * @ClassName ObservabilityActorInstanceTest
 * @Description Verifies the actor's write-ahead instance registry sink (v2.3):
 *              the business trigger (RUN_STARTED with business id and request
 *              payload) inserts the RUNNING row, the terminal events move it
 *              to COMPLETED (with the measured duration) / FAILED (with the
 *              error reason), ghost terminal events are ignored and a failing
 *              registry never breaks the timeline.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.ops.observability.events;
import com.oli.orchestrator_demo.support.ChatClientStubs;
import com.oli.orchestrator_demo.support.Await;
import com.oli.orchestrator_demo.ops.observability.metrics.RunMetricsStore;
import com.oli.orchestrator_demo.support.Await;
import com.oli.orchestrator_demo.ops.observability.infra.NoopRunMetricsStore;

import static com.oli.orchestrator_demo.support.Await.until;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.oli.orchestrator_demo.support.ChatClientStubs;
import com.oli.orchestrator_demo.support.ChatClientStubs.CollectingInstanceStore;
import com.oli.orchestrator_demo.ops.instance.InstanceStore;
import com.oli.orchestrator_demo.kernel.contract.model.run.RunInstance;
import com.oli.orchestrator_demo.ops.observability.events.RunTimeline.RunStatus;

class ObservabilityActorInstanceTest {

  @Test
  void tracksTheInstanceLifecycleFromBusinessTriggerToOutcome() {
    CollectingInstanceStore registry = new CollectingInstanceStore();
    ObservabilityActor actor = ChatClientStubs.actor(new NoopRunMetricsStore(), registry);

    actor.tell(AgentObservabilityEvent.runStarted("run-1", "tpl", "scn-a", "biz-42",
        "SEQUENTIAL", "{\"destination\":\"Kyoto\"}"));
    actor.tell(AgentObservabilityEvent.stageCompleted("run-1", "research", "research", 0,
        List.of(), "SEQUENTIAL", "main", 100, 1, null, "{\"in\":1}", "{\"out\":1}"));
    actor.tell(AgentObservabilityEvent.runCompleted("run-1", "{\"review\":{}}"));

    until(() -> actor.timeline("run-1").map(t -> t.status() == RunStatus.COMPLETED).orElse(false));
    until(() -> registry.find("run-1").map(i -> RunInstance.COMPLETED.equals(i.status())).orElse(false));

    assertThat(registry.find("run-1")).hasValueSatisfying(instance -> {
      assertThat(instance.businessId()).isEqualTo("biz-42");
      assertThat(instance.scenarioId()).isEqualTo("scn-a");
      assertThat(instance.requestJson()).contains("Kyoto");
      assertThat(instance.trigger()).isEqualTo(RunInstance.TRIGGER_API);
      assertThat(instance.startedAt()).isNotNull();
      assertThat(instance.completedAt()).isNotNull();
      assertThat(instance.durationMs()).isNotNull();
      assertThat(instance.errorMessage()).isNull();
    });
  }

  @Test
  void marksTheInstanceFailedWithTheErrorReason() {
    CollectingInstanceStore registry = new CollectingInstanceStore();
    ObservabilityActor actor = ChatClientStubs.actor(new NoopRunMetricsStore(), registry);

    actor.tell(AgentObservabilityEvent.runStarted("run-1", "tpl", null, "biz-42",
        "SEQUENTIAL", "{}"));
    actor.tell(AgentObservabilityEvent.runFailed("run-1", "model boom"));

    until(() -> registry.find("run-1").map(i -> RunInstance.FAILED.equals(i.status())).orElse(false));
    assertThat(registry.find("run-1")).hasValueSatisfying(instance ->
        assertThat(instance.errorMessage()).isEqualTo("model boom"));
  }

  @Test
  void ignoresGhostTerminalEventsAndSurvivesFailingRegistries() {
    InstanceStore failing = new InstanceStore() {
      @Override
      public void start(RunInstance instance) {
        throw new IllegalStateException("registry down");
      }

      @Override
      public void complete(String runId, Instant completedAt, Long durationMs) {
        throw new IllegalStateException("registry down");
      }

      @Override
      public void fail(String runId, Instant completedAt, String errorMessage) {
        throw new IllegalStateException("registry down");
      }

      @Override
      public java.util.Optional<RunInstance> find(String runId) {
        return java.util.Optional.empty();
      }

      @Override
      public List<RunInstance> query(InstanceQuery query) {
        return List.of();
      }

      @Override
      public String name() {
        return "failing";
      }
    };
    ObservabilityActor actor = ChatClientStubs.actor(new NoopRunMetricsStore(), failing);

    // Ghost terminal event without a RUN_STARTED: no registry call, no timeline.
    actor.tell(AgentObservabilityEvent.runCompleted("ghost", "{}"));

    // Known run with a failing registry: the timeline still folds.
    actor.tell(AgentObservabilityEvent.runStarted("run-1", "tpl", null, null, "SEQUENTIAL", "{}"));
    actor.tell(AgentObservabilityEvent.runCompleted("run-1", "{}"));

    until(() -> actor.timeline("run-1").map(t -> t.status() == RunStatus.COMPLETED).orElse(false));
    assertThat(actor.timeline("ghost")).isEmpty();
    assertThat(actor.recentRuns()).hasSize(1);
  }

  @Test
  void stageRowsCarryTheScenarioAttributionFromTheRunMeta() {
    CopyOnWriteArrayList<RunMetricsStore.RunStageMetrics> rows = new CopyOnWriteArrayList<>();
    RunMetricsStore metrics = new RunMetricsStore() {
      @Override
      public void record(RunStageMetrics row) {
        rows.add(row);
      }

      @Override
      public List<StageMetricsSummary> aggregate(Instant since, String scenarioId) {
        return List.of();
      }

      @Override
      public List<RunStageMetrics> byRun(String runId) {
        return rows.stream().filter(r -> r.runId().equals(runId)).toList();
      }

      @Override
      public String name() {
        return "collecting";
      }
    };
    ObservabilityActor actor = ChatClientStubs.actor(metrics);

    actor.tell(AgentObservabilityEvent.runStarted("run-1", "tpl", "scn-a", "biz-42", "SEQUENTIAL", "{}"));
    actor.tell(AgentObservabilityEvent.stageCompleted("run-1", "research", "research", 0,
        List.of(), "SEQUENTIAL", "main", 100, 1, null, "{}", "{}"));

    until(() -> rows.size() == 1);
    assertThat(rows.get(0).scenarioId()).isEqualTo("scn-a");
  }
}
