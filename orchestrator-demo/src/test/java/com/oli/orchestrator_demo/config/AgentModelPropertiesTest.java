/**
 * @ClassName AgentModelPropertiesTest
 * @Description Verifies the binding of `orchestrator.agents.*`: configured
 *              agents pick up their model/temperature, unconfigured agents
 *              keep the pre-seeded defaults (null = inherit backend default).
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AgentModelPropertiesTest {

  private AgentModelProperties bind(Map<String, String> values) {
    AgentModelProperties properties = new AgentModelProperties();
    new Binder(new MapConfigurationPropertySource(values))
        .bind("orchestrator", Bindable.ofInstance(properties));
    return properties;
  }

  @Test
  void bindsPerAgentModelSettings() {
    AgentModelProperties properties = bind(Map.of(
        "orchestrator.agents.research.model", "llama3.2",
        "orchestrator.agents.research.temperature", "0.2",
        "orchestrator.agents.review.model", "gpt-4o-mini"));

    assertThat(properties.agent("research").getModel()).isEqualTo("llama3.2");
    assertThat(properties.agent("research").getTemperature()).isEqualTo(0.2);
    assertThat(properties.agent("review").getModel()).isEqualTo("gpt-4o-mini");
    assertThat(properties.agent("review").getTemperature()).isNull();
  }

  @Test
  void unconfiguredAgentsInheritBackendDefaults() {
    AgentModelProperties properties = bind(Map.of());

    assertThat(properties.agent("budget").getModel()).isNull();
    assertThat(properties.agent("budget").getTemperature()).isNull();
    assertThat(properties.agent("itinerary").getModel()).isNull();
  }

  @Test
  void unknownAgentNameFailsFast() {
    AgentModelProperties properties = bind(Map.of());

    assertThatThrownBy(() -> properties.agent("nonexistent"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nonexistent");
  }
}
