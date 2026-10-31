/**
 * @ClassName AgentDescriptorRegistry
 * @Description Collects the self-declared {@link AgentDescriptor slot
 *              contracts} of every context agent bean at startup: the agents
 *              are the code-side truth for their own metadata, this registry
 *              only gathers the contracts, enriches them with the built-in
 *              model defaults (`orchestrator.agents.*`) and offers them to
 *              the control plane as seeding material.
 *
 *              The registry is attached to the {@link AgentConfigRegistry}
 *              after construction (setter injection instead of a constructor
 *              dependency — the agents need the registry-backed runtime store,
 *              so a constructor cycle would be unavoidable otherwise). Once
 *              attached, the control-plane state is the single source of
 *              truth for the effective descriptors; the built-ins serve as
 *              the fallback for agents without a persisted entry.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.control;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.oli.orchestrator_demo.agent.AbstractContextAgent;
import com.oli.orchestrator_demo.config.AgentModelProperties;

import jakarta.annotation.PostConstruct;

@Component
public class AgentDescriptorRegistry {

  private final AgentModelProperties modelProperties;
  private final AgentConfigRegistry configRegistry;
  private final Map<String, AgentDescriptor> builtIns;

  public AgentDescriptorRegistry(List<AbstractContextAgent<?>> agents, AgentModelProperties modelProperties,
      AgentConfigRegistry configRegistry) {
    this.modelProperties = modelProperties;
    this.configRegistry = configRegistry;
    Map<String, AgentDescriptor> collected = new LinkedHashMap<>();
    for (AbstractContextAgent<?> agent : agents) {
      if (collected.putIfAbsent(agent.descriptor().name(), enrich(agent.descriptor())) != null) {
        throw new IllegalStateException("Duplicate agent descriptor for '%s'".formatted(agent.descriptor().name()));
      }
    }
    this.builtIns = Collections.unmodifiableMap(collected);
  }

  /**
   * Hands the collected contracts to the control plane (seeding + hot-swap
   * validation). Runs after this bean and all agent beans are initialized;
   * the setter-style attach avoids the constructor cycle
   * agents -> runtime store -> config registry -> descriptors -> agents.
   */
  @PostConstruct
  void attachToControlPlane() {
    configRegistry.attachDescriptors(this);
  }

  /** Built-in descriptors collected from the agent beans, in declaration order. */
  public Map<String, AgentDescriptor> builtIns() {
    return builtIns;
  }

  /** The effective descriptor of the agent (persisted override first), or null when it has none. */
  public AgentDescriptor effective(Set<String> agentNames, Map<String, AgentDescriptor> overrides, String name) {
    if (!agentNames.contains(name)) {
      return null;
    }
    AgentDescriptor override = overrides.get(name);
    return override != null ? override : builtIns.get(name);
  }

  /**
   * Effective descriptors of every known agent: persisted overrides win,
   * built-ins fill the gaps. This map is the validation input of
   * {@link com.oli.orchestrator_demo.workflow.WorkflowTemplate#validate}.
   */
  public Map<String, AgentDescriptor> effectiveDescriptors(Set<String> agentNames,
      Map<String, AgentDescriptor> overrides) {
    Map<String, AgentDescriptor> effective = new LinkedHashMap<>();
    for (String name : agentNames) {
      AgentDescriptor descriptor = effective(agentNames, overrides, name);
      if (descriptor != null) {
        effective.put(name, descriptor);
      }
    }
    return Collections.unmodifiableMap(effective);
  }

  /** Fills a client-supplied descriptor's blank model defaults from the built-in configuration. */
  AgentDescriptor enrich(AgentDescriptor update) {
    AgentModelProperties.AgentSpec spec = modelProperties.agent(update.name());
    return new AgentDescriptor(update.name(), update.title(), update.description(),
        update.outputSlots(), update.inputSlots(), update.pendingSafeSlots(),
        update.defaultModel() != null ? update.defaultModel() : spec.getModel(),
        update.defaultTemperature() != null ? update.defaultTemperature() : spec.getTemperature());
  }
}
