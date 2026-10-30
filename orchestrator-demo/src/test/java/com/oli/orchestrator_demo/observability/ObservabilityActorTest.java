/**
 * @ClassName ObservabilityActorTest
 * @Description Verifies the actor's event folding: run timelines are built
 *              from RUN_STARTED and updated by stage/run events, failed runs
 *              are marked as such, events for unknown runs are dropped and
 *              runs beyond the retention cap are evicted oldest first.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.observability;

import static com.oli.orchestrator_demo.Await.until;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.oli.orchestrator_demo.ChatClientStubs;
import com.oli.orchestrator_demo.observability.AgentObservabilityEvent.Type;
import com.oli.orchestrator_demo.observability.RunTimeline.RunStatus;

class ObservabilityActorTest {

  private final ObservabilityActor actor = ChatClientStubs.actor();

  @Test
  void foldsEventsIntoARunTimeline() {
    actor.tell(AgentObservabilityEvent.runStarted("run-1", "default-trip-planning", "SEQUENTIAL"));
    actor.tell(AgentObservabilityEvent.stageCompleted("run-1", "research", "research", 0,
        List.of(), "SEQUENTIAL", "main", 5, "{\"in\":1}", "{\"out\":1}"));
    actor.tell(AgentObservabilityEvent.runCompleted("run-1", "{\"final\":true}"));

    until(() -> actor.timeline("run-1").map(t -> t.status() == RunStatus.COMPLETED).orElse(false));

    RunTimeline timeline = actor.timeline("run-1").orElseThrow();
    assertThat(timeline.templateName()).isEqualTo("default-trip-planning");
    assertThat(timeline.mode()).isEqualTo("SEQUENTIAL");
    assertThat(timeline.finalContextJson()).isEqualTo("{\"final\":true}");
    assertThat(timeline.events()).extracting(AgentObservabilityEvent::type)
        .containsExactly(Type.RUN_STARTED, Type.STAGE_COMPLETED, Type.RUN_COMPLETED);
  }

  @Test
  void marksRunsFailedWhenAStageFails() {
    actor.tell(AgentObservabilityEvent.runStarted("run-2", "tpl", "CONCURRENT"));
    actor.tell(AgentObservabilityEvent.stageFailed("run-2", "budget", "budget", 1,
        List.of("itinerary"), "CONCURRENT", "vt", 3, "boom", "{\"in\":1}"));
    actor.tell(AgentObservabilityEvent.runFailed("run-2", "boom"));

    until(() -> actor.timeline("run-2").map(t -> t.status() == RunStatus.FAILED).orElse(false));

    RunTimeline timeline = actor.timeline("run-2").orElseThrow();
    assertThat(timeline.completedAt()).isNotNull();
    assertThat(timeline.events()).extracting(AgentObservabilityEvent::type)
        .containsExactly(Type.RUN_STARTED, Type.STAGE_FAILED, Type.RUN_FAILED);
    assertThat(timeline.events().get(1).errorMessage()).isEqualTo("boom");
    assertThat(timeline.events().get(1).parallelPeers()).containsExactly("itinerary");
  }

  @Test
  void dropsEventsForUnknownRuns() {
    actor.tell(AgentObservabilityEvent.stageCompleted("ghost", "research", "research", 0,
        List.of(), "SEQUENTIAL", "main", 1, "{}", "{}"));

    // Nothing may ever be stored for a run that was never started.
    assertThat(actor.recentRuns()).isEmpty();
  }

  @Test
  void evictsOldestRunsBeyondRetention() {
    for (int i = 0; i < ObservabilityActor.MAX_RETAINED_RUNS + 50; i++) {
      actor.tell(AgentObservabilityEvent.runStarted("run-" + i, "tpl", "SEQUENTIAL"));
    }

    until(() -> actor.timeline("run-" + (ObservabilityActor.MAX_RETAINED_RUNS + 49)).isPresent());
    until(() -> actor.recentRuns().size() == ObservabilityActor.MAX_RETAINED_RUNS);

    assertThat(actor.timeline("run-0")).isEmpty();
    assertThat(actor.timeline("run-49")).isEmpty();
    assertThat(actor.timeline("run-50")).isPresent();
  }
}
