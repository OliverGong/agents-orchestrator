/**
 * @ClassName UsageCaptureAdvisorTest
 * @Description Verifies the usage capture inside the real ChatClient
 *              machinery: a mocked ChatModel answering with response metadata
 *              (usage + model) has its usage accumulated into the stage's
 *              {@link LmUsageCollector}, multiple calls sum up, and calls
 *              without a stage accumulator (or without metadata) are silently
 *              ignored.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.core.agent.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import com.oli.orchestrator_demo.ops.observability.events.AgentObservabilityEvent;
import com.oli.orchestrator_demo.ops.observability.metrics.LmUsageCollector;

class UsageCaptureAdvisorTest {

  @AfterEach
  void clearThreadLocal() {
    LmUsageCollector.clear();
  }

  private ChatModel model(String text, String modelName, DefaultUsage usage) {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    when(chatModel.call(org.mockito.ArgumentMatchers.any(Prompt.class)))
        .thenAnswer(invocation -> new ChatResponse(
            List.of(new Generation(new AssistantMessage(text))),
            ChatResponseMetadata.builder().model(modelName).usage(usage).build()));
    return chatModel;
  }

  @Test
  void capturesUsageFromResponseMetadata() {
    LmUsageCollector.begin();
    ChatModel chatModel = model("{}", "qwen3:8b", new DefaultUsage(120, 45));
    ChatClient.builder(chatModel).defaultAdvisors(new UsageCaptureAdvisor()).build()
        .prompt().user("plan a trip").call().content();

    AgentObservabilityEvent.LmUsage usage = LmUsageCollector.drain();
    assertThat(usage.promptTokens()).isEqualTo(120);
    assertThat(usage.completionTokens()).isEqualTo(45);
    assertThat(usage.totalTokens()).isEqualTo(165);
    assertThat(usage.model()).isEqualTo("qwen3:8b");
    assertThat(usage.retries()).isZero();
  }

  @Test
  void accumulatesAcrossCallsInsideOneStage() {
    LmUsageCollector.begin();
    ChatModel chatModel = model("{}", "gpt-test", new DefaultUsage(10, 5));
    ChatClient client = ChatClient.builder(chatModel).defaultAdvisors(new UsageCaptureAdvisor()).build();
    client.prompt().user("one").call().content();
    client.prompt().user("two").call().content();

    assertThat(LmUsageCollector.drain().totalTokens()).isEqualTo(30);
  }

  @Test
  void ignoresCallsWithoutMetadataOrStage() {
    // No stage accumulator: recording is a no-op.
    ChatModel bare = model("{}", "gpt-test", new DefaultUsage(10, 5));
    ChatClient.builder(bare).defaultAdvisors(new UsageCaptureAdvisor()).build()
        .prompt().user("outside").call().content();
    assertThat(LmUsageCollector.drain()).isNull();

    // Stage accumulator but a response without metadata: usage stays empty.
    LmUsageCollector.begin();
    ChatModel model = mock(ChatModel.class);
    org.mockito.Mockito.when(model.getOptions()).thenReturn(ChatOptions.builder().build());
    org.mockito.Mockito.when(model.call(org.mockito.ArgumentMatchers.any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{}")))));
    ChatClient.builder(model).defaultAdvisors(new UsageCaptureAdvisor()).build()
        .prompt().user("no metadata").call().content();
    AgentObservabilityEvent.LmUsage usage = LmUsageCollector.drain();
    assertThat(usage.promptTokens()).isZero();
    assertThat(usage.model()).isNull();
  }
}
