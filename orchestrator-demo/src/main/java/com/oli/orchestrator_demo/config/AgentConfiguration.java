/**
 * @ClassName AgentConfiguration
 * @Description Initializes the agents definition: one ChatClient per agent,
 *              built on the backend ChatModel selected through
 *              `spring.ai.model.chat` (ollama / openai / ...) and customized
 *              per agent via `orchestrator.agents.<name>.model` and
 *              `.temperature` (see {@link AgentModelProperties}). Agents
 *              without explicit settings inherit the backend defaults. The
 *              collaboration template of the orchestrator is bound through
 *              {@link com.oli.orchestrator_demo.workflow.WorkflowProperties}.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.oli.orchestrator_demo.agent.ResponseSanitizerAdvisor;
import com.oli.orchestrator_demo.workflow.WorkflowProperties;

@Configuration
@EnableConfigurationProperties({AgentModelProperties.class, WorkflowProperties.class})
public class AgentConfiguration {

  private static final String RESEARCH_PERSONA = """
      You are a travel research agent.

      Your only responsibility is to identify useful places,
      food options and practical travel information.

      Do not create the final itinerary.
      Do not perform budget calculations.
      Return factual and concise information.
      """;

  private static final String BUDGET_PERSONA = """
      You are a travel budget agent.

      Estimate realistic costs using the supplied research.
      Never silently exceed the user's budget.
      Clearly indicate whether the proposed trip is affordable.
      """;

  private static final String ITINERARY_PERSONA = """
      You are an itinerary planning agent.

      Create a realistic day-by-day plan using only the supplied
      research and budget constraints.

      Avoid impossible travel times and budget violations.
      """;

  private static final String REVIEW_PERSONA = """
      You are the final review agent.

      Review the research, budget and itinerary.
      Correct inconsistencies and produce a clear final answer.

      Never invent missing prices or attractions.
      """;

  @Bean
  ChatClient researchChatClient(ChatModel chatModel, AgentModelProperties properties) {
    return chatClient(chatModel, properties.agent("research"), RESEARCH_PERSONA);
  }

  @Bean
  ChatClient budgetChatClient(ChatModel chatModel, AgentModelProperties properties) {
    return chatClient(chatModel, properties.agent("budget"), BUDGET_PERSONA);
  }

  @Bean
  ChatClient itineraryChatClient(ChatModel chatModel, AgentModelProperties properties) {
    return chatClient(chatModel, properties.agent("itinerary"), ITINERARY_PERSONA);
  }

  @Bean
  ChatClient reviewChatClient(ChatModel chatModel, AgentModelProperties properties) {
    return chatClient(chatModel, properties.agent("review"), REVIEW_PERSONA);
  }

  /**
   * Builds an agent specific ChatClient: the backend ChatModel supplies the
   * provider connection, the agent spec overrides model name and temperature
   * (null = inherit backend defaults), the persona fixes the responsibility.
   */
  private static ChatClient chatClient(ChatModel chatModel, AgentModelProperties.AgentSpec spec,
      String systemPrompt) {
    ChatOptions.Builder<?> options = ChatOptions.builder();
    if (spec.getModel() != null) {
      options = options.model(spec.getModel());
    }
    if (spec.getTemperature() != null) {
      options = options.temperature(spec.getTemperature());
    }
    return ChatClient.builder(chatModel)
        .defaultSystem(systemPrompt)
        .defaultAdvisors(new SimpleLoggerAdvisor(), new ResponseSanitizerAdvisor())
        .defaultOptions(options)
        .build();
  }
}
