/**
 * @ClassName ItineraryAgent
 * @Description Step 3 of the workflow: creates a realistic day-by-day plan
 *              using only the research findings and budget constraints already
 *              present in the context, and attaches an {@link ItineraryResponse}.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.oli.orchestrator_demo.model.ItineraryResponse;
import com.oli.orchestrator_demo.model.OrchestratorContext;
import com.oli.orchestrator_demo.model.TripRequest;

@Component
public class ItineraryAgent extends AbstractContextAgent<ItineraryResponse> {

  public ItineraryAgent(@Qualifier("itineraryChatClient") ChatClient chatClient) {
    super("itinerary", chatClient);
  }

  @Override
  protected ItineraryResponse respond(OrchestratorContext context) {
    TripRequest request = context.request();
    return chatClient
        .prompt()
        .user(
          user -> user
            .text("""
                Create a realistic day-by-day itinerary for this trip.

                Destination: {destination}
                Number of days: {days}
                Total budget: {budget}

                Research findings:
                {research}

                Budget breakdown:
                {budgetBreakdown}

                Use only the supplied research and stay within the budget.
                Avoid impossible travel times and budget violations.
                Return one plan per day with morning, afternoon and evening
                activities plus the estimated cost of the day.
                """)
            .param("destination", request.destination())
            .param("days", request.numberOfDays())
            .param("budget", request.budget())
            .param("research", context.researchResponse())
            .param("budgetBreakdown", context.budgetResponse()))
        .call()
        .entity(ItineraryResponse.class);
  }

  @Override
  protected OrchestratorContext merge(OrchestratorContext context, ItineraryResponse response) {
    return context.withItineraryResponse(response);
  }
}
