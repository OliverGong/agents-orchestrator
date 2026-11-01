/**
 * @ClassName InstanceStore
 * @Description Persistence seam of the run-instance registry, mirroring the
 *              control/meta/teamlog store split: {@link FileInstanceStore}
 *              (JSON, default/seed backend) or {@link DbInstanceStore}
 *              ({@code obs_run_instance} table, same database as the control
 *              plane). Writes come from the observability actor in
 *              write-ahead order (before the timeline fold and SSE push) and
 *              must never throw into it — implementations degrade failures to
 *              warnings at the call site. The read side powers the
 *              {@code /api/v1/instances} correlation API: runs are queried by
 *              business id, scenario, status and free text (run id or business
 *              id), so the request's unique id and the business id together
 *              reconstruct the full context of an instantiation.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.ops.instance;

import com.oli.orchestrator_demo.kernel.contract.RunInstance;
import com.oli.orchestrator_demo.ops.instance.infra.DbInstanceStore;
import com.oli.orchestrator_demo.ops.instance.infra.FileInstanceStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InstanceStore {

  /** Search facets; null means "do not filter". */
  record InstanceQuery(
    Instant since,
    String businessId,
    String scenarioId,
    String status,
    String text,
    int limit
  ) {
  }

  /** Inserts the RUNNING row at the business trigger (request entry). */
  void start(RunInstance instance);

  /** Marks the instance completed (terminal). */
  void complete(String runId, Instant completedAt, Long durationMs);

  /** Marks the instance failed (terminal, with the error reason). */
  void fail(String runId, Instant completedAt, String errorMessage);

  /** One instance by its unique request id. */
  Optional<RunInstance> find(String runId);

  /** Newest-first instances matching the facets, at most {@code limit} rows. */
  List<RunInstance> query(InstanceQuery query);

  /** Human-readable backend name for logs. */
  String name();

  /**
   * Shared in-memory facet matching, the single definition of the query
   * semantics: the file backend filters with it directly, the database backend
   * translates it to SQL and test fakes reuse it verbatim.
   */
  static boolean matches(InstanceQuery query, RunInstance i) {
    if (query.since() != null && i.startedAt().isBefore(query.since())) {
      return false;
    }
    if (query.businessId() != null && !query.businessId().equals(i.businessId())) {
      return false;
    }
    if (query.scenarioId() != null && !query.scenarioId().equals(i.scenarioId())) {
      return false;
    }
    if (query.status() != null && !query.status().equalsIgnoreCase(i.status())) {
      return false;
    }
    if (query.text() != null && !query.text().isBlank()) {
      String needle = query.text().toLowerCase();
      boolean hit = contains(i.runId(), needle) || contains(i.businessId(), needle)
          || contains(i.scenarioId(), needle) || contains(i.errorMessage(), needle);
      if (!hit) {
        return false;
      }
    }
    return true;
  }

  private static boolean contains(String value, String needle) {
    return value != null && value.toLowerCase().contains(needle);
  }
}
