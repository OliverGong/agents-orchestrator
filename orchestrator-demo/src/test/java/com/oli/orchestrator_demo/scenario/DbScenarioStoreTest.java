/**
 * @ClassName DbScenarioStoreTest
 * @Description Exercises the database-backed scenario store against an
 *              in-memory H2 (the same portable SQL runs on PostgreSQL):
 *              schema bootstrap, save/load round-trip of scenarios (request
 *              template, workflow override present and absent, agent
 *              overrides, MCP refs, artifact types, active flag), the version
 *              stamp and the one-time JSON → DB import from the file store.
 * @Author OliverGong
 * @Date Oct 31, 2026
 * @Version 2.1
 */
package com.oli.orchestrator_demo.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.oli.orchestrator_demo.control.CalibrationRule;
import com.oli.orchestrator_demo.control.WorkflowControlState;

import tools.jackson.databind.json.JsonMapper;

class DbScenarioStoreTest {

  @TempDir
  Path tempDir;

  private JdbcClient jdbc;
  private DbScenarioStore store;

  @BeforeEach
  void setUp() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    jdbc = JdbcClient.create(dataSource);
    store = new DbScenarioStore(jdbc, JsonMapper.builder().build());
    store.initialize();
  }

  private Scenario fullScenario(String id, WorkflowControlState override) {
    Map<String, Scenario.AgentOverride> overrides = new LinkedHashMap<>();
    overrides.put("research", new Scenario.AgentOverride("custom persona", "qwen:7b", 0.4,
        List.of(new CalibrationRule("\\s+", " ", "collapse")), List.of("weather")));
    return new Scenario(id, "场景 " + id, "描述", new Scenario.RequestTemplate("Kyoto", 3, 12000.5, " temples "),
        override, overrides, List.of("weather"), Scenario.ArtifactType.SPRING_BOOT_PROJECT, true, Instant.now());
  }

  @Test
  void roundTripsScenariosThroughTheDatabase() {
    WorkflowControlState override = new WorkflowControlState("CONCURRENT", List.of(
        new WorkflowControlState.StageControlState("research", "research", List.of()),
        new WorkflowControlState.StageControlState("budget", "budget", List.of("research"))));
    ScenarioState state = new ScenarioState(7, Instant.now(),
        List.of(fullScenario("with-override", override), fullScenario("without-override", null)));
    store.save(state);

    ScenarioState loaded = store.load().orElseThrow();

    assertThat(loaded.version()).isEqualTo(7);
    assertThat(loaded.scenarios()).hasSize(2);
    Scenario withOverride = loaded.scenarios().get(0);
    assertThat(withOverride.workflowOverride()).isEqualTo(override);
    assertThat(withOverride.artifactType()).isEqualTo(Scenario.ArtifactType.SPRING_BOOT_PROJECT);
    assertThat(withOverride.requestTemplate()).isEqualTo(
        new Scenario.RequestTemplate("Kyoto", 3, 12000.5, " temples "));
    assertThat(withOverride.agentOverrides().get("research").systemPrompt()).isEqualTo("custom persona");
    assertThat(withOverride.agentOverrides().get("research").calibrationRules())
        .containsExactly(new CalibrationRule("\\s+", " ", "collapse"));
    assertThat(withOverride.mcpServerRefs()).containsExactly("weather");
    Scenario withoutOverride = loaded.scenarios().get(1);
    assertThat(withoutOverride.workflowOverride()).isNull();
    assertThat(withoutOverride.name()).isEqualTo("场景 without-override");
  }

  @Test
  void importFromImportsTheFileStateOnlyOnce() {
    FileScenarioStore fileStore = new FileScenarioStore(tempDir.resolve("scenarios.json"),
        JsonMapper.builder().build());
    fileStore.save(new ScenarioState(3, Instant.now(), List.of(fullScenario("seeded", null))));
    assertThat(store.load()).isEmpty();

    store.importFrom(fileStore);
    assertThat(store.load().orElseThrow().scenarios()).hasSize(1);
    assertThat(store.load().orElseThrow().version()).isEqualTo(3);

    // A second import is a no-op: the database is already populated.
    fileStore.save(new ScenarioState(99, Instant.now(), List.of()));
    store.importFrom(fileStore);
    assertThat(store.load().orElseThrow().version()).isEqualTo(3);
  }

  @Test
  void loadReturnsEmptyWhenNothingIsPersisted() {
    assertThat(store.load()).isEmpty();
    assertThat(store.name()).isEqualTo("database");
  }
}
