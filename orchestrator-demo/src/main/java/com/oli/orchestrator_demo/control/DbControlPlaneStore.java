/**
 * @ClassName DbControlPlaneStore
 * @Description The database-backed {@link ControlPlaneStore} (v2.0 persistence
 *              base): PostgreSQL in production, H2 in tests. The versioned
 *              {@link ControlPlaneState} is spread over normalized tables —
 *              {@code cp_meta} (single-row version stamp), {@code cp_agent},
 *              {@code cp_workflow}/{@code cp_workflow_stage},
 *              {@code cp_mcp_server}, {@code cp_agent_descriptor} — so later
 *              planes (scenario matching, metadata maintenance) can query the
 *              entities directly instead of parsing a JSON blob; nested
 *              lists/maps (slots, rules, env) stay as JSON text columns.
 *
 *              Saves run in one transaction (delete + reinsert of the tiny
 *              tables), which — with PostgreSQL's write-ahead logging — gives
 *              crash-safe, all-or-nothing control-plane updates. Secret
 *              values ({@code cp_mcp_server.env}) are field-encrypted with
 *              {@link ControlPlaneCrypto} before they touch the database and
 *              decrypted on load, so a raw SELECT never reveals a token.
 *
 *              The schema is created idempotently at startup
 *              ({@code CREATE TABLE IF NOT EXISTS}, portable between
 *              PostgreSQL and H2); a migration tool (Flyway) can take over
 *              once the schema starts evolving.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 2.0
 */
