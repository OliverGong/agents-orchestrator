/**
 * @ClassName DryRunReport
 * @Description The structured result of a scenario dry-run (推演): the
 *              resolved workflow, the dependency waves with every stage's
 *              effective configuration (agent, model, MCP servers, slot
 *              contract), the DAG edges and the blackboard slot flows. The
 *              frontend renders the waves/edges directly as a DAG and a
 *              swimlane — no real model call ever happens; a dry-run is pure
 *              template validation plus wave expansion over the live control
 *              plane state.
 * @Author OliverGong
 * @Date Oct 31, 2026
 * @Version 2.1
 */
package com.oli.orchestrator_demo.scenario;

import java.util.List;

public record DryRunReport(
  String scenarioId,
  boolean valid,
  List<String> issues,
  String resolvedMode,
  String resolvedTemplateSource,
  List<WaveView> waves,
  List<EdgeView> edges,
  List<SlotFlowView> slotFlows
) {

  /** One dependency wave; {@code parallel} is true when it holds several stages. */
  public record WaveView(int waveIndex, boolean parallel, List<StageView> stages) {
  }

  /**
   * One simulated stage: the effective configuration after merging the
   * scenario overrides onto the live control plane, plus the agent's slot
   * contract (inputs consumed / outputs produced / pending-safe inputs).
   */
  public record StageView(String stageName, String agentName, List<String> dependsOn,
      String effectiveModel, Double effectiveTemperature, List<String> mcpServers,
      List<String> inputs, List<String> outputs, List<String> pendingSafe, List<String> warnings) {
  }

  /** One DAG edge: the stage that must complete before the dependent stage starts. */
  public record EdgeView(String from, String to) {
  }

  /** One blackboard slot and the stages producing/consuming it. */
  public record SlotFlowView(String slot, List<String> producedBy, List<String> consumedBy,
      boolean pendingSafe) {
  }
}
