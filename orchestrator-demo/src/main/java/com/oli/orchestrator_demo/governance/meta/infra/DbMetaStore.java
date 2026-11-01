/**
 * @ClassName DbMetaStore
 * @Description The database-backed {@link MetaStore} (PostgreSQL in
 *              production, H2 in tests): the versioned {@link MetaState}
 *              spread over {@code mp_version} (single-row stamp),
 *              {@code mp_runtime} (one row of global runtime knobs),
 *              {@code mp_portal} and {@code mp_release} — mirroring the
 *              {@code cp_*} control-plane table style (idempotent portable
 *              DDL, transactional delete+reinsert saves) so the release
 *              pipeline (M4) can query portals and per-scenario release
 *              configs directly.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.governance.meta.infra;
import com.oli.orchestrator_demo.governance.meta.MetaState;
import com.oli.orchestrator_demo.governance.meta.MetaStore;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

public class DbMetaStore implements MetaStore {

  private static final Logger log = LoggerFactory.getLogger(DbMetaStore.class);

  /** Portable (PostgreSQL + H2) idempotent schema. */
  private static final String[] SCHEMA = {
      """
      CREATE TABLE IF NOT EXISTS mp_version (
        id INT PRIMARY KEY,
        version BIGINT NOT NULL,
        updated_at TIMESTAMP WITH TIME ZONE NOT NULL
      )""",
      """
      CREATE TABLE IF NOT EXISTS mp_runtime (
        id INT PRIMARY KEY,
        model_backend VARCHAR(128),
        request_timeout_seconds INT,
        max_wave_concurrency INT,
        temperature DOUBLE PRECISION,
        top_p DOUBLE PRECISION
      )""",
      """
      CREATE TABLE IF NOT EXISTS mp_portal (
        id VARCHAR(64) PRIMARY KEY,
        name VARCHAR(128) NOT NULL,
        kind VARCHAR(32) NOT NULL,
        endpoint TEXT NOT NULL,
        enabled BOOLEAN NOT NULL
      )""",
      """
      CREATE TABLE IF NOT EXISTS mp_release (
        scenario_id VARCHAR(64) PRIMARY KEY,
        portal_id VARCHAR(64),
        version VARCHAR(64),
        changelog TEXT,
        artifact_type VARCHAR(32)
      )"""
  };

  private final JdbcClient jdbc;

  public DbMetaStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Creates the schema idempotently. */
  public void initialize() {
    for (String ddl : SCHEMA) {
      jdbc.sql(ddl).update();
    }
    log.info("Meta plane: database backend enabled (mp_* tables)");
  }

  /**
   * One-time JSON → database migration: when the database holds no state and
   * the seed store (the file backend) has one, it is imported unchanged.
   */
  public void importFrom(MetaStore seed) {
    if (jdbc.sql("SELECT COUNT(*) FROM mp_version").query(Long.class).single() > 0) {
      return;
    }
    Optional<MetaState> fileState = seed.load();
    if (fileState.isPresent()) {
      save(fileState.get());
      log.info("Meta plane: imported state (version {}) from {} into the database",
          fileState.get().version(), seed.name());
    }
  }

  @Override
  @Transactional
  public void save(MetaState state) {
    jdbc.sql("DELETE FROM mp_release").update();
    jdbc.sql("DELETE FROM mp_portal").update();
    jdbc.sql("DELETE FROM mp_runtime").update();
    jdbc.sql("DELETE FROM mp_version").update();

    jdbc.sql("INSERT INTO mp_version (id, version, updated_at) VALUES (1, ?, ?)")
        .param(state.version()).param(Timestamp.from(state.updatedAt())).update();

    MetaState.RuntimeConfig runtime = state.runtime();
    jdbc.sql("""
        INSERT INTO mp_runtime (id, model_backend, request_timeout_seconds, max_wave_concurrency,
            temperature, top_p) VALUES (1, ?, ?, ?, ?, ?)""")
        .param(runtime.modelBackend()).param(runtime.requestTimeoutSeconds())
        .param(runtime.maxWaveConcurrency()).param(runtime.temperature()).param(runtime.topP())
        .update();

    for (MetaState.PortalConfig portal : state.portals()) {
      jdbc.sql("INSERT INTO mp_portal (id, name, kind, endpoint, enabled) VALUES (?, ?, ?, ?, ?)")
          .param(portal.id()).param(portal.name()).param(portal.kind()).param(portal.endpoint())
          .param(portal.enabled()).update();
    }
    for (MetaState.ReleaseConfig release : state.releases()) {
      jdbc.sql("INSERT INTO mp_release (scenario_id, portal_id, version, changelog, artifact_type) "
          + "VALUES (?, ?, ?, ?, ?)")
          .param(release.scenarioId()).param(release.portalId()).param(release.version())
          .param(release.changelog()).param(release.artifactType()).update();
    }
  }

  @Override
  public Optional<MetaState> load() {
    return jdbc.sql("SELECT version, updated_at FROM mp_version WHERE id = 1")
        .query((rs, n) -> {
          MetaState.RuntimeConfig runtime = jdbc.sql("""
              SELECT model_backend, request_timeout_seconds, max_wave_concurrency, temperature, top_p
              FROM mp_runtime WHERE id = 1""")
              .query((rr, rn) -> new MetaState.RuntimeConfig(rr.getString("model_backend"),
                  rr.getObject("request_timeout_seconds") == null ? null : rr.getInt("request_timeout_seconds"),
                  rr.getObject("max_wave_concurrency") == null ? null : rr.getInt("max_wave_concurrency"),
                  rr.getObject("temperature") == null ? null : rr.getDouble("temperature"),
                  rr.getObject("top_p") == null ? null : rr.getDouble("top_p")))
              .single();
          List<MetaState.PortalConfig> portals = jdbc.sql(
              "SELECT id, name, kind, endpoint, enabled FROM mp_portal ORDER BY id")
              .query((pr, pn) -> new MetaState.PortalConfig(pr.getString("id"), pr.getString("name"),
                  pr.getString("kind"), pr.getString("endpoint"), pr.getBoolean("enabled")))
              .list();
          List<MetaState.ReleaseConfig> releases = jdbc.sql(
              "SELECT scenario_id, portal_id, version, changelog, artifact_type FROM mp_release ORDER BY scenario_id")
              .query((qr, qn) -> new MetaState.ReleaseConfig(qr.getString("scenario_id"),
                  qr.getString("portal_id"), qr.getString("version"), qr.getString("changelog"),
                  qr.getString("artifact_type")))
              .list();
          return new MetaState(rs.getLong("version"), rs.getTimestamp("updated_at").toInstant(),
              runtime, portals, releases);
        })
        .optional();
  }

  @Override
  public String name() {
    return "database";
  }
}
