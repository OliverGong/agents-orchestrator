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
package com.oli.orchestrator_demo.support;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
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

import com.oli.orchestrator_demo.kernel.contract.port.runtime.AgentRuntimeStore;
import com.oli.orchestrator_demo.kernel.contract.port.runtime.RunInstanceSink;
import com.oli.orchestrator_demo.ops.instance.InstanceStore;
import com.oli.orchestrator_demo.kernel.contract.model.run.RunInstance;
import com.oli.orchestrator_demo.ops.observability.infra.NoopRunMetricsStore;
import com.oli.orchestrator_demo.ops.observability.events.ObservabilityActor;
import com.oli.orchestrator_demo.ops.observability.events.ObservabilitySsePublisher;
import com.oli.orchestrator_demo.ops.observability.metrics.RunMetricsStore;
import com.oli.orchestrator_demo.governance.teamlog.infra.NoopTeamLogStore;

import tools.jackson.databind.json.JsonMapper;

public final class ChatClientStubs {

  /**
   * The mocked {@link ChatModel} together with the runtime store the agents
   * read their (control-plane) configuration from.
   */
  public record StubbedModel(ChatModel model, AgentRuntimeStore store) {
  }

  /** In-memory {@link InstanceStore} fake for actor tests. */
  public static final class CollectingInstanceStore implements InstanceStore {
    private final Map<String, RunInstance> rows = new ConcurrentHashMap<>();

    @Override
    public void start(RunInstance instance) {
      rows.put(instance.runId(), instance);
    }

    @Override
    public void complete(String runId, Instant completedAt, Long durationMs) {
      mutate(runId, i -> i.completed(completedAt, durationMs));
    }

    @Override
    public void fail(String runId, Instant completedAt, String errorMessage) {
      mutate(runId, i -> i.failed(completedAt, errorMessage));
    }

    @Override
    public Optional<RunInstance> find(String runId) {
      return Optional.ofNullable(rows.get(runId));
    }

    @Override
    public List<RunInstance> query(InstanceQuery query) {
      return rows.values().stream()
          .filter(i -> InstanceStore.matches(query, i))
          .sorted(Comparator.comparing(RunInstance::startedAt).reversed())
          .limit(Math.max(1, query.limit()))
          .toList();
    }

    @Override
    public String name() {
      return "collecting";
    }

    private void mutate(String runId, UnaryOperator<RunInstance> op) {
      rows.computeIfPresent(runId, (id, current) -> op.apply(current));
    }
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

  /** Bridges a test instance store onto the write-side contract port. */
  public static RunInstanceSink sink(InstanceStore store) {
    return new RunInstanceSink() {
      @Override
      public void start(RunInstance instance) {
        store.start(instance);
      }

      @Override
      public void complete(String runId, Instant completedAt, Long durationMs) {
        store.complete(runId, completedAt, durationMs);
      }

      @Override
      public void fail(String runId, Instant completedAt, String errorMessage) {
        store.fail(runId, completedAt, errorMessage);
      }
    };
  }

  /** An observability actor wired with a real (emitter-less) SSE publisher. */
  public static ObservabilityActor actor() {
    return new ObservabilityActor(new ObservabilitySsePublisher(JsonMapper.builder().build()),
        new NoopRunMetricsStore(), new NoopTeamLogStore(), sink(new CollectingInstanceStore()));
  }

  /** An observability actor wired with the given (test) metrics store. */
  public static ObservabilityActor actor(RunMetricsStore metricsStore) {
    return new ObservabilityActor(new ObservabilitySsePublisher(JsonMapper.builder().build()),
        metricsStore, new NoopTeamLogStore(), sink(new CollectingInstanceStore()));
  }

  /** An observability actor wired with the given metrics and instance stores. */
  public static ObservabilityActor actor(RunMetricsStore metricsStore, InstanceStore instanceStore) {
    return new ObservabilityActor(new ObservabilitySsePublisher(JsonMapper.builder().build()),
        metricsStore, new NoopTeamLogStore(), sink(instanceStore));
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
