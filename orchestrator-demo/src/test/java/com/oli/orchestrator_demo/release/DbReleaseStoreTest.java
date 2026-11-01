/**
 * @ClassName DbReleaseStoreTest
 * @Description Exercises the database-backed release registry against an
 *              in-memory H2 (the same portable SQL runs on PostgreSQL):
 *              schema bootstrap, append round-trip (assigned id, every column
 *              including the package JSON), the scenario-scoped newest-first
 *              list and unknown-id lookups.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class DbReleaseStoreTest {

  private DbReleaseStore store;

  @BeforeEach
  void setUp() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    store = new DbReleaseStore(JdbcClient.create(dataSource));
    store.initialize();
  }

  private ReleaseRecord record(String scenarioId, String version) {
    return new ReleaseRecord(null, scenarioId, "portal-a", version, "changelog", "HTML",
        Instant.parse("2026-11-01T08:00:00Z"), "hash-" + version, "{\"pkg\":\"" + version + "\"}");
  }

  @Test
  void appendsAndReadsBackEveryColumn() {
    ReleaseRecord appended = store.append(record("scn-1", "v1.0.0"));

    assertThat(appended.id()).isEqualTo(1L);
    ReleaseRecord loaded = store.get(1).orElseThrow();
    assertThat(loaded.scenarioId()).isEqualTo("scn-1");
    assertThat(loaded.portalId()).isEqualTo("portal-a");
    assertThat(loaded.releaseVersion()).isEqualTo("v1.0.0");
    assertThat(loaded.changelog()).isEqualTo("changelog");
    assertThat(loaded.artifactType()).isEqualTo("HTML");
    assertThat(loaded.publishedAt()).isEqualTo(Instant.parse("2026-11-01T08:00:00Z"));
    assertThat(loaded.packageHash()).isEqualTo("hash-v1.0.0");
    assertThat(loaded.packageJson()).isEqualTo("{\"pkg\":\"v1.0.0\"}");
  }

  @Test
  void listIsNewestFirstAndFiltersByScenario() {
    store.append(record("scn-1", "v1.0.0"));
    store.append(record("scn-2", "v0.1.0"));
    store.append(record("scn-1", "v1.1.0"));

    List<ReleaseRecord> all = store.list(null);
    assertThat(all).extracting(ReleaseRecord::id).containsExactly(3L, 2L, 1L);

    List<ReleaseRecord> scoped = store.list("scn-1");
    assertThat(scoped).extracting(ReleaseRecord::releaseVersion).containsExactly("v1.1.0", "v1.0.0");
    assertThat(store.list("ghost")).isEmpty();
  }

  @Test
  void unknownIdsResolveToEmpty() {
    store.append(record("scn-1", "v1.0.0"));
    assertThat(store.get(99)).isEmpty();
    assertThat(store.name()).isEqualTo("database");
  }
}
