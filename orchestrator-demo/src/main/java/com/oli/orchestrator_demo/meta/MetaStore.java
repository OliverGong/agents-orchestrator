/**
 * @ClassName MetaStore
 * @Description Persistence seam of the metadata maintenance plane, mirroring
 *              {@link com.oli.orchestrator_demo.control.ControlPlaneStore} and
 *              the scenario store: the {@link MetaRegistry} stays the single
 *              writer while the implementations decide where the versioned
 *              {@link MetaState} lives — the JSON {@link FileMetaStore}
 *              (default, also the seed backend) or {@link DbMetaStore}
 *              (PostgreSQL in production, H2 in tests).
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.meta;
import com.oli.orchestrator_demo.control.ControlPlaneStore;
import com.oli.orchestrator_demo.meta.infra.DbMetaStore;
import com.oli.orchestrator_demo.meta.infra.FileMetaStore;

import java.util.Optional;

public interface MetaStore {

  /** The persisted state, or empty when nothing was persisted yet (seed path). */
  Optional<MetaState> load();

  /** Atomically persists the given state (the version travels inside the state). */
  void save(MetaState state);

  /** Human-readable backend name for logs. */
  String name();
}
