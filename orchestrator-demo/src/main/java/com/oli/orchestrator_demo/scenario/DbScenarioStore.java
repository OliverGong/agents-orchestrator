/**
 * @ClassName DbScenarioStore
 * @Description The database-backed {@link ScenarioStore} (PostgreSQL in
 *              production, H2 in tests). Every scenario is one row of
 *              {@code cp_scenario}; nested structures (request template,
 *              workflow override, agent overrides, MCP refs) stay as JSON text
 *              columns, mirroring the {@code cp_*} control-plane tables so a
 *              later matching plane can query scenarios directly. Saves run in
 *              one transaction (delete + reinsert of the tiny table plus the
 *              {@code cp_scenario_meta} version row), which — with
 *              PostgreSQL's write-ahead logging — gives crash-safe,
 *              all-or-nothing scenario updates. No secrets live in scenarios,
 *              so no field crypto is needed here. The schema is created
 *              idempotently (portable between PostgreSQL and H2).
 * @Author OliverGong
 * @Date Oct 31, 2026
 * @Version 2.1
 */
package com.oli.orchestrator_demo.scenario;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.oli.orchestrator_demo.control.WorkflowControlState;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public class DbScenarioStore implements ScenarioStore {

  private static final Logger log = LoggerFactory.getLogger(DbScenarioStore.class);

  /** Portable (PostgreSQL + H2) idempotent schema. */
  private static final String[] SCHEMA = {
      """
      CREATE TABLE IF NOT EXISTS cp_scenario_meta (
        id INT PRIMARY KEY,
        version BIGINT NOT NULL,
        updated_at TIMESTAMP WITH TIME ZONE NOT NULL
      )""",
      """
      CREATE TABLE IF NOT EXISTS cp_scenario (
        id VARCHAR(64) PRIMARY KEY,
        display_order INT NOT NULL,
        name VARCHAR(128) NOT NULL,
        description TEXT NOT NULL,
        request_template TEXT NOT NULL,
        workflow_override TEXT,
        agent_overrides TEXT NOT NULL,
        mcp_refs TEXT NOT NULL,
        artifact_type VARCHAR(32) NOT NULL,
        active BOOLEAN NOT NULL,
        updated_at TIMESTAMP WITH TIME ZONE NOT NULL
      )"""
  };

  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

  private final JdbcClient jdbc;
  private final ObjectMapper objectMapper;

  public DbScenarioStore(JdbcClient jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /** Creates the schema idempotently. */
  public void initialize() {
    for (String ddl : SCHEMA) {
      jdbc.sql(ddl).update();
    }
  }

  /**
   * One-time JSON → database migration: when the database holds no scenarios
   * and the seed store (the file backend) has some, they are imported
   * unchanged. No-op on every later boot.
   */
  public void importFrom(ScenarioStore seed) {
    if (currentVersion() >= 0) {
      return;
    }
    Optional<ScenarioState> fileState = seed.load();
    if (fileState.isPresent()) {
      save(fileState.get());
      log.info("Scenario plane: imported {} scenario(s) (version {}) from {} into the database",
          fileState.get().scenarios().size(), fileState.get().version(), seed.name());
    }
  }

  private long currentVersion() {
    return jdbc.sql("SELECT version FROM cp_scenario_meta WHERE id = 1")
        .query(Long.class)
        .optional()
        .orElse(-1L);
  }

  @Override
  @Transactional
  public void save(ScenarioState state) {
    jdbc.sql("DELETE FROM cp_scenario").update();
    jdbc.sql("DELETE FROM cp_scenario_meta").update();
    jdbc.sql("INSERT INTO cp_scenario_meta (id, version, updated_at) VALUES (1, ?, ?)")
        .param(state.version()).param(Timestamp.from(state.updatedAt())).update();
    int order = 0;
    for (Scenario scenario : state.scenarios()) {
      jdbc.sql("""
          INSERT INTO cp_scenario (id, display_order, name, description, request_template,
              workflow_override, agent_overrides, mcp_refs, artifact_type, active, updated_at)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
          .param(scenario.id()).param(order++).param(scenario.name()).param(scenario.description())
          .param(objectMapper.writeValueAsString(scenario.requestTemplate()))
          // Jackson writes the literal "null" for a null override, so the load
          // side can always readValue the column (never SQL NULL).
          .param(objectMapper.writeValueAsString(scenario.workflowOverride()))
          .param(objectMapper.writeValueAsString(scenario.agentOverrides()))
          .param(objectMapper.writeValueAsString(scenario.mcpServerRefs()))
          .param(scenario.artifactType().name()).param(scenario.active())
          .param(Timestamp.from(state.updatedAt())).update();
    }
  }

  @Override
  public Optional<ScenarioState> load() {
    return jdbc.sql("SELECT version, updated_at FROM cp_scenario_meta WHERE id = 1")
        .query((rs, n) -> {
          List<Scenario> scenarios = jdbc.sql("""
              SELECT id, name, description, request_template, workflow_override, agent_overrides,
                  mcp_refs, artifact_type, active, updated_at FROM cp_scenario ORDER BY display_order""")
              .query((srs, sn) -> new Scenario(
                  srs.getString("id"),
                  srs.getString("name"),
                  srs.getString("description"),
                  objectMapper.readValue(srs.getString("request_template"), Scenario.RequestTemplate.class),
                  objectMapper.readValue(srs.getString("workflow_override"), WorkflowControlState.class),
                  objectMapper.readValue(srs.getString("agent_overrides"),
                      new TypeReference<java.util.LinkedHashMap<String, Scenario.AgentOverride>>() { }),
                  objectMapper.readValue(srs.getString("mcp_refs"), STRINGS),
                  Scenario.ArtifactType.valueOf(srs.getString("artifact_type")),
                  srs.getBoolean("active"),
                  srs.getTimestamp("updated_at").toInstant()))
              .list();
          return new ScenarioState(rs.getLong("version"), rs.getTimestamp("updated_at").toInstant(), scenarios);
        })
        .optional();
  }

  @Override
  public String name() {
    return "database";
  }
}
