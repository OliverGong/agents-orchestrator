/**
 * @ClassName AgentModelProperties
 * @Description Configuration properties for per-agent model selection, bound
 *              from the `orchestrator.agents.*` entries of
 *              application.properties / application.yaml:
 *
 *              orchestrator.agents.research.model=qwen:7b
 *              orchestrator.agents.research.temperature=0.7
 *
 *              A null model/temperature means "inherit from the backend
 *              defaults" configured through the standard Spring AI properties
 *              (spring.ai.ollama.* / spring.ai.openai.*).
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orchestrator")
public class AgentModelProperties {

  /** Per-agent model settings, keyed by agent name (research, budget, itinerary, review). */
  private Map<String, AgentSpec> agents = new LinkedHashMap<>();

  public AgentModelProperties() {
    // Pre-seed the four known agents so a spec is always resolvable.
    for (String name : new String[] {"research", "budget", "itinerary", "review"}) {
      agents.put(name, new AgentSpec());
    }
  }

  /** Returns the spec of the given agent, failing fast for unknown agent names. */
  public AgentSpec agent(String name) {
    AgentSpec spec = agents.get(name);
    if (spec == null) {
      throw new IllegalStateException(
          "No model configuration for agent '%s'. Known agents: %s".formatted(name, agents.keySet()));
    }
    return spec;
  }

  public Map<String, AgentSpec> getAgents() {
    return agents;
  }

  public void setAgents(Map<String, AgentSpec> agents) {
    this.agents = agents;
  }

  /**
   * Model settings of a single agent. Null values inherit the backend defaults.
   */
  public static class AgentSpec {

    /** Model name handed to the backend, e.g. `qwen:7b` (Ollama) or `gpt-4o-mini` (OpenAI). */
    private String model;

    /** Sampling temperature; null inherits the backend default. */
    private Double temperature;

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public Double getTemperature() {
      return temperature;
    }

    public void setTemperature(Double temperature) {
      this.temperature = temperature;
    }
  }
}
