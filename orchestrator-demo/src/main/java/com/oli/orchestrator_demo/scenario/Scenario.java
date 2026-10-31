/**
 * @ClassName Scenario
 * @Description The scenario meta-model (v2.1): one self-contained "orchestrator
 *              logic group" that bundles everything a business case needs —
 *              a request template, an optional workflow template override,
 *              per-agent runtime configuration overrides, the MCP servers the
 *              case depends on and the artifact type the run should produce.
 *              A scenario never copies the control plane: unset fields inherit
 *              whatever is live at activation time, so {@code activate} merely
 *              pushes the overrides through the single-writer registry and the
 *              next run is dynamically instantiated from the persisted data.
 *              Persisted via {@link ScenarioStore} (file or the
 *              {@code cp_scenario} database table).
 * @Author OliverGong
 * @Date Oct 31, 2026
 * @Version 2.1
 */
package com.oli.orchestrator_demo.scenario;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.oli.orchestrator_demo.control.AgentRuntimeConfig;
import com.oli.orchestrator_demo.control.CalibrationRule;
import com.oli.orchestrator_demo.control.WorkflowControlState;

public record Scenario(
  String id,
  String name,
  String description,
  RequestTemplate requestTemplate,
  WorkflowControlState workflowOverride,
  Map<String, AgentOverride> agentOverrides,
  List<String> mcpServerRefs,
  ArtifactType artifactType,
  boolean active,
  Instant updatedAt
) {

  /** The artifact the run is expected to produce (only HTML is realized in v2.1). */
  public enum ArtifactType { HTML, SPRING_BOOT_PROJECT, JAR, AUDIO }

  /** The request the console pre-fills (and the dry-run simulates) for this scenario. */
  public record RequestTemplate(String destination, Integer numberOfDays, Double budget, String preferences) {
  }

  /**
   * A partial agent configuration: {@code null} fields keep the live control
   * plane value at activation time, present fields overwrite it.
   */
  public record AgentOverride(String systemPrompt, String model, Double temperature,
      List<CalibrationRule> calibrationRules, List<String> mcpRefs) {

    public AgentOverride {
      calibrationRules = calibrationRules == null ? null : List.copyOf(calibrationRules);
      mcpRefs = mcpRefs == null ? null : List.copyOf(mcpRefs);
    }

    /** Merges this override onto the current runtime configuration (null keeps). */
    public AgentRuntimeConfig applyTo(AgentRuntimeConfig current) {
      return new AgentRuntimeConfig(current.name(),
          systemPrompt != null ? systemPrompt : current.systemPrompt(),
          model != null ? model : current.model(),
          temperature != null ? temperature : current.temperature(),
          calibrationRules != null ? calibrationRules : current.calibrationRules(),
          mcpRefs != null ? mcpRefs : current.mcpRefs());
    }
  }

  public Scenario {
    agentOverrides = agentOverrides == null ? Map.of() : new LinkedHashMap<>(agentOverrides);
    mcpServerRefs = mcpServerRefs == null ? List.of() : List.copyOf(mcpServerRefs);
  }
}
