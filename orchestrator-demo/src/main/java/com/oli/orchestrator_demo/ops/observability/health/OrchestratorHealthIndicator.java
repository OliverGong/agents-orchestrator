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
package com.oli.orchestrator_demo.ops.observability.health;
import com.oli.orchestrator_demo.ops.observability.events.ObservabilityActor;

import java.util.List;

import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import com.oli.orchestrator_demo.kernel.contract.model.context.OrchestratorSnapshot;
import com.oli.orchestrator_demo.kernel.contract.port.runtime.RunStatusReader;

@Component("orchestrator")
public class OrchestratorHealthIndicator implements HealthIndicator {

  private final RunStatusReader orchestrator;
  private final ObservabilityActor observability;

  public OrchestratorHealthIndicator(RunStatusReader orchestrator, ObservabilityActor observability) {
    this.orchestrator = orchestrator;
    this.observability = observability;
  }

  @Override
  public Health health() {
    OrchestratorSnapshot snapshot = orchestrator.snapshot();
    return Health.up()
        .withDetail("template", snapshot.templateName())
        .withDetail("mode", snapshot.mode())
        .withDetail("stages", snapshot.stages().stream().map(OrchestratorSnapshot.StageView::name).toList())
        .withDetail("agents", snapshot.agentNames())
        .withDetail("retainedRuns", observability.retainedRuns())
        .build();
  }
}
