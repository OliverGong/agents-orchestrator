/**
 * @ClassName RunInstance
 * @Description One durable workflow instance of the instance registry (v2.3,
 *              the "data plan" business-trigger layer of the iPaaS whitepaper
 *              mapping): every incoming plan request instantiates exactly one
 *              row, keyed by the request's unique {@code runId} and correlated
 *              with the caller's {@code businessId}. The row follows the whole
 *              instance lifecycle from the business trigger (the plan request
 *              payload) through RUNNING to COMPLETED / FAILED, so monitoring
 *              can answer "what happened to business X" across restarts — the
 *              finer-grained multi-agent / multi-model call details stay in
 *              {@code obs_run_metrics}, linked by the same {@code runId}.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.ops.instance;

import java.time.Instant;

public record RunInstance(
  String runId,
  String businessId,
  String scenarioId,
  String templateName,
  String mode,
  String trigger,
  String status,
  String requestJson,
  Instant startedAt,
  Instant completedAt,
  Long durationMs,
  String errorMessage
) {

  /** Passive trigger of the whitepaper's scheduling model: an external caller drove the flow. */
  public static final String TRIGGER_API = "API";

  public static final String RUNNING = "RUNNING";
  public static final String COMPLETED = "COMPLETED";
  public static final String FAILED = "FAILED";

  public RunInstance {
    if (trigger == null) {
      trigger = TRIGGER_API;
    }
  }

  public RunInstance completed(Instant at, Long durationMs) {
    return new RunInstance(runId, businessId, scenarioId, templateName, mode, trigger, COMPLETED,
        requestJson, startedAt, at, durationMs, null);
  }

  public RunInstance failed(Instant at, String errorMessage) {
    return new RunInstance(runId, businessId, scenarioId, templateName, mode, trigger, FAILED,
        requestJson, startedAt, at, durationMs, errorMessage);
  }
}
