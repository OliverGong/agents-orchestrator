/**
 * @ClassName AgentObservabilityEvent
 * @Description One immutable observability event of a workflow run, emitted by
 *              the orchestrator (or the components it drives) and consumed by
 *              the {@link ObservabilityActor}. Events record whether the
 *              intermediate execution state was sequential or concurrent
 *              (mode + wave index + the stages that shared the wave) and carry
 *              the agent context as JSON, so a whole run can be replayed from
 *              the outside. Static factories keep the many optional fields
 *              type-safe at the call sites.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.observability;

import java.time.Instant;
import java.util.List;

public record AgentObservabilityEvent(
  Type type,
  Instant timestamp,
  String runId,
  String templateName,
  String stageName,
  String agentName,
  Integer waveIndex,
  List<String> parallelPeers,
  String mode,
  String threadName,
  Long durationMs,
  String errorMessage,
  String inputContextJson,
  String outputContextJson
) {

  public enum Type {
    RUN_STARTED, STAGE_COMPLETED, STAGE_FAILED, RUN_COMPLETED, RUN_FAILED
  }

  public AgentObservabilityEvent {
    parallelPeers = parallelPeers == null ? List.of() : List.copyOf(parallelPeers);
  }

  public static AgentObservabilityEvent runStarted(String runId, String templateName, String mode) {
    return new AgentObservabilityEvent(Type.RUN_STARTED, Instant.now(), runId, templateName,
        null, null, null, null, mode, null, null, null, null, null);
  }

  public static AgentObservabilityEvent stageCompleted(String runId, String stageName, String agentName,
      int waveIndex, List<String> parallelPeers, String mode, String threadName, long durationMs,
      String inputContextJson, String outputContextJson) {
    return new AgentObservabilityEvent(Type.STAGE_COMPLETED, Instant.now(), runId, null,
        stageName, agentName, waveIndex, parallelPeers, mode, threadName, durationMs, null,
        inputContextJson, outputContextJson);
  }

  public static AgentObservabilityEvent stageFailed(String runId, String stageName, String agentName,
      int waveIndex, List<String> parallelPeers, String mode, String threadName, long durationMs,
      String errorMessage, String inputContextJson) {
    return new AgentObservabilityEvent(Type.STAGE_FAILED, Instant.now(), runId, null,
        stageName, agentName, waveIndex, parallelPeers, mode, threadName, durationMs, errorMessage,
        inputContextJson, null);
  }

  /** Reuses the {@code outputContextJson} slot to carry the final context. */
  public static AgentObservabilityEvent runCompleted(String runId, String finalContextJson) {
    return new AgentObservabilityEvent(Type.RUN_COMPLETED, Instant.now(), runId, null,
        null, null, null, null, null, null, null, null, null, finalContextJson);
  }

  public static AgentObservabilityEvent runFailed(String runId, String errorMessage) {
    return new AgentObservabilityEvent(Type.RUN_FAILED, Instant.now(), runId, null,
        null, null, null, null, null, null, null, errorMessage, null, null);
  }
}
