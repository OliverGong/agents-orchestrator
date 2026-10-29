/**
 * @ClassName ChatClientStubs
 * @Description Test support: builds a ChatClient on top of a mocked ChatModel
 *              that always answers with a fixed JSON payload. This keeps the
 *              real ChatClient machinery (prompt building, structured output
 *              conversion via entity()) under test while no live model is
 *              required.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo;

import java.util.List;
import java.util.stream.Collectors;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

public final class ChatClientStubs {

  /**
   * The mocked {@link ChatModel} together with a {@link ChatClient} built on top of it.
   */
  public record StubbedModel(ChatModel model, ChatClient client) {
  }

  private ChatClientStubs() {
  }

  /**
   * Creates a model/client pair where every model call answers with the given JSON.
   */
  public static StubbedModel modelReturning(String assistantJson) {
    ChatModel model = Mockito.mock(ChatModel.class);
    Mockito.when(model.getOptions()).thenReturn(ChatOptions.builder().build());
    Mockito.when(model.call(Mockito.any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(assistantJson)))));
    return new StubbedModel(model, ChatClient.builder(model).build());
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
