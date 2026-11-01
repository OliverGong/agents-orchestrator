/**
 * @ClassName OrchestratorSnapshot
 * @Description Immutable point-in-time view of the orchestration runtime for
 *              health/info probes: the live collaboration template identity
 *              (with its stage wiring) and the registered agents. Probes
 *              consume this snapshot via {@link RunStatusReader} instead of
 *              importing the trip module.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.kernel.contract.model.context;
import com.oli.orchestrator_demo.kernel.contract.port.runtime.RunStatusReader;

import java.util.List;

public record OrchestratorSnapshot(
  String templateName,
  String mode,
  List<StageView> stages,
  List<String> agentNames
) {

  public OrchestratorSnapshot {
    stages = stages == null ? List.of() : List.copyOf(stages);
    agentNames = agentNames == null ? List.of() : List.copyOf(agentNames);
  }

  /** One template stage as seen by the probes (identity + wiring, no execution state). */
  public record StageView(String name, String agent, List<String> dependsOn) {

    public StageView {
      dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
  }
}
