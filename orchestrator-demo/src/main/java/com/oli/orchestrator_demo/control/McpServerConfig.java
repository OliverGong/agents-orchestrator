/**
 * @ClassName McpServerConfig
 * @Description One MCP (Model Context Protocol) server entry recorded through
 *              the control plane. Since the v1.1 wiring the connection is
 *              actually opened by {@link com.oli.orchestrator_demo.mcp.McpConnectionRegistry}
 *              (three transports: STREAMABLE_HTTP — the modern default, SSE —
 *              deprecated but kept for compatibility, STDIO — spawns a local
 *              child process) and the discovered tools are injected into the
 *              mapped agents ({@link AgentRuntimeConfig#mcpRefs()}), with every
 *              invocation reported to the observability timeline.
 *
 *              The record is deliberately transport-agnostic: HTTP-flavored
 *              entries carry an {@code endpoint} URL, STDIO entries a
 *              {@code command} plus optional {@code args}/{@code env}. Fields
 *              unknown to older persisted state files are normalized to empty
 *              defaults by the compact constructor, so existing JSON loads
 *              unchanged.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.1
 */
package com.oli.orchestrator_demo.control;

import java.util.List;
import java.util.Map;

public record McpServerConfig(
  String name,
  String transport,
  String endpoint,
  String command,
  List<String> args,
  Map<String, String> env,
  boolean enabled,
  String description
) {

  /** Supported transport identifiers. STREAMABLE_HTTP is the modern default. */
  public static final String TRANSPORT_SSE = "SSE";
  public static final String TRANSPORT_STDIO = "STDIO";
  public static final String TRANSPORT_STREAMABLE_HTTP = "STREAMABLE_HTTP";

  public McpServerConfig {
    name = name == null ? "" : name.trim();
    transport = transport == null ? TRANSPORT_SSE : transport.trim();
    endpoint = endpoint == null ? "" : endpoint.trim();
    command = command == null ? "" : command.trim();
    args = args == null ? List.of() : List.copyOf(args);
    env = env == null ? Map.of() : Map.copyOf(env);
    description = description == null ? "" : description;
  }

  /** Convenience constructor for HTTP servers without STDIO details (tests, UI defaults). */
  public McpServerConfig(String name, String transport, String endpoint, boolean enabled, String description) {
    this(name, transport, endpoint, "", List.of(), Map.of(), enabled, description);
  }

  /** Whether the entry needs an HTTP endpoint (STREAMABLE_HTTP / SSE). */
  public boolean httpTransport() {
    return TRANSPORT_STREAMABLE_HTTP.equals(transport) || TRANSPORT_SSE.equals(transport);
  }

  /** Convenience view for UI hint texts. */
  public String location() {
    return httpTransport() ? endpoint : command + (args.isEmpty() ? "" : " " + String.join(" ", args));
  }
}
