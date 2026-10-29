/**
 * @ClassName ReviewAgent
 * @Description Step 4 (final step) of the workflow: reviews the research,
 *              budget and itinerary against the original requirements,
 *              corrects inconsistencies and attaches a {@link ReviewResponse}
 *              with the final summary to the context.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.oli.orchestrator_demo.model.OrchestratorContext;
import com.oli.orchestrator_demo.model.ReviewResponse;
import com.oli.orchestrator_demo.model.TripRequest;

@Component
public class ReviewAgent extends AbstractContextAgent<ReviewResponse> {

  public ReviewAgent(@Qualifier("reviewChatClient") ChatClient chatClient) {
    super("review", chatClient);
  }

  @Override
  protected ReviewResponse respond(OrchestratorContext context) {
    TripRequest request = context.request();
    return chatClient
        .prompt()
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
            .param("budgetBreakdown", context.budgetResponse())
            .param("itinerary", context.itineraryResponse()))
        .call()
        .entity(ReviewResponse.class);
  }

  @Override
  protected OrchestratorContext merge(OrchestratorContext context, ReviewResponse response) {
    return context.withReviewResponse(response);
  }
}
