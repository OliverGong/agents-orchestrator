/**
 * @ClassName DryRunEngine
 * @Description The scenario rehearsal engine (场景推演): expands a scenario
 *              against the live control plane without touching a model. It
 *              resolves the effective workflow template (scenario override or
 *              the active one), runs the same structural and slot-closure
 *              validation the orchestrator would, expands the dependency
 *              waves, merges the scenario's agent overrides onto the current
 *              runtime configuration for an effective per-stage view and
 *              derives the DAG edges plus the blackboard slot flows. Every
 *              problem surfaces as an issue with the exact validation message
 *              the real run would have failed with — the console renders the
 *              report as DAG and swimlane before anyone spends tokens.
 * @Author OliverGong
 * @Date Oct 31, 2026
 * @Version 2.1
 */
package com.oli.orchestrator_demo.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.oli.orchestrator_demo.control.AgentConfigRegistry;
import com.oli.orchestrator_demo.control.AgentDescriptor;
import com.oli.orchestrator_demo.control.AgentRuntimeConfig;
import com.oli.orchestrator_demo.control.ControlPlaneState;
import com.oli.orchestrator_demo.control.WorkflowControlState;
import com.oli.orchestrator_demo.workflow.ExecutionMode;
import com.oli.orchestrator_demo.workflow.StageSpec;
import com.oli.orchestrator_demo.workflow.WorkflowTemplate;

@Component
public class DryRunEngine {

  private final AgentConfigRegistry controlPlane;

  public DryRunEngine(AgentConfigRegistry controlPlane) {
    this.controlPlane = controlPlane;
  }

  /** Rehearses the scenario; never throws for validation problems — it reports them. */
  public DryRunReport run(Scenario scenario) {
    ControlPlaneState cp = controlPlane.state();
    boolean overridden = scenario.workflowOverride() != null;
    WorkflowControlState workflow = overridden ? scenario.workflowOverride() : cp.workflow();
    WorkflowTemplate template = toTemplate("scenario:" + scenario.id(), workflow);

    List<String> issues = new ArrayList<>();
    try {
      template.validate(cp.agents().keySet(), cp.descriptors());
    }
    catch (IllegalStateException ex) {
      issues.add(ex.getMessage());
    }
    Set<String> knownServers = new LinkedHashSet<>(cp.mcpServers().stream().map(s -> s.name()).toList());
    for (String ref : scenario.mcpServerRefs()) {
      if (!knownServers.contains(ref)) {
        issues.add("MCP server '%s' required by the scenario is not configured in the control plane"
            .formatted(ref));
      }
    }

    List<DryRunReport.WaveView> waves = new ArrayList<>();
    List<DryRunReport.EdgeView> edges = new ArrayList<>();
    List<List<StageSpec>> rawWaves = List.of();
    try {
      rawWaves = template.waves();
    }
    catch (IllegalStateException ex) {
      issues.add(ex.getMessage());
    }
    if (!rawWaves.isEmpty()) {
      for (int i = 0; i < rawWaves.size(); i++) {
        List<DryRunReport.StageView> stages = rawWaves.get(i).stream()
            .map(stage -> stageView(stage, scenario, cp))
            .toList();
        waves.add(new DryRunReport.WaveView(i, rawWaves.get(i).size() > 1, stages));
      }
      for (StageSpec stage : template.stages()) {
        for (String dependency : stage.dependsOn()) {
          edges.add(new DryRunReport.EdgeView(dependency, stage.name()));
        }
      }
    }
    return new DryRunReport(scenario.id(), issues.isEmpty(), issues, workflow.mode(),
        overridden ? "scenario-override" : "control-plane", waves, edges, slotFlows(template, cp));
  }

  private DryRunReport.StageView stageView(StageSpec stage, Scenario scenario, ControlPlaneState cp) {
    List<String> warnings = new ArrayList<>();
    AgentRuntimeConfig config = cp.agents().get(stage.agent());
    Scenario.AgentOverride override = scenario.agentOverrides().get(stage.agent());
    if (config == null) {
      warnings.add("agent '%s' has no runtime configuration".formatted(stage.agent()));
      return new DryRunReport.StageView(stage.name(), stage.agent(), stage.dependsOn(),
          null, null, List.of(), List.of(), List.of(), List.of(), warnings);
    }
    AgentRuntimeConfig effective = override == null ? config : override.applyTo(config);

    Set<String> knownServers = new LinkedHashSet<>(cp.mcpServers().stream().map(s -> s.name()).toList());
    for (String server : effective.mcpRefs()) {
      if (!knownServers.contains(server)) {
        warnings.add("MCP server '%s' is not configured in the control plane".formatted(server));
      }
    }

    AgentDescriptor descriptor = cp.descriptors().get(stage.agent());
    List<String> inputs = descriptor == null ? List.of() : descriptor.inputSlots();
    List<String> outputs = descriptor == null ? List.of() : descriptor.outputSlots();
    List<String> pendingSafe = descriptor == null ? List.of() : descriptor.pendingSafeSlots();
    return new DryRunReport.StageView(stage.name(), stage.agent(), stage.dependsOn(),
        effective.model(), effective.temperature(), List.copyOf(effective.mcpRefs()),
        inputs, outputs, pendingSafe, warnings);
  }

  /** Slot → producing/consuming stages, derived from the agent descriptors. */
  private List<DryRunReport.SlotFlowView> slotFlows(WorkflowTemplate template, ControlPlaneState cp) {
    Map<String, List<String>> producedBy = new LinkedHashMap<>();
    Map<String, List<String>> consumedBy = new LinkedHashMap<>();
    Map<String, Boolean> pendingSafe = new LinkedHashMap<>();
    for (StageSpec stage : template.stages()) {
      AgentDescriptor descriptor = cp.descriptors().get(stage.agent());
      if (descriptor == null) {
        continue;
      }
      for (String slot : descriptor.outputSlots()) {
        producedBy.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(stage.name());
      }
      for (String slot : descriptor.inputSlots()) {
        consumedBy.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(stage.name());
        pendingSafe.putIfAbsent(slot, descriptor.pendingSafeSlots().contains(slot));
      }
    }
    LinkedHashSet<String> slots = new LinkedHashSet<>();
    slots.addAll(producedBy.keySet());
    slots.addAll(consumedBy.keySet());
    return slots.stream()
        .map(slot -> new DryRunReport.SlotFlowView(slot,
            producedBy.getOrDefault(slot, List.of()), consumedBy.getOrDefault(slot, List.of()),
            pendingSafe.getOrDefault(slot, false)))
        .toList();
  }

  private WorkflowTemplate toTemplate(String name, WorkflowControlState workflow) {
    ExecutionMode mode;
    try {
      mode = ExecutionMode.valueOf(workflow.mode());
    }
    catch (RuntimeException ex) {
      mode = ExecutionMode.SEQUENTIAL;
    }
    List<StageSpec> stages = workflow.stages().stream()
        .map(stage -> new StageSpec(stage.name(), stage.agent(), stage.dependsOn()))
        .toList();
    return new WorkflowTemplate(name, mode, stages);
  }
}
