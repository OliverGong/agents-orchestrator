/**
 * @ClassName ControlPlaneReader
 * @Description Read-only view of the control-plane state consumed by the MCP
 *              integration: the recorded MCP servers to reconcile against and
 *              the runtime configuration of the agents the tools are mapped
 *              to. Implemented by the control plane's agent-config registry;
 *              version-based optimistic reconciliation is part of the port so
 *              consumers skip no-op passes.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.kernel.contract;

import java.util.List;

public interface ControlPlaneReader {

  /** Monotonic control-plane version, bumped on every mutation. */
  long version();

  /** The recorded MCP server entries (enabled and disabled). */
  List<McpServerConfig> mcpServers();

  /** The runtime configuration of the given agent, or a failure for unknown names. */
  AgentRuntimeConfig agent(String name);
}
