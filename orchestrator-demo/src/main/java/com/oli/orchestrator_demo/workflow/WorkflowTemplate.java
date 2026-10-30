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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.oli.orchestrator_demo.control.AgentDescriptor;

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
   * The built-in parallel trip planning pipeline: research -> (budget ||
   * itinerary) -> review. Budget and itinerary only depend on the research,
   * so under {@link ExecutionMode#CONCURRENT} they run in the same wave from
   * the same context snapshot; the itinerary agent is aware that the budget
   * breakdown may not be on the blackboard yet (it plans against the user's
   * budget alone), and the final review stage reconciles both contributions.
   */
  public static List<StageSpec> parallelTripPlanningStages() {
    return List.of(
        new StageSpec("research", "research", List.of()),
        new StageSpec("budget", "budget", List.of("research")),
        new StageSpec("itinerary", "itinerary", List.of("research")),
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
    validate(availableAgents, Map.of());
  }

  /**
   * Validates the template structurally (see {@link #validate(Set)}) and —
   * when the agents declare slot contracts — for logical closure: every
   * consumed input slot must be on the blackboard when the consuming stage
   * starts. That is guaranteed either by a producing stage that completes
   * beforehand (a preceding stage under SEQUENTIAL execution, a member of an
   * earlier dependency wave under CONCURRENT execution) or by the consuming
   * agent declaring the slot pending-safe (it explicitly tolerates the
   * absence and says so in its prompt). The wave rule is what keeps
   * same-wave parallel siblings from consuming each other's outputs, and the
   * closure rule rejects templates with unfilled gaps. An empty descriptor
   * map skips the slot checks (agents without a contract).
   */
  public void validate(Set<String> availableAgents, Map<String, AgentDescriptor> descriptors) {
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
    if (descriptors.isEmpty()) {
      return;
    }
    Map<String, Integer> waveIndex = new HashMap<>();
    if (mode == ExecutionMode.CONCURRENT) {
      List<List<StageSpec>> waves = waves();
      for (int wave = 0; wave < waves.size(); wave++) {
        final int waveNumber = wave;
        waves.get(wave).forEach(stage -> waveIndex.put(stage.name(), waveNumber));
      }
    }
    for (int position = 0; position < stages.size(); position++) {
      StageSpec stage = stages.get(position);
      AgentDescriptor descriptor = descriptors.get(stage.agent());
      if (descriptor == null) {
        continue;
      }
      for (String slot : descriptor.inputSlots()) {
        if (descriptor.pendingSafeSlots().contains(slot)) {
          continue;
        }
        assertSlotAvailable(stage, descriptor, slot, position, waveIndex, descriptors);
      }
    }
  }

  /**
   * Fails fast when {@code slot} is not on the blackboard when {@code stage}
   * starts: no stage produces it, or the only producers run later (or —
   * concurrently — in the same wave).
   */
  private void assertSlotAvailable(StageSpec stage, AgentDescriptor descriptor, String slot, int position,
      Map<String, Integer> waveIndex, Map<String, AgentDescriptor> descriptors) {
    List<String> producers = new ArrayList<>();
    for (int candidate = 0; candidate < stages.size(); candidate++) {
      StageSpec producerStage = stages.get(candidate);
      AgentDescriptor producer = descriptors.get(producerStage.agent());
      if (producer == null || !producer.outputSlots().contains(slot)) {
        continue;
      }
      producers.add(producerStage.name());
      boolean completedBefore = mode == ExecutionMode.SEQUENTIAL
          ? candidate < position
          : waveIndex.get(producerStage.name()) < waveIndex.get(stage.name());
      if (completedBefore) {
        return;
      }
    }
    String cause = producers.isEmpty()
        ? "no stage of the template produces it"
        : "its only producer stage(s) %s %s".formatted(producers,
            mode == ExecutionMode.SEQUENTIAL
                ? "run later in the template"
                : "run in the same wave or later");
    throw new IllegalStateException(("Stage '%s' of workflow template '%s' consumes slot '%s' via agent '%s', "
        + "but %s. Declare the slot pending-safe for agent '%s' or reorder the stages.")
            .formatted(stage.name(), name, slot, stage.agent(), cause, stage.agent()));
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
