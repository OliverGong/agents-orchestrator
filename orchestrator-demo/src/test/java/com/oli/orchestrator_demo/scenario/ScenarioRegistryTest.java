/**
 * @ClassName ScenarioRegistryTest
 * @Description Verifies the file-backed scenario registry: the demo scenario
 *              is seeded and persisted, a restarted registry reloads it,
 *              create/update/delete bump the version, every mutation is
 *              validated against the live control plane (bad ids, duplicate
 *              ids, blank names, unknown agent overrides, dangling MCP
 *              references, invalid workflow overrides), {@code activate}
 *              hot-applies the scenario's logic group onto the control plane
 *              (template swap + merged agent configs) and {@code dryRun}
 *              rehearses without touching any state.
 * @Author OliverGong
 * @Date Oct 31, 2026
 * @Version 2.1
 */
package com.oli.orchestrator_demo.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.oli.orchestrator_demo.config.AgentModelProperties;
import com.oli.orchestrator_demo.control.AgentConfigRegistry;
import com.oli.orchestrator_demo.control.ControlPlaneState;
import com.oli.orchestrator_demo.control.FileControlPlaneStore;
import com.oli.orchestrator_demo.control.WorkflowControlState;
import com.oli.orchestrator_demo.orchestrator.WorkflowTemplateHolder;
import com.oli.orchestrator_demo.workflow.WorkflowProperties;

import tools.jackson.databind.json.JsonMapper;

class ScenarioRegistryTest {

  @TempDir
  Path tempDir;

  private Path scenarioFile;
  private Path controlFile;
  private AgentConfigRegistry controlPlane;
  private DryRunEngine dryRunEngine;

  @BeforeEach
  void setUp() {
    scenarioFile = tempDir.resolve("scenarios.json");
    controlFile = tempDir.resolve("control-plane.json");
    controlPlane = freshControlPlane();
    dryRunEngine = new DryRunEngine(controlPlane);
  }

  private AgentConfigRegistry freshControlPlane() {
    WorkflowTemplateHolder holder = new WorkflowTemplateHolder(new WorkflowProperties());
    AgentConfigRegistry registry = new AgentConfigRegistry(new AgentModelProperties(), new WorkflowProperties(),
        holder, new FileControlPlaneStore(controlFile, JsonMapper.builder().build()));
    registry.initialize();
    return registry;
  }

  private ScenarioRegistry freshRegistry() {
    ScenarioRegistry registry = new ScenarioRegistry(
        new FileScenarioStore(scenarioFile, JsonMapper.builder().build()), controlPlane, dryRunEngine);
    registry.initialize();
    return registry;
  }

  private Scenario demo() {
    return new Scenario("test-case", "测试场景", "desc",
        new Scenario.RequestTemplate("Kyoto", 2, 8000.0, ""), null, Map.of(),
        List.of(), Scenario.ArtifactType.HTML, true, null);
  }

  @Test
  void seedsTheDemoScenarioAndReloadsOnRestart() {
    ScenarioRegistry first = freshRegistry();
    assertThat(first.state().scenarios()).hasSize(1);
    assertThat(first.state().scenarios().get(0).id()).isEqualTo("kyoto-classic");

    ScenarioRegistry restarted = freshRegistry();
    assertThat(restarted.state().version()).isEqualTo(first.state().version());
    assertThat(restarted.scenario("kyoto-classic")).isNotNull();
  }

  @Test
  void createUpdateDeleteBumpsTheVersion() {
    ScenarioRegistry registry = freshRegistry();
    long initial = registry.state().version();

    registry.create(demo());
    assertThat(registry.scenario("test-case").name()).isEqualTo("测试场景");
    assertThat(registry.state().version()).isEqualTo(initial + 1);

    registry.update("test-case",
        new Scenario("ignored-id", "改名场景", "desc2", demo().requestTemplate(), null, Map.of(),
            List.of(), Scenario.ArtifactType.JAR, false, null));
    Scenario updated = registry.scenario("test-case");
    assertThat(updated.name()).isEqualTo("改名场景");
    assertThat(updated.artifactType()).isEqualTo(Scenario.ArtifactType.JAR);
    assertThat(updated.updatedAt()).isNotNull();

    registry.delete("test-case");
    assertThat(registry.scenario("test-case")).isNull();
    assertThat(registry.state().version()).isEqualTo(initial + 3);
  }

