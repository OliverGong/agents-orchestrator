/**
 * @ClassName ScenarioRegistry
 * @Description The single writer of the scenario plane. On startup it loads
 *              the persisted {@link ScenarioState} from the
 *              {@link ScenarioStore} or seeds it with one demo scenario;
 *              afterwards every mutation is synchronized, validated against
 *              the live control plane (known agents, configured MCP servers,
 *              workflow-override closure), version-bumped and handed to the
 *              store. {@code activate} is the dynamic instantiation path: it
 *              pushes the scenario's workflow and agent overrides through the
 *              {@link AgentConfigRegistry} (the control-plane single writer),
 *              so the very next run executes the scenario's logic group —
 *              while {@code dryRun} delegates to the {@link DryRunEngine} for
 *              a token-free rehearsal.
 * @Author OliverGong
 * @Date Oct 31, 2026
 * @Version 2.1
 */
package com.oli.orchestrator_demo.scenario;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.oli.orchestrator_demo.control.AgentConfigRegistry;
import com.oli.orchestrator_demo.control.ControlPlaneState;
import com.oli.orchestrator_demo.control.McpServerConfig;
import com.oli.orchestrator_demo.workflow.WorkflowTemplate;

import jakarta.annotation.PostConstruct;

@Component
public class ScenarioRegistry {

  private static final Logger log = LoggerFactory.getLogger(ScenarioRegistry.class);

  /** Scenario ids become URL segments and database keys: keep them slug-safe. */
  private static final Pattern SCENARIO_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

  private final ScenarioStore store;
  private final AgentConfigRegistry controlPlane;
  private final DryRunEngine dryRunEngine;

  /** Guarded by this; readers get immutable snapshots. */
  private ScenarioState state;

  public ScenarioRegistry(ScenarioStore store, AgentConfigRegistry controlPlane, DryRunEngine dryRunEngine) {
    this.store = store;
    this.controlPlane = controlPlane;
    this.dryRunEngine = dryRunEngine;
  }

  /** Public for tests outside the package; Spring invokes it as {@code @PostConstruct}. */
  @PostConstruct
  public void initialize() {
    var persisted = store.load();
    if (persisted.isPresent()) {
      state = persisted.get();
      log.info("Scenario plane loaded from {} (version {}, {} scenario(s))",
          store.name(), state.version(), state.scenarios().size());
    }
    else {
      state = new ScenarioState(1, Instant.now(), List.of(seedScenario()));
      store.save(state);
      log.info("Scenario plane seeded to {} (1 demo scenario)", store.name());
    }
  }

  private static Scenario seedScenario() {
    return new Scenario("kyoto-classic", "京都经典四阶段", "内置演示场景：research → budget → itinerary → review 的默认旅行规划逻辑组。",
        new Scenario.RequestTemplate("Kyoto", 3, 15000.0, "temples and local food"),
        null, java.util.Map.of(), List.of(), Scenario.ArtifactType.HTML, true, Instant.now());
  }

  /** The full scenario-plane snapshot (immutable record). */
  public synchronized ScenarioState state() {
    return state;
  }

  public synchronized Scenario scenario(String id) {
    return state.scenarios().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
  }

  public synchronized ScenarioState create(Scenario scenario) {
    validate(scenario, true);
    Scenario stored = new Scenario(scenario.id(), scenario.name(), scenario.description(),
        scenario.requestTemplate(), scenario.workflowOverride(), scenario.agentOverrides(),
        scenario.mcpServerRefs(), scenario.artifactType(), scenario.active(), Instant.now());
    List<Scenario> scenarios = new ArrayList<>(state.scenarios());
    scenarios.add(stored);
    return bumpAndStore(scenarios);
  }

  public synchronized ScenarioState update(String id, Scenario scenario) {
    Scenario existing = requireScenario(id);
    // The path id wins over any body id; updatedAt is set by this registry.
    Scenario updated = new Scenario(id, scenario.name(), scenario.description(),
        scenario.requestTemplate(), scenario.workflowOverride(), scenario.agentOverrides(),
        scenario.mcpServerRefs(), scenario.artifactType(), scenario.active(), Instant.now());
    validate(updated, false);
    List<Scenario> scenarios = new ArrayList<>(state.scenarios());
    scenarios.set(scenarios.indexOf(existing), updated);
    return bumpAndStore(scenarios);
  }

  public synchronized ScenarioState delete(String id) {
    requireScenario(id);
    List<Scenario> scenarios = state.scenarios().stream().filter(s -> !s.id().equals(id)).toList();
    return bumpAndStore(scenarios);
  }

