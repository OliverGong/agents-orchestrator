/**
 * @ClassName ObservabilityActor
 * @Description Actor-style in-memory observability store. Producers never
 *              touch state — they {@code tell(...)} an event into a bounded
 *              mailbox and move on, which keeps the hot path (agent execution)
 *              free of blocking writes. A single dedicated consumer thread is
 *              the only state owner: it folds events into per-run
 *              {@link RunTimeline}s (evicting the oldest runs beyond the
 *              retention cap), so no other synchronization is needed and
 *              readers only ever see immutable snapshots. This complements the
 *              distributed view (OpenTelemetry traces in Grafana Tempo,
 *              metrics in Prometheus) with a queryable, per-run view of the
 *              whole agent context exposed via
 *              {@code GET /api/trips/runs/{runId}}.
 *
 *              Since v2.2 the actor is also the write-ahead sink for the
 *              durable stage metrics: every stage event lands in the
 *              {@link RunMetricsStore} <em>before</em> the timeline fold and
 *              the SSE fan-out, so the per-scenario LM history (durations,
 *              retries, token consumption) survives service restarts; a store
 *              failure degrades to a warning, never to a lost timeline. The
 *              scenario attribution recorded with each row comes from the
 *              run's {@code RUN_STARTED} event.
 *
 *              Since v2.3 the actor additionally drives the durable
 *              instance registry through the {@link RunInstanceSink} port in
 *              the same write-ahead order: the business
 *              trigger (request entry with its business id and payload)
 *              inserts the RUNNING instance row, and the terminal events move
 *              it to COMPLETED / FAILED — the trigger-to-outcome correlation
 *              that survives beyond the in-memory retention cap and service
 *              restarts. Store failures degrade to warnings, exactly like the
 *              other write-ahead sinks.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 2.3
 */
package com.oli.orchestrator_demo.ops.observability.events;
import com.oli.orchestrator_demo.ops.observability.metrics.RunMetricsStore;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.oli.orchestrator_demo.kernel.contract.port.runtime.RunInstanceSink;
import com.oli.orchestrator_demo.kernel.contract.model.run.RunInstance;
import com.oli.orchestrator_demo.governance.teamlog.infra.NoopTeamLogStore;
import com.oli.orchestrator_demo.governance.teamlog.TeamLogEntry;
import com.oli.orchestrator_demo.governance.teamlog.TeamLogStore;

import jakarta.annotation.PreDestroy;

@Component
public class ObservabilityActor {

  private static final Logger log = LoggerFactory.getLogger(ObservabilityActor.class);

  static final int MAX_RETAINED_RUNS = 100;
  private static final int MAILBOX_CAPACITY = 10_000;
  private static final long POLL_TIMEOUT_MS = 200;

  private final LinkedBlockingQueue<AgentObservabilityEvent> mailbox = new LinkedBlockingQueue<>(MAILBOX_CAPACITY);
  private final Map<String, RunTimeline> timelines = new ConcurrentHashMap<>();
  /** Actor-thread-only insertion order used to evict the oldest runs. */
  private final ArrayDeque<String> insertionOrder = new ArrayDeque<>();
  /** Actor-thread-only run attribution (from RUN_STARTED): scenario + business id. */
  private final Map<String, RunMeta> runMetas = new HashMap<>();
  private final ObservabilitySsePublisher ssePublisher;
  private final RunMetricsStore metricsStore;
  private final TeamLogStore teamLogStore;
  private final RunInstanceSink instanceSink;
  private final Thread actorThread = Thread.ofVirtual().name("observability-actor").start(this::consume);
  private volatile boolean accepting = true;

  /** Per-run attribution captured at request entry (RUN_STARTED). */
  private record RunMeta(String scenarioId, String businessId) {
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ObservabilityActor(ObservabilitySsePublisher ssePublisher, RunMetricsStore metricsStore,
      TeamLogStore teamLogStore, RunInstanceSink instanceSink) {
    this.ssePublisher = ssePublisher;
    this.metricsStore = metricsStore;
    this.teamLogStore = teamLogStore;
    this.instanceSink = instanceSink;
    // Retry events are published from inside agent calls (no bean access);
    // the static bus routes them into the same mailbox as direct producers.
    ObservabilityEventBus.subscribe(this::tell);
  }

