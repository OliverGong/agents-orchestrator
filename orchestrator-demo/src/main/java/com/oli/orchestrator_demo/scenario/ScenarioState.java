/**
 * @ClassName ScenarioState
 * @Description The versioned snapshot of all persisted scenarios — the
 *              scenario-plane counterpart of the control-plane
 *              {@link com.oli.orchestrator_demo.control.ControlPlaneState}.
 *              The registry keeps one immutable instance as the read-side
 *              truth and bumps the version on every write, so clients can
 *              detect concurrent edits and the store can persist atomically.
 * @Author OliverGong
 * @Date Oct 31, 2026
 * @Version 2.1
 */
package com.oli.orchestrator_demo.scenario;

import java.time.Instant;
import java.util.List;

public record ScenarioState(long version, Instant updatedAt, List<Scenario> scenarios) {

  public ScenarioState {
    scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
  }
}
