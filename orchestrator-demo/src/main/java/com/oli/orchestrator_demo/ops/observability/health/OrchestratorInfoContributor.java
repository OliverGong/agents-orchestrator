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
package com.oli.orchestrator_demo.ops.observability.health;

import java.util.Map;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import com.oli.orchestrator_demo.kernel.contract.OrchestratorSnapshot;
import com.oli.orchestrator_demo.kernel.contract.RunStatusReader;

@Component
public class OrchestratorInfoContributor implements InfoContributor {

  private final RunStatusReader orchestrator;

  public OrchestratorInfoContributor(RunStatusReader orchestrator) {
    this.orchestrator = orchestrator;
  }

  @Override
  public void contribute(Info.Builder builder) {
    OrchestratorSnapshot snapshot = orchestrator.snapshot();
    builder.withDetail("workflow", Map.of(
        "template", snapshot.templateName(),
        "mode", snapshot.mode(),
        "stages", snapshot.stages().stream()
            .map(stage -> stage.name() + " -> " + stage.agent() + stage.dependsOn())
            .toList(),
        "agents", snapshot.agentNames()));
  }
}
