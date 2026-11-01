/**
 * @ClassName FileTeamLogStoreTest
 * @Description Verifies the JSONL file backend of the team log: appends
 *              receive monotonic ids across restarts, search applies every
 *              facet (time window, category, level, run/scenario, free text)
 *              newest-first with a limit, torn trailing lines never break a
 *              search and the store answers empty when nothing was logged.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.teamlog.infra;
import com.oli.orchestrator_demo.teamlog.TeamLogEntry;
import com.oli.orchestrator_demo.teamlog.TeamLogStore;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

class FileTeamLogStoreTest {

  @TempDir
  Path tempDir;

  private FileTeamLogStore store;

  @BeforeEach
  void setUp() {
    store = new FileTeamLogStore(tempDir.resolve("team-log.jsonl"), JsonMapper.builder().build());
  }

  private TeamLogEntry runEntry(String runId, String level, String message) {
    // A fixed past timestamp keeps the ordering deterministic: entries created
    // with Instant.now() (e.g. audits) always sort as "newest".
    return new TeamLogEntry(null, Instant.parse("2025-01-01T08:00:00Z"), TeamLogEntry.RUN,
        level, "orchestrator", runId, "scn-a", "STAGE_FAILED", message, "boom");
  }

  @Test
  void appendsWithMonotonicIdsAndRoundTrips() {
    store.append(runEntry("run-1", TeamLogEntry.ERROR, "stage failed"));
    store.append(new TeamLogEntry(null, Instant.parse("2026-11-01T09:00:00Z"), TeamLogEntry.AUDIT,
        TeamLogEntry.INFO, "meta-plane", null, null, "PORTAL_SAVED", "meta.portal:p1", "{}"));

    List<TeamLogEntry> found = store.search(
        new TeamLogStore.TeamLogQuery(null, null, null, null, null, null, 10));

    assertThat(found).hasSize(2);
    assertThat(found.get(0).id()).isEqualTo(1); // newest first
    assertThat(found.get(0).category()).isEqualTo("AUDIT");
    assertThat(found.get(0).action()).isEqualTo("PORTAL_SAVED");
    assertThat(found.get(1).id()).isEqualTo(0);
    assertThat(found.get(1).level()).isEqualTo("ERROR");

    // Ids keep growing across "restarts" (new store instance, same file).
    FileTeamLogStore restarted = new FileTeamLogStore(tempDir.resolve("team-log.jsonl"),
        JsonMapper.builder().build());
    restarted.append(new TeamLogEntry(null, Instant.parse("2026-11-01T10:00:00Z"), TeamLogEntry.RUN,
        TeamLogEntry.WARN, "orchestrator", "run-2", "scn-a", "STAGE_RETRIED", "retrying", null));
    assertThat(restarted.search(
        new TeamLogStore.TeamLogQuery(null, null, null, null, null, null, 10)).get(0).id())
        .isEqualTo(2);
  }

  @Test
  void searchAppliesEveryFacet() {
    store.append(runEntry("run-1", TeamLogEntry.ERROR, "stage 'research' failed"));
    store.append(runEntry("run-2", TeamLogEntry.INFO, "run completed"));
    store.append(TeamLogEntry.audit("meta-plane", "RUNTIME_UPDATED", "meta.runtime", "{}"));

    assertThat(store.search(new TeamLogStore.TeamLogQuery(null, "RUN", null, null, null, null, 10)))
        .hasSize(2);
    assertThat(store.search(new TeamLogStore.TeamLogQuery(null, "RUN", TeamLogEntry.ERROR,
        null, null, null, 10))).hasSize(1);
    assertThat(store.search(new TeamLogStore.TeamLogQuery(null, null, null, "run-1", null, null, 10)))
        .hasSize(1);
    assertThat(store.search(new TeamLogStore.TeamLogQuery(null, null, null, null, "scn-a", null, 10)))
        .hasSize(2);
    assertThat(store.search(new TeamLogStore.TeamLogQuery(null, null, null, null, null,
        "runtime", 10))).hasSize(1);
    assertThat(store.search(new TeamLogStore.TeamLogQuery(
        Instant.parse("2026-11-01T09:00:00Z"), null, null, null, null, null, 10))).isEmpty();
    assertThat(store.search(new TeamLogStore.TeamLogQuery(null, null, null, null, null, null, 1)))
        .hasSize(1);
  }

  @Test
  void tornTrailingLinesNeverBreakTheSearch() throws Exception {
    store.append(runEntry("run-1", TeamLogEntry.ERROR, "stage failed"));
    Files.writeString(tempDir.resolve("team-log.jsonl"),
        "{\"ts\":\"2026-11-01T08", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

    assertThat(store.search(new TeamLogStore.TeamLogQuery(null, null, null, null, null, null, 10)))
        .hasSize(1);
  }

  @Test
  void answersEmptyWhenNothingWasLogged() {
    assertThat(store.search(new TeamLogStore.TeamLogQuery(null, null, null, null, null, null, 10)))
        .isEmpty();
    assertThat(store.name()).startsWith("file:");
  }
}
