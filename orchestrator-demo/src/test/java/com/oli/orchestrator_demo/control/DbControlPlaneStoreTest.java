/**
 * @ClassName DbControlPlaneStoreTest
 * @Description Exercises the database-backed control-plane store against an
 *              in-memory H2 (the same portable SQL runs on PostgreSQL):
 *              schema bootstrap, full save/load round-trip of every entity
 *              (agents with calibration rules and MCP refs, workflow stages,
 *              MCP servers with encrypted env values, descriptors), the
 *              cheap version probe, the one-time JSON → DB import and the
 *              plaintext fallback when no crypto key is configured.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 2.0
 */
package com.oli.orchestrator_demo.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.json.JsonMapper;

class DbControlPlaneStoreTest {

  @TempDir
  Path tempDir;

  private JdbcClient jdbc;
  private DbControlPlaneStore store;

  @BeforeEach
  void setUp() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    jdbc = JdbcClient.create(dataSource);
    store = new DbControlPlaneStore(jdbc, JsonMapper.builder().build(), new ControlPlaneCrypto("test-key"));
    store.initialize();
  }

  private ControlPlaneState fullState() {
    Map<String, AgentRuntimeConfig> agents = new java.util.LinkedHashMap<>();
    agents.put("research", new AgentRuntimeConfig("research", "research persona", "qwen:7b", 0.7,
        List.of(new CalibrationRule("\\s+", " ", "collapse spaces")), List.of("weather")));
    agents.put("review", new AgentRuntimeConfig("review", "review persona", null, null, List.of(), List.of()));
    WorkflowControlState workflow = new WorkflowControlState("CONCURRENT", List.of(
        new WorkflowControlState.StageControlState("research", "research", List.of()),
        new WorkflowControlState.StageControlState("review", "review", List.of("research"))));
    List<McpServerConfig> servers = List.of(new McpServerConfig("weather", "STDIO", "",
        "uvx", List.of("mcp-weather"), Map.of("API_KEY", "t0p-secret", "LOG", "debug"),
        true, "weather tools"));
    Map<String, AgentDescriptor> descriptors = Map.of("research",
        new AgentDescriptor("research", "Researcher", "finds things",
            List.of("research.points"), List.of("request"), List.of("budget"), "qwen:7b", 0.7));
    return new ControlPlaneState(7, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
        agents, workflow, servers, descriptors);
  }

  @Test
  void emptyDatabaseLoadsEmptyAndReportsNoVersion() {
    assertThat(store.load()).isEmpty();
    assertThat(store.currentVersion()).isEqualTo(-1);
  }

  @Test
  void roundTripsEveryEntityAndKeepsInsertionOrder() {
    ControlPlaneState state = fullState();
    store.save(state);

    ControlPlaneState loaded = store.load().orElseThrow();
    assertThat(loaded.version()).isEqualTo(7);
    assertThat(loaded.agents().keySet()).containsExactly("research", "review");
    assertThat(loaded.agents().get("research").systemPrompt()).isEqualTo("research persona");
    assertThat(loaded.agents().get("research").model()).isEqualTo("qwen:7b");
    assertThat(loaded.agents().get("research").temperature()).isEqualTo(0.7);
    assertThat(loaded.agents().get("research").calibrationRules())
        .containsExactly(new CalibrationRule("\\s+", " ", "collapse spaces"));
    assertThat(loaded.agents().get("research").mcpRefs()).containsExactly("weather");
    assertThat(loaded.workflow().mode()).isEqualTo("CONCURRENT");
    assertThat(loaded.workflow().stages()).extracting(WorkflowControlState.StageControlState::name)
        .containsExactly("research", "review");
    assertThat(loaded.workflow().stages().get(1).dependsOn()).containsExactly("research");
    assertThat(loaded.mcpServers()).hasSize(1);
    assertThat(loaded.mcpServers().get(0).command()).isEqualTo("uvx");
    assertThat(loaded.mcpServers().get(0).args()).containsExactly("mcp-weather");
    assertThat(loaded.mcpServers().get(0).env()).containsEntry("API_KEY", "t0p-secret");
    assertThat(loaded.descriptors()).containsKey("research");
    assertThat(loaded.descriptors().get("research").outputSlots()).containsExactly("research.points");
    assertThat(store.currentVersion()).isEqualTo(7);
  }

  @Test
  void encryptsSecretEnvValuesAtRest() {
    store.save(fullState());

    String rawEnv = jdbc.sql("SELECT env FROM cp_mcp_server WHERE name = 'weather'")
        .query(String.class).single();
    // Every env value is field-encrypted at rest; a raw SELECT reveals nothing.
    assertThat(rawEnv).contains("\"API_KEY\":\"enc:v1:").contains("\"LOG\":\"enc:v1:")
        .doesNotContain("t0p-secret").doesNotContain("debug");
  }

  @Test
  void storesPlaintextWithoutCryptoKey() {
    DbControlPlaneStore plain = new DbControlPlaneStore(jdbc, JsonMapper.builder().build(),
        ControlPlaneCrypto.disabled());
    plain.initialize();
    plain.save(fullState());

    String rawEnv = jdbc.sql("SELECT env FROM cp_mcp_server WHERE name = 'weather'")
        .query(String.class).single();
    assertThat(rawEnv).contains("t0p-secret");
    assertThat(plain.load().orElseThrow().mcpServers().get(0).env()).containsEntry("API_KEY", "t0p-secret");
  }

  @Test
  void importsFileStateOnlyWhenDatabaseIsEmpty() {
    Path file = tempDir.resolve("control-plane.json");
    FileControlPlaneStore seed = new FileControlPlaneStore(file, JsonMapper.builder().build());
    seed.save(fullState());

    store.importFrom(seed);
    assertThat(store.currentVersion()).isEqualTo(7);
    assertThat(store.load().orElseThrow().mcpServers()).hasSize(1);

    // A second import is a no-op: the database is already populated.
    seed.save(new ControlPlaneState(99, Instant.now(), Map.of(),
        new WorkflowControlState("SEQUENTIAL", List.of()), List.of(), Map.of()));
    store.importFrom(seed);
    assertThat(store.currentVersion()).isEqualTo(7);
  }

  @Test
  void cacheServesUntilTheVersionMoves() {
    CachedControlPlaneStore cached = new CachedControlPlaneStore(store, java.time.Duration.ofSeconds(30));
    store.save(fullState());
    assertThat(cached.load().orElseThrow().version()).isEqualTo(7);

    // External write through the delegate: the version probe busts the cache.
    store.save(new ControlPlaneState(8, Instant.now(), Map.of(),
        new WorkflowControlState("SEQUENTIAL", List.of()), List.of(), Map.of()));
    assertThat(cached.load().orElseThrow().version()).isEqualTo(8);

    // Write-through: saves through the cache are immediately visible.
    cached.save(new ControlPlaneState(9, Instant.now(), Map.of(),
        new WorkflowControlState("SEQUENTIAL", List.of()), List.of(), Map.of()));
    assertThat(cached.load().orElseThrow().version()).isEqualTo(9);
    assertThat(cached.name()).isEqualTo("cached database");
  }
}
