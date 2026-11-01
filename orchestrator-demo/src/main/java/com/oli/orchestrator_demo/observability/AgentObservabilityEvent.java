/**
 * @ClassName AgentObservabilityEvent
 * @Description One immutable observability event of a workflow run, emitted by
 *              the orchestrator (or the components it drives) and consumed by
 *              the {@link ObservabilityActor}. Events record whether the
 *              intermediate execution state was sequential or concurrent
 *              (mode + wave index + the stages that shared the wave) and carry
 *              the agent context as JSON, so a whole run can be replayed from
 *              the outside. Since the MCP wiring, stage events are interleaved
 *              with {@code TOOL_CALLED} events (one per MCP tool invocation,
 *              attributed to the stage that triggered it via the
 *              {@link RunContext} thread-local), so a replayed timeline shows
 *              exactly which external tools fed which agent answer. Static
 *              factories keep the many optional fields type-safe at the call
 *              sites.
 *
 *              Since v2.2 the events also carry the control-plane scenario the
 *              run was started from ({@code scenarioId}, only set on
 *              {@code RUN_STARTED}) and the aggregated LM usage of the stage
 *              ({@code usage}: prompt/completion/total tokens, the model that
 *              actually served the call and the number of parse retries) — the
 *              usage is captured per model call by the
 *              {@code UsageCaptureAdvisor} and accumulated on the
 *              {@link LmUsageCollector} thread-local, so a stage event holds
 *              the full consumption of all its attempts.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 2.2
 */
package com.oli.orchestrator_demo.observability;

import java.time.Instant;
import java.util.List;

public record AgentObservabilityEvent(
  Type type,
  Instant timestamp,
  String runId,
  String templateName,
  String scenarioId,
  String stageName,
  String agentName,
  Integer waveIndex,
  List<String> parallelPeers,
  String mode,
  String threadName,
  Long durationMs,
  String errorMessage,
  Integer attempt,
  LmUsage usage,
  String inputContextJson,
  String outputContextJson,
  ToolCallDetail toolCall
) {

  public enum Type {
    RUN_STARTED, STAGE_COMPLETED, STAGE_FAILED, STAGE_RETRIED, RUN_COMPLETED, RUN_FAILED, TOOL_CALLED
  }

  /**
   * Aggregated LM consumption of one stage (all attempts): token counts from
   * the model metadata, the model that served the call and the parse retries
   * observed inside the stage. Recorded verbatim into the durable
   * {@code obs_run_metrics} table for per-scenario history.
   */
  public record LmUsage(
    long promptTokens,
    long completionTokens,
    long totalTokens,
    String model,
    int retries
  ) {
  }

  /** One MCP tool invocation observed during a stage execution. */
  public record ToolCallDetail(
    String serverName,
    String toolName,
    long durationMs,
    String outcome,
    String resultSummary,
    String errorMessage
  ) {
  }

  public AgentObservabilityEvent {
    parallelPeers = parallelPeers == null ? List.of() : List.copyOf(parallelPeers);
  }

  public static AgentObservabilityEvent runStarted(String runId, String templateName, String scenarioId,
      String mode) {
    return new AgentObservabilityEvent(Type.RUN_STARTED, Instant.now(), runId, templateName,
        scenarioId, null, null, null, null, mode, null, null, null, null, null, null, null, null);
  }

  public static AgentObservabilityEvent stageCompleted(String runId, String stageName, String agentName,
      int waveIndex, List<String> parallelPeers, String mode, String threadName, long durationMs,
      Integer attempt, LmUsage usage, String inputContextJson, String outputContextJson) {
    return new AgentObservabilityEvent(Type.STAGE_COMPLETED, Instant.now(), runId, null,
        null, stageName, agentName, waveIndex, parallelPeers, mode, threadName, durationMs, null, attempt,
        usage, inputContextJson, outputContextJson, null);
  }

  public static AgentObservabilityEvent stageFailed(String runId, String stageName, String agentName,
      int waveIndex, List<String> parallelPeers, String mode, String threadName, long durationMs,
      String errorMessage, Integer attempt, LmUsage usage, String inputContextJson) {
    return new AgentObservabilityEvent(Type.STAGE_FAILED, Instant.now(), runId, null,
        null, stageName, agentName, waveIndex, parallelPeers, mode, threadName, durationMs, errorMessage,
        attempt, usage, inputContextJson, null, null);
  }

  /**
   * Emitted by the agent base class whenever a parse retry re-samples the
   * model; the attribution fields come from the {@link RunContext} of the
   * executing thread (null-safe: retries outside a workflow run are dropped).
   */
  public static AgentObservabilityEvent stageRetried(String runId, String stageName, String agentName,
      Integer waveIndex, String threadName, int attempt, String errorMessage) {
    return new AgentObservabilityEvent(Type.STAGE_RETRIED, Instant.now(), runId, null,
        null, stageName, agentName, waveIndex, null, null, threadName, null,
        "attempt %d failed to parse model output: %s".formatted(attempt, errorMessage), attempt,
        null, null, null, null);
  }

  /** Reuses the {@code outputContextJson} slot to carry the final context. */
  public static AgentObservabilityEvent runCompleted(String runId, String finalContextJson) {
    return new AgentObservabilityEvent(Type.RUN_COMPLETED, Instant.now(), runId, null,
        null, null, null, null, null, null, null, null, null, null, null, null, finalContextJson, null);
  }

  public static AgentObservabilityEvent runFailed(String runId, String errorMessage) {
    return new AgentObservabilityEvent(Type.RUN_FAILED, Instant.now(), runId, null,
        null, null, null, null, null, null, null, null, errorMessage, null, null, null, null, null);
  }

  /**
   * Emitted by the instrumented MCP tool callbacks while a stage is executing;
   * the attribution fields (run/stage/agent/wave) come from the
   * {@link RunContext} of the executing thread and are {@code null} for tool
   * calls outside a workflow run.
   */
  public static AgentObservabilityEvent toolCalled(String runId, String stageName, String agentName,
      Integer waveIndex, String serverName, String toolName, long durationMs,
      String resultSummary, String errorMessage) {
    ToolCallDetail detail = new ToolCallDetail(serverName, toolName, durationMs,
        errorMessage == null ? "OK" : "ERROR", resultSummary, errorMessage);
    return new AgentObservabilityEvent(Type.TOOL_CALLED, Instant.now(), runId, null,
        null, stageName, agentName, waveIndex, null, null, null, durationMs, errorMessage, null,
        null, null, null, detail);
  }
}
