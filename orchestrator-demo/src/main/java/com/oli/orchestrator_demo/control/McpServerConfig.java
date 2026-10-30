/**
 * @ClassName McpServerConfig
 * @Description One MCP (Model Context Protocol) server entry recorded through
 *              the control plane. Version 1 covers the configuration plane:
 *              servers are entered, validated and mapped to agents via
 *              {@link AgentRuntimeConfig#mcpRefs()}; the actual client wiring
 *              (opening SSE/stdio transports and exposing the tools to the
 *              ChatClient) lands in the next iteration and then feeds the
 *              per-stage tool call details of the observability timeline.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.control;

public record McpServerConfig(
  String name,
  String transport,
  String endpoint,
  boolean enabled,
  String description
) {

  /** Supported transport identifiers. */
  public static final String TRANSPORT_SSE = "SSE";
  public static final String TRANSPORT_STDIO = "STDIO";

  public McpServerConfig {
    name = name == null ? "" : name.trim();
    transport = transport == null ? TRANSPORT_SSE : transport.trim();
    endpoint = endpoint == null ? "" : endpoint.trim();
    description = description == null ? "" : description;
  }
}
