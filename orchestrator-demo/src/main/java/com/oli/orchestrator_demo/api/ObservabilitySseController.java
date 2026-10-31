/**
 * @ClassName ObservabilitySseController
 * @Description The subscription endpoint of the live observability stream:
 *              {@code GET /api/v1/events/stream} opens a text/event-stream
 *              connection that receives every {@link AgentObservabilityEvent}
 *              as it is folded into the run timeline (see
 *              {@link ObservabilitySsePublisher}). This is the push-side
 *              complement of the polling endpoints under
 *              {@code /api/trips/runs} and drives the console's real-time
 *              blackboard view without any polling latency.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.oli.orchestrator_demo.observability.ObservabilitySsePublisher;

@RestController
public class ObservabilitySseController {

  private final ObservabilitySsePublisher publisher;

  public ObservabilitySseController(ObservabilitySsePublisher publisher) {
    this.publisher = publisher;
  }

  @GetMapping(value = "/api/v1/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream() {
    return publisher.register();
  }
}
