/**
 * @ClassName AgentDefaults
 * @Description The built-in agent personas and their default runtime
 *              configuration. These texts are the seed for the control-plane
 *              state file: once the registry is initialized, the control
 *              plane (not this class) is the single source of truth for the
 *              effective system prompts.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.control;

import java.util.List;
import java.util.Map;

import com.oli.orchestrator_demo.config.AgentModelProperties;

public final class AgentDefaults {

  /** Agents of the demo, in workflow declaration order. */
  public static final List<String> KNOWN_AGENTS = List.of("research", "budget", "itinerary", "review");

  private static final Map<String, String> SYSTEM_PROMPTS = Map.of(
      "research", """
          You are a travel research agent.

          Your only responsibility is to identify useful places,
          food options and practical travel information.

          Do not create the final itinerary.
          Do not perform budget calculations.
          Return factual and concise information.
          """,
      "budget", """
          You are a travel budget agent.

          Estimate realistic costs using the supplied research.
          Never silently exceed the user's budget.
          Clearly indicate whether the proposed trip is affordable.
          """,
      "itinerary", """
          You are an itinerary planning agent.

          Create a realistic day-by-day plan using only the supplied
          research and budget constraints.

          Avoid impossible travel times and budget violations.
          """,
      "review", """
          You are the final review agent.

          Review the research, budget and itinerary.
          Correct inconsistencies and produce a clear final answer.

          Never invent missing prices or attractions.
          """);

  private AgentDefaults() {
  }

  /** The built-in persona of the given agent, failing fast for unknown names. */
  public static String systemPrompt(String name) {
    String prompt = SYSTEM_PROMPTS.get(name);
    if (prompt == null) {
      throw new IllegalStateException(
          "No default persona for agent '%s'. Known agents: %s".formatted(name, SYSTEM_PROMPTS.keySet()));
    }
    return prompt;
  }

  /** The default runtime configuration: built-in persona plus the model settings from `orchestrator.agents.*`. */
  public static AgentRuntimeConfig defaultConfig(String name, AgentModelProperties properties) {
    AgentModelProperties.AgentSpec spec = properties.agent(name);
    return new AgentRuntimeConfig(name, systemPrompt(name), spec.getModel(), spec.getTemperature(),
        List.of(), List.of());
  }
}
