/**
 * @ClassName BudgetAgentTest
 * @Description Verifies that the budget agent consumes the research findings
 *              of the previous step and attaches the structured budget
 *              response to the context.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.oli.orchestrator_demo.ChatClientStubs;
import com.oli.orchestrator_demo.model.OrchestratorContext;
import com.oli.orchestrator_demo.model.ResearchResponse;
import com.oli.orchestrator_demo.model.TripRequest;

class BudgetAgentTest {

  @Test
  void estimatesCostsFromResearchAndAttachesBudgetResponseToContext() {
    var stub = ChatClientStubs.modelReturning("""
        {"accommodation":5000.00,
         "transportCost":2000.00,
         "foodCost":3000.00,
         "activityCost":1500.00,
         "totalCost":11500.00,
         "isBudgetMet":true,
         "recommendations":["Book accommodation early"]}
        """);
    BudgetAgent agent = new BudgetAgent(stub.model(), stub.store());

    OrchestratorContext context = OrchestratorContext
        .start(new TripRequest("Tokyo", 5, new BigDecimal("15000"), "food and culture"))
        .withResearchResponse(new ResearchResponse(
            List.of("Senso-ji"), List.of("Buy a Suica card"), List.of("Ramen")));

    OrchestratorContext result = agent.execute(context);

    assertThat(result.budgetResponse()).isNotNull();
    assertThat(result.budgetResponse().totalCost()).isEqualByComparingTo("11500");
    assertThat(result.budgetResponse().isBudgetMet()).isTrue();
    assertThat(result.budgetResponse().recommendations()).containsExactly("Book accommodation early");

    String userText = ChatClientStubs.userTextOf(stub.model());
    assertThat(userText)
        .contains("Destination: Tokyo")
        .contains("Number of days: 5")
        .contains("Total budget: 15000")
        .contains("Senso-ji");
  }
}