  /**
   * Dynamic instantiation: applies the scenario's workflow override and agent
   * overrides to the live control plane (the next run then executes this
   * logic group). Reference validation happens here before anything is
   * hot-applied, so a broken scenario never leaves a half-activated plane.
   */
  public synchronized ControlPlaneState activate(String id) {
    Scenario scenario = requireScenario(id);
    validateReferences(scenario);
    ControlPlaneState cp;
    if (scenario.workflowOverride() != null) {
      try {
        cp = controlPlane.updateWorkflow(scenario.workflowOverride());
      }
      catch (IllegalStateException ex) {
        throw new IllegalArgumentException(ex.getMessage());
      }
    }
    else {
      cp = controlPlane.state();
    }
    for (var entry : scenario.agentOverrides().entrySet()) {
      cp = controlPlane.updateAgent(entry.getKey(), entry.getValue().applyTo(cp.agents().get(entry.getKey())));
    }
    log.info("Scenario plane: scenario '{}' activated onto the control plane", scenario.id());
    return cp;
  }

  /** Token-free rehearsal of the scenario (no state change). */
  public DryRunReport dryRun(String id) {
    Scenario scenario = requireScenario(id);
    return dryRunEngine.run(scenario);
  }

  private ScenarioState bumpAndStore(List<Scenario> scenarios) {
    state = new ScenarioState(state.version() + 1, Instant.now(), scenarios);
    store.save(state);
    log.info("Scenario plane: {} scenario(s) persisted (version {})", scenarios.size(), state.version());
    return state;
  }

  private Scenario requireScenario(String id) {
    Scenario scenario = scenario(id);
    if (scenario == null) {
      throw new IllegalArgumentException(
          "Unknown scenario '%s'. Known scenarios: %s".formatted(id,
              state.scenarios().stream().map(Scenario::id).toList()));
    }
    return scenario;
  }

  private void validate(Scenario scenario, boolean creating) {
    if (scenario.id() == null || !SCENARIO_ID.matcher(scenario.id()).matches()) {
      throw new IllegalArgumentException(
          "Scenario id '%s' must match [a-z0-9][a-z0-9-]{0,62}".formatted(scenario.id()));
    }
    if (creating && scenario(scenario.id()) != null) {
      throw new IllegalArgumentException("Scenario '%s' already exists".formatted(scenario.id()));
    }
    if (scenario.name() == null || scenario.name().isBlank()) {
      throw new IllegalArgumentException("Scenario name must not be blank");
    }
    if (scenario.artifactType() == null) {
      throw new IllegalArgumentException("Scenario artifactType must not be null");
    }
    if (scenario.requestTemplate() == null) {
      throw new IllegalArgumentException("Scenario requestTemplate must not be null");
    }
    if (scenario.requestTemplate().numberOfDays() == null || scenario.requestTemplate().numberOfDays() < 1) {
      throw new IllegalArgumentException("Scenario requestTemplate.numberOfDays must be >= 1");
    }
    validateReferences(scenario);
  }

  /** Overrides must point at the live control plane: agents, MCP servers, template closure. */
  private void validateReferences(Scenario scenario) {
    ControlPlaneState cp = controlPlane.state();
    Set<String> knownAgents = cp.agents().keySet();
    for (String agent : scenario.agentOverrides().keySet()) {
      if (!knownAgents.contains(agent)) {
        throw new IllegalArgumentException(
            "Agent override '%s' of scenario '%s' is not a known agent".formatted(agent, scenario.id()));
      }
    }
    Set<String> knownServers = new HashSet<>(cp.mcpServers().stream().map(McpServerConfig::name).toList());
    for (String ref : scenario.mcpServerRefs()) {
      if (!knownServers.contains(ref)) {
        throw new IllegalArgumentException(
            "MCP reference '%s' of scenario '%s' is not a configured MCP server".formatted(ref, scenario.id()));
      }
    }
    if (scenario.workflowOverride() != null) {
      WorkflowTemplate override = new WorkflowTemplate("scenario:" + scenario.id(),
          toMode(scenario.workflowOverride().mode()),
          scenario.workflowOverride().stages().stream()
              .map(stage -> new com.oli.orchestrator_demo.workflow.StageSpec(stage.name(), stage.agent(),
                  stage.dependsOn()))
              .toList());
      try {
        override.validate(knownAgents, cp.descriptors());
      }
      catch (IllegalStateException ex) {
        throw new IllegalArgumentException(ex.getMessage());
      }
    }
  }

  private com.oli.orchestrator_demo.workflow.ExecutionMode toMode(String mode) {
    try {
      return com.oli.orchestrator_demo.workflow.ExecutionMode.valueOf(mode);
    }
    catch (RuntimeException ex) {
      return com.oli.orchestrator_demo.workflow.ExecutionMode.SEQUENTIAL;
    }
  }
}