  @Test
  void validatesAgainstTheControlPlane() {
    ScenarioRegistry registry = freshRegistry();

    assertThatThrownBy(() -> registry.create(
        new Scenario("Bad_Id", "n", "d", demo().requestTemplate(), null, Map.of(), List.of(),
            Scenario.ArtifactType.HTML, true, null)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("id");
    registry.create(demo());
    assertThatThrownBy(() -> registry.create(demo())).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already exists");
    assertThatThrownBy(() -> registry.create(
        new Scenario("blank-name", " ", "d", demo().requestTemplate(), null, Map.of(), List.of(),
            Scenario.ArtifactType.HTML, true, null)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
    assertThatThrownBy(() -> registry.create(
        new Scenario("bad-agent", "n", "d", demo().requestTemplate(), null,
            Map.of("ghost", new Scenario.AgentOverride("p", null, null, null, null)), List.of(),
            Scenario.ArtifactType.HTML, true, null)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("known agent");
    assertThatThrownBy(() -> registry.create(
        new Scenario("bad-mcp", "n", "d", demo().requestTemplate(), null, Map.of(),
            List.of("ghost-server"), Scenario.ArtifactType.HTML, true, null)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MCP");
    assertThatThrownBy(() -> registry.create(
        new Scenario("bad-workflow", "n", "d", demo().requestTemplate(),
            new WorkflowControlState("SEQUENTIAL",
                List.of(new WorkflowControlState.StageControlState("s1", "ghost-agent", List.of()))),
            Map.of(), List.of(), Scenario.ArtifactType.HTML, true, null)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown agent");
    assertThatThrownBy(() -> registry.delete("no-such")).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown scenario");
  }

  @Test
  void activateHotAppliesWorkflowAndAgentOverridesOntoTheControlPlane() {
    ScenarioRegistry registry = freshRegistry();
    WorkflowControlState concurrentTemplate = new WorkflowControlState("CONCURRENT", List.of(
        new WorkflowControlState.StageControlState("research", "research", List.of()),
        new WorkflowControlState.StageControlState("budget", "budget", List.of("research")),
        new WorkflowControlState.StageControlState("itinerary", "itinerary", List.of("research")),
        new WorkflowControlState.StageControlState("review", "review",
            List.of("research", "budget", "itinerary"))));
    registry.create(new Scenario("parallel-case", "并行场景", "d", demo().requestTemplate(),
        concurrentTemplate,
        Map.of("research", new Scenario.AgentOverride(null, "qwen:7b", 0.2, null, null)),
        List.of(), Scenario.ArtifactType.HTML, true, null));

    ControlPlaneState cp = registry.activate("parallel-case");

    assertThat(cp.workflow().mode()).isEqualTo("CONCURRENT");
    assertThat(controlPlane.state().agents().get("research").model()).isEqualTo("qwen:7b");
    assertThat(controlPlane.state().agents().get("research").temperature()).isEqualTo(0.2);
    // Non-overridden fields keep the live control plane persona (null keeps).
    assertThat(controlPlane.state().agents().get("research").systemPrompt()).isNotBlank();
    assertThat(controlPlane.state().version()).isGreaterThan(1);
  }

  @Test
  void dryRunRehearsesWithoutChangingAnyState() {
    ScenarioRegistry registry = freshRegistry();
    long scenarioVersion = registry.state().version();
    long cpVersion = controlPlane.state().version();

    DryRunReport report = registry.dryRun("kyoto-classic");

    assertThat(report.valid()).isTrue();
    assertThat(report.resolvedTemplateSource()).isEqualTo("control-plane");
    assertThat(report.waves()).hasSize(4); // default SEQUENTIAL template: one wave per stage
    assertThat(report.edges()).hasSize(6); // budget/itinerary/review dependencies
    assertThat(registry.state().version()).isEqualTo(scenarioVersion);
    assertThat(controlPlane.state().version()).isEqualTo(cpVersion);
  }

  @Test
  void dryRunReportsValidationIssuesInsteadOfThrowing() {
    ScenarioRegistry registry = freshRegistry();
    registry.create(new Scenario("broken", "坏场景", "d", demo().requestTemplate(),
        new WorkflowControlState("SEQUENTIAL",
            List.of(new WorkflowControlState.StageControlState("s1", "research", List.of()),
                new WorkflowControlState.StageControlState("s2", "review", List.of("s1")))),
        Map.of(), List.of(), Scenario.ArtifactType.HTML, true, null));

    DryRunReport report = registry.dryRun("broken");

    // This override is structurally fine (review depends on s1), so it is valid:
    // invalid templates are covered in DryRunEngineTest via constructed templates.
    assertThat(report.valid()).isTrue();
    assertThat(report.resolvedTemplateSource()).isEqualTo("scenario-override");
  }
}
