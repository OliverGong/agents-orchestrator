/**
 * @ClassName WorkflowProperties
 * @Description Configuration properties of the collaboration template, bound
 *              from the `orchestrator.workflow.*` entries of
 *              application.properties / application.yaml:
 *
 *              orchestrator.workflow.mode=concurrent
 *              orchestrator.workflow.stages[0].name=research
 *              orchestrator.workflow.stages[1].name=budget
 *              orchestrator.workflow.stages[1].depends-on=research
 *
 *              The list index defines the declaration order (sequential
 *              execution order and dependency validation reference it). When
 *              no stages are configured, the built-in trip planning template
 *              applies (research -> budget -> itinerary -> review), executed
 *              with the configured mode. A stage's `agent` defaults to the
 *              stage name itself.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.workflow;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orchestrator.workflow")
public class WorkflowProperties {

  private ExecutionMode mode = ExecutionMode.SEQUENTIAL;

  private List<StageDefinition> stages = new ArrayList<>();

  /** Builds the collaboration template described by this configuration. */
  public WorkflowTemplate toTemplate() {
    if (stages.isEmpty()) {
      return new WorkflowTemplate("default-trip-planning", mode, WorkflowTemplate.defaultTripPlanningStages());
    }
    List<StageSpec> specs = stages.stream()
        .map(definition -> new StageSpec(definition.getName(),
            definition.getAgent() != null ? definition.getAgent() : definition.getName(),
            definition.getDependsOn()))
        .toList();
    return new WorkflowTemplate("configured", mode, specs);
  }

  public ExecutionMode getMode() {
    return mode;
  }

  public void setMode(ExecutionMode mode) {
    this.mode = mode;
  }

  public List<StageDefinition> getStages() {
    return stages;
  }

  public void setStages(List<StageDefinition> stages) {
    this.stages = stages;
  }

  /** Definition of a single stage; {@code agent} defaults to the stage name. */
  public static class StageDefinition {

    /** Identifier of the stage, referenced by other stages' depends-on. */
    private String name;

    /** Name of the agent to run; defaults to the stage name. */
    private String agent;

    /** Stages that must complete before this stage may start. */
    private List<String> dependsOn = new ArrayList<>();

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getAgent() {
      return agent;
    }

    public void setAgent(String agent) {
      this.agent = agent;
    }

    public List<String> getDependsOn() {
      return dependsOn;
    }

    public void setDependsOn(List<String> dependsOn) {
      this.dependsOn = dependsOn;
    }
  }
}
