/**
 * @ClassName ModelDefaultsBridge
 * @Description Agent-module bridge implementing the {@link ModelDefaultsReader}
 *              contract port over the module's {@code orchestrator.agents.*}
 *              configuration properties. Keeps the control plane depending on
 *              the port instead of importing this module's configuration.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.core.agent.config;

import org.springframework.stereotype.Component;

import com.oli.orchestrator_demo.kernel.contract.port.ModelDefaultsReader;

@Component
public class ModelDefaultsBridge implements ModelDefaultsReader {

  private final AgentModelProperties properties;

  public ModelDefaultsBridge(AgentModelProperties properties) {
    this.properties = properties;
  }

  @Override
  public ModelDefaults defaults(String agent) {
    AgentModelProperties.AgentSpec spec = properties.agent(agent);
    return new ModelDefaults(spec.getModel(), spec.getTemperature());
  }
}
