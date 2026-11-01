/**
 * @ClassName DbInstanceStore
 * @Description The database-backed {@link InstanceStore} (PostgreSQL in
 *              production, H2 in tests): one row per instantiated run in the
 *              {@code obs_run_instance} table, mirroring the {@code cp_*}
 *              schema style (idempotent portable DDL, {@code TIMESTAMP WITH
 *              TIME ZONE}). The row is inserted at the business trigger
 *              (request entry) and updated to its terminal state by the
 *              observability actor in write-ahead order, so with PostgreSQL's
 *              WAL the trigger-to-outcome correlation by unique request id and
 *              business id survives service restarts. Query facets translate
 *              to a portable dynamic WHERE clause (LOWER/LIKE text search on
 *              run id, business id and error).
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.ops.instance.infra;
import com.oli.orchestrator_demo.ops.instance.InstanceStore;
import com.oli.orchestrator_demo.ops.instance.RunInstance;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

public class DbInstanceStore implements InstanceStore {

  private static final Logger log = LoggerFactory.getLogger(DbInstanceStore.class);

  /** Portable (PostgreSQL + H2) idempotent schema. */
  private static final String[] SCHEMA = {
      """
      CREATE TABLE IF NOT EXISTS obs_run_instance (
        run_id VARCHAR(64) PRIMARY KEY,
        business_id VARCHAR(128),
        scenario_id VARCHAR(64),
        template_name VARCHAR(128),
        mode VARCHAR(16),
        trigger_type VARCHAR(32) NOT NULL,
        status VARCHAR(16) NOT NULL,
        request_json TEXT,
        started_at TIMESTAMP WITH TIME ZONE NOT NULL,
        completed_at TIMESTAMP WITH TIME ZONE,
        duration_ms BIGINT,
        error_message TEXT
      )""",
      "CREATE INDEX IF NOT EXISTS obs_run_instance_business_idx ON obs_run_instance (business_id)",
      "CREATE INDEX IF NOT EXISTS obs_run_instance_scenario_idx ON obs_run_instance (scenario_id)",
      "CREATE INDEX IF NOT EXISTS obs_run_instance_started_idx ON obs_run_instance (started_at)"
  };

  private final JdbcClient jdbc;

  public DbInstanceStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Creates the schema idempotently. */
  public void initialize() {
    for (String ddl : SCHEMA) {
      jdbc.sql(ddl).update();
    }
    log.info("Instance registry database: durable run instances enabled (obs_run_instance)");
  }

  @Override
  public void start(RunInstance instance) {
    jdbc.sql("""
        INSERT INTO obs_run_instance (run_id, business_id, scenario_id, template_name, mode,
            trigger_type, status, request_json, started_at, completed_at, duration_ms, error_message)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .param(instance.runId()).param(instance.businessId()).param(instance.scenarioId())
        .param(instance.templateName()).param(instance.mode()).param(instance.trigger())
        .param(instance.status()).param(instance.requestJson()).param(Timestamp.from(instance.startedAt()))
        .param(instance.completedAt() == null ? null : Timestamp.from(instance.completedAt()))
        .param(instance.durationMs()).param(instance.errorMessage())
        .update();
  }

  @Override
  public void complete(String runId, Instant completedAt, Long durationMs) {
    jdbc.sql("""
        UPDATE obs_run_instance SET status = ?, completed_at = ?, duration_ms = ?, error_message = NULL
        WHERE run_id = ?""")
        .param(RunInstance.COMPLETED).param(Timestamp.from(completedAt)).param(durationMs).param(runId)
        .update();
  }

  @Override
  public void fail(String runId, Instant completedAt, String errorMessage) {
    jdbc.sql("""
        UPDATE obs_run_instance SET status = ?, completed_at = ?, error_message = ? WHERE run_id = ?""")
        .param(RunInstance.FAILED).param(Timestamp.from(completedAt)).param(errorMessage).param(runId)
        .update();
  }

  @Override
  public Optional<RunInstance> find(String runId) {
    return jdbc.sql("""
        SELECT run_id, business_id, scenario_id, template_name, mode, trigger_type, status,
            request_json, started_at, completed_at, duration_ms, error_message
        FROM obs_run_instance WHERE run_id = ?""")
        .param(runId)
        .query((rs, n) -> mapRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getTimestamp(9),
            rs.getTimestamp(10), (Long) rs.getObject(11), rs.getString(12)))
        .optional();
  }

  @Override
  public List<RunInstance> query(InstanceQuery query) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    List<Object> params = new ArrayList<>();
    if (query.since() != null) {
      where.append(" AND started_at >= ?");
      params.add(Timestamp.from(query.since()));
    }
    if (query.businessId() != null) {
      where.append(" AND business_id = ?");
      params.add(query.businessId());
    }
    if (query.scenarioId() != null) {
      where.append(" AND scenario_id = ?");
      params.add(query.scenarioId());
    }
    if (query.status() != null) {
      where.append(" AND UPPER(status) = UPPER(?)");
      params.add(query.status());
    }
    if (query.text() != null && !query.text().isBlank()) {
      where.append(" AND (LOWER(run_id) LIKE ? OR LOWER(COALESCE(business_id, '')) LIKE ?"
          + " OR LOWER(COALESCE(scenario_id, '')) LIKE ? OR LOWER(COALESCE(error_message, '')) LIKE ?)");
      String needle = "%" + query.text().toLowerCase() + "%";
      params.add(needle);
      params.add(needle);
      params.add(needle);
      params.add(needle);
    }
    params.add(Math.max(1, query.limit()));
    return jdbc.sql("""
        SELECT run_id, business_id, scenario_id, template_name, mode, trigger_type, status,
            request_json, started_at, completed_at, duration_ms, error_message
        FROM obs_run_instance""" + where + " ORDER BY started_at DESC LIMIT ?")
        .params(params)
        .query((rs, n) -> mapRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getTimestamp(9),
            rs.getTimestamp(10), (Long) rs.getObject(11), rs.getString(12)))
        .list();
  }

  private static RunInstance mapRow(String runId, String businessId, String scenarioId,
      String templateName, String mode, String trigger, String status, String requestJson,
      Timestamp startedAt, Timestamp completedAt, Long durationMs, String errorMessage) {
    return new RunInstance(runId, businessId, scenarioId, templateName, mode, trigger, status,
        requestJson, startedAt.toInstant(),
        completedAt == null ? null : completedAt.toInstant(), durationMs, errorMessage);
  }

  @Override
  public String name() {
    return "database";
  }
}