package com.oli.orchestrator_demo.control;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public class DbControlPlaneStore implements ControlPlaneStore {

  private static final Logger log = LoggerFactory.getLogger(DbControlPlaneStore.class);

  /** Portable (PostgreSQL + H2) idempotent schema. */
  private static final String[] SCHEMA = {
      """
      CREATE TABLE IF NOT EXISTS cp_meta (
        id INT PRIMARY KEY,
        version BIGINT NOT NULL,
        updated_at TIMESTAMP WITH TIME ZONE NOT NULL
      )""",
      """
      CREATE TABLE IF NOT EXISTS cp_agent (
        name VARCHAR(64) PRIMARY KEY,
        display_order INT NOT NULL,
        system_prompt TEXT NOT NULL,
        model VARCHAR(128),
        temperature DOUBLE PRECISION,
        calibration_rules TEXT NOT NULL,
        mcp_refs TEXT NOT NULL,
        updated_at TIMESTAMP WITH TIME ZONE NOT NULL
      )""",
      """
      CREATE TABLE IF NOT EXISTS cp_workflow (
        id INT PRIMARY KEY,
        mode VARCHAR(16) NOT NULL
      )""",
      """
      CREATE TABLE IF NOT EXISTS cp_workflow_stage (
        stage_order INT PRIMARY KEY,
        name VARCHAR(128) NOT NULL,
        agent VARCHAR(64) NOT NULL,
        depends_on TEXT NOT NULL
      )""",
      """
      CREATE TABLE IF NOT EXISTS cp_mcp_server (
        name VARCHAR(64) PRIMARY KEY,
        server_order INT NOT NULL,
        transport VARCHAR(32) NOT NULL,
        endpoint TEXT NOT NULL,
        command TEXT NOT NULL,
        args TEXT NOT NULL,
        env TEXT NOT NULL,
        enabled BOOLEAN NOT NULL,
        description TEXT NOT NULL
      )""",
      """
      CREATE TABLE IF NOT EXISTS cp_agent_descriptor (
        agent_name VARCHAR(64) PRIMARY KEY,
        title VARCHAR(255) NOT NULL,
        description TEXT NOT NULL,
        output_slots TEXT NOT NULL,
        input_slots TEXT NOT NULL,
        pending_safe_slots TEXT NOT NULL,
        default_model VARCHAR(128),
        default_temperature DOUBLE PRECISION
      )"""
  };

  private static final TypeReference<List<CalibrationRule>> RULES = new TypeReference<>() { };
  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
  private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

  private final JdbcClient jdbc;
  private final ObjectMapper objectMapper;
  private final ControlPlaneCrypto crypto;

  public DbControlPlaneStore(JdbcClient jdbc, ObjectMapper objectMapper, ControlPlaneCrypto crypto) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.crypto = crypto;
  }

  /** Creates the schema idempotently and reports the crypto posture loudly. */
  public void initialize() {
    for (String ddl : SCHEMA) {
      jdbc.sql(ddl).update();
    }
    if (!crypto.enabled()) {
      log.warn("Control plane database: ORCHESTRATOR_CRYPTO_KEY is not set - MCP secrets "
          + "are stored in plaintext. Set the key to enable AES-GCM field encryption.");
    }
  }

  /**
   * One-time JSON → database migration: when the database holds no state and
   * the seed store (the file backend) has one, the file state is imported
   * unchanged. No-op on every later boot.
   */
  public void importFrom(ControlPlaneStore seed) {
    if (currentVersion() >= 0) {
      return;
    }
    Optional<ControlPlaneState> fileState = seed.load();
    if (fileState.isPresent()) {
      save(fileState.get());
      log.info("Control plane: imported state (version {}) from {} into the database",
          fileState.get().version(), seed.name());
    }
  }

  @Override
  @Transactional
  public void save(ControlPlaneState state) {
    jdbc.sql("DELETE FROM cp_agent_descriptor").update();
    jdbc.sql("DELETE FROM cp_mcp_server").update();
    jdbc.sql("DELETE FROM cp_workflow_stage").update();
    jdbc.sql("DELETE FROM cp_workflow").update();
    jdbc.sql("DELETE FROM cp_agent").update();
    jdbc.sql("DELETE FROM cp_meta").update();

    jdbc.sql("""
        INSERT INTO cp_meta (id, version, updated_at) VALUES (1, ?, ?)""")
        .param(state.version()).param(Timestamp.from(state.updatedAt())).update();

    int order = 0;
    for (AgentRuntimeConfig agent : state.agents().values()) {
      jdbc.sql("""
          INSERT INTO cp_agent (name, display_order, system_prompt, model, temperature,
              calibration_rules, mcp_refs, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
          .param(agent.name()).param(order++).param(agent.systemPrompt()).param(agent.model())
          .param(agent.temperature()).param(json(agent.calibrationRules())).param(json(agent.mcpRefs()))
          .param(Timestamp.from(state.updatedAt())).update();
    }

    jdbc.sql("INSERT INTO cp_workflow (id, mode) VALUES (1, ?)").param(state.workflow().mode()).update();
    for (int i = 0; i < state.workflow().stages().size(); i++) {
      WorkflowControlState.StageControlState stage = state.workflow().stages().get(i);
      jdbc.sql("""
          INSERT INTO cp_workflow_stage (stage_order, name, agent, depends_on) VALUES (?, ?, ?, ?)""")
          .param(i).param(stage.name()).param(stage.agent()).param(json(stage.dependsOn())).update();
    }

    for (int i = 0; i < state.mcpServers().size(); i++) {
      McpServerConfig server = state.mcpServers().get(i);
      jdbc.sql("""
          INSERT INTO cp_mcp_server (name, server_order, transport, endpoint, command, args, env,
              enabled, description) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
          .param(server.name()).param(i).param(server.transport()).param(server.endpoint())
          .param(server.command()).param(json(server.args())).param(json(crypto.encryptEnv(server.env())))
          .param(server.enabled()).param(server.description()).update();
    }

    for (AgentDescriptor descriptor : state.descriptors().values()) {
      jdbc.sql("""
          INSERT INTO cp_agent_descriptor (agent_name, title, description, output_slots, input_slots,
              pending_safe_slots, default_model, default_temperature) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
          .param(descriptor.name()).param(descriptor.title()).param(descriptor.description())
          .param(json(descriptor.outputSlots())).param(json(descriptor.inputSlots()))
          .param(json(descriptor.pendingSafeSlots())).param(descriptor.defaultModel())
          .param(descriptor.defaultTemperature()).update();
    }
  }

  @Override
  public Optional<ControlPlaneState> load() {
    return jdbc.sql("SELECT version, updated_at FROM cp_meta WHERE id = 1")
        .query((rs, n) -> {
          List<AgentRuntimeConfig> agentRows = jdbc.sql("""
              SELECT name, display_order, system_prompt, model, temperature, calibration_rules, mcp_refs
              FROM cp_agent ORDER BY display_order""")
              .query((ars, an) -> new AgentRuntimeConfig(ars.getString("name"), ars.getString("system_prompt"),
                  ars.getString("model"), ars.getObject("temperature") == null ? null : ars.getDouble("temperature"),
                  objectMapper.readValue(ars.getString("calibration_rules"), RULES),
                  objectMapper.readValue(ars.getString("mcp_refs"), STRINGS)))
              .list();
          LinkedHashMap<String, AgentRuntimeConfig> agents = new LinkedHashMap<>();
          agentRows.forEach(agent -> agents.put(agent.name(), agent));

          WorkflowControlState workflow = jdbc.sql(
              "SELECT mode FROM cp_workflow WHERE id = 1")
              .query((wrs, wn) -> new WorkflowControlState(wrs.getString("mode"),
                  jdbc.sql("SELECT name, agent, depends_on FROM cp_workflow_stage ORDER BY stage_order")
                      .query((srs, sn) -> new WorkflowControlState.StageControlState(srs.getString("name"),
                          srs.getString("agent"), objectMapper.readValue(srs.getString("depends_on"), STRINGS)))
                      .list()))
              .optional()
              .orElse(new WorkflowControlState("SEQUENTIAL", List.of()));

          List<McpServerConfig> servers = jdbc.sql("""
              SELECT name, transport, endpoint, command, args, env, enabled, description
              FROM cp_mcp_server ORDER BY server_order""")
              .query((srs, sn) -> new McpServerConfig(srs.getString("name"), srs.getString("transport"),
                  srs.getString("endpoint"), srs.getString("command"),
                  objectMapper.readValue(srs.getString("args"), STRINGS),
                  crypto.decryptEnv(objectMapper.readValue(srs.getString("env"), STRING_MAP)),
                  srs.getBoolean("enabled"), srs.getString("description")))
              .list();

          Map<String, AgentDescriptor> descriptors = new LinkedHashMap<>();
          jdbc.sql("""
              SELECT agent_name, title, description, output_slots, input_slots, pending_safe_slots,
                  default_model, default_temperature FROM cp_agent_descriptor""")
              .query((drs, dn) -> {
                AgentDescriptor descriptor = new AgentDescriptor(drs.getString("agent_name"),
                    drs.getString("title"), drs.getString("description"),
                    objectMapper.readValue(drs.getString("output_slots"), STRINGS),
                    objectMapper.readValue(drs.getString("input_slots"), STRINGS),
                    objectMapper.readValue(drs.getString("pending_safe_slots"), STRINGS),
                    drs.getString("default_model"),
                    drs.getObject("default_temperature") == null ? null : drs.getDouble("default_temperature"));
                descriptors.put(descriptor.name(), descriptor);
                return descriptor;
              })
              .list();

          return new ControlPlaneState(rs.getLong("version"), rs.getTimestamp("updated_at").toInstant(),
              agents, workflow, servers, descriptors);
        })
        .optional();
  }

  @Override
  public long currentVersion() {
    return jdbc.sql("SELECT version FROM cp_meta WHERE id = 1")
        .query(Long.class)
        .optional()
        .orElse(-1L);
  }

  @Override
  public String name() {
    return "database";
  }

  private String json(Object value) {
    return value == null ? "[]" : objectMapper.writeValueAsString(value);
  }
}
