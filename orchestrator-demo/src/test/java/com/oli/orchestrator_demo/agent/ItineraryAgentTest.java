/**
 * @ClassName ItineraryAgentTest
 * @Description Verifies that the itinerary agent consumes research findings
 *              and budget breakdown of the previous steps and attaches the
 *              structured day-by-day itinerary to the context.
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

class ItineraryAgentTest {

  @Test
  void plansDaysFromResearchAndBudgetAndAttachesItineraryToContext() {
    var stub = ChatClientStubs.modelReturning("""
        {"dayPlans":[
          {"day":1,"morning":"Senso-ji","afternoon":"Ueno Park","evening":"Ramen tasting","estimatedCost":250.00},
          {"day":2,"morning":"Tsukiji market","afternoon":"Akihabara","evening":"Izakaya","estimatedCost":300.00}
        ]}
        """);
    ItineraryAgent agent = new ItineraryAgent(stub.model(), stub.store());

    OrchestratorContext context = OrchestratorContext
        .start(new TripRequest("Tokyo", 5, new BigDecimal("15000"), "food and culture"))
        .withResearchResponse(new ResearchResponse(
            List.of("Senso-ji"), List.of("Buy a Suica card"), List.of("Ramen")))
        .withBudgetResponse(new BudgetResponse(
            new BigDecimal("5000"), new BigDecimal("2000"), new BigDecimal("3000"),
            new BigDecimal("1500"), new BigDecimal("11500"), true, List.of("Book early")));

    OrchestratorContext result = agent.execute(context);

    assertThat(result.itineraryResponse()).isNotNull();
    assertThat(result.itineraryResponse().dayPlans()).hasSize(2);
    assertThat(result.itineraryResponse().dayPlans().get(0).day()).isEqualTo(1);
    assertThat(result.itineraryResponse().dayPlans().get(0).morning()).isEqualTo("Senso-ji");
    assertThat(result.itineraryResponse().dayPlans().get(0).estimatedCost()).isEqualByComparingTo("250");

    String userText = ChatClientStubs.userTextOf(stub.model());
    assertThat(userText)
        .contains("Destination: Tokyo")
        .contains("Senso-ji")
        .contains("Book early");
  }

  @Test
  void plansWithoutBudgetBreakdownWhenTheBudgetStageRunsInParallel() {
    var stub = ChatClientStubs.modelReturning("""
        {"dayPlans":[
          {"day":1,"morning":"Senso-ji","afternoon":"Ueno Park","evening":"Ramen tasting","estimatedCost":250.00}
        ]}
        """);
    ItineraryAgent agent = new ItineraryAgent(stub.model(), stub.store());

    // Parallel template: budget and itinerary start from the same snapshot,
    // so the budget breakdown is not on the blackboard yet.
    OrchestratorContext context = OrchestratorContext
        .start(new TripRequest("Tokyo", 5, new BigDecimal("15000"), "food and culture"))
        .withResearchResponse(new ResearchResponse(
            List.of("Senso-ji"), List.of("Buy a Suica card"), List.of("Ramen")));

    OrchestratorContext result = agent.execute(context);

    assertThat(result.itineraryResponse()).isNotNull();

    String userText = ChatClientStubs.userTextOf(stub.model());
    assertThat(userText)
        .contains("not on the blackboard yet")
        .contains("stay conservative");
  }
}
