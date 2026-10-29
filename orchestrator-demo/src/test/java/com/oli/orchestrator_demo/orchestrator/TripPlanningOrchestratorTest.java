/**
 * @ClassName TripPlanningOrchestratorTest
 * @Description Verifies the template-driven orchestration contract: the
 *              default template runs the agents in its defined order with
 *              cumulative context passing (regardless of bean order),
 *              concurrent mode runs independent stages in parallel from the
 *              same snapshot and merges their contributions, and unknown
 *              template references fail fast. It also verifies the emitted
 *              observability events: mode, wave index, parallel peers, the
 *              agent context JSON at every stage boundary and the failed-run
 *              bookkeeping, plus the process metrics recorded through
 *              {@link OrchestratorMetrics} (run/stage counters and the run
 *              duration timer).
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.orchestrator;

import static com.oli.orchestrator_demo.Await.until;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.oli.orchestrator_demo.agent.Agent;
import com.oli.orchestrator_demo.model.BudgetResponse;
import com.oli.orchestrator_demo.model.ItineraryResponse;
import com.oli.orchestrator_demo.model.OrchestratorContext;
import com.oli.orchestrator_demo.model.ResearchResponse;
import com.oli.orchestrator_demo.model.ReviewResponse;
import com.oli.orchestrator_demo.model.TripRequest;
import com.oli.orchestrator_demo.observability.AgentObservabilityEvent;
import com.oli.orchestrator_demo.observability.AgentObservabilityEvent.Type;
import com.oli.orchestrator_demo.observability.ObservabilityActor;
import com.oli.orchestrator_demo.observability.OrchestratorMetrics;
import com.oli.orchestrator_demo.observability.RunTimeline;
import com.oli.orchestrator_demo.observability.RunTimeline.RunStatus;
import com.oli.orchestrator_demo.workflow.ExecutionMode;
import com.oli.orchestrator_demo.workflow.WorkflowProperties;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.json.JsonMapper;

class TripPlanningOrchestratorTest {

  private static final ResearchResponse RESEARCH =
      new ResearchResponse(List.of("Senso-ji"), List.of("Buy a Suica card"), List.of("Ramen"));
  private static final BudgetResponse BUDGET = new BudgetResponse(
      new BigDecimal("5000"), new BigDecimal("2000"), new BigDecimal("3000"),
      new BigDecimal("1500"), new BigDecimal("11500"), true, List.of("Book early"));
  private static final ItineraryResponse ITINERARY = new ItineraryResponse(List.of(
      new ItineraryResponse.DayPlan(1, "Senso-ji", "Ueno Park", "Ramen tasting", new BigDecimal("250"))));
  private static final ReviewResponse REVIEW =
      new ReviewResponse(true, "A well balanced trip", List.of(), List.of("Book tickets in advance"));

  private final TripRequest request =
      new TripRequest("Tokyo", 5, new BigDecimal("15000"), "food and culture");

  /** Fake agent: records every invocation and returns its scripted contribution. */
  record ScriptedAgent(String name, List<String> order, List<OrchestratorContext> received,
      Function<OrchestratorContext, OrchestratorContext> action)
      implements Agent<OrchestratorContext, OrchestratorContext> {

    @Override
    public OrchestratorContext execute(OrchestratorContext context) {
      order.add(name);
      received.add(context);
      return action.apply(context);
    }
  }

  private static TripPlanningOrchestrator orchestrator(List<Agent<OrchestratorContext, OrchestratorContext>> agents,
      WorkflowProperties properties, ObservabilityActor observability) {
    return orchestrator(agents, properties, observability, new SimpleMeterRegistry());
  }

  private static TripPlanningOrchestrator orchestrator(List<Agent<OrchestratorContext, OrchestratorContext>> agents,
      WorkflowProperties properties, ObservabilityActor observability, SimpleMeterRegistry meters) {
    return new TripPlanningOrchestrator(agents, properties, observability,
        ObservationRegistry.create(), JsonMapper.builder().build(),
        new OrchestratorMetrics(meters, observability));
  }

  @Test
  void runsDefaultTemplateSequentiallyThroughThePipeline() {
    List<String> order = Collections.synchronizedList(new ArrayList<>());
    List<OrchestratorContext> received = Collections.synchronizedList(new ArrayList<>());
    ObservabilityActor observability = new ObservabilityActor();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();

    ScriptedAgent research = new ScriptedAgent("research", order, received,
        ctx -> ctx.withResearchResponse(RESEARCH));
    ScriptedAgent budget = new ScriptedAgent("budget", order, received,
        ctx -> ctx.withBudgetResponse(BUDGET));
    ScriptedAgent itinerary = new ScriptedAgent("itinerary", order, received,
        ctx -> ctx.withItineraryResponse(ITINERARY));
    ScriptedAgent review = new ScriptedAgent("review", order, received,
        ctx -> ctx.withReviewResponse(REVIEW));

    // The bean list is intentionally shuffled: the template defines the order.
    TripPlanningOrchestrator orchestrator =
        orchestrator(List.of(review, budget, research, itinerary), new WorkflowProperties(), observability, meters);

    OrchestratorContext result = orchestrator.planTrip(request);

    assertThat(order).containsExactly("research", "budget", "itinerary", "review");
    // Every stage receives the cumulative context of its predecessors.
    assertThat(received.get(0)).isEqualTo(OrchestratorContext.start(request));
    assertThat(received.get(1).researchResponse()).isEqualTo(RESEARCH);
    assertThat(received.get(2).budgetResponse()).isEqualTo(BUDGET);
    assertThat(received.get(3).itineraryResponse()).isEqualTo(ITINERARY);
    assertThat(result.reviewResponse()).isEqualTo(REVIEW);

    RunTimeline timeline = awaitTimeline(observability, RunStatus.COMPLETED);
    assertThat(timeline.templateName()).isEqualTo("default-trip-planning");
    assertThat(timeline.mode()).isEqualTo("SEQUENTIAL");
    assertThat(timeline.events()).extracting(AgentObservabilityEvent::type)
        .containsExactly(Type.RUN_STARTED,
            Type.STAGE_COMPLETED, Type.STAGE_COMPLETED, Type.STAGE_COMPLETED, Type.STAGE_COMPLETED,
            Type.RUN_COMPLETED);
    List<AgentObservabilityEvent> stageEvents = stageEvents(timeline);
    assertThat(stageEvents).extracting(AgentObservabilityEvent::stageName)
        .containsExactly("research", "budget", "itinerary", "review");
    assertThat(stageEvents).allSatisfy(event -> {
      assertThat(event.waveIndex()).isNotNull();
      assertThat(event.parallelPeers()).isEmpty();
      assertThat(event.durationMs()).isNotNull();
    });
    // The context JSON shows the state of the blackboard at every stage boundary.
    assertThat(stageEvents.get(0).inputContextJson()).contains("\"destination\":\"Tokyo\"");
    assertThat(stageEvents.get(0).outputContextJson()).contains("Senso-ji");
    assertThat(stageEvents.get(3).inputContextJson()).contains("Senso-ji");
    assertThat(timeline.finalContextJson()).contains("reviewResponse");

    // Process metrics: one run, four stage completions, one successful run timer.
    assertThat(meters.get(OrchestratorMetrics.RUNS_STARTED).tag("mode", "SEQUENTIAL").counter().count())
        .isEqualTo(1);
    assertThat(meters.get(OrchestratorMetrics.RUNS_COMPLETED).tag("mode", "SEQUENTIAL").counter().count())
        .isEqualTo(1);
    assertThat(meters.get(OrchestratorMetrics.RUN_DURATION).tag("outcome", "success").timer().count())
        .isEqualTo(1);
    assertThat(meters.get(OrchestratorMetrics.STAGES_COMPLETED).tag("mode", "SEQUENTIAL").counters()
        .stream().mapToDouble(Counter::count).sum()).isEqualTo(4.0);
    assertThat(meters.get(OrchestratorMetrics.STAGES_COMPLETED).tag("agent", "research").counter().count())
        .isEqualTo(1);
    assertThat(meters.get(OrchestratorMetrics.RETAINED_RUNS).gauge().value()).isEqualTo(1.0);
  }

  @Test
  void concurrentModeRunsIndependentStagesInParallelAndMergesTheirContributions() {
    List<String> order = Collections.synchronizedList(new ArrayList<>());
    List<OrchestratorContext> snapshots = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch researchStarted = new CountDownLatch(1);
    CountDownLatch budgetStarted = new CountDownLatch(1);
    ObservabilityActor observability = new ObservabilityActor();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();

    ScriptedAgent research = new ScriptedAgent("research", order, snapshots, ctx -> {
      researchStarted.countDown();
      await(budgetStarted);
      return ctx.withResearchResponse(RESEARCH);
    });
    ScriptedAgent budget = new ScriptedAgent("budget", order, snapshots, ctx -> {
      budgetStarted.countDown();
      await(researchStarted);
      return ctx.withBudgetResponse(BUDGET);
    });
    ScriptedAgent review = new ScriptedAgent("review", order, snapshots,
        ctx -> ctx.withReviewResponse(REVIEW));

    WorkflowProperties properties = new WorkflowProperties();
    properties.setMode(ExecutionMode.CONCURRENT);
    properties.getStages().add(new WorkflowProperties.StageDefinition() {{
      setName("research");
    }});
    properties.getStages().add(new WorkflowProperties.StageDefinition() {{
      setName("budget");
    }});
    properties.getStages().add(new WorkflowProperties.StageDefinition() {{
      setName("review");
      setDependsOn(List.of("research", "budget"));
    }});

    TripPlanningOrchestrator orchestrator =
        orchestrator(List.of(research, budget, review), properties, observability, meters);

    OrchestratorContext result = orchestrator.planTrip(request);

    // True parallelism: the rendezvous inside both stages succeeded, and the
    // final stage only ran once the whole first wave had completed.
    assertThat(order).containsExactlyInAnyOrder("research", "budget", "review");
    assertThat(order.indexOf("review")).isGreaterThan(order.indexOf("research"));
    assertThat(order.indexOf("review")).isGreaterThan(order.indexOf("budget"));
    // Both parallel stages started from the same snapshot (only the request).
    assertThat(snapshots.subList(0, 2)).allSatisfy(snapshot -> {
      assertThat(snapshot.request()).isEqualTo(request);
      assertThat(snapshot.researchResponse()).isNull();
      assertThat(snapshot.budgetResponse()).isNull();
    });
    // The contributions of the wave are merged into the final context.
    assertThat(result.researchResponse()).isEqualTo(RESEARCH);
    assertThat(result.budgetResponse()).isEqualTo(BUDGET);
    assertThat(result.reviewResponse()).isEqualTo(REVIEW);

    RunTimeline timeline = awaitTimeline(observability, RunStatus.COMPLETED);
    assertThat(timeline.mode()).isEqualTo("CONCURRENT");
    List<AgentObservabilityEvent> stageEvents = stageEvents(timeline);
    assertThat(stageEvents).extracting(AgentObservabilityEvent::stageName)
        .containsExactlyInAnyOrder("research", "budget", "review");
    AgentObservabilityEvent researchEvent = stageEvent(stageEvents, "research");
    AgentObservabilityEvent budgetEvent = stageEvent(stageEvents, "budget");
    AgentObservabilityEvent reviewEvent = stageEvent(stageEvents, "review");
    // Wave 0 = research || budget (each sees the other as its parallel peer),
    // wave 1 = review alone.
    assertThat(researchEvent.waveIndex()).isZero();
    assertThat(researchEvent.parallelPeers()).containsExactly("budget");
    assertThat(budgetEvent.waveIndex()).isZero();
    assertThat(budgetEvent.parallelPeers()).containsExactly("research");
    assertThat(reviewEvent.waveIndex()).isEqualTo(1);
    assertThat(reviewEvent.parallelPeers()).isEmpty();

    // Concurrent stage completions are counted with the CONCURRENT mode tag.
    assertThat(meters.get(OrchestratorMetrics.STAGES_COMPLETED).tag("mode", "CONCURRENT").counters()
        .stream().mapToDouble(Counter::count).sum()).isEqualTo(3.0);
    assertThat(meters.get(OrchestratorMetrics.RUNS_STARTED).tag("template", "configured").counter().count())
        .isEqualTo(1);
  }

  @Test
  void failedStageIsRecordedAndTheRunMarkedFailed() {
    ObservabilityActor observability = new ObservabilityActor();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    WorkflowProperties properties = new WorkflowProperties();
    properties.getStages().add(new WorkflowProperties.StageDefinition() {{
      setName("research");
    }});
    TripPlanningOrchestrator orchestrator = orchestrator(
        List.of(new ScriptedAgent("research", new ArrayList<>(), new ArrayList<>(),
            ctx -> {
              throw new IllegalStateException("boom");
            })),
        properties, observability, meters);

    assertThatThrownBy(() -> orchestrator.planTrip(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("boom");

    RunTimeline timeline = awaitTimeline(observability, RunStatus.FAILED);
    assertThat(timeline.mode()).isEqualTo("SEQUENTIAL");
    assertThat(timeline.events()).extracting(AgentObservabilityEvent::type)
        .containsExactly(Type.RUN_STARTED, Type.STAGE_FAILED, Type.RUN_FAILED);
    assertThat(timeline.events().get(1).errorMessage()).isEqualTo("boom");

    // The failed run and its failed stage are both counted, plus the failure timer.
    assertThat(meters.get(OrchestratorMetrics.RUNS_FAILED).tag("mode", "SEQUENTIAL").counter().count())
        .isEqualTo(1);
    assertThat(meters.get(OrchestratorMetrics.RUN_DURATION).tag("outcome", "failure").timer().count())
        .isEqualTo(1);
    assertThat(meters.get(OrchestratorMetrics.STAGES_FAILED).tag("agent", "research").counter().count())
        .isEqualTo(1);
  }

  @Test
  void unknownAgentInTemplateFailsFast() {
    WorkflowProperties properties = new WorkflowProperties();
    properties.getStages().add(new WorkflowProperties.StageDefinition() {{
      setName("ghost");
    }});

    assertThatThrownBy(() -> new TripPlanningOrchestrator(
        List.of(new ScriptedAgent("research", new ArrayList<>(), new ArrayList<>(), Function.identity())),
        properties, new ObservabilityActor(), ObservationRegistry.create(),
        JsonMapper.builder().build(),
        new OrchestratorMetrics(new SimpleMeterRegistry(), new ObservabilityActor())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ghost");
  }

  @Test
  void concurrentWaveFailureCancelsStragglersAndReturnsPromptly() {
    WorkflowProperties properties = new WorkflowProperties();
    properties.setMode(ExecutionMode.CONCURRENT);
    properties.getStages().add(new WorkflowProperties.StageDefinition() {{
      setName("boom");
    }});
    properties.getStages().add(new WorkflowProperties.StageDefinition() {{
      setName("slow");
    }});

    List<String> order = Collections.synchronizedList(new ArrayList<>());
    List<OrchestratorContext> received = Collections.synchronizedList(new ArrayList<>());
    ObservabilityActor observability = new ObservabilityActor();
    ScriptedAgent boom = new ScriptedAgent("boom", order, received,
        ctx -> {
          throw new IllegalStateException("boom");
        });
    // Blocks forever on purpose; the cancellation must interrupt it instead
    // of the caller waiting for it at executor.close().
    ScriptedAgent slow = new ScriptedAgent("slow", order, received, ctx -> {
      awaitQuietly();
      return ctx;
    });

    TripPlanningOrchestrator orchestrator = orchestrator(List.of(boom, slow), properties, observability);

    long startedAt = System.currentTimeMillis();
    assertThatThrownBy(() -> orchestrator.planTrip(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("boom");
    assertThat(System.currentTimeMillis() - startedAt).isLessThan(5000);
  }

  private static RunTimeline awaitTimeline(ObservabilityActor observability, RunStatus expectedStatus) {
    until(() -> observability.recentRuns().stream()
        .findFirst()
        .map(t -> t.status() == expectedStatus)
        .orElse(false));
    return observability.recentRuns().get(0);
  }

  private static List<AgentObservabilityEvent> stageEvents(RunTimeline timeline) {
    return timeline.events().stream()
        .filter(event -> event.type() == Type.STAGE_COMPLETED || event.type() == Type.STAGE_FAILED)
        .toList();
  }

  private static AgentObservabilityEvent stageEvent(List<AgentObservabilityEvent> events, String stageName) {
    return events.stream()
        .filter(event -> stageName.equals(event.stageName()))
        .findFirst()
        .orElseThrow();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("Stage was not executed concurrently");
      }
    }
    catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(ex);
    }
  }

  /** Blocks the calling (virtual) thread until interrupted by the wave cancellation. */
  private static void awaitQuietly() {
    try {
      TimeUnit.MINUTES.sleep(10);
    }
    catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
