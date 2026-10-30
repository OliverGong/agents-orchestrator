/**
 * @ClassName WorkflowControlState
 * @Description The control-plane view of the collaboration template: the
 *              execution mode plus the ordered stage list (stage name, agent
 *              mapping and dependencies). Edited through PUT
 *              {@code /api/v1/control/workflow}, validated against the
 *              registered agents (declared-order dependencies, no cycles)
 *              and hot-applied to the running orchestrator via
 *              {@link com.oli.orchestrator_demo.orchestrator.WorkflowTemplateHolder}.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.control;

import java.util.List;

public record WorkflowControlState(String mode, List<StageControlState> stages) {

  public WorkflowControlState {
    stages = stages == null ? List.of() : List.copyOf(stages);
  }

  /** One workflow stage; {@code agent} defaults to the stage name. */
  public record StageControlState(String name, String agent, List<String> dependsOn) {

    public StageControlState {
      dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
  }
}
