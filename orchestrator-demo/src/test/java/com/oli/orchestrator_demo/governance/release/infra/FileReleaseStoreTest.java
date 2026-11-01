/**
 * @ClassName FileReleaseStoreTest
 * @Description Verifies the JSON file backend of the release registry:
 *              append assigns incrementing ids, records survive a restart,
 *              the list view is newest-first with the optional scenario
 *              filter and unknown ids resolve to empty.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.governance.release.infra;
import com.oli.orchestrator_demo.governance.release.model.ReleaseRecord;
import com.oli.orchestrator_demo.governance.release.ReleaseStore;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

class FileReleaseStoreTest {

  @TempDir
  Path tempDir;

  private Path registryFile;

  @BeforeEach
  void setUp() {
    registryFile = tempDir.resolve("release-registry.json");
  }

  private FileReleaseStore freshStore() {
    return new FileReleaseStore(registryFile, JsonMapper.builder().build());
  }

  private ReleaseRecord record(String scenarioId, String version) {
    return new ReleaseRecord(null, scenarioId, "portal-a", version, "changelog", "HTML",
        Instant.parse("2026-11-01T08:00:00Z"), "hash-" + version, "{\"pkg\":true}");
  }

  @Test
  void appendsWithIncrementingIdsAndReloadsOnRestart() {
    FileReleaseStore store = freshStore();
    ReleaseRecord first = store.append(record("scn-1", "v1.0.0"));
    ReleaseRecord second = store.append(record("scn-1", "v1.1.0"));

    assertThat(first.id()).isEqualTo(1L);
    assertThat(second.id()).isEqualTo(2L);
    assertThat(second.packageJson()).isEqualTo("{\"pkg\":true}");

    FileReleaseStore restarted = freshStore();
    assertThat(restarted.list(null)).extracting(ReleaseRecord::id).containsExactly(2L, 1L);
    assertThat(restarted.get(2).orElseThrow().releaseVersion()).isEqualTo("v1.1.0");
  }

  @Test
  void listFiltersByScenarioNewestFirst() {
    ReleaseStore store = freshStore();
    store.append(record("scn-1", "v1.0.0"));
    store.append(record("scn-2", "v0.1.0"));
    store.append(record("scn-1", "v1.1.0"));

    List<ReleaseRecord> all = store.list(null);
    assertThat(all).hasSize(3);
    assertThat(all.get(0).id()).isEqualTo(3L);

    List<ReleaseRecord> scoped = store.list("scn-1");
    assertThat(scoped).extracting(ReleaseRecord::releaseVersion).containsExactly("v1.1.0", "v1.0.0");
    assertThat(store.list("ghost")).isEmpty();
  }

  @Test
  void getReturnsEmptyForUnknownIds() {
    ReleaseStore store = freshStore();
    store.append(record("scn-1", "v1.0.0"));
    assertThat(store.get(1)).isPresent();
    assertThat(store.get(99)).isEmpty();
    assertThat(store.name()).contains("release-registry.json");
  }
}
