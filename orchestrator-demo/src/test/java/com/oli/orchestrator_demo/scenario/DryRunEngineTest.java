/**
 * @ClassName DryRunEngineTest
 * @Description Verifies the scenario rehearsal engine: the default control
 *              plane template expands into four sequential waves with the full
 *              DAG edge set and slot flows derived from the agent descriptors;
 *              a CONCURRENT override groups the independent stages into one
 *              parallel wave; validation problems (unknown stage agents,
 *              slot-closure gaps) are reported as issues instead of thrown;
 *              agent overrides and scenario MCP requirements show up as the
 *              effective per-stage configuration (with warnings for
 *              unconfigured servers).
 * @Author OliverGong
 * @Date Oct 31, 2026
 * @Version 2.1
 */
package com.oli.orchestrator_demo.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.oli.orchestrator_demo.config.AgentModelProperties;
import com.oli.orchestrator_demo.control.AgentConfigRegistry;
import com.oli.orchestrator_demo.control.AgentDescriptor;
import com.oli.orchestrator_demo.control.FileControlPlaneStore;
import com.oli.orchestrator_demo.control.WorkflowControlState;
import com.oli.orchestrator_demo.orchestrator.WorkflowTemplateHolder;
import com.oli.orchestrator_demo.workflow.WorkflowProperties;

import tools.jackson.databind.json.JsonMapper;

class DryRunEngineTest {

  @TempDir
  Path tempDir;

  private AgentConfigRegistry controlPlane;
  private DryRunEngine engine;

  @BeforeEach
  void setUp() {
    WorkflowTemplateHolder holder = new WorkflowTemplateHolder(new WorkflowProperties());
    controlPlane = new AgentConfigRegistry(new AgentModelProperties(), new WorkflowProperties(), holder,
        new FileControlPlaneStore(tempDir.resolve("control-plane.json"), JsonMapper.builder().build()));
    controlPlane.initialize();
    controlPlane.updateDescriptor("research",
        new AgentDescriptor("research", "Research", "", List.of("researchResponse"), List.of(), List.of(), null, null));
    controlPlane.updateDescriptor("budget",
        new AgentDescriptor("budget", "Budget", "", List.of("budgetResponse"), List.of("researchResponse"),
            List.of(), null, null));
    controlPlane.updateDescriptor("itinerary",
        new AgentDescriptor("itinerary", "Itinerary", "", List.of("itineraryResponse"),
            List.of("researchResponse", "budgetResponse"), List.of(), null, null));
    controlPlane.updateDescriptor("review",
        new AgentDescriptor("review", "Review", "", List.of("reviewResponse"),
            List.of("researchResponse", "budgetResponse", "itineraryResponse"), List.of(), null, null));
    engine = new DryRunEngine(controlPlane);
  }

  private Scenario scenario(WorkflowControlState override, Map<String, Scenario.AgentOverride> agents,
      List<String> mcpRefs) {
    return new Scenario("dry", "推演场景", "", new Scenario.RequestTemplate("Kyoto", 2, 8000.0, ""),
        override, agents, mcpRefs, Scenario.ArtifactType.HTML, true, Instant.now());
  }

  @Test
  void expandsTheDefaultTemplateIntoWavesEdgesAndSlotFlows() {
    DryRunReport report = engine.run(scenario(null, Map.of(), List.of()));

    assertThat(report.valid()).isTrue();
    assertThat(report.resolvedTemplateSource()).isEqualTo("control-plane");
    assertThat(report.resolvedMode()).isEqualTo("SEQUENTIAL");
    assertThat(report.waves()).hasSize(4);
    assertThat(report.waves()).allSatisfy(wave -> assertThat(wave.parallel()).isFalse());
    assertThat(report.edges()).containsExactlyInAnyOrder(
        new DryRunReport.EdgeView("research", "budget"),
        new DryRunReport.EdgeView("research", "itinerary"),
        new DryRunReport.EdgeView("budget", "itinerary"),
        new DryRunReport.EdgeView("research", "review"),
        new DryRunReport.EdgeView("budget", "review"),
        new DryRunReport.EdgeView("itinerary", "review"));

    DryRunReport.SlotFlowView research = report.slotFlows().stream()
        .filter(flow -> flow.slot().equals("researchResponse")).findFirst().orElseThrow();
    assertThat(research.producedBy()).containsExactly("research");
    assertThat(research.consumedBy()).containsExactly("budget", "itinerary", "review");
    assertThat(research.pendingSafe()).isFalse();

    DryRunReport.StageView first = report.waves().get(0).stages().get(0);
    assertThat(first.agentName()).isEqualTo("research");
    assertThat(first.outputs()).containsExactly("researchResponse");
  }

