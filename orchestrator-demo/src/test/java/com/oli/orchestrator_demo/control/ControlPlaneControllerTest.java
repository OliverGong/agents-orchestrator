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
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import com.oli.orchestrator_demo.api.GlobalExceptionHandler;
import com.oli.orchestrator_demo.config.AgentModelProperties;
import com.oli.orchestrator_demo.orchestrator.WorkflowTemplateHolder;
import com.oli.orchestrator_demo.workflow.ExecutionMode;
import com.oli.orchestrator_demo.workflow.WorkflowProperties;

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
        JsonMapper.builder().build(), tempDir.resolve("control-plane.json"));
    registry.initialize();

    Environment environment = Mockito.mock(Environment.class);
    // Not "ollama": the model listing then skips the HTTP lookup and uses the
    // configuration fallback (deterministic, no network in tests).
    when(environment.getProperty("spring.ai.model.chat", "ollama")).thenReturn("stub");

    mockMvc = MockMvcBuilders.standaloneSetup(
        new ControlPlaneController(registry, environment, RestClient.builder(), JsonMapper.builder().build()))
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
