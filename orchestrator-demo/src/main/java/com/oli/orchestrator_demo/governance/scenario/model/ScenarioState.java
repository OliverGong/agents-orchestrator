/**
 * @ClassName ScenarioState
 * @Description The versioned snapshot of all persisted scenarios — the
 *              scenario-plane counterpart of the control-plane
 *              {@link com.oli.orchestrator_demo.governance.control.state.ControlPlaneState}.
 *              The registry keeps one immutable instance as the read-side
 *              truth and bumps the version on every write, so clients can
 *              detect concurrent edits and the store can persist atomically.
 *              Since v2.2 the snapshot also records which scenario is
 *              currently activated onto the control plane
 *              ({@code activeScenarioId}, null = the plane default), so the
 *              console can show the live context bar and close the
 *              lifecycle chain (dry-run → activate → run → replay → metrics)
 *              across page reloads and clients.
 * @Author OliverGong
 * @Date Oct 31, 2026
 * @Version 2.2
 */
package com.oli.orchestrator_demo.governance.scenario.model;
import com.oli.orchestrator_demo.governance.control.state.ControlPlaneState;

import java.time.Instant;
import java.util.List;

public record ScenarioState(long version, Instant updatedAt, List<Scenario> scenarios,
    String activeScenarioId) {

  public ScenarioState {
    scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
    activeScenarioId = activeScenarioId == null || activeScenarioId.isBlank() ? null : activeScenarioId;
  }
}
