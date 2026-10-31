/**
 * @ClassName WorkflowTemplateTest
 * @Description Verifies the collaboration template: the built-in trip planning
 *              pipeline validates and decomposes into four single-stage
 *              waves, independent stages share a wave, and unknown agents,
 *              misplaced dependencies and cycles fail fast.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.oli.orchestrator_demo.control.AgentDescriptor;

class WorkflowTemplateTest {

  @Test
  void defaultTripPlanningTemplateValidatesAndRunsInFourWaves() {
    WorkflowTemplate template = new WorkflowTemplate("default", ExecutionMode.SEQUENTIAL,
        WorkflowTemplate.defaultTripPlanningStages());

    template.validate(Set.of("research", "budget", "itinerary", "review"));

    List<List<StageSpec>> waves = template.waves();
    assertThat(waves).hasSize(4);
    assertThat(waves).allSatisfy(wave -> assertThat(wave).hasSize(1));
    assertThat(waves.get(0).get(0).name()).isEqualTo("research");
    assertThat(waves.get(3).get(0).name()).isEqualTo("review");
  }

  @Test
  void parallelTripPlanningTemplateSharesTheBudgetAndItineraryWave() {
    WorkflowTemplate template = new WorkflowTemplate("parallel-trip-planning", ExecutionMode.CONCURRENT,
        WorkflowTemplate.parallelTripPlanningStages());

    template.validate(Set.of("research", "budget", "itinerary", "review"));

    List<List<StageSpec>> waves = template.waves();
    assertThat(waves).hasSize(3);
    assertThat(waves.get(0)).extracting(StageSpec::name).containsExactly("research");
    assertThat(waves.get(1)).extracting(StageSpec::name).containsExactlyInAnyOrder("budget", "itinerary");
    assertThat(waves.get(2)).extracting(StageSpec::name).containsExactly("review");
  }

  @Test
  void independentStagesShareTheSameWave() {
    WorkflowTemplate template = new WorkflowTemplate("parallel", ExecutionMode.CONCURRENT, List.of(
        StageSpec.of("research"),
        StageSpec.of("budget"),
        new StageSpec("review", "review", List.of("research", "budget"))));

    List<List<StageSpec>> waves = template.waves();

    assertThat(waves).hasSize(2);
    assertThat(waves.get(0)).extracting(StageSpec::name).containsExactlyInAnyOrder("research", "budget");
    assertThat(waves.get(1)).extracting(StageSpec::name).containsExactly("review");
  }

  @Test
  void unknownAgentFailsValidation() {
    WorkflowTemplate template = new WorkflowTemplate("broken", ExecutionMode.SEQUENTIAL,
        List.of(StageSpec.of("nonexistent")));

    assertThatThrownBy(() -> template.validate(Set.of("research")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nonexistent")
        .hasMessageContaining("Available agents");
  }

  @Test
  void dependencyOnLaterStageFailsValidation() {
    WorkflowTemplate template = new WorkflowTemplate("misordered", ExecutionMode.SEQUENTIAL, List.of(
        new StageSpec("budget", "budget", List.of("research")),
        StageSpec.of("research")));

    assertThatThrownBy(() -> template.validate(Set.of("research", "budget")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not a preceding stage");
  }

  @Test
  void cycleFailsWaveComputation() {
    WorkflowTemplate template = new WorkflowTemplate("cyclic", ExecutionMode.SEQUENTIAL, List.of(
        new StageSpec("a", "a", List.of("b")),
        new StageSpec("b", "b", List.of("a"))));

    assertThatThrownBy(template::waves)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cycle");
  }

  @Test
  void emptyTemplateFailsValidation() {
    WorkflowTemplate template = new WorkflowTemplate("empty", ExecutionMode.SEQUENTIAL, List.of());

    assertThatThrownBy(() -> template.validate(Set.of("research")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no stages");
  }

  // ---- slot-contract validation (v2) ---------------------------------------

  /** Mirrors the self-declared contracts of the four demo agents. */
  private static Map<String, AgentDescriptor> contracts() {
    AgentDescriptor research = new AgentDescriptor("research", "Research", "",
        List.of("researchResponse"), List.of(), List.of(), null, null);
    AgentDescriptor budget = new AgentDescriptor("budget", "Budget", "",
        List.of("budgetResponse"), List.of("researchResponse"), List.of(), null, null);
    AgentDescriptor itinerary = new AgentDescriptor("itinerary", "Itinerary", "",
        List.of("itineraryResponse"), List.of("researchResponse", "budgetResponse"),
        List.of("budgetResponse"), null, null);
    AgentDescriptor review = new AgentDescriptor("review", "Review", "",
        List.of("reviewResponse"), List.of("researchResponse", "budgetResponse", "itineraryResponse"),
        List.of("budgetResponse", "itineraryResponse"), null, null);
    return Map.of("research", research, "budget", budget, "itinerary", itinerary, "review", review);
  }

  @Test
  void builtInTemplatesAreSlotClosed() {
    new WorkflowTemplate("default", ExecutionMode.SEQUENTIAL, WorkflowTemplate.defaultTripPlanningStages())
        .validate(Set.of("research", "budget", "itinerary", "review"), contracts());
    new WorkflowTemplate("parallel", ExecutionMode.CONCURRENT, WorkflowTemplate.parallelTripPlanningStages())
        .validate(Set.of("research", "budget", "itinerary", "review"), contracts());
  }

  @Test
  void unproducedInputSlotFailsValidation() {
    WorkflowTemplate template = new WorkflowTemplate("review-only", ExecutionMode.SEQUENTIAL,
        List.of(StageSpec.of("review")));

    assertThatThrownBy(() -> template.validate(Set.of("review", "research"), contracts()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("review")
        .hasMessageContaining("researchResponse")
        .hasMessageContaining("no stage of the template produces it");
  }

  @Test
  void pendingSafeDeclarationRescuesAMissingProducer() {
    // Review tolerates a missing budget/itinerary breakdown, so research ->
    // review is a logically closed template even without the budget stage.
    WorkflowTemplate template = new WorkflowTemplate("research-then-review", ExecutionMode.SEQUENTIAL, List.of(
        StageSpec.of("research"),
        new StageSpec("review", "review", List.of("research"))));

    template.validate(Set.of("research", "review"), contracts());
  }

  @Test
  void sameWaveSiblingConsumptionFailsWithoutPendingSafeDeclaration() {
    // The parallel template is only closed because the itinerary agent
    // declares the budget breakdown pending-safe; without that declaration
    // the same-wave consumption is rejected.
    Map<String, AgentDescriptor> strict = Map.of(
        "research", contracts().get("research"),
        "budget", contracts().get("budget"),
        "itinerary", new AgentDescriptor("itinerary", "Itinerary", "", List.of("itineraryResponse"),
            List.of("researchResponse", "budgetResponse"), List.of(), null, null),
        "review", contracts().get("review"));
    WorkflowTemplate template = new WorkflowTemplate("parallel", ExecutionMode.CONCURRENT,
        WorkflowTemplate.parallelTripPlanningStages());

    assertThatThrownBy(() -> template.validate(Set.of("research", "budget", "itinerary", "review"), strict))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("itinerary")
        .hasMessageContaining("budgetResponse")
        .hasMessageContaining("same wave");
  }

  @Test
  void sequentialProducerLaterInTheTemplateFailsValidation() {
    WorkflowTemplate template = new WorkflowTemplate("misordered", ExecutionMode.SEQUENTIAL, List.of(
        StageSpec.of("budget"),
        StageSpec.of("research")));

    assertThatThrownBy(() -> template.validate(Set.of("research", "budget"), contracts()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("budget")
        .hasMessageContaining("researchResponse")
        .hasMessageContaining("run later in the template");
  }

  @Test
  void concurrentTemplateTreatsADeclaredPendingSafeSiblingAsAvailable() {
    // research -> (budget || itinerary): the itinerary consumes the budget
    // breakdown of a same-wave sibling, which is only legal because the slot
    // is pending-safe — exactly the built-in parallel template.
    WorkflowTemplate template = new WorkflowTemplate("parallel", ExecutionMode.CONCURRENT, List.of(
        StageSpec.of("research"),
        new StageSpec("budget", "budget", List.of("research")),
        new StageSpec("itinerary", "itinerary", List.of("research")),
        new StageSpec("review", "review", List.of("research", "budget", "itinerary"))));

    template.validate(Set.of("research", "budget", "itinerary", "review"), contracts());
  }
}
