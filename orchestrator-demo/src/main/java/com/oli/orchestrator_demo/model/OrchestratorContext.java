/**
 * @ClassName OrchestratorContext
 * @Description Immutable, append-only workflow state shared by every agent of
 *              the trip planning workflow. Agents never mutate the context;
 *              each step contributes its own response and returns a new
 *              instance (blackboard pattern). The immutability is also what
 *              makes concurrent wave execution safe: parallel stages share a
 *              snapshot and their contributions are merged via
 *              {@link #mergeFrom}.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.model;

public record OrchestratorContext(
  TripRequest request,
  ResearchResponse researchResponse,
  BudgetResponse budgetResponse,
  ItineraryResponse itineraryResponse,
  ReviewResponse reviewResponse
) {

  /**
   * Creates the initial workflow state carrying only the user's trip request.
   */
  public static OrchestratorContext start(TripRequest request) {
    return new OrchestratorContext(request, null, null, null, null);
  }

  public OrchestratorContext withResearchResponse(ResearchResponse researchResponse) {
    return new OrchestratorContext(request, researchResponse, budgetResponse, itineraryResponse, reviewResponse);
  }

  public OrchestratorContext withBudgetResponse(BudgetResponse budgetResponse) {
    return new OrchestratorContext(request, researchResponse, budgetResponse, itineraryResponse, reviewResponse);
  }

  public OrchestratorContext withItineraryResponse(ItineraryResponse itineraryResponse) {
    return new OrchestratorContext(request, researchResponse, budgetResponse, itineraryResponse, reviewResponse);
  }

  public OrchestratorContext withReviewResponse(ReviewResponse reviewResponse) {
    return new OrchestratorContext(request, researchResponse, budgetResponse, itineraryResponse, reviewResponse);
  }

  /**
   * Merges the contribution of a concurrently executed stage into this
   * context: every slot that is still {@code null} here is filled from
   * {@code other}; slots already present are kept, which makes the merge
   * deterministic (first application wins for a conflicting write). The
   * request is never overwritten.
   */
  public OrchestratorContext mergeFrom(OrchestratorContext other) {
    return new OrchestratorContext(
        request,
        researchResponse != null ? researchResponse : other.researchResponse,
        budgetResponse != null ? budgetResponse : other.budgetResponse,
        itineraryResponse != null ? itineraryResponse : other.itineraryResponse,
        reviewResponse != null ? reviewResponse : other.reviewResponse);
  }

  /**
   * Terminal operation of the workflow: returns the final state as produced
   * by the review agent.
   */
  public OrchestratorContext end() {
    return this;
  }
}
