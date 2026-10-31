/**
 * @ClassName ChatClientStubs
 * @Description Test support: builds a ChatClient on top of a mocked ChatModel
 *              that always answers with a fixed JSON payload. This keeps the
 *              real ChatClient machinery (prompt building, structured output
 *              conversion via entity()) under test while no live model is
 *              required. Also provides the control-plane-backed collaborator
 *              instances used across the test suite (agent runtime store,
 *              observability actor with its SSE fan-out).
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.1
 */
package com.oli.orchestrator_demo;

import java.util.List;
import java.util.stream.Collectors;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import com.oli.orchestrator_demo.control.AgentRuntimeStore;
import com.oli.orchestrator_demo.observability.ObservabilityActor;
import com.oli.orchestrator_demo.observability.ObservabilitySsePublisher;

import tools.jackson.databind.json.JsonMapper;

public final class ChatClientStubs {

  /**
   * The mocked {@link ChatModel} together with the runtime store the agents
   * read their (control-plane) configuration from.
   */
  public record StubbedModel(ChatModel model, AgentRuntimeStore store) {
  }

  private ChatClientStubs() {
  }

  /**
   * Creates a model/store pair where every model call answers with the given
   * JSON; the store serves the built-in default agent configurations.
   */
  public static StubbedModel modelReturning(String assistantJson) {
    ChatModel model = Mockito.mock(ChatModel.class);
    Mockito.when(model.getOptions()).thenReturn(ChatOptions.builder().build());
    Mockito.when(model.call(Mockito.any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(assistantJson)))));
    return new StubbedModel(model, AgentRuntimeStore.fixed());
  }

  /** An observability actor wired with a real (emitter-less) SSE publisher. */
  public static ObservabilityActor actor() {
    return new ObservabilityActor(new ObservabilitySsePublisher(JsonMapper.builder().build()));
  }

  /**
   * Extracts the concatenated user message text of the captured prompt,
   * including the structured-output format instructions appended by entity().
   */
  public static String userTextOf(ChatModel model) {
    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    Mockito.verify(model, Mockito.atLeastOnce()).call(captor.capture());
    return captor.getAllValues().stream()
        .flatMap(prompt -> prompt.getInstructions().stream())
        .filter(UserMessage.class::isInstance)
        .map(message -> ((UserMessage) message).getText())
        .collect(Collectors.joining("\n"));
  }
}
