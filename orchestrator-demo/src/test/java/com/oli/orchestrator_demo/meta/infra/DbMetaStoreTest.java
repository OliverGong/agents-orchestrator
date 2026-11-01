/**
 * @ClassName DbMetaStoreTest
 * @Description Exercises the database-backed meta store against an in-memory
 *              H2 (the same portable SQL runs on PostgreSQL): schema
 *              bootstrap, save/load round-trip of the versioned meta state
 *              (runtime knobs incl. null inherit-fields, portals, per-scenario
 *              releases) and the one-time JSON → DB import from the file
 *              seed store.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.meta.infra;
import com.oli.orchestrator_demo.meta.MetaState;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.json.JsonMapper;

class DbMetaStoreTest {

  @TempDir
  Path tempDir;

  private JdbcClient jdbc;
  private DbMetaStore store;

  @BeforeEach
  void setUp() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    jdbc = JdbcClient.create(dataSource);
    store = new DbMetaStore(jdbc);
    store.initialize();
  }

  private MetaState fullState(long version) {
    return new MetaState(version, Instant.now(),
        new MetaState.RuntimeConfig("qwen3:8b", 120, 4, 0.3, 0.9),
        List.of(new MetaState.PortalConfig("portal-a", "门户 A", "WEB_PORTAL", "https://a", true),
            new MetaState.PortalConfig("portal-b", "门户 B", "DOWNLOAD", "https://b", false)),
        List.of(new MetaState.ReleaseConfig("scn-1", "portal-a", "v1.0.0", "initial", "HTML"),
            new MetaState.ReleaseConfig("scn-2", null, null, null, "JAR")));
  }

  @Test
  void roundTripsTheMetaStateThroughTheDatabase() {
    store.save(fullState(7));

    MetaState loaded = store.load().orElseThrow();

    assertThat(loaded.version()).isEqualTo(7);
    assertThat(loaded.updatedAt()).isNotNull();
    assertThat(loaded.runtime()).isEqualTo(
        new MetaState.RuntimeConfig("qwen3:8b", 120, 4, 0.3, 0.9));
    assertThat(loaded.portals()).hasSize(2);
    assertThat(loaded.portals().get(0)).isEqualTo(
        new MetaState.PortalConfig("portal-a", "门户 A", "WEB_PORTAL", "https://a", true));
    assertThat(loaded.portals().get(1).enabled()).isFalse();
    assertThat(loaded.releases()).hasSize(2);
    assertThat(loaded.releases().get(0)).isEqualTo(
        new MetaState.ReleaseConfig("scn-1", "portal-a", "v1.0.0", "initial", "HTML"));
    assertThat(loaded.releases().get(1).portalId()).isNull();
    assertThat(loaded.releases().get(1).version()).isNull();
  }

  @Test
  void nullRuntimeFieldsStayNull() {
    store.save(new MetaState(1, Instant.now(), MetaState.RuntimeConfig.defaults(), List.of(), List.of()));

    MetaState loaded = store.load().orElseThrow();

    assertThat(loaded.runtime()).isEqualTo(MetaState.RuntimeConfig.defaults());
  }

  @Test
  void importFromImportsTheFileStateOnlyOnce() {
    FileMetaStore fileStore = new FileMetaStore(tempDir.resolve("meta-plane.json"),
        JsonMapper.builder().build());
    fileStore.save(fullState(3));
    assertThat(store.load()).isEmpty();

    store.importFrom(fileStore);
    assertThat(store.load().orElseThrow().version()).isEqualTo(3);
    assertThat(store.load().orElseThrow().portals()).hasSize(2);

    // A second import is a no-op: the database is already populated.
    fileStore.save(fullState(99));
    store.importFrom(fileStore);
    assertThat(store.load().orElseThrow().version()).isEqualTo(3);
  }

  @Test
  void loadReturnsEmptyWhenNothingIsPersisted() {
    assertThat(store.load()).isEmpty();
    assertThat(store.name()).isEqualTo("database");
  }
}
