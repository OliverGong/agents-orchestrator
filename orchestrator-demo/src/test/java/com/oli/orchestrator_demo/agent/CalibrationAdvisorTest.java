/**
 * @ClassName CalibrationAdvisorTest
 * @Description Verifies the control-plane response calibration: the
 *              agent-defined rules are applied to the model output after the
 *              response sanitizer has normalized it (fences stripped), an
 *              invalid regex is skipped instead of failing the call, and the
 *              rules run in declaration order.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.oli.orchestrator_demo.ChatClientStubs;
import com.oli.orchestrator_demo.control.AgentDefaults;
import com.oli.orchestrator_demo.control.AgentRuntimeConfig;
import com.oli.orchestrator_demo.control.AgentRuntimeStore;
import com.oli.orchestrator_demo.control.CalibrationRule;
import com.oli.orchestrator_demo.model.OrchestratorContext;
import com.oli.orchestrator_demo.model.TripRequest;

class CalibrationAdvisorTest {

  private final TripRequest request =
      new TripRequest("Tokyo", 5, new BigDecimal("15000"), "food and culture");

  @Test
  void appliesRulesToTheSanitizedOutputBeforeTheEntityConversion() {
    // The model wraps its JSON in a fence and pads the first attraction with
    // spaces; the calibration rule cleans the padded value after the
    // sanitizer has stripped the fence.
    var stub = ChatClientStubs.modelReturning("""
        ```json
        {"attractions":["  Senso-ji  "],"travelTips":["Buy a Suica card"],"foodOptions":["Ramen"]}
        ```
        """);
    AgentRuntimeStore store = name -> new AgentRuntimeConfig(name, AgentDefaults.systemPrompt(name),
        null, null,
        List.of(new CalibrationRule("\"  (Senso-ji)  \"", "\"$1\"", "trim padded attraction")),
        List.of());
    ResearchAgent agent = new ResearchAgent(stub.model(), store);

    OrchestratorContext result = agent.execute(OrchestratorContext.start(request));

    assertThat(result.researchResponse().attractions()).containsExactly("Senso-ji");
    assertThat(result.researchResponse().travelTips()).containsExactly("Buy a Suica card");
  }

  @Test
  void agentsWithoutRulesLeaveTheOutputUntouched() {
    var stub = ChatClientStubs.modelReturning("""
        {"attractions":["Senso-ji"],"travelTips":[],"foodOptions":["Ramen"]}
        """);
    ResearchAgent agent = new ResearchAgent(stub.model(), AgentRuntimeStore.fixed());

    OrchestratorContext result = agent.execute(OrchestratorContext.start(request));

    assertThat(result.researchResponse().attractions()).containsExactly("Senso-ji");
  }

  @Test
  void invalidPatternsAreSkippedAndRulesRunInDeclarationOrder() {
    String out = CalibrationAdvisor.apply("research", "a-b-c", List.of(
        new CalibrationRule("[", "X", "broken regex"),
        new CalibrationRule("a-", "A-", "first"),
        new CalibrationRule("A-b", "AB", "second")));
    assertThat(out).isEqualTo("AB-c");
  }

  @Test
  void nullTextPassesThrough() {
    assertThat(CalibrationAdvisor.apply("research", null,
        List.of(new CalibrationRule("a", "b", "ignored")))).isNull();
  }
}
