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
import java.util.Set;

import org.junit.jupiter.api.Test;

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
}
