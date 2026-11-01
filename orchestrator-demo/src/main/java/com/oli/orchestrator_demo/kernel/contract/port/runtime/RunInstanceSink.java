/**
 * @ClassName RunInstanceSink
 * @Description Write-side port for run instances. The observability actor
 *              reports lifecycle transitions (start / complete / fail) through
 *              this port instead of importing the instance module's store —
 *              inverting the former bidirectional coupling. The instance
 *              module bridges the port onto its own store.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.kernel.contract.port.runtime;

import com.oli.orchestrator_demo.kernel.contract.model.run.RunInstance;

import java.time.Instant;

public interface RunInstanceSink {

  /** Records a newly started run in the RUNNING state. */
  void start(RunInstance instance);

  /** Marks the run completed with its total duration. */
  void complete(String runId, Instant completedAt, Long durationMs);

  /** Marks the run failed with the error message. */
  void fail(String runId, Instant completedAt, String errorMessage);
}
