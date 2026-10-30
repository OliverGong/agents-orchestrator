/**
 * @ClassName AgentConfigRegistryTest
 * @Description Verifies the file-backed control-plane registry: defaults are
 *              seeded and persisted, a restarted registry reloads the
 *              persisted state and re-applies the hot-swapped workflow
 *              template, updates are validated (unknown agents, blank
 *              prompts, dangling MCP references, duplicate MCP names, unknown
 *              transports) and hot-applied (agent config per call, workflow
 *              template into the holder) with a monotonically increasing
 *              version.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.oli.orchestrator_demo.config.AgentModelProperties;
import com.oli.orchestrator_demo.orchestrator.WorkflowTemplateHolder;
import com.oli.orchestrator_demo.workflow.ExecutionMode;
import com.oli.orchestrator_demo.workflow.StageSpec;
import com.oli.orchestrator_demo.workflow.WorkflowProperties;
import com.oli.orchestrator_demo.workflow.WorkflowTemplate;

import tools.jackson.databind.json.JsonMapper;

class AgentConfigRegistryTest {

  @TempDir
  Path tempDir;

  private AgentModelProperties modelProperties;
  private WorkflowTemplateHolder holder;
  private Path configFile;

  @BeforeEach
  void setUp() {
    modelProperties = new AgentModelProperties();
    modelProperties.getAgents().get("research").setModel("qwen:7b");
    modelProperties.getAgents().get("research").setTemperature(0.7);
    configFile = tempDir.resolve("control-plane.json");
  }

  private AgentConfigRegistry freshRegistry() throws IOException {
    holder = new WorkflowTemplateHolder(new WorkflowProperties());
    AgentConfigRegistry registry = new AgentConfigRegistry(modelProperties, new WorkflowProperties(), holder,
        JsonMapper.builder().build(), configFile);
    registry.initialize();
    return registry;
  }

  @Test
  void seedsDefaultsAndPersistsThem() throws IOException {
    AgentConfigRegistry registry = freshRegistry();

    assertThat(Files.exists(configFile)).isTrue();
    ControlPlaneState state = registry.state();
    assertThat(state.version()).isEqualTo(1);
    assertThat(state.agents().keySet()).containsExactly("research", "budget", "itinerary", "review");
    assertThat(state.agents().get("research").model()).isEqualTo("qwen:7b");
    assertThat(state.agents().get("research").temperature()).isEqualTo(0.7);
    assertThat(state.agents().get("research").systemPrompt()).contains("travel research agent");
    assertThat(state.agents().get("research").calibrationRules()).isEmpty();
    assertThat(state.workflow().mode()).isEqualTo("SEQUENTIAL");
    assertThat(state.mcpServers()).isEmpty();
    assertThat(holder.get().name()).isEqualTo("default-trip-planning");
  }

  @Test
  void reloadsPersistedStateAndReappliesTheHotSwappedWorkflow() throws IOException {
    AgentConfigRegistry first = freshRegistry();
    first.updateWorkflow(new WorkflowControlState("CONCURRENT", List.of(
        new WorkflowControlState.StageControlState("research", "research", List.of()),
        new WorkflowControlState.StageControlState("review", "review", List.of("research")))));
    long version = first.state().version();

    // A brand-new registry (simulating the restart) restores everything.
    AgentConfigRegistry second = freshRegistry();

    assertThat(second.state().version()).isEqualTo(version);
    assertThat(second.agent("research").model()).isEqualTo("qwen:7b");
    assertThat(second.agent("research").systemPrompt()).contains("travel research agent");
    assertThat(holder.get().mode()).isEqualTo(ExecutionMode.CONCURRENT);
    assertThat(holder.get().stages()).extracting(StageSpec::name).containsExactly("research", "review");
  }

  @Test
  void updateAgentBumpsVersionHotAppliesAndPersists() throws IOException {
    AgentConfigRegistry registry = freshRegistry();
    // The weather MCP server must exist before an agent can reference it.
    registry.updateMcpServers(List.of(
        new McpServerConfig("weather", "SSE", "http://localhost:3001/sse", true, "demo")));
    long before = registry.state().version();

    ControlPlaneState state = registry.updateAgent("research",
        new AgentRuntimeConfig("research", "You are terse.", "qwen3:4b", 0.1,
            List.of(new CalibrationRule("\\s+$", "", "trim trailing whitespace")), List.of("weather")));

    assertThat(state.version()).isEqualTo(before + 1);
    assertThat(registry.agent("research").systemPrompt()).isEqualTo("You are terse.");
    assertThat(registry.agent("research").model()).isEqualTo("qwen3:4b");
    assertThat(registry.agent("research").temperature()).isEqualTo(0.1);
    assertThat(registry.agent("research").calibrationRules()).hasSize(1);
    assertThat(registry.agent("research").mcpRefs()).containsExactly("weather");

    // The update survived the (simulated) restart.
    assertThat(freshRegistry().agent("research").temperature()).isEqualTo(0.1);
  }

  @Test
  void updateAgentRejectsUnknownAgentsBlankPromptsAndDanglingMcpRefs() throws IOException {
    AgentConfigRegistry registry = freshRegistry();
    registry.updateMcpServers(List.of(
        new McpServerConfig("weather", "SSE", "http://localhost:3001/sse", true, "demo")));

    assertThatThrownBy(() -> registry.updateAgent("ghost",
        new AgentRuntimeConfig("ghost", "x", null, null, List.of(), List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ghost");
    assertThatThrownBy(() -> registry.updateAgent("research",
        new AgentRuntimeConfig("research", "   ", null, null, List.of(), List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("systemPrompt");
    assertThatThrownBy(() -> registry.updateAgent("research",
        new AgentRuntimeConfig("research", "x", null, null, List.of(), List.of("nope"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nope");
  }

  @Test
  void updateWorkflowValidatesAndHotSwapsTheTemplate() throws IOException {
    AgentConfigRegistry registry = freshRegistry();

    // Unknown agent reference fails validation (from WorkflowTemplate.validate).
    assertThatThrownBy(() -> registry.updateWorkflow(new WorkflowControlState("SEQUENTIAL", List.of(
        new WorkflowControlState.StageControlState("ghost", "ghost", List.of())))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ghost");
    // Unknown execution mode fails validation.
    assertThatThrownBy(() -> registry.updateWorkflow(
        new WorkflowControlState("WAT", List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("WAT");
    // The invalid attempts did not touch the holder or the version.
    assertThat(holder.get().name()).isEqualTo("default-trip-planning");

    registry.updateWorkflow(new WorkflowControlState("CONCURRENT", List.of(
        new WorkflowControlState.StageControlState("research", "research", List.of()),
        new WorkflowControlState.StageControlState("review", "review", List.of("research")))));

    assertThat(holder.get().mode()).isEqualTo(ExecutionMode.CONCURRENT);
    assertThat(holder.get().stages()).extracting(StageSpec::name).containsExactly("research", "review");
    assertThat(registry.state().version()).isEqualTo(2); // 1 seeded + 1 applied; rejected updates do not bump
  }

  @Test
  void updateMcpServersValidatesUniquenessTransportAndEndpoint() throws IOException {
    AgentConfigRegistry registry = freshRegistry();

    assertThatThrownBy(() -> registry.updateMcpServers(List.of(
        new McpServerConfig("weather", "SSE", "http://a", true, ""),
        new McpServerConfig("weather", "SSE", "http://b", true, ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate");
    assertThatThrownBy(() -> registry.updateMcpServers(List.of(
        new McpServerConfig("weather", "GRPC", "http://a", true, ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("transport");
    assertThatThrownBy(() -> registry.updateMcpServers(List.of(
        new McpServerConfig("weather", "SSE", " ", true, ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("endpoint");

    registry.updateMcpServers(List.of(
        new McpServerConfig("weather", "SSE", "http://localhost:3001/sse", true, "demo"),
        new McpServerConfig("shell", "STDIO", "npx -y server", false, "local tools")));
    assertThat(registry.state().mcpServers()).hasSize(2);
    assertThat(registry.state().mcpServers().get(1).transport()).isEqualTo("STDIO");
  }
}
