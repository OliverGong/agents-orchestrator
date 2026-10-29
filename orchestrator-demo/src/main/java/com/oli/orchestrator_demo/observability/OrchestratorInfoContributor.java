/**
 * @ClassName OrchestratorInfoContributor
 * @Description Actuator info contributor: surfaces the live collaboration
 *              template and registered agents under {@code GET /actuator/info},
 *              so operators can verify which workflow configuration a running
 *              instance picked up (without shell access to the config files).
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.observability;

import java.util.List;
import java.util.Map;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import com.oli.orchestrator_demo.orchestrator.TripPlanningOrchestrator;
import com.oli.orchestrator_demo.workflow.StageSpec;

@Component
public class OrchestratorInfoContributor implements InfoContributor {

  private final TripPlanningOrchestrator orchestrator;

  public OrchestratorInfoContributor(TripPlanningOrchestrator orchestrator) {
    this.orchestrator = orchestrator;
  }

  @Override
  public void contribute(Info.Builder builder) {
    builder.withDetail("workflow", Map.of(
        "template", orchestrator.template().name(),
        "mode", orchestrator.template().mode().name(),
        "stages", orchestrator.template().stages().stream()
            .map(stage -> stage.name() + " -> " + stage.agent() + stage.dependsOn())
            .toList(),
        "agents", List.copyOf(orchestrator.agentNames())));
  }
}
