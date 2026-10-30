/**
 * @ClassName McpServerStatus
 * @Description Immutable snapshot of one MCP server connection as exposed by
 *              the control plane ({@code GET /api/v1/control/mcp/status}):
 *              the recorded configuration identity plus the live connection
 *              state, the last connection error (if any) and the tool names
 *              discovered on the server.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.mcp;

import java.time.Instant;
import java.util.List;

public record McpServerStatus(
  String name,
  String transport,
  String location,
  boolean enabled,
  String state,
  String error,
  Instant connectedAt,
  List<String> toolNames
) {

  public McpServerStatus {
    toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
  }

  public static final String CONNECTED = "CONNECTED";
  public static final String CONNECTING = "CONNECTING";
  public static final String FAILED = "FAILED";
  public static final String DISABLED = "DISABLED";
}
