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
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.observability;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
  private final ObservabilitySsePublisher ssePublisher;
  private final Thread actorThread = Thread.ofVirtual().name("observability-actor").start(this::consume);
  private volatile boolean accepting = true;

  public ObservabilityActor(ObservabilitySsePublisher ssePublisher) {
    this.ssePublisher = ssePublisher;
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
    timelines.compute(event.runId(), (runId, timeline) -> fold(runId, timeline, event));
    if (event.type() == AgentObservabilityEvent.Type.RUN_STARTED) {
      insertionOrder.addLast(event.runId());
      while (insertionOrder.size() > MAX_RETAINED_RUNS) {
        timelines.remove(insertionOrder.pollFirst());
      }
    }
    // Forwarded from the actor thread, so SSE clients observe the same
    // per-run event order the timeline was folded with.
    ssePublisher.publish(event);
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
