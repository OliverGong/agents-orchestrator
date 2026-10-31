/**
 * @ClassName AbstractContextAgentTest
 * @Description Verifies the parse-failure retry contract of the agent base
 *              class: a {@link JacksonException} from the model output is
 *              retried once, and only rethrown when every attempt failed.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.oli.orchestrator_demo.control.AgentDescriptor;
import com.oli.orchestrator_demo.model.OrchestratorContext;
import com.oli.orchestrator_demo.model.ResearchResponse;
import com.oli.orchestrator_demo.model.TripRequest;
import com.oli.orchestrator_demo.observability.AgentObservabilityEvent;
import com.oli.orchestrator_demo.observability.ObservabilityEventBus;
import com.oli.orchestrator_demo.observability.RunContext;
import tools.jackson.core.exc.StreamReadException;

class AbstractContextAgentTest {

  private final TripRequest request =
      new TripRequest("Tokyo", 5, new BigDecimal("15000"), "food and culture");

  static final class FlakyResearchAgent extends AbstractContextAgent<ResearchResponse> {

    final AtomicInteger attempts = new AtomicInteger();
    final int failuresBeforeSuccess;

    FlakyResearchAgent(int failuresBeforeSuccess) {
      super("Flaky-Research-Agent", null, null);
      this.failuresBeforeSuccess = failuresBeforeSuccess;
    }

    @Override
    public AgentDescriptor descriptor() {
      return new AgentDescriptor(name(), "Flaky Research", "",
          List.of("researchResponse"), List.of(), List.of(), null, null);
    }

    @Override
    protected ResearchResponse respond(OrchestratorContext context) {
      if (attempts.incrementAndGet() <= failuresBeforeSuccess) {
        throw new StreamReadException("Unexpected character ('`' (code 96))");
      }
      return new ResearchResponse(List.of("Senso-ji"), List.of("Buy a Suica card"), List.of("Ramen"));
    }

    @Override
    protected OrchestratorContext merge(OrchestratorContext context, ResearchResponse response) {
      return context.withResearchResponse(response);
    }
  }

  @Test
  void retriesOnceAndSucceedsWhenSecondAttemptParses() {
    FlakyResearchAgent agent = new FlakyResearchAgent(1);
    OrchestratorContext start = OrchestratorContext.start(request);

    OrchestratorContext result = agent.execute(start);

    assertThat(agent.attempts.get()).isEqualTo(AbstractContextAgent.MAX_ATTEMPTS);
    assertThat(result.researchResponse().attractions()).containsExactly("Senso-ji");
  }

  @Test
  void rethrowsAfterExhaustingAllAttempts() {
    FlakyResearchAgent agent = new FlakyResearchAgent(Integer.MAX_VALUE);
    OrchestratorContext start = OrchestratorContext.start(request);

    assertThatThrownBy(() -> agent.execute(start))
        .isInstanceOf(StreamReadException.class)
        .hasMessageContaining("Unexpected character");

    assertThat(agent.attempts.get()).isEqualTo(AbstractContextAgent.MAX_ATTEMPTS);
  }

  @Test
  void firstCleanAttemptDoesNotRetry() {
    FlakyResearchAgent agent = new FlakyResearchAgent(0);
    OrchestratorContext start = OrchestratorContext.start(request);

    agent.execute(start);

    assertThat(agent.attempts.get()).isEqualTo(1);
  }

  @Test
  void publishesARetryEventAttributedToTheRunningStage() {
    List<AgentObservabilityEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    ObservabilityEventBus.subscribe(events::add);
    RunContext.set(new RunContext.Attribution("run-1", "research", "research", 0));
    try {
      FlakyResearchAgent agent = new FlakyResearchAgent(1);
      agent.execute(OrchestratorContext.start(request));

      assertThat(events).hasSize(1);
      AgentObservabilityEvent event = events.get(0);
      assertThat(event.type()).isEqualTo(AgentObservabilityEvent.Type.STAGE_RETRIED);
      assertThat(event.runId()).isEqualTo("run-1");
      assertThat(event.stageName()).isEqualTo("research");
      assertThat(event.agentName()).isEqualTo("research");
      assertThat(event.attempt()).isEqualTo(1);
      assertThat(event.errorMessage()).contains("attempt 1");
    }
    finally {
      RunContext.clear();
    }
  }

  @Test
  void dropsTheRetryEventOutsideAWorkflowRun() {
    List<AgentObservabilityEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    ObservabilityEventBus.subscribe(events::add);

    new FlakyResearchAgent(1).execute(OrchestratorContext.start(request));

    assertThat(events).isEmpty();
  }
}
