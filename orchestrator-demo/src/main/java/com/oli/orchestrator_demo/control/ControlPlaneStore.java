/**
 * @ClassName ControlPlaneStore
 * @Description Persistence seam of the control plane. The registry
 *              ({@link AgentConfigRegistry}) stays the single writer — it
 *              validates, hot-applies and bumps the version — while the store
 *              implementations decide <em>where</em> the versioned
 *              {@link ControlPlaneState} lives: the file-backed
 *              {@link FileControlPlaneStore} (default, also the seed/fallback
 *              backend) or {@code DbControlPlaneStore} (PostgreSQL in
 *              production, selected with
 *              {@code orchestrator.control.store=db}).
 *
 *              Implementations must persist atomically (a crashed save never
 *              leaves a torn state behind) and return immutable snapshots
 *              from {@link #load()}; they never mutate a loaded state.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 2.0
 */
package com.oli.orchestrator_demo.control;

import java.util.Optional;

public interface ControlPlaneStore {

  /** The persisted state, or empty when nothing was persisted yet (seed path). */
  Optional<ControlPlaneState> load();

  /** Atomically persists the given state (the version travels inside the state). */
  void save(ControlPlaneState state);

  /**
   * Cheap staleness probe for cache layers: the persisted version, or
   * {@code -1} when nothing is persisted. Must be significantly cheaper than
   * a full {@link #load()}.
   */
  long currentVersion();

  /** Human-readable backend name for logs and health output. */
  String name();
}
