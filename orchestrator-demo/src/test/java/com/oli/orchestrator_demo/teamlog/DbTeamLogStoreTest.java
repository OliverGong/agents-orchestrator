/**
 * @ClassName DbTeamLogStoreTest
 * @Description Exercises the database-backed team log against an in-memory
 *              H2 (the same portable SQL runs on PostgreSQL): schema
 *              bootstrap, append/search round-trip with every facet (time
 *              window, category, level, run/scenario attribution, free text)
 *              and the newest-first ordering with a limit.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.teamlog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

class DbTeamLogStoreTest {

  @TempDir
  Path tempDir;

  private DbTeamLogStore store;

  @BeforeEach
  void setUp() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    store = new DbTeamLogStore(JdbcClient.create(dataSource));
    store.initialize();
  }

  private TeamLogEntry entry(String category, String level, String runId, String action, String message) {
    return entry(category, level, runId, "scn-a", action, message);
  }

  private TeamLogEntry entry(String category, String level, String runId, String scenarioId,
      String action, String message) {
    return new TeamLogEntry(null, Instant.parse("2026-11-01T08:00:00Z"), category, level,
        "orchestrator", runId, scenarioId, action, message, "detail-payload");
  }

  @Test
  void roundTripsEntriesThroughTheDatabase() {
    store.append(entry(TeamLogEntry.RUN, TeamLogEntry.ERROR, "run-1", "STAGE_FAILED", "stage 'research' failed"));
    store.append(entry(TeamLogEntry.RUN, TeamLogEntry.INFO, "run-2", "RUN_COMPLETED", "run completed"));
    store.append(entry(TeamLogEntry.AUDIT, TeamLogEntry.INFO, null, null, "PORTAL_SAVED", "meta.portal:p1"));

    List<TeamLogEntry> found = store.search(
        new TeamLogStore.TeamLogQuery(null, null, null, null, null, null, 10));

    assertThat(found).hasSize(3);
    assertThat(found.get(0).category()).isEqualTo("AUDIT"); // newest first
    assertThat(found.get(0).id()).isNotNull();
    assertThat(found.get(2).level()).isEqualTo("ERROR");
    assertThat(found.get(2).detail()).isEqualTo("detail-payload");
  }

  @Test
  void searchAppliesEveryFacet() {
    store.append(entry(TeamLogEntry.RUN, TeamLogEntry.ERROR, "run-1", "STAGE_FAILED", "stage failed"));
    store.append(entry(TeamLogEntry.RUN, TeamLogEntry.INFO, "run-2", "RUN_COMPLETED", "run completed"));
    store.append(entry(TeamLogEntry.AUDIT, TeamLogEntry.INFO, null, null, "SCENARIO_CREATED", "scenario:x"));

    assertThat(store.search(new TeamLogStore.TeamLogQuery(null, "RUN", null, null, null, null, 10)))
        .hasSize(2);
    assertThat(store.search(new TeamLogStore.TeamLogQuery(null, "run", null, null, null, null, 10)))
        .hasSize(2); // case-insensitive
    assertThat(store.search(new TeamLogStore.TeamLogQuery(null, null, TeamLogEntry.ERROR, null, null, null, 10)))
        .hasSize(1);
    assertThat(store.search(new TeamLogStore.TeamLogQuery(null, null, null, "run-1", null, null, 10)))
        .hasSize(1);
    assertThat(store.search(new TeamLogStore.TeamLogQuery(null, null, null, null, "scn-a", null, 10)))
        .hasSize(2);
    assertThat(store.search(new TeamLogStore.TeamLogQuery(null, null, null, null, null, "scenario", 10)))
        .hasSize(1);
    assertThat(store.search(new TeamLogStore.TeamLogQuery(
        Instant.parse("2026-11-01T09:00:00Z"), null, null, null, null, null, 10))).isEmpty();
    assertThat(store.search(new TeamLogStore.TeamLogQuery(null, null, null, null, null, null, 2)))
        .hasSize(2);
  }

  @Test
  void answersEmptyWhenNothingWasLogged() {
    assertThat(store.search(new TeamLogStore.TeamLogQuery(null, null, null, null, null, null, 10)))
        .isEmpty();
    assertThat(store.name()).isEqualTo("database");
  }
}
