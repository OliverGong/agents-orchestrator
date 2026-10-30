/**
 * @ClassName TripPlanningOrchestrator
 * @Description Template-driven orchestrator: it executes the stages of the
 *              {@link WorkflowTemplate collaboration template} (bound from
 *              `orchestrator.workflow.*`, see {@link WorkflowProperties}) on
 *              the shared abstract {@link OrchestratorContext}. Depending on
 *              the template's {@link ExecutionMode}, stages run sequentially
 *              or, when their dependencies allow it, concurrently on virtual
 *              threads; concurrent contributions are merged into the wave
 *              snapshot afterwards. The orchestrator knows nothing about the
 *              concrete trip planning agents — it only resolves agents by
 *              name, so new agents/templates require no orchestrator change.
 *
 *              Every run is also observable end to end: a Micrometer
 *              {@code orchestrator.planTrip} observation becomes the parent
 *              span of all agent spans (propagated into the virtual threads of
 *              concurrent waves via {@link ContextSnapshot}), and the run is
 *              reported to the {@link ObservabilityActor} as events that carry
 *              the mode, wave index, parallel peers and the agent context as
 *              JSON at every stage boundary, and run/stage process metrics are
 *              recorded through {@link OrchestratorMetrics} for
 *              {@code /actuator/prometheus}.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.orchestrator;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.oli.orchestrator_demo.agent.Agent;
import com.oli.orchestrator_demo.model.OrchestratorContext;
import com.oli.orchestrator_demo.model.TripRequest;
import com.oli.orchestrator_demo.observability.AgentObservabilityEvent;
import com.oli.orchestrator_demo.observability.ObservabilityActor;
import com.oli.orchestrator_demo.observability.OrchestratorMetrics;
import com.oli.orchestrator_demo.workflow.ExecutionMode;
import com.oli.orchestrator_demo.workflow.StageSpec;
import com.oli.orchestrator_demo.workflow.WorkflowTemplate;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.ObjectMapper;

@Service
public class TripPlanningOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(TripPlanningOrchestrator.class);

  private static final int MAX_CONTEXT_JSON_LENGTH = 20_000;

  private final WorkflowTemplateHolder templateHolder;
  private final Map<String, Agent<OrchestratorContext, OrchestratorContext>> agents;
  private final ObservabilityActor observability;
  private final ObservationRegistry observationRegistry;
  private final ObjectMapper objectMapper;
  private final OrchestratorMetrics orchestratorMetrics;

  public TripPlanningOrchestrator(List<Agent<OrchestratorContext, OrchestratorContext>> agents,
      WorkflowTemplateHolder templateHolder, ObservabilityActor observability,
      ObservationRegistry observationRegistry, ObjectMapper objectMapper,
      OrchestratorMetrics orchestratorMetrics) {
    // Insertion-ordered so template()/agentNames() report agents in declaration order.
    Map<String, Agent<OrchestratorContext, OrchestratorContext>> resolved = new LinkedHashMap<>();
    for (Agent<OrchestratorContext, OrchestratorContext> agent : agents) {
      if (resolved.putIfAbsent(agent.name(), agent) != null) {
        throw new IllegalStateException("Duplicate agent name: " + agent.name());
      }
    }
    this.agents = Collections.unmodifiableMap(resolved);
    this.templateHolder = templateHolder;
    // Fail fast at startup if the initial template does not match the agents.
    this.templateHolder.get().validate(this.agents.keySet());
    this.observability = observability;
    this.observationRegistry = observationRegistry;
    this.objectMapper = objectMapper;
    this.orchestratorMetrics = orchestratorMetrics;
    log.info("Workflow template '{}' ({}): {}", template().name(), template().mode(),
        template().stages().stream()
            .map(stage -> stage.name() + stage.dependsOn())
            .toList());
  }

  /** The currently active collaboration template (hot-swappable via the control plane). */
  public WorkflowTemplate template() {
    return templateHolder.get();
  }

  /** Names of the registered agents, in declaration order (for Actuator health/info). */
  public Set<String> agentNames() {
    return agents.keySet();
  }

  /**
   * Plans a trip by executing the collaboration template: the stages run wave
   * by wave, sequentially or concurrently as configured. Each agent reads
   * what its dependencies contributed and adds its own response to the shared
   * context. The run is traced (span + per-agent child spans) and reported to
   * the observability actor as a replayable event timeline.
   */
  public OrchestratorContext planTrip(TripRequest request) {
    String runId = UUID.randomUUID().toString();
    // Read once per run: a control-plane template swap takes effect on the
    // next request without disturbing the ones already executing.
    WorkflowTemplate template = templateHolder.get();
    log.info("Planning trip for request: {} (run {})", request, runId);

    long workflowStartTime = System.currentTimeMillis();

    Observation run = Observation.createNotStarted("orchestrator.planTrip", observationRegistry)
        .lowCardinalityKeyValue("template", template.name())
        .lowCardinalityKeyValue("mode", template.mode().name())
        .start();
    observability.tell(AgentObservabilityEvent.runStarted(runId, template.name(), template.mode().name()));
    orchestratorMetrics.runStarted(template.name(), template.mode().name());
    try (Observation.Scope scope = run.openScope()) {
      // Captured once inside the scope so every concurrent wave task inherits
      // this run's span as its parent (no orphan spans in the trace waterfall).
      ContextSnapshot propagation = ContextSnapshot.captureAll();
      OrchestratorContext context = OrchestratorContext.start(request);
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<List<StageSpec>> waves = template.waves();
        for (int waveIndex = 0; waveIndex < waves.size(); waveIndex++) {
          context = executeWave(runId, template.mode(), context, waves.get(waveIndex), waveIndex, propagation,
              executor);
        }
      }
      OrchestratorContext finalContext = context.end();
      observability.tell(AgentObservabilityEvent.runCompleted(runId, toJson(finalContext)));
      long elapsed = System.currentTimeMillis() - workflowStartTime;
      orchestratorMetrics.runCompleted(template.name(), template.mode().name(), Duration.ofMillis(elapsed));
      log.info("Trip planning workflow completed in {} ms", elapsed);
      log.info("Trip planning response: {}", finalContext);
      return finalContext;
    }
    catch (RuntimeException ex) {
      observability.tell(AgentObservabilityEvent.runFailed(runId, describe(ex)));
      orchestratorMetrics.runFailed(template.name(), template.mode().name(),
          Duration.ofMillis(System.currentTimeMillis() - workflowStartTime));
      run.error(ex);
      throw ex;
    }
    finally {
      run.stop();
    }
  }

  private OrchestratorContext executeWave(String runId, ExecutionMode mode, OrchestratorContext context,
      List<StageSpec> wave, int waveIndex, ContextSnapshot propagation, ExecutorService executor) {
    if (mode == ExecutionMode.CONCURRENT && wave.size() > 1) {
      return executeWaveConcurrently(runId, mode, context, wave, waveIndex, propagation, executor);
    }
    OrchestratorContext current = context;
    for (StageSpec stage : wave) {
      current = executeStage(runId, mode, current, stage, waveIndex, parallelPeers(wave, stage));
    }
    return current;
  }

  /**
   * Every stage of the wave starts from the same context snapshot; the
   * contributions are merged back in template definition order (first
   * application wins for a conflicting slot), which keeps the result
   * deterministic regardless of completion order. The captured
   * {@link ContextSnapshot} makes each task run under the run's observation,
   * so its agent spans stay children of the orchestrator span.
   */
  private OrchestratorContext executeWaveConcurrently(String runId, ExecutionMode mode, OrchestratorContext snapshot,
      List<StageSpec> wave, int waveIndex, ContextSnapshot propagation, ExecutorService executor) {
    List<CompletableFuture<OrchestratorContext>> futures = wave.stream()
        .map(stage -> CompletableFuture.supplyAsync(
            () -> executeStage(runId, mode, snapshot, stage, waveIndex, parallelPeers(wave, stage)),
            propagation.wrapExecutor(executor)))
        .toList();
    OrchestratorContext merged = snapshot;
    for (int i = 0; i < wave.size(); i++) {
      try {
        merged = merged.mergeFrom(futures.get(i).join());
      }
      catch (CompletionException ex) {
        // Fail fast: the caller must receive the error immediately instead of
        // waiting for stragglers at executor.close(). CompletableFuture.cancel
        // does NOT interrupt running tasks, so shut the executor down now —
        // sibling model calls get interrupted and unwind quickly.
        executor.shutdownNow();
        throw rethrownCause(ex);
      }
    }
    return merged;
  }

  private OrchestratorContext executeStage(String runId, ExecutionMode mode, OrchestratorContext context,
      StageSpec stage, int waveIndex, List<String> parallelPeers) {
    Agent<OrchestratorContext, OrchestratorContext> agent = agents.get(stage.agent());
    log.info("Executing stage '{}' -> agent '{}'", stage.name(), agent.name());
    long startTime = System.currentTimeMillis();
    String threadName = Thread.currentThread().getName();
    try {
      OrchestratorContext output = agent.execute(context);
      observability.tell(AgentObservabilityEvent.stageCompleted(runId, stage.name(), agent.name(), waveIndex,
          parallelPeers, mode.name(), threadName, System.currentTimeMillis() - startTime,
          toJson(context), toJson(output)));
      orchestratorMetrics.stageCompleted(agent.name(), mode.name());
      return output;
    }
    catch (RuntimeException ex) {
      observability.tell(AgentObservabilityEvent.stageFailed(runId, stage.name(), agent.name(), waveIndex,
          parallelPeers, mode.name(), threadName, System.currentTimeMillis() - startTime,
          describe(ex), toJson(context)));
      orchestratorMetrics.stageFailed(agent.name(), mode.name());
      throw ex;
    }
  }

  private static List<String> parallelPeers(List<StageSpec> wave, StageSpec stage) {
    return wave.stream()
        .map(StageSpec::name)
        .filter(name -> !name.equals(stage.name()))
        .toList();
  }

  /** Unwraps the future so failure semantics match the sequential execution. */
  private static RuntimeException rethrownCause(CompletionException ex) {
    return switch (ex.getCause()) {
      case RuntimeException runtime -> runtime;
      case Error error -> {
        throw error;
      }
      default -> ex;
    };
  }

  private String toJson(Object value) {
    try {
      String json = objectMapper.writeValueAsString(value);
      return json.length() <= MAX_CONTEXT_JSON_LENGTH
          ? json
          : json.substring(0, MAX_CONTEXT_JSON_LENGTH) + "...[truncated]";
    }
    catch (RuntimeException ex) {
      return String.valueOf(value);
    }
  }

  private static String describe(RuntimeException ex) {
    return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
  }
}
