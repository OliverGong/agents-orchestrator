/**
 * @ClassName ControlPlaneController
 * @Description HTTP surface of the control plane under
 *              {@code /api/v1/control}: runtime-editable agent configuration
 *              (system prompt, model selection, temperature, calibration
 *              rules, MCP mapping), the collaboration template and the
 *              recorded MCP servers, plus the available model list for the
 *              selection UI. Every PUT validates its input, hot-applies the
 *              change and returns the bumped control-plane state, so the
 *              console can render the new version immediately. Validation
 *              failures surface as 400 via the global exception handler.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/control")
public class ControlPlaneController {

  private static final Logger log = LoggerFactory.getLogger(ControlPlaneController.class);

  private final AgentConfigRegistry registry;
  private final Environment environment;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  public ControlPlaneController(AgentConfigRegistry registry, Environment environment,
      RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
    this.registry = registry;
    this.environment = environment;
    this.restClient = restClientBuilder.build();
    this.objectMapper = objectMapper;
  }

  /** Full snapshot: agents + workflow + MCP servers + version. */
  @GetMapping("/state")
  public ControlPlaneState state() {
    return registry.state();
  }

  @GetMapping("/agents")
  public Map<String, AgentRuntimeConfig> agents() {
    return registry.state().agents();
  }

  @GetMapping("/agents/{name}")
  public ResponseEntity<AgentRuntimeConfig> agent(@PathVariable String name) {
    AgentRuntimeConfig config = registry.state().agents().get(name);
    return config == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(config);
  }

  @PutMapping("/agents/{name}")
  public ControlPlaneState updateAgent(@PathVariable String name, @RequestBody AgentRuntimeConfig update) {
    return registry.updateAgent(name, update);
  }

  @GetMapping("/workflow")
  public WorkflowControlState workflow() {
    return registry.state().workflow();
  }

  @PutMapping("/workflow")
  public ControlPlaneState updateWorkflow(@RequestBody WorkflowControlState workflow) {
    try {
      return registry.updateWorkflow(workflow);
    }
    catch (IllegalStateException ex) {
      // Template validation failures (unknown references, dependency order,
      // cycles) are configuration mistakes -> 400, not a 500.
      throw new IllegalArgumentException(ex.getMessage());
    }
  }

  @GetMapping("/mcp/servers")
  public List<McpServerConfig> mcpServers() {
    return registry.state().mcpServers();
  }

  @PutMapping("/mcp/servers")
  public ControlPlaneState updateMcpServers(@RequestBody List<McpServerConfig> servers) {
    return registry.updateMcpServers(servers);
  }

  /**
   * Model names for the selection UI. With the Ollama backend the live list
   * comes from {@code GET {base-url}/api/tags}; any failure falls back to the
   * models referenced by the current configuration (so the console keeps
   * working offline).
   */
  @GetMapping("/models")
  public Map<String, Object> models() {
    String backend = environment.getProperty("spring.ai.model.chat", "ollama");
    List<String> models = new ArrayList<>();
    if ("ollama".equals(backend)) {
      String baseUrl = environment.getProperty("spring.ai.ollama.base-url", "http://localhost:11434");
      try {
        String body = restClient.get().uri(baseUrl + "/api/tags").retrieve().body(String.class);
        JsonNode entries = objectMapper.readTree(body).get("models");
        if (entries != null) {
          entries.forEach(entry -> models.add(entry.get("name").asText()));
        }
      }
      catch (RuntimeException ex) {
        log.warn("Model list from Ollama ({}) unavailable, falling back to configuration: {}",
            baseUrl, ex.getMessage());
      }
    }
    if (models.isEmpty()) {
      registry.state().agents().values().stream()
          .map(AgentRuntimeConfig::model)
          .filter(Objects::nonNull)
          .distinct()
          .forEach(models::add);
    }
    return Map.of("backend", backend, "models", models);
  }
}
