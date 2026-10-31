/**
 * @ClassName ObservabilitySsePublisher
 * @Description Server-sent-events fan-out of the observability event stream.
 *              The {@link ObservabilityActor} forwards every folded event to
 *              {@link #publish(AgentObservabilityEvent)} from its single
 *              consumer thread, which preserves the per-run event order;
 *              sending happens on per-emitter virtual threads so a slow
 *              browser client can never stall the actor. Clients subscribe
 *              via {@code GET /api/v1/events/stream} and receive one named
 *              event per observability record (run_started, stage_completed,
 *              stage_failed, run_completed, run_failed) with the event
 *              serialized as JSON — this is what drives the console's live
 *              blackboard view of the full agent context.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.observability;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class ObservabilitySsePublisher {

  private static final Logger log = LoggerFactory.getLogger(ObservabilitySsePublisher.class);

  private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
  private final ObjectMapper objectMapper;
  private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();

  public ObservabilitySsePublisher(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Opens a stream (no server-side timeout) and immediately confirms the subscription. */
  public SseEmitter register() {
    SseEmitter emitter = new SseEmitter(0L);
    emitter.onCompletion(() -> emitters.remove(emitter));
    emitter.onTimeout(() -> emitters.remove(emitter));
    emitter.onError(ex -> emitters.remove(emitter));
    emitters.add(emitter);
    send(emitter, "connected", "{\"status\":\"connected\"}");
    return emitter;
  }

  /** Fans one observability event out to every open stream; never blocks the caller. */
  public void publish(AgentObservabilityEvent event) {
    if (emitters.isEmpty()) {
      return;
    }
    String data;
    try {
      data = objectMapper.writeValueAsString(event);
    }
    catch (JacksonException ex) {
      log.warn("SSE event serialization failed ({}): {}", event.type(), ex.getMessage());
      return;
    }
    String name = event.type().name().toLowerCase(Locale.ROOT);
    for (SseEmitter emitter : emitters) {
      senders.execute(() -> send(emitter, name, data));
    }
  }

  private void send(SseEmitter emitter, String name, String data) {
    try {
      emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
    }
    catch (IOException | IllegalStateException ex) {
      // Broken pipe / completed emitter: drop it; the browser reconnects itself.
      emitters.remove(emitter);
    }
  }

  @PreDestroy
  void shutdown() throws InterruptedException {
    senders.shutdown();
    senders.awaitTermination(2, TimeUnit.SECONDS);
  }
}
