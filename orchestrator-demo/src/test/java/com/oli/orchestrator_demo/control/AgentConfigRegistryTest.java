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
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;

import com.oli.orchestrator_demo.agent.BudgetAgent;
import com.oli.orchestrator_demo.agent.ItineraryAgent;
import com.oli.orchestrator_demo.agent.ResearchAgent;
import com.oli.orchestrator_demo.agent.ReviewAgent;
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
        new FileControlPlaneStore(configFile, JsonMapper.builder().build()));
    registry.initialize();
    return registry;
  }

  @Test
  void persistsThroughTheDatabaseStoreAndReloadsOnRestart() {
    org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:registry" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    DbControlPlaneStore store = new DbControlPlaneStore(
        org.springframework.jdbc.core.simple.JdbcClient.create(dataSource),
        JsonMapper.builder().build(), ControlPlaneCrypto.disabled());
    store.initialize();

    holder = new WorkflowTemplateHolder(new WorkflowProperties());
    AgentConfigRegistry first = new AgentConfigRegistry(modelProperties, new WorkflowProperties(), holder, store);
    first.initialize();
    first.updateAgent("research", new AgentRuntimeConfig("research", "db persona", "m1", 0.5, List.of(), List.of()));

    // "Restart": a fresh registry over the same database reloads the state.
    AgentConfigRegistry restarted = new AgentConfigRegistry(new AgentModelProperties(),
        new WorkflowProperties(), holder, store);
    restarted.initialize();
    assertThat(restarted.state().version()).isEqualTo(2);
    assertThat(restarted.agent("research").systemPrompt()).isEqualTo("db persona");
    assertThat(restarted.agent("research").temperature()).isEqualTo(0.5);
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

    // STDIO servers need a command (the endpoint field is HTTP-only).
    assertThatThrownBy(() -> registry.updateMcpServers(List.of(
        new McpServerConfig("shell", "STDIO", "", " ", List.of(), Map.of(), false, "local tools"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("command");
    // Names become tool-name prefixes, so they must stay identifier-safe.
    assertThatThrownBy(() -> registry.updateMcpServers(List.of(
        new McpServerConfig("bad name!", "SSE", "http://a", true, ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bad name!");

    registry.updateMcpServers(List.of(
        new McpServerConfig("weather", "SSE", "http://localhost:3001/sse", true, "demo"),
        new McpServerConfig("shell", "STDIO", "", "npx", List.of("-y", "server"), Map.of(), false, "local tools")));
    assertThat(registry.state().mcpServers()).hasSize(2);
    assertThat(registry.state().mcpServers().get(1).transport()).isEqualTo("STDIO");
    assertThat(registry.state().mcpServers().get(1).command()).isEqualTo("npx");
  }

  // ---- agent descriptors (slot contracts) -----------------------------------

  /** A descriptor registry over the real agent beans (mocked model, fixed store). */
  private AgentDescriptorRegistry descriptorRegistry() {
    ChatModel model = Mockito.mock(ChatModel.class);
    return new AgentDescriptorRegistry(List.of(
        new ResearchAgent(model, AgentRuntimeStore.fixed(), null),
        new BudgetAgent(model, AgentRuntimeStore.fixed(), null),
        new ItineraryAgent(model, AgentRuntimeStore.fixed(), null),
        new ReviewAgent(model, AgentRuntimeStore.fixed(), null)), modelProperties, null);
  }

  @Test
  void attachDescriptorsSeedsTheBuiltInContractsWithoutBumpingTheVersion() throws IOException {
    AgentConfigRegistry registry = freshRegistry();
    long version = registry.state().version();

    registry.attachDescriptors(descriptorRegistry());

    assertThat(registry.state().version()).isEqualTo(version);
    assertThat(registry.state().descriptors()).containsOnlyKeys("research", "budget", "itinerary", "review");
    // The default model/temperature of the persona are enriched from the
    // `orchestrator.agents.*` configuration.
    assertThat(registry.descriptor("research").defaultModel()).isEqualTo("qwen:7b");
    assertThat(registry.descriptor("research").defaultTemperature()).isEqualTo(0.7);
    assertThat(registry.descriptor("itinerary").pendingSafeSlots()).containsExactly("budgetResponse");
    // Seeding survived the (simulated) restart.
    AgentConfigRegistry reloaded = freshRegistry();
    reloaded.attachDescriptors(descriptorRegistry());
    assertThat(reloaded.state().descriptors()).containsOnlyKeys("research", "budget", "itinerary", "review");
  }

  @Test
  void updateDescriptorMergesDefaultsValidatesAndPersists() throws IOException {
    modelProperties.getAgents().get("itinerary").setModel("qwen3:4b");
    modelProperties.getAgents().get("itinerary").setTemperature(0.3);
    AgentConfigRegistry registry = freshRegistry();
    registry.attachDescriptors(descriptorRegistry());
    long before = registry.state().version();

    ControlPlaneState state = registry.updateDescriptor("itinerary", new AgentDescriptor("itinerary",
        "Day Planner", "Plans the days.", List.of("itineraryResponse"),
        List.of("researchResponse", "budgetResponse"), List.of("budgetResponse"), null, null));

    assertThat(state.version()).isEqualTo(before + 1);
    AgentDescriptor effective = registry.descriptor("itinerary");
    assertThat(effective.title()).isEqualTo("Day Planner");
    assertThat(effective.defaultModel()).isEqualTo("qwen3:4b"); // merged from the built-in defaults
    assertThat(effective.defaultTemperature()).isEqualTo(0.3);
    assertThat(registry.state().descriptors().get("itinerary").title()).isEqualTo("Day Planner");

    assertThatThrownBy(() -> registry.updateDescriptor("ghost",
        new AgentDescriptor("ghost", "x", "", List.of(), List.of(), List.of(), null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ghost");
    assertThatThrownBy(() -> registry.updateDescriptor("research",
        new AgentDescriptor("budget", "x", "", List.of(), List.of(), List.of(), null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
    assertThatThrownBy(() -> registry.updateDescriptor("research",
        new AgentDescriptor("research", "x", "", List.of(" "), List.of(), List.of(), null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }

  @Test
  void updateDescriptorRejectsEditsThatBreakTheActiveTemplate() throws IOException {
    AgentConfigRegistry registry = freshRegistry();
    registry.attachDescriptors(descriptorRegistry());
    // research -> review is closed because the review agent declares the
    // budget/itinerary slots pending-safe.
    registry.updateWorkflow(new WorkflowControlState("SEQUENTIAL", List.of(
        new WorkflowControlState.StageControlState("research", "research", List.of()),
        new WorkflowControlState.StageControlState("review", "review", List.of("research")))));
    long before = registry.state().version();

    // Removing the pending-safe declaration would break the active template.
    assertThatThrownBy(() -> registry.updateDescriptor("review", new AgentDescriptor("review", "Review", "",
        List.of("reviewResponse"), List.of("researchResponse", "budgetResponse", "itineraryResponse"),
        List.of(), null, null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("review")
        .hasMessageContaining("budgetResponse");
    assertThat(registry.state().version()).isEqualTo(before); // rejected edits do not bump
    assertThat(registry.descriptor("review").pendingSafeSlots()).isNotEmpty();

    // Keeping the declaration is accepted.
    registry.updateDescriptor("review", new AgentDescriptor("review", "Strict Reviewer", "",
        List.of("reviewResponse"), List.of("researchResponse", "budgetResponse", "itineraryResponse"),
        List.of("budgetResponse", "itineraryResponse"), null, null));
    assertThat(registry.descriptor("review").title()).isEqualTo("Strict Reviewer");
  }

  @Test
  void updateWorkflowValidatesTheSlotClosureWhenDescriptorsAreAttached() throws IOException {
    AgentConfigRegistry registry = freshRegistry();
    registry.attachDescriptors(descriptorRegistry());

    // The parallel template is closed (pending-safe itinerary).
    registry.updateWorkflow(new WorkflowControlState("CONCURRENT", List.of(
        new WorkflowControlState.StageControlState("research", "research", List.of()),
        new WorkflowControlState.StageControlState("budget", "budget", List.of("research")),
        new WorkflowControlState.StageControlState("itinerary", "itinerary", List.of("research")),
        new WorkflowControlState.StageControlState("review", "review", List.of("research", "budget", "itinerary")))));

    // Without a research stage the budget stage's input slot is unfilled.
    assertThatThrownBy(() -> registry.updateWorkflow(new WorkflowControlState("SEQUENTIAL", List.of(
        new WorkflowControlState.StageControlState("budget", "budget", List.of())))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("budget")
        .hasMessageContaining("researchResponse")
        .hasMessageContaining("no stage of the template produces it");

    // Back to a sequential template so the itinerary's pending-safe
    // declaration can be dropped (its producer then runs strictly earlier).
    registry.updateWorkflow(new WorkflowControlState("SEQUENTIAL", List.of(
        new WorkflowControlState.StageControlState("research", "research", List.of()),
        new WorkflowControlState.StageControlState("budget", "budget", List.of("research")),
        new WorkflowControlState.StageControlState("itinerary", "itinerary", List.of("research", "budget")),
        new WorkflowControlState.StageControlState("review", "review", List.of("research", "budget", "itinerary")))));
    registry.updateDescriptor("itinerary", new AgentDescriptor("itinerary", "Itinerary", "",
        List.of("itineraryResponse"), List.of("researchResponse", "budgetResponse"), List.of(), null, null));

    // With the strict itinerary the parallel template (same-wave sibling
    // consumption) is rejected, while it was closed before the edit.
    assertThatThrownBy(() -> registry.updateWorkflow(new WorkflowControlState("CONCURRENT", List.of(
        new WorkflowControlState.StageControlState("research", "research", List.of()),
        new WorkflowControlState.StageControlState("budget", "budget", List.of("research")),
        new WorkflowControlState.StageControlState("itinerary", "itinerary", List.of("research")),
        new WorkflowControlState.StageControlState("review", "review", List.of("research", "budget", "itinerary"))))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("same wave");
  }
}
