/**
 * @ClassName InstanceController
 * @Description Read side of the run-instance registry (v2.3 correlation API,
 *              the monitoring-center closed loop of the iPaaS mapping): list
 *              the instantiated runs with their business trigger facets and
 *              fetch one instance's full context. The detail view assembles —
 *              keyed by the unique request id and correlated by the business
 *              id — the durable instance row (trigger payload, status,
 *              duration), the replayable in-memory timeline (waves, stages,
 *              tool calls, blackboard snapshots; {@code null} once evicted),
 *              the durable per-stage LM rows (multi-agent / multi-model call
 *              details from {@code obs_run_metrics}) and the team-log entries
 *              attributed to the run, so monitoring reconstructs the whole
 *              instantiation without leaving the platform.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.instance.web;
import com.oli.orchestrator_demo.instance.InstanceStore;
import com.oli.orchestrator_demo.instance.RunInstance;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oli.orchestrator_demo.observability.events.ObservabilityActor;
import com.oli.orchestrator_demo.observability.metrics.RunMetricsStore;
import com.oli.orchestrator_demo.observability.web.RunObservabilityController;
import com.oli.orchestrator_demo.teamlog.TeamLogEntry;
import com.oli.orchestrator_demo.teamlog.TeamLogStore;

@RestController
@RequestMapping("/api/v1/instances")
public class InstanceController {

  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 500;

  private final InstanceStore instances;
  private final ObservabilityActor observability;
  private final RunMetricsStore metricsStore;
  private final TeamLogStore teamLogStore;

  public InstanceController(InstanceStore instances, ObservabilityActor observability,
      RunMetricsStore metricsStore, TeamLogStore teamLogStore) {
    this.instances = instances;
    this.observability = observability;
    this.metricsStore = metricsStore;
    this.teamLogStore = teamLogStore;
  }

  /**
   * Newest-first instances matching the trigger facets. {@code window} is a
   * convenience shorthand ({@code 1h}, {@code 24h}, {@code 7d}, {@code all});
   * {@code q} free-text matches run id, business id, scenario id and error.
   */
  @GetMapping
  public List<RunInstance> list(
      @RequestParam(value = "businessId", required = false) String businessId,
      @RequestParam(value = "scenarioId", required = false) String scenarioId,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "window", defaultValue = "24h") String window,
      @RequestParam(value = "limit", defaultValue = "100") int limit) {
    return instances.query(new InstanceStore.InstanceQuery(
        since(window), businessId, scenarioId, status, q, Math.min(Math.max(limit, 1), MAX_LIMIT)));
  }

  /** Full context of one instantiation; 404 when the run id is unknown. */
  @GetMapping("/{runId}")
  public ResponseEntity<InstanceDetails> instance(@PathVariable String runId) {
    return instances.find(runId)
        .<InstanceDetails>map(instance -> new InstanceDetails(
            instance,
            observability.timeline(runId).map(RunObservabilityController::toDetails).orElse(null),
            metricsStore.byRun(runId),
            teamLogStore.search(new TeamLogStore.TeamLogQuery(null, null, null, runId, null, null, 200))))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private static Instant since(String window) {
    return switch (window == null ? "all" : window) {
      case "1h" -> Instant.now().minus(1, ChronoUnit.HOURS);
      case "24h" -> Instant.now().minus(24, ChronoUnit.HOURS);
      case "7d" -> Instant.now().minus(7, ChronoUnit.DAYS);
      default -> null;
    };
  }

  /**
   * Full context of one instance: the durable registry row, the (evictable)
   * in-memory run timeline, the durable per-stage LM rows and the run's
   * team-log trail — all linked by the unique run id / business id pair.
   */
  public record InstanceDetails(
      RunInstance instance,
      RunObservabilityController.RunDetails run,
      List<RunMetricsStore.RunStageMetrics> stages,
      List<TeamLogEntry> logs) {
  }
}
