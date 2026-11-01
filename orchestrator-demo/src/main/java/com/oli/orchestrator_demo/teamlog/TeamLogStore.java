/**
 * @ClassName TeamLogStore
 * @Description Persistence seam of the runtime-team log, mirroring the
 *              control/meta store split: {@link FileTeamLogStore} (JSONL,
 *              default/seed backend) or {@link DbTeamLogStore}
 *              ({@code team_log} table, same database as the control plane).
 *              Appends are fire-and-forget from the caller's perspective —
 *              implementations must never throw into the hot path's callers
 *              (the recorder degrades store failures to warnings).
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.teamlog;
import com.oli.orchestrator_demo.teamlog.infra.DbTeamLogStore;
import com.oli.orchestrator_demo.teamlog.infra.FileTeamLogStore;

import java.time.Instant;
import java.util.List;

public interface TeamLogStore {

  /** Search facets; null means "do not filter". */
  record TeamLogQuery(
    Instant since,
    String category,
    String level,
    String runId,
    String scenarioId,
    String text,
    int limit
  ) {
  }

  /** Appends one entry (implementations assign the id). */
  void append(TeamLogEntry entry);

  /** Newest-first entries matching the query facets, at most {@code limit} rows. */
  List<TeamLogEntry> search(TeamLogQuery query);

  /** Human-readable backend name for logs. */
  String name();
}
