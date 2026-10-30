/**
 * @ClassName AgentDescriptorRegistryTest
 * @Description Verifies the collection of the self-declared slot contracts:
 *              the built-in descriptors are gathered from the real agent
 *              beans in declaration order, enriched with the
 *              {@code orchestrator.agents.*} model defaults, and the
 *              effective view prefers persisted control-plane overrides with
 *              the built-ins as fallback.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;

import com.oli.orchestrator_demo.agent.BudgetAgent;
import com.oli.orchestrator_demo.agent.ItineraryAgent;
import com.oli.orchestrator_demo.agent.ResearchAgent;
import com.oli.orchestrator_demo.agent.ReviewAgent;
import com.oli.orchestrator_demo.config.AgentModelProperties;

class AgentDescriptorRegistryTest {

  private AgentModelProperties modelProperties;
  private AgentDescriptorRegistry registry;

  @BeforeEach
  void setUp() {
    modelProperties = new AgentModelProperties();
    modelProperties.getAgents().get("research").setModel("qwen:7b");
    modelProperties.getAgents().get("research").setTemperature(0.7);

    ChatModel model = Mockito.mock(ChatModel.class);
    registry = new AgentDescriptorRegistry(List.of(
        new ResearchAgent(model, AgentRuntimeStore.fixed(), null),
        new BudgetAgent(model, AgentRuntimeStore.fixed(), null),
        new ItineraryAgent(model, AgentRuntimeStore.fixed(), null),
        new ReviewAgent(model, AgentRuntimeStore.fixed(), null)), modelProperties, null);
  }

  @Test
  void collectsTheBuiltInContractsFromTheAgentBeans() {
    assertThat(registry.builtIns()).containsOnlyKeys("research", "budget", "itinerary", "review");

    AgentDescriptor research = registry.builtIns().get("research");
    assertThat(research.outputSlots()).containsExactly("researchResponse");
    assertThat(research.inputSlots()).isEmpty();
    assertThat(research.defaultModel()).isEqualTo("qwen:7b"); // enriched from the configuration
    assertThat(research.defaultTemperature()).isEqualTo(0.7);

    assertThat(registry.builtIns().get("budget").inputSlots()).containsExactly("researchResponse");
    assertThat(registry.builtIns().get("itinerary").pendingSafeSlots()).containsExactly("budgetResponse");
    assertThat(registry.builtIns().get("review").pendingSafeSlots())
        .containsExactly("budgetResponse", "itineraryResponse");
  }

  @Test
  void rejectsDuplicateAgentNames() {
    ChatModel model = Mockito.mock(ChatModel.class);
    assertThatThrownBy(() -> new AgentDescriptorRegistry(List.of(
        new ResearchAgent(model, AgentRuntimeStore.fixed(), null),
        new ResearchAgent(model, AgentRuntimeStore.fixed(), null)), modelProperties, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("research");
  }

  @Test
  void theEffectiveViewPrefersPersistedOverrides() {
    AgentDescriptor override = new AgentDescriptor("budget", "Cost Controller", "",
        List.of("budgetResponse"), List.of("researchResponse"), List.of(), null, null);
    Set<String> agents = Set.copyOf(AgentDefaults.KNOWN_AGENTS);

    Map<String, AgentDescriptor> effective = registry.effectiveDescriptors(agents, Map.of("budget", override));

    assertThat(effective.get("budget").title()).isEqualTo("Cost Controller"); // override wins
    assertThat(effective.get("research").title()).isEqualTo("Travel Research"); // built-in fallback
    assertThat(effective).containsOnlyKeys("research", "budget", "itinerary", "review");
    assertThat(registry.effective(agents, Map.of(), "ghost")).isNull();
  }
}
