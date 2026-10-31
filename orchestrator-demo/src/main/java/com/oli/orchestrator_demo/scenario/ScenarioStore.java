/**
 * @ClassName ScenarioStore
 * @Description Persistence seam of the scenario plane, mirroring
 *              {@link com.oli.orchestrator_demo.control.ControlPlaneStore}:
 *              the {@link ScenarioRegistry} stays the single writer while the
 *              implementations decide where the versioned {@link ScenarioState}
 *              lives — the JSON {@link FileScenarioStore} (default, also the
 *              seed/fallback backend) or {@link DbScenarioStore} (PostgreSQL
 *              in production, H2 in tests). Implementations persist atomically
 *              and return immutable snapshots; they never mutate loaded state.
 * @Author OliverGong
 * @Date Oct 31, 2026
 * @Version 2.1
 */
package com.oli.orchestrator_demo.scenario;

import java.util.Optional;

public interface ScenarioStore {

  /** The persisted state, or empty when nothing was persisted yet (seed path). */
  Optional<ScenarioState> load();

  /** Atomically persists the given state (the version travels inside the state). */
  void save(ScenarioState state);

  /** Human-readable backend name for logs. */
  String name();
}
