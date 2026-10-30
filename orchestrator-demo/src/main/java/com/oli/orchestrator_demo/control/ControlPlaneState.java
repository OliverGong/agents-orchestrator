/**
 * @ClassName ControlPlaneState
 * @Description The full, versioned state of the control plane: the runtime
 *              configuration of every agent, the collaboration template and
 *              the recorded MCP servers. Persisted as JSON (see
 *              {@code orchestrator.control.config-file}), reloaded on
 *              startup and bumped monotonically on every control-plane
 *              change, so clients can detect concurrent edits. The map keeps
 *              the agent declaration order for stable UI rendering.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.control;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ControlPlaneState(
  long version,
  Instant updatedAt,
  Map<String, AgentRuntimeConfig> agents,
  WorkflowControlState workflow,
  List<McpServerConfig> mcpServers
) {

  public ControlPlaneState {
    agents = agents == null ? new LinkedHashMap<>() : new LinkedHashMap<>(agents);
    workflow = workflow == null ? new WorkflowControlState("SEQUENTIAL", List.of()) : workflow;
    mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
  }
}
