/**
 * @ClassName OrchestratorMetrics
 * @Description Micrometer catalog of the orchestration process metrics,
 *              complementing the per-agent {@code agent.execution.duration}
 *              timer produced by the AOP aspect: run-level counters and
 *              duration (template/mode/outcome tagged), stage-level counters
 *              (agent/mode tagged) and a gauge of the observability actor's
 *              retained runs. All series flow through
 *              {@code /actuator/prometheus} into Grafana dashboards; the run
 *              duration timer is histogram-enabled via
 *              {@code management.metrics.distribution.percentiles-histogram.*}
 *              so p95 works with {@code histogram_quantile}.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.observability;

import java.time.Duration;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class OrchestratorMetrics {

  public static final String RUN_DURATION = "orchestrator.run.duration";
  public static final String RUNS_STARTED = "orchestrator.runs.started";
  public static final String RUNS_COMPLETED = "orchestrator.runs.completed";
  public static final String RUNS_FAILED = "orchestrator.runs.failed";
  public static final String STAGES_COMPLETED = "orchestrator.stage.completed";
  public static final String STAGES_FAILED = "orchestrator.stage.failed";
  public static final String RETAINED_RUNS = "orchestrator.observability.retained.runs";

  private final MeterRegistry registry;

  public OrchestratorMetrics(MeterRegistry registry, ObservabilityActor observability) {
    this.registry = registry;
    Gauge.builder(RETAINED_RUNS, observability, actor -> actor.retainedRuns())
        .description("Run timelines currently retained by the observability actor")
        .register(registry);
  }

  public void runStarted(String template, String mode) {
    registry.counter(RUNS_STARTED, "template", template, "mode", mode).increment();
  }

  public void runCompleted(String template, String mode, Duration duration) {
    registry.counter(RUNS_COMPLETED, "template", template, "mode", mode).increment();
    runTimer(template, mode, "success").record(duration);
  }

  public void runFailed(String template, String mode, Duration duration) {
    registry.counter(RUNS_FAILED, "template", template, "mode", mode).increment();
    runTimer(template, mode, "failure").record(duration);
  }

  public void stageCompleted(String agent, String mode) {
    registry.counter(STAGES_COMPLETED, "agent", agent, "mode", mode).increment();
  }

  public void stageFailed(String agent, String mode) {
    registry.counter(STAGES_FAILED, "agent", agent, "mode", mode).increment();
  }

  private Timer runTimer(String template, String mode, String outcome) {
    return Timer.builder(RUN_DURATION)
        .description("Duration of workflow runs")
        .tag("template", template)
        .tag("mode", mode)
        .tag("outcome", outcome)
        .register(registry);
  }
}
