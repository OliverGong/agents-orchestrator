/**
 * @ClassName AgentConfigRegistry
 * @Description The file-backed store of the control plane (production
 *              {@link AgentRuntimeStore}). On startup it either loads the
 *              persisted {@link ControlPlaneState} JSON or seeds it from the
 *              built-in personas and the `orchestrator.agents.*` /
 *              `orchestrator.workflow.*` properties; afterwards the state
 *              file is the single source of truth. Every update is
 *              synchronized (single-writer semantics), validates its input,
 *              bumps the version, hot-applies the change (workflow template
 *              into the {@link WorkflowTemplateHolder}; agent configuration
 *              is read per call by the agents) and persists the state
 *              atomically (temp file + move), so a crash can never leave a
 *              half-written file behind.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.control;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.oli.orchestrator_demo.config.AgentModelProperties;
import com.oli.orchestrator_demo.orchestrator.WorkflowTemplateHolder;
import com.oli.orchestrator_demo.workflow.ExecutionMode;
import com.oli.orchestrator_demo.workflow.StageSpec;
import com.oli.orchestrator_demo.workflow.WorkflowProperties;
import com.oli.orchestrator_demo.workflow.WorkflowTemplate;

import jakarta.annotation.PostConstruct;
import tools.jackson.databind.ObjectMapper;

@Component
public class AgentConfigRegistry implements AgentRuntimeStore {

  private static final Logger log = LoggerFactory.getLogger(AgentConfigRegistry.class);

  private final AgentModelProperties modelProperties;
  private final WorkflowProperties workflowProperties;
  private final WorkflowTemplateHolder templateHolder;
  private final ObjectMapper objectMapper;
  private final Set<String> knownAgents;
  private final Path configFile;

  /** Guarded by this; readers get immutable record snapshots. */
  private ControlPlaneState state;

  public AgentConfigRegistry(AgentModelProperties modelProperties, WorkflowProperties workflowProperties,
      WorkflowTemplateHolder templateHolder, ObjectMapper objectMapper,
      @Value("${orchestrator.control.config-file:config/control-plane.json}") Path configFile) {
    this.modelProperties = modelProperties;
    this.workflowProperties = workflowProperties;
    this.templateHolder = templateHolder;
    this.objectMapper = objectMapper;
    // The demo roster is static: the same list seeds the state file, so it is
    // also the validation reference for workflow stage agents. Depending on
    // the orchestrator here would create a constructor cycle (agents -> store
    // -> registry -> orchestrator -> agents).
    this.knownAgents = Set.copyOf(AgentDefaults.KNOWN_AGENTS);
    this.configFile = configFile;
  }

  @PostConstruct
  void initialize() throws IOException {
    if (Files.exists(configFile)) {
      state = objectMapper.readValue(configFile, ControlPlaneState.class);
      WorkflowTemplate persisted = toTemplate(state.workflow());
      persisted.validate(knownAgents);
      templateHolder.replace(persisted);
      log.info("Control plane state loaded from {} (version {}, template '{}')",
          configFile, state.version(), persisted.name());
    }
    else {
      Map<String, AgentRuntimeConfig> agents = new LinkedHashMap<>();
      for (String name : AgentDefaults.KNOWN_AGENTS) {
        agents.put(name, AgentDefaults.defaultConfig(name, modelProperties));
      }
      state = new ControlPlaneState(1, Instant.now(), agents,
          fromTemplate(workflowProperties.toTemplate()), List.of());
      save();
      log.info("Control plane state seeded to {} (template '{}')",
          configFile, workflowProperties.toTemplate().name());
    }
  }

  @Override
  public AgentRuntimeConfig agent(String name) {
    AgentRuntimeConfig config = state.agents().get(name);
    if (config == null) {
      throw new IllegalStateException(
          "No runtime configuration for agent '%s'. Known agents: %s".formatted(name, state.agents().keySet()));
    }
    return config;
  }

  /** The full control-plane snapshot (immutable record). */
  public ControlPlaneState state() {
    return state;
  }

  /**
   * Hot-applies a new runtime configuration for one agent: blank prompts and
   * MCP references pointing at unrecorded servers are rejected.
   */
  public synchronized ControlPlaneState updateAgent(String name, AgentRuntimeConfig update) {
    if (!state.agents().containsKey(name)) {
      throw new IllegalArgumentException(
          "Unknown agent '%s'. Known agents: %s".formatted(name, state.agents().keySet()));
    }
    if (update.systemPrompt() == null || update.systemPrompt().isBlank()) {
      throw new IllegalArgumentException("systemPrompt of agent '%s' must not be blank".formatted(name));
    }
    Set<String> serverNames = new HashSet<>(state.mcpServers().stream().map(McpServerConfig::name).toList());
    for (String ref : update.mcpRefs()) {
      if (!serverNames.contains(ref)) {
        throw new IllegalArgumentException(
            "MCP reference '%s' of agent '%s' is not a configured MCP server".formatted(ref, name));
      }
    }
    Map<String, AgentRuntimeConfig> agents = new LinkedHashMap<>(state.agents());
    agents.put(name, new AgentRuntimeConfig(name, update.systemPrompt(), emptyToNull(update.model()),
        update.temperature(), update.calibrationRules(), update.mcpRefs()));
    state = new ControlPlaneState(state.version() + 1, Instant.now(), agents, state.workflow(), state.mcpServers());
    save();
    log.info("Control plane: agent '{}' updated (version {})", name, state.version());
    return state;
  }

  /**
   * Hot-applies a new collaboration template: validates it against the
   * registered agents (unknown references, dependency order, cycles) and
   * swaps it into the running orchestrator before persisting.
   */
  public synchronized ControlPlaneState updateWorkflow(WorkflowControlState workflow) {
    WorkflowTemplate template = toTemplate(workflow);
    template.validate(knownAgents);
    templateHolder.replace(template);
    state = new ControlPlaneState(state.version() + 1, Instant.now(), state.agents(), workflow, state.mcpServers());
    save();
    log.info("Control plane: workflow template replaced with '{}' ({}) (version {})",
        template.name(), template.mode(), state.version());
    return state;
  }

  /** Replaces the recorded MCP servers; names must be unique and transports known. */
  public synchronized ControlPlaneState updateMcpServers(List<McpServerConfig> servers) {
    Set<String> names = new HashSet<>();
    for (McpServerConfig server : servers) {
      if (server.name().isBlank()) {
        throw new IllegalArgumentException("MCP server name must not be blank");
      }
      if (!names.add(server.name())) {
        throw new IllegalArgumentException("Duplicate MCP server name '%s'".formatted(server.name()));
      }
      if (!McpServerConfig.TRANSPORT_SSE.equals(server.transport())
          && !McpServerConfig.TRANSPORT_STDIO.equals(server.transport())) {
        throw new IllegalArgumentException(
            "MCP server '%s' has unknown transport '%s' (expected %s or %s)".formatted(
                server.name(), server.transport(), McpServerConfig.TRANSPORT_SSE, McpServerConfig.TRANSPORT_STDIO));
      }
      if (server.endpoint().isBlank()) {
        throw new IllegalArgumentException("MCP server '%s' must declare an endpoint".formatted(server.name()));
      }
    }
    state = new ControlPlaneState(state.version() + 1, Instant.now(), state.agents(), state.workflow(), servers);
    save();
    log.info("Control plane: {} MCP server(s) recorded (version {})", servers.size(), state.version());
    return state;
  }

  private WorkflowTemplate toTemplate(WorkflowControlState workflow) {
    ExecutionMode mode;
    try {
      mode = ExecutionMode.valueOf(workflow.mode());
    }
    catch (IllegalArgumentException | NullPointerException ex) {
      throw new IllegalArgumentException(
          "Unknown execution mode '%s' (expected SEQUENTIAL or CONCURRENT)".formatted(workflow.mode()));
    }
    List<StageSpec> specs = workflow.stages().stream()
        .map(stage -> new StageSpec(stage.name(),
            stage.agent() == null || stage.agent().isBlank() ? stage.name() : stage.agent(),
            stage.dependsOn()))
        .toList();
    return new WorkflowTemplate("configured", mode, specs);
  }

  private static WorkflowControlState fromTemplate(WorkflowTemplate template) {
    return new WorkflowControlState(template.mode().name(), template.stages().stream()
        .map(stage -> new WorkflowControlState.StageControlState(stage.name(), stage.agent(), stage.dependsOn()))
        .toList());
  }

  private static String emptyToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /** Atomic persistence: write to a sibling temp file, then move it into place. */
  private void save() {
    try {
      if (configFile.getParent() != null) {
        Files.createDirectories(configFile.getParent());
      }
      Path temp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), state);
      Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    catch (IOException ex) {
      throw new UncheckedIOException("Failed to persist control plane state to " + configFile, ex);
    }
  }
}
