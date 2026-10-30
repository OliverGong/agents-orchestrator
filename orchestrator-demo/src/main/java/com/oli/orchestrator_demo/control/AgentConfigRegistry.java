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
import java.util.regex.Pattern;

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

  /** MCP server names become tool-name prefixes, so they must stay identifier-safe. */
  private static final Pattern MCP_SERVER_NAME = Pattern.compile("[A-Za-z0-9_.-]+");

  private final AgentModelProperties modelProperties;
  private final WorkflowProperties workflowProperties;
  private final WorkflowTemplateHolder templateHolder;
  private final ObjectMapper objectMapper;
  private final Set<String> knownAgents;
  private final Path configFile;

  /**
   * Attached after construction by {@link AgentDescriptorRegistry} (setter
   * injection: the agents need this registry as their runtime store, so a
   * constructor dependency would create a bean cycle). Null until then —
   * validation then falls back to the structural checks only.
   */
  private AgentDescriptorRegistry descriptorRegistry;

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

  /** Public for tests outside the package; Spring invokes it as {@code @PostConstruct}. */
  @PostConstruct
  public void initialize() throws IOException {
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
          fromTemplate(workflowProperties.toTemplate()), List.of(), Map.of());
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
    state = new ControlPlaneState(state.version() + 1, Instant.now(), agents, state.workflow(), state.mcpServers(),
        state.descriptors());
    save();
    log.info("Control plane: agent '{}' updated (version {})", name, state.version());
    return state;
  }

  /**
   * Hot-applies a new collaboration template: validates it against the
   * registered agents (unknown references, dependency order, cycles and —
   * when agent descriptors are attached — the slot-contract closure) and
   * swaps it into the running orchestrator before persisting.
   */
  public synchronized ControlPlaneState updateWorkflow(WorkflowControlState workflow) {
    WorkflowTemplate template = toTemplate(workflow);
    template.validate(knownAgents, effectiveDescriptors());
    templateHolder.replace(template);
    state = new ControlPlaneState(state.version() + 1, Instant.now(), state.agents(), workflow, state.mcpServers(),
        state.descriptors());
    save();
    log.info("Control plane: workflow template replaced with '{}' ({}) (version {})",
        template.name(), template.mode(), state.version());
    return state;
  }

  /**
   * Attaches the descriptor registry after construction and seeds the
   * built-in slot contracts into the state for agents without a persisted
   * descriptor (seeding is not a user edit: the version stays untouched).
   * The active template is then re-checked against the contracts — warn
   * only, because a stale persisted descriptor must not prevent booting.
   */
  public synchronized void attachDescriptors(AgentDescriptorRegistry registry) {
    this.descriptorRegistry = registry;
    Map<String, AgentDescriptor> descriptors = new LinkedHashMap<>(state.descriptors());
    boolean seeded = false;
    for (Map.Entry<String, AgentDescriptor> entry : registry.builtIns().entrySet()) {
      if (state.agents().containsKey(entry.getKey()) && !descriptors.containsKey(entry.getKey())) {
        descriptors.put(entry.getKey(), entry.getValue());
        seeded = true;
      }
    }
    if (seeded) {
      state = new ControlPlaneState(state.version(), state.updatedAt(), state.agents(), state.workflow(),
          state.mcpServers(), descriptors);
      save();
      log.info("Control plane: seeded {} agent descriptor(s) from the agent beans", descriptors.size());
    }
    try {
      toTemplate(state.workflow()).validate(knownAgents, effectiveDescriptors());
    }
    catch (IllegalStateException ex) {
      log.warn("Control plane: the active workflow template violates the agent slot contracts: {}", ex.getMessage());
    }
  }

  /**
   * Hot-applies a new slot-contract descriptor for one agent: blank model
   * defaults fall back to the built-in persona, and the active template is
   * re-validated against the new contract before committing — a descriptor
   * edit that would break the running workflow is rejected.
   */
  public synchronized ControlPlaneState updateDescriptor(String name, AgentDescriptor update) {
    if (!state.agents().containsKey(name)) {
      throw new IllegalArgumentException(
          "Unknown agent '%s'. Known agents: %s".formatted(name, state.agents().keySet()));
    }
    if (update == null || !name.equals(update.name())) {
      throw new IllegalArgumentException("Descriptor name '%s' does not match the agent '%s'"
          .formatted(update == null ? null : update.name(), name));
    }
    for (List<String> slotGroup : List.of(update.outputSlots(), update.inputSlots(), update.pendingSafeSlots())) {
      for (String slot : slotGroup) {
        if (slot == null || slot.isBlank()) {
          throw new IllegalArgumentException("Slot names of the descriptor of agent '%s' must not be blank"
              .formatted(name));
        }
      }
    }
    AgentDescriptor merged = descriptorRegistry == null ? update : descriptorRegistry.enrich(update);
    Map<String, AgentDescriptor> descriptors = new LinkedHashMap<>(state.descriptors());
    descriptors.put(name, merged);
    toTemplate(state.workflow()).validate(knownAgents,
        descriptorRegistry == null ? Map.of() : descriptorRegistry.effectiveDescriptors(
            state.agents().keySet(), descriptors));
    state = new ControlPlaneState(state.version() + 1, Instant.now(), state.agents(), state.workflow(),
        state.mcpServers(), descriptors);
    save();
    log.info("Control plane: descriptor of agent '{}' updated (version {})", name, state.version());
    return state;
  }

  /** The effective descriptor of the agent, or null when it has none. */
  public AgentDescriptor descriptor(String name) {
    return descriptorRegistry == null ? null
        : descriptorRegistry.effective(state.agents().keySet(), state.descriptors(), name);
  }

  /** The descriptors workflow validation runs against (empty when not attached yet). */
  private Map<String, AgentDescriptor> effectiveDescriptors() {
    return descriptorRegistry == null ? Map.of() : descriptorRegistry.effectiveDescriptors(
        state.agents().keySet(), state.descriptors());
  }

  /**
   * Replaces the recorded MCP servers: names must be unique, URL-safe and
   * become the tool-name prefix; HTTP transports (STREAMABLE_HTTP, the
   * deprecated SSE) require an endpoint, STDIO requires a command. The MCP
   * registry picks the change up on its next reconcile pass.
   */
  public synchronized ControlPlaneState updateMcpServers(List<McpServerConfig> servers) {
    Set<String> names = new HashSet<>();
    for (McpServerConfig server : servers) {
      if (server.name().isBlank()) {
        throw new IllegalArgumentException("MCP server name must not be blank");
      }
      if (!MCP_SERVER_NAME.matcher(server.name()).matches()) {
        throw new IllegalArgumentException("MCP server name '%s' may only contain letters, digits, '_', '.' and '-'"
            .formatted(server.name()));
      }
      if (!names.add(server.name())) {
        throw new IllegalArgumentException("Duplicate MCP server name '%s'".formatted(server.name()));
      }
      boolean http = server.httpTransport();
      if (!http && !McpServerConfig.TRANSPORT_STDIO.equals(server.transport())) {
        throw new IllegalArgumentException("MCP server '%s' has unknown transport '%s' (expected %s, %s or %s)"
            .formatted(server.name(), server.transport(), McpServerConfig.TRANSPORT_STREAMABLE_HTTP,
                McpServerConfig.TRANSPORT_SSE, McpServerConfig.TRANSPORT_STDIO));
      }
      if (McpServerConfig.TRANSPORT_SSE.equals(server.transport())) {
        log.warn("MCP server '{}' uses the deprecated SSE transport; prefer STREAMABLE_HTTP", server.name());
      }
      if (http && server.endpoint().isBlank()) {
        throw new IllegalArgumentException("MCP server '%s' must declare an endpoint".formatted(server.name()));
      }
      if (!http && server.command().isBlank()) {
        throw new IllegalArgumentException("MCP server '%s' with STDIO transport must declare a command"
            .formatted(server.name()));
      }
    }
    state = new ControlPlaneState(state.version() + 1, Instant.now(), state.agents(), state.workflow(), servers,
        state.descriptors());
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
