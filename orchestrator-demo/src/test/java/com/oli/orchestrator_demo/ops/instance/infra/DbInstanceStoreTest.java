/**
 * @ClassName DbInstanceStoreTest
 * @Description Exercises the database-backed instance registry against an
 *              in-memory H2 (the same portable SQL runs on PostgreSQL):
 *              schema bootstrap, the trigger→outcome lifecycle round-trip
 *              (RUNNING row at request entry, terminal update), the query
 *              facets (business id, scenario, status, free text, time window)
 *              and newest-first ordering with a limit.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.ops.instance.infra;
import com.oli.orchestrator_demo.ops.instance.InstanceStore;
import com.oli.orchestrator_demo.kernel.contract.RunInstance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

class DbInstanceStoreTest {

  @TempDir
  Path tempDir;

  private DbInstanceStore store;

  @BeforeEach
  void setUp() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    store = new DbInstanceStore(JdbcClient.create(dataSource));
    store.initialize();
  }

  private RunInstance running(String runId, String businessId, Instant startedAt) {
    return new RunInstance(runId, businessId, "scn-a", "tpl", "SEQUENTIAL", RunInstance.TRIGGER_API,
        RunInstance.RUNNING, "{\"destination\":\"Kyoto\"}", startedAt, null, null, null);
  }

  @Test
  void followsTheInstanceLifecycle() {
    store.start(running("run-1", "biz-1", Instant.parse("2026-11-01T08:00:00Z")));
    assertThat(store.find("run-1")).hasValueSatisfying(i -> {
      assertThat(i.status()).isEqualTo(RunInstance.RUNNING);
      assertThat(i.trigger()).isEqualTo(RunInstance.TRIGGER_API);
      assertThat(i.requestJson()).contains("Kyoto");
    });

    store.complete("run-1", Instant.parse("2026-11-01T08:00:05Z"), 5000L);
    assertThat(store.find("run-1")).hasValueSatisfying(i -> {
      assertThat(i.status()).isEqualTo(RunInstance.COMPLETED);
      assertThat(i.durationMs()).isEqualTo(5000L);
    });

    store.start(running("run-2", "biz-1", Instant.parse("2026-11-01T08:01:00Z")));
    store.fail("run-2", Instant.parse("2026-11-01T08:01:02Z"), "model boom");
    assertThat(store.find("run-2")).hasValueSatisfying(i -> {
      assertThat(i.status()).isEqualTo(RunInstance.FAILED);
      assertThat(i.errorMessage()).isEqualTo("model boom");
    });
  }

  @Test
  void queriesApplyEveryFacet() {
    store.start(running("run-1", "biz-1", Instant.parse("2026-11-01T08:00:00Z")));
    store.start(running("run-2", "biz-2", Instant.parse("2026-11-01T08:01:00Z")));
    store.complete("run-2", Instant.parse("2026-11-01T08:01:05Z"), 5000L);
    store.start(running("run-3", "biz-3", Instant.parse("2026-11-01T08:02:00Z")));
    store.fail("run-3", Instant.parse("2026-11-01T08:02:05Z"), "model boom");

    assertThat(store.query(new InstanceStore.InstanceQuery(null, "biz-1", null, null, null, 10)))
        .hasSize(1).first().extracting(RunInstance::runId).isEqualTo("run-1");
    assertThat(store.query(new InstanceStore.InstanceQuery(null, null, "scn-a", "COMPLETED", null, 10)))
        .hasSize(1).first().extracting(RunInstance::runId).isEqualTo("run-2");
    assertThat(store.query(new InstanceStore.InstanceQuery(null, null, null, null, "boom", 10)))
        .hasSize(1).first().extracting(RunInstance::runId).isEqualTo("run-3");
    assertThat(store.query(new InstanceStore.InstanceQuery(Instant.parse("2026-11-01T08:01:30Z"),
        null, null, null, null, 10))).hasSize(1).first().extracting(RunInstance::runId)
        .isEqualTo("run-3");
    assertThat(store.query(new InstanceStore.InstanceQuery(null, null, null, null, null, 1)))
        .hasSize(1).first().extracting(RunInstance::runId).isEqualTo("run-3"); // newest first
  }

  @Test
  void answersEmptyWhenNothingWasInstantiated() {
    assertThat(store.query(new InstanceStore.InstanceQuery(null, null, null, null, null, 10)))
        .isEmpty();
    assertThat(store.find("missing")).isEmpty();
    assertThat(store.name()).isEqualTo("database");
  }
}
