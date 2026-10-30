/**
 * @ClassName ReviewAgentTest
 * @Description Verifies that the review agent consumes all upstream results
 *              (research, budget, itinerary) and attaches the final
 *              structured review to the context.
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
import com.oli.orchestrator_demo.model.BudgetResponse;
import com.oli.orchestrator_demo.model.ItineraryResponse;
import com.oli.orchestrator_demo.model.OrchestratorContext;
import com.oli.orchestrator_demo.model.ResearchResponse;
import com.oli.orchestrator_demo.model.TripRequest;

class ReviewAgentTest {

  @Test
  void reviewsAllResultsAndAttachesReviewResponseToContext() {
    var stub = ChatClientStubs.modelReturning("""
        {"isValid":true,
         "summary":"A well balanced 5 day trip within budget",
         "issues":[],
         "recommendations":["Pre-book the teamLab tickets"]}
        """);
    ReviewAgent agent = new ReviewAgent(stub.model(), stub.store(), null);

    OrchestratorContext context = OrchestratorContext
        .start(new TripRequest("Tokyo", 5, new BigDecimal("15000"), "food and culture"))
        .withResearchResponse(new ResearchResponse(
            List.of("Senso-ji"), List.of("Buy a Suica card"), List.of("Ramen")))
        .withBudgetResponse(new BudgetResponse(
            new BigDecimal("5000"), new BigDecimal("2000"), new BigDecimal("3000"),
            new BigDecimal("1500"), new BigDecimal("11500"), true, List.of("Book early")))
        .withItineraryResponse(new ItineraryResponse(List.of(
            new ItineraryResponse.DayPlan(1, "Senso-ji", "Ueno Park", "Ramen tasting", new BigDecimal("250")))));

    OrchestratorContext result = agent.execute(context);

    assertThat(result.reviewResponse()).isNotNull();
    assertThat(result.reviewResponse().isValid()).isTrue();
    assertThat(result.reviewResponse().summary()).isEqualTo("A well balanced 5 day trip within budget");
    assertThat(result.reviewResponse().issues()).isEmpty();

    String userText = ChatClientStubs.userTextOf(stub.model());
    assertThat(userText)
        .contains("Destination: Tokyo")
        .contains("Senso-ji")
        .contains("Book early")
        .contains("DayPlan[day=1");
  }
}
