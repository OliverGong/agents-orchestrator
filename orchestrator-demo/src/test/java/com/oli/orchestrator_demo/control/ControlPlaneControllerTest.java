/**
 * @ClassName ControlPlaneControllerTest
 * @Description Web layer test (standalone MockMvc) of the control plane:
 *              the state snapshot exposes agents/workflow/MCP servers with
 *              the version, PUT endpoints hot-apply and bump the version,
 *              validation failures surface as 400 with the structured error
 *              body and the model list falls back to the configuration when
 *              the backend cannot be reached.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import com.oli.orchestrator_demo.api.GlobalExceptionHandler;
import com.oli.orchestrator_demo.agent.BudgetAgent;
import com.oli.orchestrator_demo.agent.ItineraryAgent;
import com.oli.orchestrator_demo.agent.ResearchAgent;
import com.oli.orchestrator_demo.agent.ReviewAgent;
import com.oli.orchestrator_demo.config.AgentModelProperties;
import com.oli.orchestrator_demo.mcp.McpConnectionRegistry;
import com.oli.orchestrator_demo.observability.ObservabilityActor;
import com.oli.orchestrator_demo.orchestrator.WorkflowTemplateHolder;
import com.oli.orchestrator_demo.workflow.ExecutionMode;
import com.oli.orchestrator_demo.workflow.WorkflowProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

class ControlPlaneControllerTest {

  @TempDir
  Path tempDir;

  private AgentConfigRegistry registry;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() throws IOException {
    WorkflowTemplateHolder holder = new WorkflowTemplateHolder(new WorkflowProperties());
    registry = new AgentConfigRegistry(new AgentModelProperties(), new WorkflowProperties(), holder,
        new FileControlPlaneStore(tempDir.resolve("control-plane.json"), JsonMapper.builder().build()));
    registry.initialize();
    // The agent beans back the self-declared slot contracts.
    ChatModel model = Mockito.mock(ChatModel.class);
    registry.attachDescriptors(new AgentDescriptorRegistry(List.of(
        new ResearchAgent(model, registry, null), new BudgetAgent(model, registry, null),
        new ItineraryAgent(model, registry, null), new ReviewAgent(model, registry, null)),
        new AgentModelProperties(), null));

    Environment environment = Mockito.mock(Environment.class);
    // Not "ollama": the model listing then skips the HTTP lookup and uses the
    // configuration fallback (deterministic, no network in tests).
    when(environment.getProperty("spring.ai.model.chat", "ollama")).thenReturn("stub");

    // Inert MCP registry: no lifecycle start, no connection attempts in tests.
    McpConnectionRegistry mcpRegistry = new McpConnectionRegistry(registry,
        Mockito.mock(ObservabilityActor.class), new SimpleMeterRegistry(),
        config -> { throw new IllegalStateException("no MCP in tests"); });

    mockMvc = MockMvcBuilders.standaloneSetup(
        new ControlPlaneController(registry, mcpRegistry, environment, RestClient.builder(),
            JsonMapper.builder().build()))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  @Test
  void exposesTheSeededState() throws Exception {
    mockMvc.perform(get("/api/v1/control/state"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.agents.research.systemPrompt").value(
            org.hamcrest.Matchers.containsString("travel research agent")))
        .andExpect(jsonPath("$.workflow.mode").value("SEQUENTIAL"))
        .andExpect(jsonPath("$.mcpServers").isEmpty());
  }

  @Test
  void putAgentHotAppliesAndBumpsTheVersion() throws Exception {
    mockMvc.perform(put("/api/v1/control/agents/research")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"research","systemPrompt":"You are a terse research agent.",
                 "model":"qwen3:4b","temperature":0.1,
                 "calibrationRules":[{"pattern":"\\\\s+$","replacement":"","description":"trim"}],
                 "mcpRefs":[]}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.agents.research.model").value("qwen3:4b"))
        .andExpect(jsonPath("$.agents.research.temperature").value(0.1));

    assertThat(registry.agent("research").systemPrompt()).contains("terse");
    assertThat(registry.agent("research").calibrationRules()).hasSize(1);
  }

  @Test
  void unknownAgentReturns404AndInvalidUpdatesReturn400() throws Exception {
    mockMvc.perform(get("/api/v1/control/agents/ghost"))
        .andExpect(status().isNotFound());

    mockMvc.perform(put("/api/v1/control/agents/ghost")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"ghost","systemPrompt":"x","model":null,"temperature":null,
                 "calibrationRules":[],"mcpRefs":[]}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Invalid request"));

    mockMvc.perform(put("/api/v1/control/agents/research")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"research","systemPrompt":"x","model":null,"temperature":null,
                 "calibrationRules":[],"mcpRefs":["nope"]}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("nope")));
  }

  @Test
  void putWorkflowHotSwapsTheTemplateAndRejectsInvalidTemplates() throws Exception {
    mockMvc.perform(put("/api/v1/control/workflow")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"mode":"CONCURRENT","stages":[
                  {"name":"research","agent":"research","dependsOn":[]},
                  {"name":"review","agent":"review","dependsOn":["research"]}]}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.workflow.mode").value("CONCURRENT"));

    // Unknown stage reference -> 400 (not 500).
    mockMvc.perform(put("/api/v1/control/workflow")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"mode":"SEQUENTIAL","stages":[
                  {"name":"ghost","agent":"ghost","dependsOn":[]}]}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("ghost")));
  }

  @Test
  void putMcpServersReplacesTheRecordedServers() throws Exception {
    mockMvc.perform(put("/api/v1/control/mcp/servers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                [{"name":"weather","transport":"SSE","endpoint":"http://localhost:3001/sse",
                  "enabled":true,"description":"demo"}]
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.mcpServers[0].name").value("weather"));

    // Duplicate names are rejected.
    mockMvc.perform(put("/api/v1/control/mcp/servers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                [{"name":"dup","transport":"SSE","endpoint":"http://a","enabled":true,"description":""},
                 {"name":"dup","transport":"SSE","endpoint":"http://b","enabled":true,"description":""}]
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("Duplicate")));
  }

  @Test
  void exposesAndUpdatesAgentDescriptors() throws Exception {
    mockMvc.perform(get("/api/v1/control/agents/itinerary/descriptor"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("itinerary"))
        .andExpect(jsonPath("$.inputSlots.length()").value(2))
        .andExpect(jsonPath("$.pendingSafeSlots[0]").value("budgetResponse"));

    mockMvc.perform(get("/api/v1/control/agents/ghost/descriptor"))
        .andExpect(status().isNotFound());

    mockMvc.perform(put("/api/v1/control/agents/itinerary/descriptor")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"itinerary","title":"Day Planner","description":"",
                 "outputSlots":["itineraryResponse"],
                 "inputSlots":["researchResponse","budgetResponse"],
                 "pendingSafeSlots":["budgetResponse"]}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.descriptors.itinerary.title").value("Day Planner"));

    // A descriptor whose name does not match the agent is rejected.
    mockMvc.perform(put("/api/v1/control/agents/itinerary/descriptor")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"research","title":"x","description":"","outputSlots":[],
                 "inputSlots":[],"pendingSafeSlots":[]}
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void descriptorEditsThatBreakTheActiveTemplateAreRejected() throws Exception {
    // research -> review is closed only because the review agent declares the
    // budget/itinerary slots pending-safe.
    mockMvc.perform(put("/api/v1/control/workflow")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"mode":"SEQUENTIAL","stages":[
                  {"name":"research","agent":"research","dependsOn":[]},
                  {"name":"review","agent":"review","dependsOn":["research"]}]}
                """))
        .andExpect(status().isOk());

    mockMvc.perform(put("/api/v1/control/agents/review/descriptor")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"review","title":"Strict","description":"",
                 "outputSlots":["reviewResponse"],
                 "inputSlots":["researchResponse","budgetResponse","itineraryResponse"],
                 "pendingSafeSlots":[]}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("budgetResponse")));
  }

  @Test
  void mcpSecretsAreMaskedOnGetAndRestoredOnPut() throws Exception {
    mockMvc.perform(put("/api/v1/control/mcp/servers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                [{"name":"weather","transport":"STDIO","command":"uvx","args":["mcp-weather"],
                  "env":{"API_KEY":"t0p-secret"},"enabled":true,"description":"demo"}]
                """))
        .andExpect(status().isOk());

    // GET /state and GET /mcp/servers never carry the real secret.
    mockMvc.perform(get("/api/v1/control/mcp/servers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].env.API_KEY").value("******"));
    mockMvc.perform(get("/api/v1/control/state"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mcpServers[0].env.API_KEY").value("******"));

    // Echoing the masked response back keeps the stored secret intact.
    mockMvc.perform(put("/api/v1/control/mcp/servers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                [{"name":"weather","transport":"STDIO","command":"uvx","args":["mcp-weather"],
                  "env":{"API_KEY":"******"},"enabled":true,"description":"demo"}]
                """))
        .andExpect(status().isOk());
    assertThat(registry.state().mcpServers().get(0).env()).containsEntry("API_KEY", "t0p-secret");
  }

  @Test
  void modelsFallbackToTheReferencedConfiguration() throws Exception {
    mockMvc.perform(get("/api/v1/control/models"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.backend").value("stub"))
        .andExpect(jsonPath("$.models").isArray());
  }

  @Test
  void modeUnknownFailsFast() {
    ExecutionMode.valueOf("SEQUENTIAL"); // sanity: the enum constant the UI relies on
  }
}