  /** Fire-and-forget mailbox send: never blocks the producer. */
  public void tell(AgentObservabilityEvent event) {
    if (!accepting || !mailbox.offer(event)) {
      log.warn("Observability event dropped (mailbox full or actor shut down): {}", event.type());
    }
  }

  public Optional<RunTimeline> timeline(String runId) {
    return Optional.ofNullable(timelines.get(runId));
  }

  /** All retained runs, newest first. */
  public List<RunTimeline> recentRuns() {
    return timelines.values().stream()
        .sorted(Comparator.comparing(RunTimeline::startedAt).reversed())
        .toList();
  }

  /** Number of run timelines currently retained (exposed as a Micrometer gauge). */
  public int retainedRuns() {
    return timelines.size();
  }

  @PreDestroy
  void shutdown() throws InterruptedException {
    accepting = false;
    actorThread.join(TimeUnit.SECONDS.toMillis(5));
  }

  private void consume() {
    while (accepting || !mailbox.isEmpty()) {
      try {
        AgentObservabilityEvent event = mailbox.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (event != null) {
          apply(event);
        }
      }
      catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /** Single-threaded state transition: the whole mutable state lives here. */
  private void apply(AgentObservabilityEvent event) {
    recordLog(event);
    recordMetrics(event);
    if (event.type() == AgentObservabilityEvent.Type.RUN_STARTED) {
      recordInstanceStart(event);
      insertionOrder.addLast(event.runId());
      runMetas.put(event.runId(), new RunMeta(event.scenarioId(), event.businessId()));
      while (insertionOrder.size() > MAX_RETAINED_RUNS) {
        String evicted = insertionOrder.pollFirst();
        timelines.remove(evicted);
        runMetas.remove(evicted);
      }
    }
    else if (event.type() == AgentObservabilityEvent.Type.RUN_COMPLETED
        || event.type() == AgentObservabilityEvent.Type.RUN_FAILED) {
      recordInstanceFinish(event);
      runMetas.remove(event.runId());
    }
    timelines.compute(event.runId(), (runId, timeline) -> fold(runId, timeline, event));
    // Forwarded from the actor thread, so SSE clients observe the same
    // per-run event order the timeline was folded with.
    ssePublisher.publish(event);
  }

  /**
   * Write-ahead stage metrics: persisted before the fold and the SSE push. A
   * failing store degrades to a warning — observability persistence must
   * never break the run timeline.
   */
  private void recordMetrics(AgentObservabilityEvent event) {
    if (event.type() != AgentObservabilityEvent.Type.STAGE_COMPLETED
        && event.type() != AgentObservabilityEvent.Type.STAGE_FAILED) {
      return;
    }
    // Ghost stage events (no RUN_STARTED) are dropped by the fold — keep the
    // durable rows consistent with the timelines.
    if (!timelines.containsKey(event.runId())) {
      return;
    }
    try {
      AgentObservabilityEvent.LmUsage usage = event.usage();
      RunMeta meta = runMetas.get(event.runId());
      metricsStore.record(new RunMetricsStore.RunStageMetrics(
          event.runId(), meta == null ? null : meta.scenarioId(), event.stageName(), event.agentName(),
          usage == null ? null : usage.model(), event.waveIndex(), event.attempt(), event.type().name(),
          event.durationMs(), usage == null ? null : usage.promptTokens(),
          usage == null ? null : usage.completionTokens(), usage == null ? null : usage.totalTokens(),
          event.timestamp()));
    }
    catch (RuntimeException ex) {
      log.warn("Run metrics persistence failed for run {} stage {}: {}",
          event.runId(), event.stageName(), ex.getMessage());
    }
  }

  /**
   * Write-ahead instance lifecycle: the business trigger inserts the RUNNING
   * row (with the request payload from the RUN_STARTED event), the terminal
   * events move it to COMPLETED / FAILED. A failing store degrades to a
   * warning; ghost terminal events (no instance row) are ignored.
   */
  private void recordInstanceStart(AgentObservabilityEvent event) {
    try {
      instanceSink.start(new RunInstance(event.runId(), event.businessId(), event.scenarioId(),
          event.templateName(), event.mode(), RunInstance.TRIGGER_API, RunInstance.RUNNING,
          event.inputContextJson(), event.timestamp(), null, null, null));
    }
    catch (RuntimeException ex) {
      log.warn("Instance registry start failed for run {}: {}", event.runId(), ex.getMessage());
    }
  }

  private void recordInstanceFinish(AgentObservabilityEvent event) {
    try {
      RunTimeline timeline = timelines.get(event.runId());
      if (timeline == null) {
        return;
      }
      long durationMs = Duration.between(timeline.startedAt(), event.timestamp()).toMillis();
      if (event.type() == AgentObservabilityEvent.Type.RUN_COMPLETED) {
        instanceSink.complete(event.runId(), event.timestamp(), durationMs);
      }
      else {
        instanceSink.fail(event.runId(), event.timestamp(), event.errorMessage());
      }
    }
    catch (RuntimeException ex) {
      log.warn("Instance registry finish failed for run {}: {}", event.runId(), ex.getMessage());
    }
  }

  /**
   * Write-ahead run log: every lifecycle / stage / tool event lands in the
   * team log before the fold and the SSE push (same WAL ordering as the
   * metrics rows), so the runtime team's Log Explorer covers the whole
   * observability review — not only business logs. A failing store degrades
   * to a warning, never to a lost timeline.
   */
  private void recordLog(AgentObservabilityEvent event) {
    try {
      teamLogStore.append(toLogEntry(event));
    }
    catch (RuntimeException ex) {
      log.warn("Team log append failed for run {} event {}: {}",
          event.runId(), event.type(), ex.getMessage());
    }
  }

  /** Maps one observability event to its durable team-log entry. */
  private TeamLogEntry toLogEntry(AgentObservabilityEvent event) {
    RunMeta meta = runMetas.get(event.runId());
    String scenarioId = event.scenarioId() != null ? event.scenarioId()
        : meta == null ? null : meta.scenarioId();
    String level = TeamLogEntry.INFO;
    String message = event.type().name();
    switch (event.type()) {
      case RUN_STARTED -> message = "run started with template '%s' (%s)".formatted(
          event.templateName(), event.mode() == null ? "workflow" : event.mode());
      case STAGE_COMPLETED -> message = stageLine(event,
          "completed in %d ms".formatted(event.durationMs() == null ? 0 : event.durationMs()));
      case STAGE_FAILED -> {
        level = TeamLogEntry.ERROR;
        message = stageLine(event, "failed: " + event.errorMessage());
      }
      case STAGE_RETRIED -> {
        level = TeamLogEntry.WARN;
        message = stageLine(event, "retrying (attempt %d): %s".formatted(
            event.attempt() == null ? 1 : event.attempt(), event.errorMessage()));
      }
      case TOOL_CALLED -> {
        AgentObservabilityEvent.ToolCallDetail tool = event.toolCall();
        if (tool != null && tool.errorMessage() != null) {
          level = TeamLogEntry.WARN;
        }
        message = tool == null ? "tool call recorded"
            : "MCP %s/%s %s in %d ms".formatted(tool.serverName(), tool.toolName(),
                tool.outcome(), tool.durationMs());
      }
      case RUN_COMPLETED -> message = "run completed";
      case RUN_FAILED -> {
        level = TeamLogEntry.ERROR;
        message = "run failed: " + event.errorMessage();
      }
    }
    return new TeamLogEntry(null, event.timestamp(), TeamLogEntry.RUN, level, "orchestrator",
        event.runId(), scenarioId, event.type().name(), message, event.errorMessage());
  }

  private static String stageLine(AgentObservabilityEvent event, String tail) {
    AgentObservabilityEvent.LmUsage usage = event.usage();
    String tokens = usage == null ? "" : " · tokens %d (prompt %d / completion %d)".formatted(
        usage.totalTokens(), usage.promptTokens(), usage.completionTokens());
    return "stage '%s' via agent '%s' %s%s".formatted(
        event.stageName(), event.agentName(), tail, tokens);
  }

  private RunTimeline fold(String runId, RunTimeline timeline, AgentObservabilityEvent event) {
    if (timeline == null) {
      // Events may only start a run; anything else for an unknown run is dropped.
      return event.type() == AgentObservabilityEvent.Type.RUN_STARTED ? RunTimeline.started(event) : null;
    }
    return switch (event.type()) {
      case RUN_STARTED -> timeline; // duplicate start: keep the first
      case TOOL_CALLED, STAGE_COMPLETED, STAGE_FAILED, STAGE_RETRIED -> timeline.withEvent(event);
      case RUN_COMPLETED -> timeline.withEvent(event).completed(event.timestamp(), event.outputContextJson());
      case RUN_FAILED -> timeline.withEvent(event).failed(event.timestamp());
    };
  }
}
