/**
 * @ClassName WorkflowTemplate
 * @Description Collaboration template of the orchestrator: an ordered list of
 *              {@link StageSpec stages}, each naming the agent to run and the
 *              stages it depends on. The template is pure data plus pure
 *              computation (validation and dependency waves); the execution
 *              itself lives in the orchestrator. Templates are defined side by
 *              side with the orchestrator, either through the built-in default
 *              or through `orchestrator.workflow.stages.*` configuration.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.workflow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record WorkflowTemplate(String name, ExecutionMode mode, List<StageSpec> stages) {

  public WorkflowTemplate {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Workflow template name must not be blank");
    }
    mode = mode == null ? ExecutionMode.SEQUENTIAL : mode;
    stages = stages == null ? List.of() : List.copyOf(stages);
  }

  /**
   * The built-in trip planning pipeline: research -> budget -> itinerary ->
   * review (every stage depends on all of its predecessors).
   */
  public static List<StageSpec> defaultTripPlanningStages() {
    return List.of(
        new StageSpec("research", "research", List.of()),
        new StageSpec("budget", "budget", List.of("research")),
        new StageSpec("itinerary", "itinerary", List.of("research", "budget")),
        new StageSpec("review", "review", List.of("research", "budget", "itinerary")));
  }

  /**
   * Validates the template against the available agents: every stage must
   * reference a known agent and may only depend on stages that precede it in
   * the definition order (which also rules out dependency cycles and keeps
   * the sequential execution order well-defined). Unknown references fail
   * fast with a descriptive message.
   */
  public void validate(Set<String> availableAgents) {
    if (stages.isEmpty()) {
      throw new IllegalStateException("Workflow template '%s' declares no stages".formatted(name));
    }
    Set<String> precedingStages = new LinkedHashSet<>();
    for (StageSpec stage : stages) {
      if (!precedingStages.add(stage.name())) {
        throw new IllegalStateException(
            "Duplicate stage name '%s' in workflow template '%s'".formatted(stage.name(), name));
      }
      if (!availableAgents.contains(stage.agent())) {
        throw new IllegalStateException(
            ("Stage '%s' of workflow template '%s' references unknown agent '%s'. Available agents: %s")
                .formatted(stage.name(), name, stage.agent(), availableAgents));
      }
      for (String dependency : stage.dependsOn()) {
        if (!precedingStages.contains(dependency)) {
          throw new IllegalStateException(
              ("Stage '%s' of workflow template '%s' depends on '%s', which is not a preceding stage")
                  .formatted(stage.name(), name, dependency));
        }
      }
    }
  }

  /**
   * Groups the stages into dependency waves (Kahn's algorithm): the first
   * wave holds every stage without pending dependencies, each subsequent wave
   * holds the stages whose dependencies are all covered by earlier waves.
   * Stages of the same wave are independent and may therefore run in
   * parallel. Throws when the dependencies form a cycle.
   */
  public List<List<StageSpec>> waves() {
    Set<String> done = new HashSet<>();
    List<List<StageSpec>> waves = new ArrayList<>();
    while (done.size() < stages.size()) {
      List<StageSpec> wave = stages.stream()
          .filter(stage -> !done.contains(stage.name()))
          .filter(stage -> done.containsAll(stage.dependsOn()))
          .toList();
      if (wave.isEmpty()) {
        List<String> unresolved = stages.stream()
            .map(StageSpec::name)
            .filter(name -> !done.contains(name))
            .toList();
        throw new IllegalStateException(
            "Workflow template '%s' contains a dependency cycle among stages: %s".formatted(name, unresolved));
      }
      wave.forEach(stage -> done.add(stage.name()));
      waves.add(wave);
    }
    return waves;
  }
}
