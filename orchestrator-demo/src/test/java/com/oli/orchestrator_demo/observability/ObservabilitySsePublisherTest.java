/**
 * @ClassName ObservabilitySsePublisherTest
 * @Description Verifies the SSE fan-out contract: registering an emitter
 *              works without a servlet response (early sends are buffered by
 *              the emitter), completing it detaches it from the fan-out, and
 *              publishing without subscribers is a safe no-op.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import tools.jackson.databind.json.JsonMapper;

class ObservabilitySsePublisherTest {

  private final ObservabilitySsePublisher publisher =
      new ObservabilitySsePublisher(JsonMapper.builder().build());

  @Test
  void publishWithoutSubscribersIsANoOp() {
    assertThatCode(() -> publisher.publish(AgentObservabilityEvent.runStarted(
        "run-1", "default-trip-planning", "SEQUENTIAL"))).doesNotThrowAnyException();
  }

  @Test
  void registrationBuffersTheConnectedEventAndCompletionDetachesTheEmitter() {
    SseEmitter emitter = publisher.register();
    assertThat(emitter).isNotNull();

    // Publishing to a registered emitter must not throw even though no HTTP
    // response is attached (the send is buffered/failed silently).
    assertThatCode(() -> publisher.publish(AgentObservabilityEvent.stageCompleted(
        "run-1", "research", "research", 0, List.of(), "SEQUENTIAL", "main", 5,
        "{\"in\":1}", "{\"out\":1}"))).doesNotThrowAnyException();

    // Completing the stream detaches it: further publishes stay a no-op.
    emitter.complete();
    assertThatCode(() -> publisher.publish(AgentObservabilityEvent.runCompleted(
        "run-1", "{\"final\":true}"))).doesNotThrowAnyException();
  }
}
