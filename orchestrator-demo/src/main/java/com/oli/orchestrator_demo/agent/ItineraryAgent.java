/**
 * @ClassName ItineraryAgent
 * @Description Step 3 of the workflow: creates a realistic day-by-day plan
 *              using the research findings (and, when available, the budget
 *              breakdown) already present in the context, and attaches an
 *              {@link ItineraryResponse}.
 *
 *              In the parallel template (research -> (budget || itinerary) ->
 *              review) both stages start from the same snapshot, so the
 *              budget breakdown is not on the blackboard yet; the prompt then
 *              says so explicitly and lets the agent plan against the user's
 *              budget alone — the review stage reconciles the two
 *              contributions afterwards.
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
import com.oli.orchestrator_demo.model.ItineraryResponse;
import com.oli.orchestrator_demo.model.OrchestratorContext;
import com.oli.orchestrator_demo.model.TripRequest;

@Component
public class ItineraryAgent extends AbstractContextAgent<ItineraryResponse> {

  /** Replaces the budget breakdown slot when the budget stage runs in parallel. */
  static final String BUDGET_PENDING = """
      (the budget agent is running in parallel and its breakdown is not on the \
      blackboard yet - plan against the user's budget alone and stay conservative)""";

  public ItineraryAgent(ChatModel chatModel, AgentRuntimeStore runtimeStore, McpConnectionRegistry mcpRegistry) {
    super("itinerary", chatModel, runtimeStore, mcpRegistry);
  }

  @Override
  public AgentDescriptor descriptor() {
    // The budget breakdown is optional by design: in the parallel template the
    // budget stage runs as a same-wave sibling, so the slot is pending-safe.
    return new AgentDescriptor(name(), "Itinerary Planning",
        "Creates a realistic day-by-day plan from the research (and the budget breakdown, when available).",
        List.of("itineraryResponse"), List.of("researchResponse", "budgetResponse"),
        List.of("budgetResponse"), null, null);
  }

  @Override
  protected ItineraryResponse respond(OrchestratorContext context) {
    AgentRuntimeConfig runtime = runtime();
    TripRequest request = context.request();
    return chatClient
        .prompt()
        .system(runtime.systemPrompt())
        .options(chatOptions(runtime))
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
            .param("budgetBreakdown", context.budgetResponse() != null
                ? context.budgetResponse()
                : BUDGET_PENDING))
        .call()
        .entity(ItineraryResponse.class);
  }

  @Override
  protected OrchestratorContext merge(OrchestratorContext context, ItineraryResponse response) {
    return context.withItineraryResponse(response);
  }
}
