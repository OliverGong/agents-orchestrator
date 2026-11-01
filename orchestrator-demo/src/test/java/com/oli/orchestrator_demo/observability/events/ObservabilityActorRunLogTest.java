/**
 * @ClassName ObservabilityActorRunLogTest
 * @Description Verifies the actor's write-ahead run-log sink: every lifecycle
 *              / stage / tool event lands in the {@link TeamLogStore} with
 *              RUN attribution (level, action, run/scenario ids, token sums in
 *              the message), a failing log store never breaks the timeline and
 *              the 2-argument constructor keeps working against the noop
 *              backend.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.observability.events;
import com.oli.orchestrator_demo.Await;
import com.oli.orchestrator_demo.observability.infra.NoopRunMetricsStore;

import static com.oli.orchestrator_demo.Await.until;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.oli.orchestrator_demo.ChatClientStubs;
import com.oli.orchestrator_demo.observability.events.AgentObservabilityEvent.LmUsage;
import com.oli.orchestrator_demo.observability.events.RunTimeline.RunStatus;
import com.oli.orchestrator_demo.teamlog.TeamLogEntry;
import com.oli.orchestrator_demo.teamlog.TeamLogStore;

import tools.jackson.databind.json.JsonMapper;

class ObservabilityActorRunLogTest {

  /** Collecting fake so assertions can run without a database. */
  private static final class CollectingLogStore implements TeamLogStore {
    final List<TeamLogEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void append(TeamLogEntry entry) {
      entries.add(entry);
    }

    @Override
    public List<TeamLogEntry> search(TeamLogQuery query) {
      return List.of();
    }

    @Override
    public String name() {
      return "collecting";
    }
  }

  private ObservabilityActor actorWithLog(TeamLogStore logStore) {
    return new ObservabilityActor(new ObservabilitySsePublisher(JsonMapper.builder().build()),
        new NoopRunMetricsStore(), logStore, new ChatClientStubs.CollectingInstanceStore());
  }

  @Test
  void persistsRunLogEntriesForEveryEventType() {
    CollectingLogStore logStore = new CollectingLogStore();
    ObservabilityActor actor = actorWithLog(logStore);

    actor.tell(AgentObservabilityEvent.runStarted("run-1", "parallel-trip-planning", "scn-a", null,
        "CONCURRENT", "{}"));
    actor.tell(AgentObservabilityEvent.toolCalled("run-1", "research", "research", 0,
        "weather", "getForecast", 120, "sunny 20C", null));
    actor.tell(AgentObservabilityEvent.stageCompleted("run-1", "research", "research", 0,
        List.of(), "CONCURRENT", "main", 100, 1, new LmUsage(120, 45, 165, "qwen3:8b", 1),
        "{\"in\":1}", "{\"out\":1}"));
    actor.tell(AgentObservabilityEvent.stageFailed("run-1", "budget", "budget", 0,
        List.of(), "CONCURRENT", "main", 50, "model boom", 1, null, "{\"in\":2}"));
    actor.tell(AgentObservabilityEvent.runCompleted("run-1", "{}"));

    until(() -> logStore.entries.size() == 5);
    until(() -> actor.timeline("run-1").map(t -> t.status() == RunStatus.COMPLETED).orElse(false));

    TeamLogEntry started = logStore.entries.get(0);
    assertThat(started.category()).isEqualTo(TeamLogEntry.RUN);
    assertThat(started.level()).isEqualTo(TeamLogEntry.INFO);
    assertThat(started.action()).isEqualTo("RUN_STARTED");
    assertThat(started.runId()).isEqualTo("run-1");
    assertThat(started.scenarioId()).isEqualTo("scn-a");
    assertThat(started.message()).contains("parallel-trip-planning").contains("CONCURRENT");

    TeamLogEntry tool = logStore.entries.get(1);
    assertThat(tool.message()).contains("weather/getForecast").contains("OK");
    assertThat(tool.scenarioId()).isEqualTo("scn-a"); // attribution rides along

    TeamLogEntry completedStage = logStore.entries.get(2);
    assertThat(completedStage.message()).contains("tokens 165").contains("prompt 120");

    TeamLogEntry failedStage = logStore.entries.get(3);
    assertThat(failedStage.level()).isEqualTo(TeamLogEntry.ERROR);
    assertThat(failedStage.message()).contains("model boom");

    TeamLogEntry finished = logStore.entries.get(4);
    assertThat(finished.action()).isEqualTo("RUN_COMPLETED");
  }

  @Test
  void failingLogStoreNeverBreaksTheTimeline() {
    TeamLogStore failing = new TeamLogStore() {
      @Override
      public void append(TeamLogEntry entry) {
        throw new IllegalStateException("log sink down");
      }

      @Override
      public List<TeamLogEntry> search(TeamLogQuery query) {
        return List.of();
      }

      @Override
      public String name() {
        return "failing";
      }
    };
    ObservabilityActor actor = actorWithLog(failing);

    actor.tell(AgentObservabilityEvent.runStarted("run-1", "tpl", "scn-a", null, "SEQUENTIAL", "{}"));

    until(() -> actor.timeline("run-1").isPresent());
    assertThat(actor.recentRuns()).hasSize(1);
  }
}
