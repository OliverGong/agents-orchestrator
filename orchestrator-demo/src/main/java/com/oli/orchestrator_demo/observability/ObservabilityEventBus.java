/**
 * @ClassName ObservabilityEventBus
 * @Description Static fan-in for observability events fired from code that has
 *              no handle on the {@link ObservabilityActor} bean — currently the
 *              agent base class reporting parse retries from deep inside the
 *              model call. The actor subscribes at construction; events
 *              published before any listener exists (or outside a workflow
 *              run) are simply dropped. Kept deliberately tiny: producers with
 *              real bean access keep calling {@code actor.tell(...)} directly.
 * @Author OliverGong
 * @Date Oct 31, 2026
 * @Version 2.1
 */
package com.oli.orchestrator_demo.observability;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class ObservabilityEventBus {

  private static final List<Consumer<AgentObservabilityEvent>> LISTENERS = new CopyOnWriteArrayList<>();

  private ObservabilityEventBus() {
  }

  public static void subscribe(Consumer<AgentObservabilityEvent> listener) {
    LISTENERS.add(listener);
  }

  public static void publish(AgentObservabilityEvent event) {
    for (Consumer<AgentObservabilityEvent> listener : LISTENERS) {
      listener.accept(event);
    }
  }
}
