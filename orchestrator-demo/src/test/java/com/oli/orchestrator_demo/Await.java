/**
 * @ClassName Await
 * @Description Small polling helper for assertions on state that is updated
 *              asynchronously (e.g. events folded into the observability
 *              actor by its consumer thread).
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo;

import static org.assertj.core.api.Assertions.fail;

import java.util.function.Supplier;

public final class Await {

  private static final long DEFAULT_TIMEOUT_MS = 5_000;

  private Await() {
  }

  public static void until(Supplier<Boolean> condition) {
    long deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      if (Boolean.TRUE.equals(condition.get())) {
        return;
      }
      try {
        Thread.sleep(20);
      }
      catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while awaiting condition", ex);
      }
    }
    fail("Condition was not met within %d ms".formatted(DEFAULT_TIMEOUT_MS));
  }
}
