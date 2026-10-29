/**
 * @ClassName StageSpec
 * @Description One stage of a {@link WorkflowTemplate collaboration template}:
 *              runs the named agent after every stage listed in
 *              {@code dependsOn} has completed. The stage name is also the
 *              reference other stages use in their {@code dependsOn} list.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.workflow;

import java.util.List;

public record StageSpec(String name, String agent, List<String> dependsOn) {

  public StageSpec {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Stage name must not be blank");
    }
    if (agent == null || agent.isBlank()) {
      throw new IllegalArgumentException("Agent of stage '%s' must not be blank".formatted(name));
    }
    dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
  }

  /** Convenience factory: the stage is named after the agent it runs. */
  public static StageSpec of(String agent, String... dependsOn) {
    return new StageSpec(agent, agent, List.of(dependsOn));
  }
}
