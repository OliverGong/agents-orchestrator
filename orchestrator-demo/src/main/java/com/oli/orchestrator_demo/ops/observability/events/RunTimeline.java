/**
 * @ClassName RunTimeline
 * @Description Immutable, fold-only projection of one workflow run: built from
 *              the run's {@code RUN_STARTED} event and updated by appending
 *              every further {@link AgentObservabilityEvent}. Because each
 *              update returns a new instance, the actor can swap timelines in
 *              a concurrent map while readers always see a consistent
 *              snapshot. Since v2.3 the timeline also carries the business
 *              trigger ({@code businessId}) recorded at request entry, so the
 *              replay views can correlate the run with its durable instance
 *              registry row.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 2.3
 */
package com.oli.orchestrator_demo.ops.observability.events;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record RunTimeline(
  String runId,
  String templateName,
  String scenarioId,
  String businessId,
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
    return new RunTimeline(event.runId(), event.templateName(), event.scenarioId(), event.businessId(),
        event.mode(), event.timestamp(), RunStatus.RUNNING, null, null, List.of(event));
  }

  RunTimeline withEvent(AgentObservabilityEvent event) {
    List<AgentObservabilityEvent> appended = new ArrayList<>(events);
    appended.add(event);
    return new RunTimeline(runId, templateName, scenarioId, businessId, mode, startedAt, status,
        completedAt, finalContextJson, appended);
  }

  RunTimeline completed(Instant at, String finalContextJson) {
    return new RunTimeline(runId, templateName, scenarioId, businessId, mode, startedAt,
        RunStatus.COMPLETED, at, finalContextJson, events);
  }

  RunTimeline failed(Instant at) {
    return new RunTimeline(runId, templateName, scenarioId, businessId, mode, startedAt,
        RunStatus.FAILED, at, finalContextJson, events);
  }
}
