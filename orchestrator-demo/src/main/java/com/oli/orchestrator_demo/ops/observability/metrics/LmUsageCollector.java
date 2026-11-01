/**
 * @ClassName LmUsageCollector
 * @Description Thread-local accumulator for the LM consumption of one workflow
 *              stage. The {@code UsageCaptureAdvisor} records every model
 *              call's token usage (plus the model name that served it) and the
 *              agent base class reports parse retries; the orchestrator opens
 *              the stage with {@link #begin}, drains the aggregate when it
 *              publishes the stage event and clears it in the finally block —
 *              so one stage event carries the consumption of all its attempts,
 *              including the retry re-samples. Works on both the request
 *              thread (sequential mode) and the per-stage virtual threads
 *              (concurrent mode), because the accumulator lives and dies on
 *              the executing thread.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.ops.observability.metrics;
import com.oli.orchestrator_demo.ops.observability.events.AgentObservabilityEvent;

public final class LmUsageCollector {

  private static final ThreadLocal<Accumulator> CURRENT = new ThreadLocal<>();

  private LmUsageCollector() {
  }

  private static final class Accumulator {
    long promptTokens;
    long completionTokens;
    long totalTokens;
    String model;
    int retries;

    AgentObservabilityEvent.LmUsage toUsage() {
      return new AgentObservabilityEvent.LmUsage(promptTokens, completionTokens, totalTokens, model, retries);
    }
  }

  /** Opens a fresh accumulator for the stage about to execute on this thread. */
  public static void begin() {
    CURRENT.set(new Accumulator());
  }

  /**
   * Adds one model call's usage (null-safe: backends without usage reporting
   * or calls outside a stage simply contribute nothing).
   */
  public static void record(Integer promptTokens, Integer completionTokens, Integer totalTokens, String model) {
    Accumulator accumulator = CURRENT.get();
    if (accumulator == null) {
      return;
    }
    if (promptTokens != null) {
      accumulator.promptTokens += promptTokens;
    }
    if (completionTokens != null) {
      accumulator.completionTokens += completionTokens;
    }
    if (totalTokens != null) {
      accumulator.totalTokens += totalTokens;
    }
    if (model != null && !model.isBlank()) {
      accumulator.model = model;
    }
  }

  /** Reports one parse retry re-sample inside the current stage. */
  public static void recordRetry() {
    Accumulator accumulator = CURRENT.get();
    if (accumulator != null) {
      accumulator.retries++;
    }
  }

  /** The aggregate of the current stage (empty usage when nothing recorded). */
  public static AgentObservabilityEvent.LmUsage drain() {
    Accumulator accumulator = CURRENT.get();
    return accumulator == null ? null : accumulator.toUsage();
  }

  /** Drops the accumulator — called in the stage's finally block. */
  public static void clear() {
    CURRENT.remove();
  }
}
