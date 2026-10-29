/**
 * @ClassName BudgetAgent
 * @Description Step 2 of the workflow: estimates realistic costs from the
 *              research findings produced by the {@link ResearchAgent} and
 *              attaches a {@link BudgetResponse} to the context.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.oli.orchestrator_demo.model.BudgetResponse;
import com.oli.orchestrator_demo.model.OrchestratorContext;
import com.oli.orchestrator_demo.model.TripRequest;

@Component
public class BudgetAgent extends AbstractContextAgent<BudgetResponse> {

  public BudgetAgent(@Qualifier("budgetChatClient") ChatClient chatClient) {
    super("budget", chatClient);
  }

  @Override
  protected BudgetResponse respond(OrchestratorContext context) {
    TripRequest request = context.request();
    return chatClient
        .prompt()
        .user(
          user -> user
            .text("""
                Estimate realistic costs for this trip.

                Destination: {destination}
                Number of days: {days}
                Total budget: {budget}

                Research findings:
                {research}

                Break the costs down into accommodation, transport, food and
                activities, state the total cost, clearly indicate whether the
                trip stays within the budget and add short recommendations.
                """)
            .param("destination", request.destination())
            .param("days", request.numberOfDays())
            .param("budget", request.budget())
            .param("research", context.researchResponse()))
        .call()
        .entity(BudgetResponse.class);
  }

  @Override
  protected OrchestratorContext merge(OrchestratorContext context, BudgetResponse response) {
    return context.withBudgetResponse(response);
  }
}
