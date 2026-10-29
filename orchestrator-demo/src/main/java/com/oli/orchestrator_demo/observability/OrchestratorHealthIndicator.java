/**
 * @ClassName OrchestratorHealthIndicator
 * @Description Custom Actuator health contributor under the {@code orchestrator}
 *              key of {@code GET /actuator/health}: reports the active
 *              collaboration template (name, mode, stages), the registered
 *              agents and the runs currently retained by the observability
 *              actor. Always UP when the context booted — template validation
 *              is fail-fast at startup, so a running application implies a
 *              valid configuration; the details exist for operators to
 *              confirm which template/mode is live without reading configs.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.observability;

import java.util.List;

import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import com.oli.orchestrator_demo.orchestrator.TripPlanningOrchestrator;
import com.oli.orchestrator_demo.workflow.StageSpec;
import com.oli.orchestrator_demo.workflow.WorkflowTemplate;

@Component("orchestrator")
public class OrchestratorHealthIndicator implements HealthIndicator {

  private final TripPlanningOrchestrator orchestrator;
  private final ObservabilityActor observability;

  public OrchestratorHealthIndicator(TripPlanningOrchestrator orchestrator, ObservabilityActor observability) {
    this.orchestrator = orchestrator;
    this.observability = observability;
  }

  @Override
  public Health health() {
    WorkflowTemplate template = orchestrator.template();
    return Health.up()
        .withDetail("template", template.name())
        .withDetail("mode", template.mode().name())
        .withDetail("stages", template.stages().stream().map(StageSpec::name).toList())
        .withDetail("agents", List.copyOf(orchestrator.agentNames()))
        .withDetail("retainedRuns", observability.retainedRuns())
        .build();
  }
}
