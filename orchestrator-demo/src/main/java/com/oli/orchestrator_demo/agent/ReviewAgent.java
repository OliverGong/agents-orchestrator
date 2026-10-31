/**
 * @ClassName ReviewAgent
 * @Description Step 4 (final step) of the workflow: reviews the research,
 *              budget and itinerary against the original requirements,
 *              corrects inconsistencies and attaches a {@link ReviewResponse}
 *              with the final summary to the context. Contributions of
 *              stages that ran in parallel are reconciled here; if a slot is
 *              still empty (e.g. a custom template lets the review depend
 *              only on the research), the prompt states that explicitly
 *              instead of silently inventing content.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.1
 */
package com.oli.orchestrator_demo.agent;

import java.util.List;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import com.oli.orchestrator_demo.control.AgentDescriptor;
import com.oli.orchestrator_demo.control.AgentRuntimeConfig;
import com.oli.orchestrator_demo.control.AgentRuntimeStore;
import com.oli.orchestrator_demo.mcp.McpConnectionRegistry;
import com.oli.orchestrator_demo.model.OrchestratorContext;
import com.oli.orchestrator_demo.model.ReviewResponse;
import com.oli.orchestrator_demo.model.TripRequest;

@Component
public class ReviewAgent extends AbstractContextAgent<ReviewResponse> {

  private static final String PENDING = """
      (not yet available - the contributing stage has not merged its result \
      into the blackboard; flag it as missing information instead of inventing it)""";

  public ReviewAgent(ChatModel chatModel, AgentRuntimeStore runtimeStore, McpConnectionRegistry mcpRegistry) {
    super("review", chatModel, runtimeStore, mcpRegistry);
  }

  @Override
  public AgentDescriptor descriptor() {
    // Budget and itinerary are reconciled when present and explicitly flagged
    // when absent (PENDING placeholders), so both slots are pending-safe.
    return new AgentDescriptor(name(), "Final Review",
        "Reconciles research, budget and itinerary, corrects inconsistencies and produces the final answer.",
        List.of("reviewResponse"), List.of("researchResponse", "budgetResponse", "itineraryResponse"),
        List.of("budgetResponse", "itineraryResponse"), null, null);
  }

  @Override
  protected ReviewResponse respond(OrchestratorContext context) {
    AgentRuntimeConfig runtime = runtime();
    TripRequest request = context.request();
    return chatClient
        .prompt()
        .system(runtime.systemPrompt())
        .options(chatOptions(runtime))
        .user(
          user -> user
            .text("""
                Review this trip planning result against the original requirements.

                Destination: {destination}
                Number of days: {days}
                Total budget: {budget}

                Research findings:
                {research}

                Budget breakdown:
                {budgetBreakdown}

                Itinerary:
                {itinerary}

                Check for inconsistencies such as impossible travel times,
                budget violations or missing information, list the issues
                found and produce a clear final summary. Never invent missing
                prices or attractions.
                """)
            .param("destination", request.destination())
            .param("days", request.numberOfDays())
            .param("budget", request.budget())
            .param("research", context.researchResponse())
            .param("budgetBreakdown", context.budgetResponse() != null
                ? context.budgetResponse()
                : PENDING)
            .param("itinerary", context.itineraryResponse() != null
                ? context.itineraryResponse()
                : PENDING))
        .call()
        .entity(ReviewResponse.class);
  }

  @Override
  protected OrchestratorContext merge(OrchestratorContext context, ReviewResponse response) {
    return context.withReviewResponse(response);
  }
}
