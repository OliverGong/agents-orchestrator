/**
 * @ClassName AgentRuntimeConfig
 * @Description Runtime-editable configuration of a single agent, managed
 *              through the control plane (GET/PUT
 *              {@code /api/v1/control/agents/{name}}) and read by the agent
 *              on every execution:
 *              <ul>
 *              <li>{@code systemPrompt} — the agent persona, hot-applied per
 *              call instead of the former compile-time ChatClient default;</li>
 *              <li>{@code model} / {@code temperature} — per-agent model
 *              selection (null = inherit the backend defaults);</li>
 *              <li>{@code calibrationRules} — post-response regex
 *              规范化/校准 rules applied after the response sanitizer;</li>
 *              <li>{@code mcpRefs} — names of the MCP servers (see
 *              {@link McpServerConfig}) this agent is mapped to.</li>
 *              </ul>
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.control;

import java.util.List;

public record AgentRuntimeConfig(
  String name,
  String systemPrompt,
  String model,
  Double temperature,
  List<CalibrationRule> calibrationRules,
  List<String> mcpRefs
) {

  public AgentRuntimeConfig {
    systemPrompt = systemPrompt == null ? "" : systemPrompt;
    calibrationRules = calibrationRules == null ? List.of() : List.copyOf(calibrationRules);
    mcpRefs = mcpRefs == null ? List.of() : List.copyOf(mcpRefs);
  }

  /** Built-in defaults for a known agent: fixed persona, no model override. */
  public static AgentRuntimeConfig defaults(String name) {
    return new AgentRuntimeConfig(name, AgentDefaults.systemPrompt(name), null, null, List.of(), List.of());
  }
}
