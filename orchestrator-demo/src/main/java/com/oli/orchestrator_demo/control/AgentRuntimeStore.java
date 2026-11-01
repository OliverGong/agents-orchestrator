/**
 * @ClassName AgentRuntimeStore
 * @Description Read-side access to the runtime configuration of an agent,
 *              consumed by {@link com.oli.orchestrator_demo.agent.AbstractContextAgent}
 *              on every model call (system prompt, model selection,
 *              calibration rules). The production implementation is the
 *          file-backed {@link AgentConfigRegistry}; {@link #fixed()} serves
 *              tests and any context without a control plane.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.control;

import java.util.LinkedHashMap;
import java.util.Map;

@FunctionalInterface
public interface AgentRuntimeStore {

  /** The runtime configuration of the given agent, or a failure for unknown names. */
  AgentRuntimeConfig agent(String name);

  /** An immutable store seeded with the built-in defaults of the known agents. */
  static AgentRuntimeStore fixed() {
    Map<String, AgentRuntimeConfig> configs = new LinkedHashMap<>();
    for (String name : AgentDefaults.KNOWN_AGENTS) {
      configs.put(name, AgentRuntimeConfig.defaults(name));
    }
    return name -> {
      AgentRuntimeConfig config = configs.get(name);
      if (config == null) {
        throw new IllegalStateException(
            "No runtime configuration for agent '%s'. Known agents: %s".formatted(name, configs.keySet()));
      }
      return config;
    };
  }
}
