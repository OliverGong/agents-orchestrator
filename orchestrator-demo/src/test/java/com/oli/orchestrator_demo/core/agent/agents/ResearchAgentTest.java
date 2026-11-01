/**
 * @ClassName ResearchAgentTest
 * @Description Verifies that the research agent sends a prompt containing all
 *              trip requirements and attaches the structured research
 *              response to the context.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.core.agent.agents;
import com.oli.orchestrator_demo.support.ChatClientStubs;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.oli.orchestrator_demo.support.ChatClientStubs;
import com.oli.orchestrator_demo.kernel.contract.model.context.OrchestratorContext;
import com.oli.orchestrator_demo.kernel.contract.model.llm.ResearchResponse;
import com.oli.orchestrator_demo.kernel.contract.model.run.TripRequest;

class ResearchAgentTest {

  @Test
  void sendsRequirementsAndAttachesResearchResponseToContext() {
    var stub = ChatClientStubs.modelReturning("""
        {"attractions":["Senso-ji","Ueno Park"],
         "travelTips":["Buy a Suica card"],
         "foodOptions":["Ramen","Sushi"]}
        """);
    ResearchAgent agent = new ResearchAgent(stub.model(), stub.store(), null);

    OrchestratorContext result = agent.execute(
        OrchestratorContext.start(new TripRequest("Tokyo", 5, new BigDecimal("15000"), "food and culture")));

    assertThat(result.researchResponse()).isEqualTo(new ResearchResponse(
        List.of("Senso-ji", "Ueno Park"), List.of("Buy a Suica card"), List.of("Ramen", "Sushi")));

    String userText = ChatClientStubs.userTextOf(stub.model());
    assertThat(userText)
        .contains("Destination: Tokyo")
        .contains("Number of days: 5")
        .contains("Total budget: 15000")
        .contains("Preferences: food and culture");
  }
}
