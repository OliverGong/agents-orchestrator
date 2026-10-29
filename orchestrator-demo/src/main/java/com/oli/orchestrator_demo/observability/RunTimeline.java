/**
 * @ClassName RunTimeline
 * @Description Immutable, fold-only projection of one workflow run: built from
 *              the run's {@code RUN_STARTED} event and updated by appending
 *              every further {@link AgentObservabilityEvent}. Because each
 *              update returns a new instance, the actor can swap timelines in
 *              a concurrent map while readers always see a consistent
 *              snapshot.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.observability;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record RunTimeline(
  String runId,
  String templateName,
  String mode,
  Instant startedAt,
  RunStatus status,
  Instant completedAt,
  String finalContextJson,
  List<AgentObservabilityEvent> events
) {

  public enum RunStatus { RUNNING, COMPLETED, FAILED }

  public RunTimeline {
    events = List.copyOf(events);
  }

  static RunTimeline started(AgentObservabilityEvent event) {
    return new RunTimeline(event.runId(), event.templateName(), event.mode(), event.timestamp(),
        RunStatus.RUNNING, null, null, List.of(event));
  }

  RunTimeline withEvent(AgentObservabilityEvent event) {
    List<AgentObservabilityEvent> appended = new ArrayList<>(events);
    appended.add(event);
    return new RunTimeline(runId, templateName, mode, startedAt, status, completedAt, finalContextJson, appended);
  }

  RunTimeline completed(Instant at, String finalContextJson) {
    return new RunTimeline(runId, templateName, mode, startedAt, RunStatus.COMPLETED, at, finalContextJson, events);
  }

  RunTimeline failed(Instant at) {
    return new RunTimeline(runId, templateName, mode, startedAt, RunStatus.FAILED, at, finalContextJson, events);
  }
}