  @Test
  void aConcurrentOverrideGroupsIndependentStagesIntoParallelWaves() {
    // The itinerary agent tolerates the missing budget breakdown (pending-safe),
    // which is what legalizes the same-wave parallel consumption.
    controlPlane.updateDescriptor("itinerary",
        new AgentDescriptor("itinerary", "Itinerary", "", List.of("itineraryResponse"),
            List.of("researchResponse", "budgetResponse"), List.of("budgetResponse"), null, null));
    WorkflowControlState override = new WorkflowControlState("CONCURRENT", List.of(
        new WorkflowControlState.StageControlState("research", "research", List.of()),
        new WorkflowControlState.StageControlState("budget", "budget", List.of("research")),
        new WorkflowControlState.StageControlState("itinerary", "itinerary", List.of("research")),
        new WorkflowControlState.StageControlState("review", "review",
            List.of("research", "budget", "itinerary"))));

    DryRunReport report = engine.run(scenario(override, Map.of(), List.of()));

    assertThat(report.valid()).isTrue();
    assertThat(report.resolvedTemplateSource()).isEqualTo("scenario-override");
    assertThat(report.resolvedMode()).isEqualTo("CONCURRENT");
    assertThat(report.waves()).hasSize(3);
    assertThat(report.waves().get(1).parallel()).isTrue();
    assertThat(report.waves().get(1).stages()).extracting(DryRunReport.StageView::stageName)
        .containsExactlyInAnyOrder("budget", "itinerary");
    assertThat(report.edges()).hasSize(5);
  }

  @Test
  void reportsValidationIssuesInsteadOfThrowing() {
    WorkflowControlState broken = new WorkflowControlState("SEQUENTIAL", List.of(
        new WorkflowControlState.StageControlState("s1", "research", List.of()),
        new WorkflowControlState.StageControlState("s2", "ghost-agent", List.of("s1"))));

    DryRunReport report = engine.run(scenario(broken, Map.of(), List.of()));

    assertThat(report.valid()).isFalse();
    assertThat(report.issues()).hasSize(1);
    assertThat(report.issues().get(0)).contains("unknown agent");
    // The wave expansion still runs so the console can show how far the plan gets.
    assertThat(report.waves()).hasSize(2);
    assertThat(report.waves().get(1).stages().get(0).warnings())
        .anySatisfy(warning -> assertThat(warning).contains("no runtime configuration"));
  }

  @Test
  void aSlotClosureGapIsReportedAsAnIssue() {
    // itinerary consumes budgetResponse but budget runs in a later stage.
    WorkflowControlState gapped = new WorkflowControlState("SEQUENTIAL", List.of(
        new WorkflowControlState.StageControlState("research", "research", List.of()),
        new WorkflowControlState.StageControlState("itinerary", "itinerary", List.of("research")),
        new WorkflowControlState.StageControlState("budget", "budget", List.of("itinerary"))));

    DryRunReport report = engine.run(scenario(gapped, Map.of(), List.of()));

    assertThat(report.valid()).isFalse();
    assertThat(report.issues().get(0)).contains("budgetResponse");
  }

  @Test
  void agentOverridesAndMcpRequirementsShapeTheEffectiveStageView() {
    DryRunReport report = engine.run(scenario(null,
        Map.of("research", new Scenario.AgentOverride(null, "qwen:7b", 0.2, null, List.of("weather"))),
        List.of()));

    DryRunReport.StageView research = report.waves().get(0).stages().get(0);
    assertThat(research.effectiveModel()).isEqualTo("qwen:7b");
    assertThat(research.effectiveTemperature()).isEqualTo(0.2);
    assertThat(research.mcpServers()).containsExactly("weather");
    assertThat(research.warnings()).anySatisfy(warning -> assertThat(warning).contains("not configured"));

    // Stages without overrides keep the control-plane values (null model = backend default).
    DryRunReport.StageView budget = report.waves().get(1).stages().get(0);
    assertThat(budget.effectiveModel()).isNull();
    assertThat(budget.mcpServers()).isEmpty();
    assertThat(budget.warnings()).isEmpty();
  }

  @Test
  void anUnconfiguredScenarioMcpRequirementInvalidatesTheReport() {
    DryRunReport report = engine.run(scenario(null, Map.of(), List.of("ghost-server")));

    assertThat(report.valid()).isFalse();
    assertThat(report.issues()).anySatisfy(issue -> assertThat(issue).contains("ghost-server"));
  }
}
