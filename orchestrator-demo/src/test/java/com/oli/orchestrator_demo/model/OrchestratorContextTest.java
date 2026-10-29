/**
 * @ClassName OrchestratorContextTest
 * @Description Unit tests for the immutable workflow state: start semantics,
 *              append-only with* derivations, immutability guarantees, the
 *              mergeFrom operation used by concurrent waves and the end()
 *              terminal operation.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class OrchestratorContextTest {

  private final TripRequest request =
      new TripRequest("Tokyo", 5, new BigDecimal("15000"), "food and culture");

  private final ResearchResponse research =
      new ResearchResponse(List.of("Senso-ji"), List.of("Buy Suica"), List.of("Ramen"));

  private final BudgetResponse budget = new BudgetResponse(
      new BigDecimal("5000"), new BigDecimal("2000"), new BigDecimal("3000"),
      new BigDecimal("1500"), new BigDecimal("11500"), true, List.of("Book early"));

  private final ItineraryResponse itinerary = new ItineraryResponse(List.of(
      new ItineraryResponse.DayPlan(1, "Senso-ji", "Ueno park", "Ramen tasting", new BigDecimal("250"))));

  private final ReviewResponse review =
      new ReviewResponse(true, "Well balanced trip", List.of(), List.of("Book tickets in advance"));

  @Test
  void startCarriesOnlyTheRequest() {
    OrchestratorContext context = OrchestratorContext.start(request);

    assertThat(context.request()).isSameAs(request);
    assertThat(context.researchResponse()).isNull();
    assertThat(context.budgetResponse()).isNull();
    assertThat(context.itineraryResponse()).isNull();
    assertThat(context.reviewResponse()).isNull();
  }

  @Test
  void withMethodsAppendResponsesProgressively() {
    OrchestratorContext context = OrchestratorContext.start(request)
        .withResearchResponse(research)
        .withBudgetResponse(budget)
        .withItineraryResponse(itinerary)
        .withReviewResponse(review);

    assertThat(context.request()).isSameAs(request);
    assertThat(context.researchResponse()).isSameAs(research);
    assertThat(context.budgetResponse()).isSameAs(budget);
    assertThat(context.itineraryResponse()).isSameAs(itinerary);
    assertThat(context.reviewResponse()).isSameAs(review);
  }

  @Test
  void derivationsNeverMutateThePreviousState() {
    OrchestratorContext start = OrchestratorContext.start(request);

    OrchestratorContext afterResearch = start.withResearchResponse(research);

    assertThat(start.researchResponse()).isNull();
    assertThat(afterResearch.researchResponse()).isSameAs(research);
    assertThat(afterResearch.request()).isSameAs(request);
  }

  @Test
  void mergeFromFillsSlotsThatAreStillNull() {
    OrchestratorContext base = OrchestratorContext.start(request);
    OrchestratorContext withResearch = base.withResearchResponse(research);
    OrchestratorContext withBudget = base.withBudgetResponse(budget);

    OrchestratorContext merged = withBudget.mergeFrom(withResearch);

    assertThat(merged.request()).isSameAs(request);
    assertThat(merged.researchResponse()).isSameAs(research);
    assertThat(merged.budgetResponse()).isSameAs(budget);
    assertThat(merged.itineraryResponse()).isNull();
    assertThat(merged.reviewResponse()).isNull();
  }

  @Test
  void mergeFromKeepsAlreadyPresentContributions() {
    ResearchResponse otherResearch =
        new ResearchResponse(List.of("Other"), List.of(), List.of());
    OrchestratorContext base = OrchestratorContext.start(request).withResearchResponse(research);
    OrchestratorContext derived = base.withResearchResponse(otherResearch);

    OrchestratorContext merged = base.mergeFrom(derived);

    assertThat(merged.researchResponse()).isSameAs(research);
  }

  @Test
  void endReturnsTheFinalState() {
    OrchestratorContext context = OrchestratorContext.start(request)
        .withResearchResponse(research)
        .withBudgetResponse(budget)
        .withItineraryResponse(itinerary)
        .withReviewResponse(review);

    assertThat(context.end()).isSameAs(context);
  }
}
