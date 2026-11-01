/**
 * @ClassName McpConnectionGateway
 * @Description Port through which the control plane observes and drives the
 *              MCP integration without importing it: the live status of every
 *              recorded server (exposed at {@code GET /api/v1/control/mcp/status})
 *              and a manual reconnect command bypassing the failure cooldown.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.kernel.contract.port;

import com.oli.orchestrator_demo.kernel.contract.config.McpServerStatus;

import java.util.List;

public interface McpConnectionGateway {

  /** Live connection state (and discovered tools) of every recorded MCP server. */
  List<McpServerStatus> status();

  /** Manual reconnect of one MCP server, bypassing the failure cooldown. */
  McpServerStatus reconnect(String name);
}
