/**
 * @ClassName ResearchAgent
 * @Description Step 1 of the workflow: gathers suitable attractions, food
 *              options and practical travel tips for the requested trip and
 *              attaches a {@link ResearchResponse} to the context.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.1
 */
package com.oli.orchestrator_demo.agent;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import com.oli.orchestrator_demo.control.AgentRuntimeConfig;
import com.oli.orchestrator_demo.control.AgentRuntimeStore;
import com.oli.orchestrator_demo.model.OrchestratorContext;
import com.oli.orchestrator_demo.model.ResearchResponse;
import com.oli.orchestrator_demo.model.TripRequest;

@Component
public class ResearchAgent extends AbstractContextAgent<ResearchResponse> {

  public ResearchAgent(ChatModel chatModel, AgentRuntimeStore runtimeStore) {
    super("research", chatModel, runtimeStore);
  }

  @Override
  protected ResearchResponse respond(OrchestratorContext context) {
    AgentRuntimeConfig runtime = runtime();
    TripRequest request = context.request();
    return chatClient
        .prompt()
        .system(runtime.systemPrompt())
        .options(chatOptions(runtime))
        .user(
          user -> user
            .text("""
                Research a trip using these requirements:

                Destination: {destination}
                Number of days: {days}
                Total budget: {budget}
                Preferences: {preferences}

                Return suitable attractions, food options
                and important travel tips.
                """)
            .param("destination", request.destination())
            .param("days", request.numberOfDays())
            .param("budget", request.budget())
            .param("preferences", request.preferences()))
        .call()
        .entity(ResearchResponse.class);
  }

  @Override
  protected OrchestratorContext merge(OrchestratorContext context, ResearchResponse response) {
    return context.withResearchResponse(response);
  }
}
