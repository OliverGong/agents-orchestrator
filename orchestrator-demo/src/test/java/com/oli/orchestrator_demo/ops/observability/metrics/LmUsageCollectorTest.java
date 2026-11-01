/**
 * @ClassName LmUsageCollectorTest
 * @Description Verifies the thread-local stage usage accumulator: usage from
 *              multiple model calls accumulates (including retry re-samples),
 *              the drain reports the aggregate without resetting it, clear
 *              drops it, and records outside a stage (no accumulator) are
 *              silently ignored — so non-workflow model calls stay unaffected.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.ops.observability.metrics;
import com.oli.orchestrator_demo.ops.observability.events.AgentObservabilityEvent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LmUsageCollectorTest {

  @AfterEach
  void clearThreadLocal() {
    LmUsageCollector.clear();
  }

  @Test
  void accumulatesUsageAndRetriesAcrossModelCalls() {
    LmUsageCollector.begin();
    LmUsageCollector.record(100, 30, 130, "qwen3:8b");
    LmUsageCollector.recordRetry();
    LmUsageCollector.record(100, 30, 130, "qwen3:8b");

    AgentObservabilityEvent.LmUsage usage = LmUsageCollector.drain();

    assertThat(usage.promptTokens()).isEqualTo(200);
    assertThat(usage.completionTokens()).isEqualTo(60);
    assertThat(usage.totalTokens()).isEqualTo(260);
    assertThat(usage.model()).isEqualTo("qwen3:8b");
    assertThat(usage.retries()).isEqualTo(1);

    // Drain is non-destructive: the orchestrator may read it twice.
    assertThat(LmUsageCollector.drain().totalTokens()).isEqualTo(260);

    LmUsageCollector.clear();
    assertThat(LmUsageCollector.drain()).isNull();
  }

  @Test
  void keepsTheLastNonBlankModelName() {
    LmUsageCollector.begin();
    LmUsageCollector.record(1, 1, 2, "");
    assertThat(LmUsageCollector.drain().model()).isNull();

    LmUsageCollector.record(1, 1, 2, "llama3.2");
    LmUsageCollector.record(1, 1, 2, null);
    assertThat(LmUsageCollector.drain().model()).isEqualTo("llama3.2");
  }

  @Test
  void ignoresRecordsOutsideAStage() {
    LmUsageCollector.record(100, 30, 130, "qwen3:8b");
    LmUsageCollector.recordRetry();
    assertThat(LmUsageCollector.drain()).isNull();
  }

  @Test
  void isolatesConcurrentThreads() throws Exception {
    LmUsageCollector.begin();
    LmUsageCollector.record(10, 5, 15, "main-model");
    AtomicReference<AgentObservabilityEvent.LmUsage> otherThread = new AtomicReference<>();
    Thread worker = Thread.ofVirtual().start(() -> {
      LmUsageCollector.begin();
      LmUsageCollector.record(500, 100, 600, "worker-model");
      otherThread.set(LmUsageCollector.drain());
    });
    worker.join();

    assertThat(otherThread.get().totalTokens()).isEqualTo(600);
    assertThat(otherThread.get().model()).isEqualTo("worker-model");
    // The main thread's accumulator is untouched.
    assertThat(LmUsageCollector.drain().totalTokens()).isEqualTo(15);
  }
}
