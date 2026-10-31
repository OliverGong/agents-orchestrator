/**
 * @ClassName OrchestratorInfoContributorTest
 * @Description Verifies the workflow info contributor of
 *              {@code GET /actuator/info}: the live collaboration template is
 *              contributed under the "workflow" key as the template name, the
 *              execution mode, the stage -> agent mapping (with declared
 *              dependencies) and the registered agent names.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

import com.oli.orchestrator_demo.ChatClientStubs;
import com.oli.orchestrator_demo.agent.Agent;
import com.oli.orchestrator_demo.model.OrchestratorContext;
import com.oli.orchestrator_demo.orchestrator.TripPlanningOrchestrator;
import com.oli.orchestrator_demo.orchestrator.WorkflowTemplateHolder;
import com.oli.orchestrator_demo.workflow.WorkflowProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.json.JsonMapper;

class OrchestratorInfoContributorTest {

  @Test
  @SuppressWarnings("unchecked")
  void contributesTheLiveWorkflowConfiguration() {
    ObservabilityActor observability = ChatClientStubs.actor();
    WorkflowProperties properties = new WorkflowProperties();
    properties.getStages().add(new WorkflowProperties.StageDefinition() {{
      setName("research");
    }});
    properties.getStages().add(new WorkflowProperties.StageDefinition() {{
      setName("review");
      setDependsOn(List.of("research"));
    }});
    TripPlanningOrchestrator orchestrator = new TripPlanningOrchestrator(
        List.of(contextAgent("research"), contextAgent("review")),
        new WorkflowTemplateHolder(properties), observability, ObservationRegistry.create(), JsonMapper.builder().build(),
        new OrchestratorMetrics(new SimpleMeterRegistry(), observability));

    Info.Builder builder = new Info.Builder();
    new OrchestratorInfoContributor(orchestrator).contribute(builder);
    Info info = builder.build();

    Map<String, Object> workflow = (Map<String, Object>) info.getDetails().get("workflow");
    assertThat(workflow)
        .containsEntry("template", "configured")
        .containsEntry("mode", "SEQUENTIAL")
        .containsEntry("agents", List.of("research", "review"));
    assertThat((List<String>) workflow.get("stages"))
        .containsExactly("research -> research[]", "review -> review[research]");
  }

  private static Agent<OrchestratorContext, OrchestratorContext> contextAgent(String name) {
    return new Agent<>() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public OrchestratorContext execute(OrchestratorContext context) {
        return context;
      }
    };
  }
}
