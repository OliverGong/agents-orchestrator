/**
 * @ClassName OrchestratorHealthIndicatorTest
 * @Description Verifies the custom {@code orchestrator} health contributor of
 *              {@code GET /actuator/health}: it reports UP with the live
 *              collaboration template (name, mode, stage names), the
 *              registered agents and the runs currently retained by the
 *              observability actor as component details.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import com.oli.orchestrator_demo.ChatClientStubs;
import com.oli.orchestrator_demo.agent.Agent;
import com.oli.orchestrator_demo.model.OrchestratorContext;
import com.oli.orchestrator_demo.orchestrator.TripPlanningOrchestrator;
import com.oli.orchestrator_demo.orchestrator.WorkflowTemplateHolder;
import com.oli.orchestrator_demo.workflow.WorkflowProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.json.JsonMapper;

class OrchestratorHealthIndicatorTest {

  @Test
  void reportsUpWithTemplateAgentsAndRetainedRuns() {
    ObservabilityActor observability = ChatClientStubs.actor();
    WorkflowProperties properties = new WorkflowProperties();
    properties.getStages().add(new WorkflowProperties.StageDefinition() {{
      setName("research");
    }});
    TripPlanningOrchestrator orchestrator = new TripPlanningOrchestrator(
        List.of(new Agent<OrchestratorContext, OrchestratorContext>() {
          @Override
          public String name() {
            return "research";
          }

          @Override
          public OrchestratorContext execute(OrchestratorContext context) {
            return context;
          }
        }),
        new WorkflowTemplateHolder(properties), observability, ObservationRegistry.create(), JsonMapper.builder().build(),
        new OrchestratorMetrics(new SimpleMeterRegistry(), observability));

    Health health = new OrchestratorHealthIndicator(orchestrator, observability).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("template", "configured")
        .containsEntry("mode", "SEQUENTIAL")
        .containsEntry("stages", List.of("research"))
        .containsEntry("agents", List.of("research"))
        .containsEntry("retainedRuns", 0);
  }
}
