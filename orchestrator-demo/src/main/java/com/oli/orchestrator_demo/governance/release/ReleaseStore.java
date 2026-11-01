/**
 * @ClassName ReleaseStore
 * @Description Persistence seam of the release registry, mirroring the
 *              control/meta/teamlog store split: the JSON
 *              {@link FileReleaseStore} (default and seed backend) or the
 *              {@link DbReleaseStore} ({@code rl_release} table). The
 *              {@link ReleaseService} is the single writer; records are
 *              append-only (every publish keeps its package re-exportable).
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.governance.release;
import com.oli.orchestrator_demo.governance.release.infra.DbReleaseStore;
import com.oli.orchestrator_demo.governance.release.infra.FileReleaseStore;

import java.util.List;
import java.util.Optional;

public interface ReleaseStore {

  /** The persisted file state (version stamp acts as the id counter). */
  record State(long version, List<ReleaseRecord> records) {
    public State {
      records = records == null ? List.of() : List.copyOf(records);
    }
  }

  /** Appends one record; implementations assign the id. */
  ReleaseRecord append(ReleaseRecord record);

  /** Newest-first records, optionally filtered to one scenario. */
  List<ReleaseRecord> list(String scenarioId);

  /** One record by id. */
  Optional<ReleaseRecord> get(long id);

  /** Human-readable backend name for logs. */
  String name();
}
