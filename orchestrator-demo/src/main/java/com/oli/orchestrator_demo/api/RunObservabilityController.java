/**
 * @ClassName RunObservabilityController
 * @Description Read-side of the observability actor: exposes the retained run
 *              timelines collected for previous plan requests. The list view
 *              summarizes each run; the detail view groups the stage events
 *              into their dependency waves and includes the agent context as
 *              JSON (input and output of every stage plus the final context),
 *              so the whole agent conversation of a run can be inspected
 *              without touching logs or the trace backend.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oli.orchestrator_demo.observability.AgentObservabilityEvent;
import com.oli.orchestrator_demo.observability.ObservabilityActor;
import com.oli.orchestrator_demo.observability.RunTimeline;

@RestController
@RequestMapping("/api/trips/runs")
public class RunObservabilityController {

  private final ObservabilityActor observability;

  public RunObservabilityController(ObservabilityActor observability) {
    this.observability = observability;
  }

  @GetMapping
  public List<RunSummary> runs() {
    return observability.recentRuns().stream()
        .map(RunObservabilityController::toSummary)
        .toList();
  }

  @GetMapping("/{runId}")
  public ResponseEntity<RunDetails> run(@PathVariable String runId) {
    return observability.timeline(runId)
        .<RunDetails>map(RunObservabilityController::toDetails)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private static RunSummary toSummary(RunTimeline timeline) {
    List<AgentObservabilityEvent> events = timeline.events();
    Long durationMs = timeline.completedAt() == null
        ? null
        : Duration.between(timeline.startedAt(), timeline.completedAt()).toMillis();
    return new RunSummary(timeline.runId(), timeline.templateName(), timeline.mode(),
        timeline.status().name(), timeline.startedAt(), timeline.completedAt(), durationMs,
        (int) events.stream().filter(event -> event.type().name().startsWith("STAGE")).count(),
        events.stream().filter(event -> event.type() == AgentObservabilityEvent.Type.STAGE_FAILED).count());
  }

  private static RunDetails toDetails(RunTimeline timeline) {
    Map<Integer, List<RunDetails.StageView>> stagesByWave = new LinkedHashMap<>();
    for (AgentObservabilityEvent event : timeline.events()) {
      if (event.type() == AgentObservabilityEvent.Type.STAGE_COMPLETED
          || event.type() == AgentObservabilityEvent.Type.STAGE_FAILED) {
        stagesByWave.computeIfAbsent(event.waveIndex(), wave -> new ArrayList<>())
            .add(new RunDetails.StageView(event.stageName(), event.agentName(), event.type().name(),
                event.threadName(), event.durationMs(), event.errorMessage(), event.inputContextJson(),
                event.outputContextJson(), event.parallelPeers()));
      }
    }
    List<RunDetails.WaveView> waves = stagesByWave.entrySet().stream()
        .map(entry -> new RunDetails.WaveView(entry.getKey(),
            "CONCURRENT".equals(timeline.mode()) && entry.getValue().size() > 1,
            List.copyOf(entry.getValue())))
        .toList();
    return new RunDetails(timeline.runId(), timeline.templateName(), timeline.mode(),
        timeline.status().name(), timeline.startedAt(), timeline.completedAt(),
        timeline.finalContextJson(), waves);
  }

  /** Compact run entry of the list endpoint. */
  public record RunSummary(String runId, String templateName, String mode, String status,
      Instant startedAt, Instant completedAt, Long durationMs, int stageCount, long failedStages) {
  }

  /** Full timeline of one run, grouped into dependency waves. */
  public record RunDetails(String runId, String templateName, String mode, String status,
      Instant startedAt, Instant completedAt, String finalContext, List<WaveView> waves) {

    /** One dependency wave; {@code parallel} is only true for concurrent waves. */
    public record WaveView(int waveIndex, boolean parallel, List<StageView> stages) {
    }

    /** One executed stage with its context snapshots. */
    public record StageView(String stageName, String agentName, String outcome, String threadName,
        Long durationMs, String errorMessage, String inputContext, String outputContext,
        List<String> parallelPeers) {
    }
  }
}
