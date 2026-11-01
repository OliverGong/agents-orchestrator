/**
 * @ClassName FileInstanceStoreTest
 * @Description Exercises the JSON file backend of the instance registry:
 *              lifecycle transitions (start RUNNING → complete / fail), the
 *              query facets (business id, scenario, status, free text, time
 *              window, limit), newest-first ordering and round-trip
 *              persistence across store instances (the correlation history
 *              survives a restart).
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.ops.instance.infra;
import com.oli.orchestrator_demo.ops.instance.InstanceStore;
import com.oli.orchestrator_demo.kernel.contract.RunInstance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

class FileInstanceStoreTest {

  @TempDir
  Path tempDir;

  private FileInstanceStore store() {
    return new FileInstanceStore(tempDir.resolve("run-instances.json"), JsonMapper.builder().build());
  }

  private RunInstance running(String runId, String businessId) {
    return running(runId, businessId, Instant.parse("2026-11-01T08:00:00Z"));
  }

  private RunInstance running(String runId, String businessId, Instant startedAt) {
    return new RunInstance(runId, businessId, "scn-a", "tpl", "SEQUENTIAL", RunInstance.TRIGGER_API,
        RunInstance.RUNNING, "{\"destination\":\"Kyoto\"}", startedAt,
        null, null, null);
  }

  @Test
  void followsTheInstanceLifecycle() {
    FileInstanceStore store = store();
    store.start(running("run-1", "biz-1"));

    assertThat(store.find("run-1")).hasValueSatisfying(i -> {
      assertThat(i.status()).isEqualTo(RunInstance.RUNNING);
      assertThat(i.requestJson()).contains("Kyoto");
      assertThat(i.completedAt()).isNull();
    });

    store.complete("run-1", Instant.parse("2026-11-01T08:00:05Z"), 5000L);
    assertThat(store.find("run-1")).hasValueSatisfying(i -> {
      assertThat(i.status()).isEqualTo(RunInstance.COMPLETED);
      assertThat(i.durationMs()).isEqualTo(5000L);
      assertThat(i.errorMessage()).isNull();
    });

    store.start(running("run-2", "biz-1"));
    store.fail("run-2", Instant.parse("2026-11-01T08:01:00Z"), "model boom");
    assertThat(store.find("run-2")).hasValueSatisfying(i -> {
      assertThat(i.status()).isEqualTo(RunInstance.FAILED);
      assertThat(i.errorMessage()).isEqualTo("model boom");
    });
  }

  @Test
  void queriesApplyEveryFacet() {
    FileInstanceStore store = store();
    store.start(running("run-1", "biz-1", Instant.parse("2026-11-01T08:00:00Z")));
    store.start(running("run-2", "biz-2", Instant.parse("2026-11-01T08:01:00Z")));
    store.complete("run-2", Instant.parse("2026-11-01T08:01:05Z"), 5000L);

    assertThat(store.query(new InstanceStore.InstanceQuery(null, "biz-1", null, null, null, 10)))
        .hasSize(1).first().extracting(RunInstance::runId).isEqualTo("run-1");
    assertThat(store.query(new InstanceStore.InstanceQuery(null, null, "scn-a", "COMPLETED", null, 10)))
        .hasSize(1).first().extracting(RunInstance::runId).isEqualTo("run-2");
    assertThat(store.query(new InstanceStore.InstanceQuery(null, null, null, "completed", null, 10)))
        .hasSize(1); // case-insensitive
    assertThat(store.query(new InstanceStore.InstanceQuery(null, null, null, null, "biz-2", 10)))
        .hasSize(1).first().extracting(RunInstance::runId).isEqualTo("run-2");
    assertThat(store.query(new InstanceStore.InstanceQuery(Instant.parse("2026-11-01T09:00:00Z"),
        null, null, null, null, 10))).isEmpty();
    assertThat(store.query(new InstanceStore.InstanceQuery(null, null, null, null, null, 1)))
        .hasSize(1).first().extracting(RunInstance::runId).isEqualTo("run-2"); // newest first
  }

  @Test
  void persistsAcrossStoreInstances() throws Exception {
    Path file = tempDir.resolve("run-instances.json");
    FileInstanceStore first = new FileInstanceStore(file, JsonMapper.builder().build());
    first.start(running("run-1", "biz-1"));
    first.complete("run-1", Instant.parse("2026-11-01T08:00:05Z"), 5000L);

    assertThat(Files.exists(file)).isTrue();

    FileInstanceStore reopened = new FileInstanceStore(file, JsonMapper.builder().build());
    List<RunInstance> all = reopened.query(new InstanceStore.InstanceQuery(null, null, null, null, null, 10));
    assertThat(all).hasSize(1);
    assertThat(all.get(0).status()).isEqualTo(RunInstance.COMPLETED);
    assertThat(all.get(0).businessId()).isEqualTo("biz-1");
  }
}
