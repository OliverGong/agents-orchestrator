/**
 * @ClassName RunStatusReader
 * @Description Read-only port over the orchestration runtime's live status.
 *              Health and info probes consume this port instead of importing
 *              the trip module, inverting the former probe-to-orchestrator
 *              dependency; the orchestrator provides the implementation.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.kernel.contract;

public interface RunStatusReader {

  /** Point-in-time snapshot of the live template and agent roster. */
  OrchestratorSnapshot snapshot();
}
