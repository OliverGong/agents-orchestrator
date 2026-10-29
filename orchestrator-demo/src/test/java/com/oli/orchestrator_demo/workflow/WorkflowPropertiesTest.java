/**
 * @ClassName WorkflowPropertiesTest
 * @Description Verifies the binding of `orchestrator.workflow.*`: the default
 *              (no stages configured) falls back to the built-in trip
 *              planning template executed with the configured mode, and
 *              custom stage definitions resolve the agent from the stage
 *              name unless overridden.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class WorkflowPropertiesTest {

  private WorkflowProperties bind(Map<String, String> values) {
    WorkflowProperties properties = new WorkflowProperties();
    new Binder(new MapConfigurationPropertySource(values))
        .bind("orchestrator.workflow", Bindable.ofInstance(properties));
    return properties;
  }

  @Test
  void defaultsToBuiltInTemplateInSequentialMode() {
    WorkflowTemplate template = bind(Map.of()).toTemplate();

    assertThat(template.mode()).isEqualTo(ExecutionMode.SEQUENTIAL);
    assertThat(template.name()).isEqualTo("default-trip-planning");
    assertThat(template.stages()).extracting(StageSpec::name)
        .containsExactly("research", "budget", "itinerary", "review");
  }

  @Test
  void bindsModeAndCustomStages() {
    WorkflowTemplate template = bind(Map.of(
        "orchestrator.workflow.mode", "concurrent",
        "orchestrator.workflow.stages[0].name", "research",
        "orchestrator.workflow.stages[0].depends-on", "",
        "orchestrator.workflow.stages[1].name", "budget",
        "orchestrator.workflow.stages[1].agent", "research",
        "orchestrator.workflow.stages[1].depends-on", "research")).toTemplate();

    assertThat(template.mode()).isEqualTo(ExecutionMode.CONCURRENT);
    assertThat(template.stages()).extracting(StageSpec::name).containsExactly("research", "budget");

    StageSpec research = template.stages().get(0);
    assertThat(research.agent()).isEqualTo("research"); // defaults to the stage name
    assertThat(research.dependsOn()).isEmpty();

    StageSpec budget = template.stages().get(1);
    assertThat(budget.agent()).isEqualTo("research"); // explicit override
    assertThat(budget.dependsOn()).containsExactly("research");
  }
}
