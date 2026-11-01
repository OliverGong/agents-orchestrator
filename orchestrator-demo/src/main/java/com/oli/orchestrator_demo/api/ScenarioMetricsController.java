/**
 * @ClassName ScenarioMetricsController
 * @Description Read side of the durable LM metrics (v2.2): aggregates the
 *              {@code obs_run_metrics} stage rows over a time window, grouped
 *              by scenario, stage, agent and model — the per-scenario history
 *              of model latency (avg/max duration), parse retries and token
 *              consumption. {@code GET /api/v1/metrics/lm} covers every
 *              scenario, {@code GET /api/v1/scenarios/{id}/metrics} scopes to
 *              one. The {@code window} parameter accepts {@code m}/{@code h}/
 *              {@code d} durations (default 7d). In file mode (no-op store)
 *              the endpoints answer with an empty list by design.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oli.orchestrator_demo.observability.RunMetricsStore;
import com.oli.orchestrator_demo.observability.RunMetricsStore.StageMetricsSummary;

@RestController
public class ScenarioMetricsController {

  private final RunMetricsStore metricsStore;

  public ScenarioMetricsController(RunMetricsStore metricsStore) {
    this.metricsStore = metricsStore;
  }

  /** LM metrics of every scenario over the window. */
  @GetMapping("/api/v1/metrics/lm")
  public List<StageMetricsSummary> lmMetrics(@RequestParam(defaultValue = "7d") String window) {
    return metricsStore.aggregate(since(window), null);
  }

  /** LM metrics of one scenario over the window. */
  @GetMapping("/api/v1/scenarios/{id}/metrics")
  public List<StageMetricsSummary> scenarioMetrics(@PathVariable String id,
      @RequestParam(defaultValue = "7d") String window) {
    return metricsStore.aggregate(since(window), id);
  }

  private static Instant since(String window) {
    String unit = window.substring(window.length() - 1);
    long amount = Long.parseLong(window.substring(0, window.length() - 1));
    Duration duration = switch (unit) {
      case "m" -> Duration.ofMinutes(amount);
      case "h" -> Duration.ofHours(amount);
      case "d" -> Duration.ofDays(amount);
      default -> throw new IllegalArgumentException("window must be a duration like 30m, 12h or 7d");
    };
    return Instant.now().minus(duration);
  }
}
